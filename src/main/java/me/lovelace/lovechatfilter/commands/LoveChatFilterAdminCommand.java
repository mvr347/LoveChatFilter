package me.lovelace.lovechatfilter.commands;

import me.lovelace.lovechatfilter.LoveChatFilter;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Единая административная команда плагина: {@code /lovechatfilteradmin <subcommand>}.
 * <p>
 * Раньше единственная admin-функция (перезагрузка конфигурации) была зарыта внутри
 * {@code /lcf reload}, что не соответствует принятому в экосистеме Love* стилю единой
 * родительской admin-команды с подкомандами. {@code /lcf reload} по-прежнему работает,
 * но лишь подсказывает переехавшую команду — см. {@link Commands}.
 */
public class LoveChatFilterAdminCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("reload", "help");

    private final LoveChatFilter plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public LoveChatFilterAdminCommand(@NotNull LoveChatFilter plugin) {
        this.plugin = plugin;
    }

    @Override
    @SuppressWarnings("NullableProblems")
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("lovechatfilter.reload")) {
            plugin.getConfigManager().sendMessage(sender, "no-permission", null, null);
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> handleReload(sender);
            default -> sendHelp(sender);
        }
        return true;
    }

    private void handleReload(@NotNull CommandSender sender) {
        plugin.reloadPlugin();
        plugin.getConfigManager().sendMessage(sender, "reload-success", null, null);
    }

    private void sendHelp(@NotNull CommandSender sender) {
        sender.sendMessage(mm.deserialize(plugin.getConfigManager().getRawMsg("admin-help-header")));
        sender.sendMessage(mm.deserialize(plugin.getConfigManager().getRawMsg("admin-help-reload")));
        sender.sendMessage(mm.deserialize(plugin.getConfigManager().getRawMsg("admin-help-footer")));
    }

    @Nullable
    @Override
    @SuppressWarnings("NullableProblems")
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("lovechatfilter.reload")) return Collections.emptyList();

        if (args.length == 1) {
            return StringUtil.copyPartialMatches(args[0], SUBCOMMANDS, new ArrayList<>());
        }
        return Collections.emptyList();
    }
}
