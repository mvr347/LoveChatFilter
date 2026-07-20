package me.lovelace.lovechatfilter.commands;

import me.lovelace.lovechatfilter.LoveChatFilter;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import java.util.ArrayList;
import java.util.List;

public class Commands implements CommandExecutor, TabCompleter {
    private final LoveChatFilter plugin;

    public Commands(LoveChatFilter plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!command.getName().equalsIgnoreCase("lcf")) return false;

        if (args.length == 0) return false;

        if (args[0].equalsIgnoreCase("grammar") && sender instanceof Player player) {
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

        if (args[0].equalsIgnoreCase("reload")) {
            if (sender.hasPermission("lovechatfilter.reload")) {
                plugin.reloadPlugin();
                plugin.getConfigManager().sendMessage(sender, "reload-success", null, null);
            } else {
                plugin.getConfigManager().sendMessage(sender, "no-permission", null, null);
            }
            return true;
        }

        return false;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        List<String> list = new ArrayList<>();

        if (command.getName().equalsIgnoreCase("lcf") && args.length == 1) {
            if (sender.hasPermission("lovechatfilter.reload")) list.add("reload");
            if (sender instanceof Player && plugin.getConfigManager().isModuleEnabled("grammar-fix")) {
                list.add("grammar");
            }
        }
        return list;
    }
}
