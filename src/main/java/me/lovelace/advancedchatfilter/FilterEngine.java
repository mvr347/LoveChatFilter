package me.lovelace.advancedchatfilter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressWarnings({"deprecation", "DuplicatedCode"})
public class FilterEngine {
    private final AdvancedChatFilter plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    private Pattern profanityPattern;
    private Pattern ipPattern;
    private Pattern adsWhitelistPattern;
    private Pattern goodwordsPattern;
    private Pattern additionalPattern;

    private int capsPercentThreshold;
    private boolean bwSignFilter, bwBookFilter, bwItemFilter;
    private boolean adsSignFilter, adsBookFilter, adsItemFilter;

    private final Map<UUID, Long> lastMessageTime = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastMessageText = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastGoodwordTimeWithTarget = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastGoodwordTimeNoTarget = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastAdditionalTime = new ConcurrentHashMap<>();

    // Common Cyrillic/Latin (and a couple of Greek) homoglyphs mapped to a single canonical
    // (Latin) form, so mixed-script letter substitution can't be used to dodge the badword filter.
    private static final Map<Character, Character> HOMOGLYPHS = buildHomoglyphMap();

    private static Map<Character, Character> buildHomoglyphMap() {
        Map<Character, Character> m = new HashMap<>();
        String cyrLower = "аеорсухкмтвн";
        String latLower = "aeopcyxkmtbh";
        String cyrUpper = "АЕОРСУХКМТВН";
        String latUpper = "AEOPCYXKMTBH";
        for (int i = 0; i < cyrLower.length(); i++) m.put(cyrLower.charAt(i), latLower.charAt(i));
        for (int i = 0; i < cyrUpper.length(); i++) m.put(cyrUpper.charAt(i), latUpper.charAt(i));
        m.put('і', 'i');
        m.put('І', 'I');
        m.put('ј', 'j');
        m.put('Ѕ', 'S');
        m.put('ѕ', 's');
        return Map.copyOf(m);
    }

    private static String normalizeHomoglyphs(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            sb.append(HOMOGLYPHS.getOrDefault(c, c));
        }
        return sb.toString();
    }

    private record CacheKey(String text, boolean bypassCaps, boolean bypassBadwords, boolean bypassAds, boolean grammarEnabled, ContentType type) {}
    private record CacheValue(String processedText, boolean triggeredCaps, boolean triggeredSwear, boolean triggeredAds, boolean triggeredGoodwords, String target, boolean triggeredAdditional, String additionalTarget) {}

    private final Cache<CacheKey, CacheValue> contentCache = Caffeine.newBuilder()
            .maximumSize(15000)
            .expireAfterAccess(1, TimeUnit.HOURS)
            .build();

    public enum ContentType { CHAT, SIGN, BOOK, ITEM }

    public static class ProcessResult {
        public String message;
        public boolean cancelled;
        public ProcessResult(String message, boolean cancelled) {
            this.message = message;
            this.cancelled = cancelled;
        }
    }

    public FilterEngine(AdvancedChatFilter plugin) {
        this.plugin = plugin;
        reloadCache();
    }

    /**
     * Removes all per-player timing/state entries for a player who left the server, so the
     * timing maps don't grow unbounded on long-running servers with many distinct players.
     */
    public void clearPlayerData(UUID uuid) {
        lastMessageTime.remove(uuid);
        lastMessageText.remove(uuid);
        lastGoodwordTimeWithTarget.remove(uuid);
        lastGoodwordTimeNoTarget.remove(uuid);
        lastAdditionalTime.remove(uuid);
    }

    public void reloadCache() {
        FileConfiguration config = plugin.getConfigManager().getConfig();
        contentCache.invalidateAll();

        // Note: per-player timing maps (spam/goodword/additional cooldowns) are intentionally
        // NOT cleared here. A config reload should not reset anti-spam cooldowns for online
        // players. They are cleaned up per-player on PlayerQuitEvent instead (see clearPlayerData).

        bwSignFilter = config.getBoolean("badwords-filter.sign-filter", true);
        bwBookFilter = config.getBoolean("badwords-filter.book-filter", true);
        bwItemFilter = config.getBoolean("badwords-filter.item-filter", true);

        adsSignFilter = config.getBoolean("ads-filter.sign-filter", true);
        adsBookFilter = config.getBoolean("ads-filter.book-filter", true);
        adsItemFilter = config.getBoolean("ads-filter.item-filter", true);

        capsPercentThreshold = config.getInt("anti-caps.max-caps-percent", 70);

        // --- БЕЗОПАСНАЯ КОМПИЛЯЦИЯ МАТОВ ---
        List<String> badwords = config.getStringList("badwords-filter.list");
        if (!badwords.isEmpty()) {
            List<String> smartRegexList = new ArrayList<>();
            for (String word : badwords) {
                if (word.startsWith("(?i)")) {
                    smartRegexList.add(word.substring(4)); // Отрезаем (?i), чтобы не сломать общий паттерн
                } else {
                    StringBuilder sb = new StringBuilder();
                    char[] chars = normalizeHomoglyphs(word.toLowerCase()).toCharArray();
                    for (int i = 0; i < chars.length; i++) {
                        sb.append(Pattern.quote(String.valueOf(chars[i]))).append("+");
                        // Separator between letters may be any non-letter character, INCLUDING
                        // digits (digits are \w, so plain \W would let "х1у2й"-style leetspeak
                        // insertion slip through untouched).
                        if (i < chars.length - 1) sb.append("[\\W\\d_]*");
                    }
                    // Anchor on both ends so the banned word must be a standalone token, not an
                    // embedded substring of a longer, unrelated word (the Scunthorpe problem).
                    smartRegexList.add("(?<![\\p{L}])" + sb + "(?![\\p{L}])");
                }
            }
            try {
                profanityPattern = Pattern.compile("(?ui)(" + String.join("|", smartRegexList) + ")");
            } catch (Exception e) {
                plugin.getLogger().severe("КРИТИЧЕСКАЯ ОШИБКА РЕГУЛЯРКИ МАТА! Проверьте конфиг.");
                profanityPattern = null;
            }
        } else {
            profanityPattern = null;
        }

        List<String> adPatterns = new ArrayList<>(config.getStringList("ads-filter.patterns"));
        adPatterns.addAll(config.getStringList("ads-filter.extra-link-patterns"));
        if (!adPatterns.isEmpty()) {
            ipPattern = Pattern.compile("(?ui)\\S*(?:" + String.join("|", adPatterns) + "|(?:\\d{1,3}[ .,]{1,3}){3}\\d{1,3})\\S*");
        } else ipPattern = null;

        List<String> whitelist = config.getStringList("ads-filter.whitelist");
        if (!whitelist.isEmpty()) {
            List<String> safeWL = new ArrayList<>();
            for (String w : whitelist) safeWL.add(Pattern.quote(w));
            adsWhitelistPattern = Pattern.compile("(?ui)(" + String.join("|", safeWL) + ")");
        } else adsWhitelistPattern = null;

        List<String> goodwords = config.getStringList("goodwords-detector.list");
        goodwordsPattern = goodwords.isEmpty() ? null : Pattern.compile("(?ui)" + String.join("|", goodwords));

        List<String> additional = config.getStringList("goodwords-detector.additional-list");
        additionalPattern = additional.isEmpty() ? null : Pattern.compile("(?ui)" + String.join("|", additional));
    }

    public ProcessResult processChat(Player player, String originalMessage) {
        ConfigManager cm = plugin.getConfigManager();

        if (cm.isModuleEnabled("anti-spam") && !player.hasPermission("advancedchatfilter.bypass.spam")) {
            double remainingTime = checkSpam(player, originalMessage);
            if (remainingTime > 0) {
                triggerCommands("anti-spam.commands-on-detect", player, originalMessage, originalMessage, "");
                cm.sendMessage(player, "anti-spam.message", "{time}", String.format("%.1f", remainingTime));
                playConfigSound(player, "anti-spam.sound");
                return new ProcessResult(originalMessage, true);
            }
        }

        boolean bypassCaps = player.hasPermission("advancedchatfilter.bypass.caps");
        boolean bypassBadwords = player.hasPermission("advancedchatfilter.bypass.badwords");
        boolean bypassAds = player.hasPermission("advancedchatfilter.bypass.ads");
        boolean grammarEnabled = plugin.getGrammarManager().isEnabled(player);

        CacheKey key = new CacheKey(originalMessage, bypassCaps, bypassBadwords, bypassAds, grammarEnabled, ContentType.CHAT);
        CacheValue result = contentCache.get(key, this::evaluateContent);

        if (result.triggeredSwear && cm.getConfig().getBoolean("badwords-filter.full-delete-message", false)) {
            triggerCommands("badwords-filter.commands-on-detect", player, result.processedText, originalMessage, "");
            playConfigSound(player, "badwords-filter.sound");
            return new ProcessResult("", true);
        }
        if (result.triggeredAds && cm.getConfig().getBoolean("ads-filter.full-delete-message", false)) {
            triggerCommands("ads-filter.commands-on-detect", player, result.processedText, originalMessage, "");
            playConfigSound(player, "ads-filter.sound");
            return new ProcessResult("", true);
        }

        if (cm.isModuleEnabled("emptymessages-clear")) {
            String clean = result.processedText.replaceAll("(?i)[&§][0-9a-fk-orx]", "").strip();
            if (clean.isEmpty()) return new ProcessResult("", true);
        }

        if (result.triggeredCaps) {
            cm.sendMessage(player, "anti-caps.message", null, null);
            playConfigSound(player, "anti-caps.sound");
            triggerCommands("anti-caps.commands-on-detect", player, result.processedText, originalMessage, "");
        }
        if (result.triggeredSwear) {
            triggerCommands("badwords-filter.commands-on-detect", player, result.processedText, originalMessage, "");
            playConfigSound(player, "badwords-filter.sound");
        }
        if (result.triggeredAds) {
            triggerCommands("ads-filter.commands-on-detect", player, result.processedText, originalMessage, "");
            playConfigSound(player, "ads-filter.sound");
        }

        if (result.triggeredAdditional && cm.isModuleEnabled("goodwords-detector")) {
            String targetName = "";
            boolean validToExecute = true;

            if (result.additionalTarget != null && !result.additionalTarget.isEmpty()) {
                Player targetPlayer = Bukkit.getPlayerExact(result.additionalTarget);
                if (targetPlayer != null && !player.getName().equalsIgnoreCase(result.additionalTarget)) {
                    targetName = targetPlayer.getName();
                } else validToExecute = false;
            }

            if (validToExecute) {
                double remaining = getRemainingTime(player, "additional");
                if (remaining <= 0) {
                    triggerCommands("goodwords-detector.additional-commands", player, result.processedText, originalMessage, targetName);
                    playConfigSound(player, "goodwords-detector.additional-sound");
                    lastAdditionalTime.put(player.getUniqueId(), System.currentTimeMillis());
                } else {
                    String rawMsg = cm.getConfig().getString("goodwords-detector.additional-cooldown-message");
                    if (rawMsg != null && !rawMsg.isEmpty() && !rawMsg.equalsIgnoreCase("NONE")) {
                        player.sendMessage(mm.deserialize(rawMsg.replace("{time}", String.format("%.1f", remaining)).replace("%target%", targetName)));
                    }
                }
            }
        }

        if (result.triggeredGoodwords && cm.isModuleEnabled("goodwords-detector")) {
            String commandPath = "goodwords-detector.commands-without-target";
            String targetName = "";
            boolean isWithTarget = false;

            if (result.target != null && !result.target.isEmpty()) {
                Player targetPlayer = Bukkit.getPlayerExact(result.target);
                if (targetPlayer != null && !player.getName().equalsIgnoreCase(result.target)) {
                    commandPath = "goodwords-detector.commands-with-target";
                    targetName = targetPlayer.getName();
                    isWithTarget = true;
                }
            }

            double remaining = getRemainingTime(player, isWithTarget ? "target" : "normal");
            if (remaining <= 0) {
                triggerCommands(commandPath, player, result.processedText, originalMessage, targetName);
                playConfigSound(player, isWithTarget ? "goodwords-detector.sound-target" : "goodwords-detector.sound-normal");
                if (isWithTarget) lastGoodwordTimeWithTarget.put(player.getUniqueId(), System.currentTimeMillis());
                else lastGoodwordTimeNoTarget.put(player.getUniqueId(), System.currentTimeMillis());
            } else {
                String configKey = isWithTarget ? "goodwords-detector.cooldown-message-target" : "goodwords-detector.cooldown-message-normal";
                String rawMsg = cm.getConfig().getString(configKey);
                if (rawMsg != null && !rawMsg.isEmpty() && !rawMsg.equalsIgnoreCase("NONE")) {
                    player.sendMessage(mm.deserialize(rawMsg.replace("{time}", String.format("%.1f", remaining)).replace("%target%", targetName)));
                }
            }
        }

        return new ProcessResult(result.processedText, false);
    }

    public String processContent(Player player, String text, ContentType type) {
        if (text == null || text.isEmpty()) return text;
        CacheKey key = new CacheKey(text,
                player.hasPermission("advancedchatfilter.bypass.caps"),
                player.hasPermission("advancedchatfilter.bypass.badwords"),
                player.hasPermission("advancedchatfilter.bypass.ads"),
                plugin.getGrammarManager().isEnabled(player), type);
        return contentCache.get(key, this::evaluateContent).processedText;
    }

    private CacheValue evaluateContent(CacheKey key) {
        String message = key.text;
        boolean triggeredCaps = false, triggeredSwear = false, triggeredAds = false, triggeredGoodwords = false, triggeredAdditional = false;
        String foundTarget = "", additionalTarget = "";
        ConfigManager cm = plugin.getConfigManager();

        if (key.type == ContentType.CHAT && cm.isModuleEnabled("anti-caps") && !key.bypassCaps && hasLetters(message)) {
            if (checkCaps(message, capsPercentThreshold)) { triggeredCaps = true; message = message.toLowerCase(); }
        }

        if (cm.isModuleEnabled("badwords-filter") && !key.bypassBadwords && profanityPattern != null) {
            if (shouldFilter(key.type, bwSignFilter, bwBookFilter, bwItemFilter)) {
                String repl = cm.getConfig().getString("badwords-filter.replacement-char", "👑");
                // Match against a homoglyph-normalized view of the message (Cyrillic/Latin
                // lookalikes folded to one canonical form) while still replacing/preserving the
                // original text, since normalization is 1:1 and preserves character offsets.
                FilterResult sr = applyRegexFilter(message, normalizeHomoglyphs(message), profanityPattern, null, repl);
                if (sr.wasFiltered) { triggeredSwear = true; message = sr.text; }
            }
        }

        if (cm.isModuleEnabled("ads-filter") && !key.bypassAds && ipPattern != null) {
            if (shouldFilter(key.type, adsSignFilter, adsBookFilter, adsItemFilter)) {
                String repl = cm.getConfig().getString("ads-filter.replacement-char", "*");
                FilterResult ar = applyRegexFilter(message, message, ipPattern, adsWhitelistPattern, repl);
                if (ar.wasFiltered) { triggeredAds = true; message = ar.text; }
            }
        }

        if (key.type == ContentType.CHAT && cm.isModuleEnabled("goodwords-detector")) {
            if (goodwordsPattern != null) {
                Matcher m = goodwordsPattern.matcher(message);
                if (m.find()) {
                    triggeredGoodwords = true;
                    for (int i = 1; i <= m.groupCount(); i++) if (m.group(i) != null) { foundTarget = m.group(i); break; }
                }
            }
            if (additionalPattern != null) {
                Matcher m = additionalPattern.matcher(message);
                if (m.find()) {
                    triggeredAdditional = true;
                    for (int i = 1; i <= m.groupCount(); i++) if (m.group(i) != null) { additionalTarget = m.group(i); break; }
                }
            }
        }

        if (key.type == ContentType.CHAT && cm.isModuleEnabled("grammar-fix") && key.grammarEnabled && hasLetters(message)) {
            message = plugin.getGrammarManager().applyGrammar(message);
        }

        return new CacheValue(message, triggeredCaps, triggeredSwear, triggeredAds, triggeredGoodwords, foundTarget, triggeredAdditional, additionalTarget);
    }

    private boolean shouldFilter(ContentType type, boolean signF, boolean bookF, boolean itemF) {
        if (type == ContentType.CHAT) return true;
        if (type == ContentType.SIGN) return signF;
        if (type == ContentType.BOOK) return bookF;
        if (type == ContentType.ITEM) return itemF;
        return true;
    }

    /**
     * Runs {@code pattern} against {@code matchText} (which may be a normalized view of
     * {@code originalText}, e.g. with homoglyphs folded) but builds the result from
     * {@code originalText}, using the match offsets. This only works correctly when
     * {@code matchText} and {@code originalText} have identical length (i.e. any normalization
     * applied is a 1:1 character mapping).
     */
    private FilterResult applyRegexFilter(String originalText, String matchText, Pattern pattern, Pattern whitelist, String repl) {
        boolean useNone = repl.equalsIgnoreCase("NONE");
        Matcher m = pattern.matcher(matchText);
        StringBuilder sb = new StringBuilder();
        boolean matched = false;
        int lastEnd = 0;

        while (m.find()) {
            int start = m.start(), end = m.end();
            sb.append(originalText, lastEnd, start);
            String matchStr = originalText.substring(start, end);
            if (whitelist != null && whitelist.matcher(matchStr).find()) {
                sb.append(matchStr);
            } else {
                matched = true;
                sb.append(useNone ? "" : repl.repeat(matchStr.length()));
            }
            lastEnd = end;
        }
        sb.append(originalText, lastEnd, originalText.length());
        return new FilterResult(sb.toString(), matched);
    }

    private double getRemainingTime(Player player, String type) {
        UUID uuid = player.getUniqueId();
        Map<UUID, Long> targetMap = switch (type) {
            case "target" -> lastGoodwordTimeWithTarget;
            case "additional" -> lastAdditionalTime;
            default -> lastGoodwordTimeNoTarget;
        };

        if (!targetMap.containsKey(uuid)) return 0;
        long now = System.currentTimeMillis();
        String configPath = switch (type) {
            case "target" -> "goodwords-detector.cooldown-seconds-target";
            case "additional" -> "goodwords-detector.additional-cooldown";
            default -> "goodwords-detector.cooldown-seconds-normal";
        };

        double cd = plugin.getConfigManager().getConfig().getDouble(configPath, 30.0) * 1000.0;
        long diff = now - targetMap.get(uuid);
        return diff >= cd ? 0 : (cd - diff) / 1000.0;
    }

    private double checkSpam(Player player, String message) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        FileConfiguration config = plugin.getConfigManager().getConfig();
        String lastText = lastMessageText.get(uuid);
        double cd = (message.equalsIgnoreCase(lastText))
                ? config.getDouble("anti-spam.same-message-cooldown-seconds", 2.0)
                : config.getDouble("anti-spam.message-cooldown-seconds", 1.5);
        long diff = now - lastMessageTime.getOrDefault(uuid, 0L);
        if (diff < (cd * 1000.0)) return (cd * 1000.0 - diff) / 1000.0;
        lastMessageTime.put(uuid, now);
        lastMessageText.put(uuid, message);
        return 0;
    }

    private boolean hasLetters(String text) {
        for (char c : text.toCharArray()) if (Character.isLetter(c)) return true;
        return false;
    }

    private boolean checkCaps(String m, int threshold) {
        int caps = 0, letters = 0;
        for (char c : m.toCharArray()) { if (Character.isLetter(c)) { letters++; if (Character.isUpperCase(c)) caps++; } }
        return letters > 3 && (caps * 100 / letters) >= threshold;
    }

    private void triggerCommands(String path, Player player, String msg, String orig, String target) {
        List<String> cmds = plugin.getConfigManager().getConfig().getStringList(path);
        if (cmds.isEmpty()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (String s : cmds) {
                String cmd = s.replace("%player%", player.getName())
                        .replace("%message%", msg)
                        .replace("%original%", orig)
                        .replace("%target%", target);
                if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI"))
                    cmd = PlaceholderAPI.setPlaceholders(player, cmd);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            }
        });
    }

    private void playConfigSound(Player player, String path) {
        String soundName = plugin.getConfigManager().getConfig().getString(path);
        if (soundName == null || soundName.equalsIgnoreCase("NONE") || soundName.isEmpty()) return;
        try {
            org.bukkit.Sound sound = org.bukkit.Sound.valueOf(soundName.toUpperCase());
            player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
        } catch (IllegalArgumentException e) {
            // Игнорируем ошибку звука
        }
    }

    private record FilterResult(String text, boolean wasFiltered) {}
}