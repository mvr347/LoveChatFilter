package me.lovelace.lovechatfilter.commands;

import me.lovelace.lovechatfilter.LoveChatFilter;
import me.lovelace.lovechatfilter.managers.WordLists;
import me.lovelace.lovechatfilter.managers.WordRules;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Единая административная команда плагина: {@code /lovechatfilteradmin <subcommand>}.
 * <ul>
 *   <li>{@code reload} - перезагрузка конфигурации (право {@code lovechatfilter.reload});</li>
 *   <li>{@code add|remove|list|check} - слова, добавляемые из игры: мат, безопасные слова и
 *       реклама (право {@code lovechatfilter.manage}).</li>
 * </ul>
 * Без нужного права команда молчит, как и остальные команды экосистемы Love*.
 */
public class LoveChatFilterAdminCommand implements CommandExecutor, TabCompleter {

    static final String RELOAD = "lovechatfilter.reload";
    static final String MANAGE = "lovechatfilter.manage";
    static final int PAGE_SIZE = 10;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final LoveChatFilter plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public LoveChatFilterAdminCommand(@NotNull LoveChatFilter plugin) {
        this.plugin = plugin;
    }

    @Override
    @SuppressWarnings("NullableProblems")
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        boolean canReload = sender.hasPermission(RELOAD);
        boolean canManage = sender.hasPermission(MANAGE);
        // Silent on purpose: a command the sender cannot use is simply not theirs.
        if (!canReload && !canManage) return true;

        if (args.length == 0) {
            sendHelp(sender, canReload, canManage);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> { if (canReload) handleReload(sender); }
            case "add" -> { if (canManage) handleAdd(sender, args); }
            case "remove" -> { if (canManage) handleRemove(sender, args); }
            case "list" -> { if (canManage) handleList(sender, args); }
            case "check" -> { if (canManage) handleCheck(sender, args); }
            default -> sendHelp(sender, canReload, canManage);
        }
        return true;
    }

    private void handleReload(@NotNull CommandSender sender) {
        plugin.reloadPlugin();
        plugin.getConfigManager().sendMessage(sender, "reload-success", null, null);
    }

    private void handleAdd(CommandSender sender, String[] args) {
        WordRules.Kind kind = args.length >= 2 ? WordRules.Kind.parse(args[1]) : null;
        if (kind == null || args.length < 3) {
            plugin.getConfigManager().sendMessage(sender, "words-usage-add", Map.of());
            return;
        }
        String word = String.join(" ", List.of(args).subList(2, args.length));
        try {
            String stored = plugin.getWordLists().add(kind, word, sender.getName(), plugin.getFilterEngine().configWords(kind));
            plugin.getFilterEngine().rebuildFilters();
            plugin.getConfigManager().sendMessage(sender, "words-added", Map.of("kind", kindName(kind), "word", stored));
        } catch (IllegalArgumentException refused) {
            plugin.getConfigManager().sendMessage(sender, "words-refused", Map.of("reason", refused.getMessage()));
        }
    }

    private void handleRemove(CommandSender sender, String[] args) {
        WordRules.Kind kind = args.length >= 2 ? WordRules.Kind.parse(args[1]) : null;
        if (kind == null || args.length < 3) {
            plugin.getConfigManager().sendMessage(sender, "words-usage-remove", Map.of());
            return;
        }
        String word = String.join(" ", List.of(args).subList(2, args.length));
        if (plugin.getWordLists().remove(kind, word)) {
            plugin.getFilterEngine().rebuildFilters();
            plugin.getConfigManager().sendMessage(sender, "words-removed", Map.of("kind", kindName(kind), "word", word));
        } else {
            plugin.getConfigManager().sendMessage(sender, "words-not-found", Map.of("word", word));
        }
    }

    private void handleList(CommandSender sender, String[] args) {
        WordRules.Kind kind = args.length >= 2 ? WordRules.Kind.parse(args[1]) : null;
        if (kind == null) {
            plugin.getConfigManager().sendMessage(sender, "words-usage-list", Map.of());
            return;
        }
        List<WordLists.Entry> entries = plugin.getWordLists().list(kind);
        if (entries.isEmpty()) {
            plugin.getConfigManager().sendMessage(sender, "words-list-empty", Map.of("kind", kindName(kind)));
            return;
        }
        int pages = (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        int page = 1;
        if (args.length >= 3) {
            try {
                page = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                plugin.getConfigManager().sendMessage(sender, "invalid-number", null, null);
                return;
            }
        }
        page = Math.max(1, Math.min(page, pages));
        plugin.getConfigManager().sendMessage(sender, "words-list-header", Map.of(
                "kind", kindName(kind), "page", String.valueOf(page), "pages", String.valueOf(pages), "count", String.valueOf(entries.size())));
        for (WordLists.Entry entry : entries.subList((page - 1) * PAGE_SIZE, Math.min(entries.size(), page * PAGE_SIZE))) {
            plugin.getConfigManager().sendMessage(sender, "words-list-line", Map.of(
                    "word", entry.word(), "by", entry.addedBy(), "date", DATE.format(Instant.ofEpochSecond(entry.addedAt()))));
        }
    }

    private void handleCheck(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.getConfigManager().sendMessage(sender, "words-usage-check", Map.of());
            return;
        }
        String text = String.join(" ", List.of(args).subList(1, args.length));
        var preview = plugin.getFilterEngine().preview(text);
        boolean clean = !preview.profane() && !preview.ads() && !preview.caps();
        if (clean) {
            plugin.getConfigManager().sendMessage(sender, "check-clean", Map.of("text", preview.text()));
            return;
        }
        List<String> found = new ArrayList<>();
        if (preview.profane()) found.add("мат (" + String.join(", ", preview.layers()) + ")");
        if (preview.ads()) found.add("реклама");
        if (preview.caps()) found.add("капс");
        plugin.getConfigManager().sendMessage(sender, "check-found", Map.of("found", String.join(", ", found), "text", preview.text()));
    }

    private String kindName(WordRules.Kind kind) {
        return plugin.getConfigManager().getRawMsg("kind-" + kind.id());
    }

    private void sendHelp(@NotNull CommandSender sender, boolean canReload, boolean canManage) {
        sender.sendMessage(mm.deserialize(plugin.getConfigManager().getRawMsg("admin-help-header")));
        if (canReload) sender.sendMessage(mm.deserialize(plugin.getConfigManager().getRawMsg("admin-help-reload")));
        if (canManage) {
            for (String key : List.of("admin-help-add", "admin-help-remove", "admin-help-list", "admin-help-check"))
                sender.sendMessage(mm.deserialize(plugin.getConfigManager().getRawMsg(key)));
        }
        sender.sendMessage(mm.deserialize(plugin.getConfigManager().getRawMsg("admin-help-footer")));
    }

    @Nullable
    @Override
    @SuppressWarnings("NullableProblems")
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        boolean canReload = sender.hasPermission(RELOAD);
        boolean canManage = sender.hasPermission(MANAGE);
        if (!canReload && !canManage) return Collections.emptyList();

        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            if (canReload) options.add("reload");
            if (canManage) options.addAll(List.of("add", "remove", "list", "check"));
            options.add("help");
            return StringUtil.copyPartialMatches(args[0], options, new ArrayList<>());
        }
        if (!canManage) return Collections.emptyList();

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && List.of("add", "remove", "list").contains(sub)) {
            return StringUtil.copyPartialMatches(args[1], List.of("bad", "safe", "ad", "мат", "безопасное", "реклама"), new ArrayList<>());
        }
        if (args.length == 3 && sub.equals("remove")) {
            WordRules.Kind kind = WordRules.Kind.parse(args[1]);
            if (kind != null) return StringUtil.copyPartialMatches(args[2], plugin.getWordLists().words(kind), new ArrayList<>());
        }
        return Collections.emptyList();
    }
}
