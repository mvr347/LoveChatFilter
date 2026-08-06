package me.lovelace.lovechatfilter.commands;

import me.lovelace.lovechatfilter.LoveChatFilter;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Игроцкая команда плагина: {@code /lcf} (алиас {@code /lovechatfilter}) и её короткий
 * прямой алиас {@code /grammar} ({@code /грамматика}) для переключения авто-исправления
 * грамматики — единственной функции плагина, доступной обычным игрокам.
 * <p>
 * Административные функции (перезагрузка конфигурации) переехали в отдельную
 * единую команду {@code /lovechatfilteradmin}, как принято в экосистеме Love*.
 * {@code /lcf reload} продолжает работать как редирект на неё, чтобы ничего не
 * ломалось для тех, кто набирает команду по привычке.
 */
public class Commands implements CommandExecutor, TabCompleter {
    private final LoveChatFilter plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public Commands(LoveChatFilter plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        boolean isMainCmd = command.getName().equalsIgnoreCase("lcf");
        boolean isGrammarCmd = command.getName().equalsIgnoreCase("grammar")
                || (isMainCmd && args.length >= 1 && args[0].equalsIgnoreCase("grammar"));

        if (isGrammarCmd) {
            return handleGrammar(sender);
        }

        if (!isMainCmd) return false;

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> handleReloadRedirect(sender);
            case "help" -> sendHelp(sender);
            default -> sendHelp(sender);
        }
        return true;
    }

    private boolean handleGrammar(@NotNull CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.getConfigManager().sendMessage(sender, "only-players", null, null);
            return true;
        }

        if (!plugin.getConfigManager().isModuleEnabled("grammar-fix")) {
            plugin.getConfigManager().sendMessage(player, "grammar-module-disabled", null, null);
            return true;
        }

        boolean state = plugin.getGrammarManager().toggle(player);
        if (state) {
            plugin.getConfigManager().sendMessage(player, "grammar-enabled", null, null);
        } else {
            plugin.getConfigManager().sendMessage(player, "grammar-disabled", null, null);
        }
        return true;
    }

    private void handleReloadRedirect(@NotNull CommandSender sender) {
        // reload переехал под /lovechatfilteradmin — здесь только понятная подсказка,
        // чтобы команда не «молчала» для тех, кто по привычке набирает /lcf reload.
        if (sender.hasPermission("lovechatfilter.reload")) {
            plugin.getConfigManager().sendMessage(sender, "reload-moved", null, null);
        } else {
            plugin.getConfigManager().sendMessage(sender, "no-permission", null, null);
        }
    }

    private void sendHelp(@NotNull CommandSender sender) {
        sender.sendMessage(mm.deserialize(plugin.getConfigManager().getRawMsg("help-header")));
        sender.sendMessage(mm.deserialize(plugin.getConfigManager().getRawMsg("help-grammar")));
        if (sender.hasPermission("lovechatfilter.reload")) {
            sender.sendMessage(mm.deserialize(plugin.getConfigManager().getRawMsg("help-admin")));
        }
        sender.sendMessage(mm.deserialize(plugin.getConfigManager().getRawMsg("help-footer")));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        List<String> list = new ArrayList<>();

        if (command.getName().equalsIgnoreCase("lcf") && args.length == 1) {
            if (sender instanceof Player && plugin.getConfigManager().isModuleEnabled("grammar-fix")) {
                list.add("grammar");
            }
            if (sender.hasPermission("lovechatfilter.reload")) list.add("reload");
            list.add("help");
        }
        return list;
    }
}
