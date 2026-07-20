package me.lovelace.lovechatfilter;

import me.lovelace.lovechatfilter.commands.Commands;
import me.lovelace.lovechatfilter.filters.FilterEngine;
import me.lovelace.lovechatfilter.listeners.ChatListener;
import me.lovelace.lovechatfilter.managers.ConfigManager;
import me.lovelace.lovechatfilter.managers.DatabaseManager;
import me.lovelace.lovechatfilter.managers.GrammarManager;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public class LoveChatFilter extends JavaPlugin {

    private ConfigManager configManager;
    private DatabaseManager databaseManager;
    private FilterEngine filterEngine;
    private GrammarManager grammarManager;

    @Override
    public void onEnable() {
        this.configManager = new ConfigManager(this);
        this.databaseManager = new DatabaseManager(this);
        this.grammarManager = new GrammarManager(this);
        this.filterEngine = new FilterEngine(this);

        getServer().getPluginManager().registerEvents(new ChatListener(this), this);

        Commands cmdHandler = new Commands(this);

        PluginCommand lcfCmd = getCommand("lcf");
        if (lcfCmd != null) {
            lcfCmd.setExecutor(cmdHandler);
            lcfCmd.setTabCompleter(cmdHandler);
        }
    }

    @Override
    public void onDisable() {
        if (databaseManager != null) databaseManager.close();
    }

    public void reloadPlugin() {
        if (configManager != null) configManager.reloadConfig();
        if (grammarManager != null) grammarManager.reload();
        if (filterEngine != null) filterEngine.reloadCache();
    }

    public ConfigManager getConfigManager() { return configManager; }
    public DatabaseManager getDatabaseManager() { return databaseManager; }
    public FilterEngine getFilterEngine() { return filterEngine; }
    public GrammarManager getGrammarManager() { return grammarManager; }
}
