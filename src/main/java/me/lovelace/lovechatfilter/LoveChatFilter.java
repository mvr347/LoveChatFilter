package me.lovelace.lovechatfilter;

import me.lovelace.lovechatfilter.api.LoveChatFilterAPI;
import me.lovelace.lovechatfilter.commands.Commands;
import me.lovelace.lovechatfilter.commands.LoveChatFilterAdminCommand;
import me.lovelace.lovechatfilter.filters.FilterEngine;
import me.lovelace.lovechatfilter.listeners.ChatListener;
import me.lovelace.lovechatfilter.listeners.CommandTabFilterListener;
import me.lovelace.lovechatfilter.managers.ConfigManager;
import me.lovelace.lovechatfilter.managers.DatabaseManager;
import me.lovelace.lovechatfilter.managers.GrammarManager;
import me.lovelace.lovechatfilter.managers.WordLists;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

public class LoveChatFilter extends JavaPlugin implements LoveChatFilterAPI {

    private ConfigManager configManager;
    private DatabaseManager databaseManager;
    private me.lovelace.lovechatfilter.expansion.FilterPlaceholderExpansion placeholderExpansion;
    private FilterEngine filterEngine;
    private GrammarManager grammarManager;
    private WordLists wordLists;
    private CommandTabFilterListener commandTabFilterListener;

    @Override
    public void onEnable() {
        this.configManager = new ConfigManager(this);
        this.databaseManager = new DatabaseManager(this);
        this.grammarManager = new GrammarManager(this);
        this.wordLists = new WordLists(
                databaseManager,
                task -> getServer().getScheduler().runTaskAsynchronously(this, task),
                getLogger()::warning);
        this.wordLists.load();
        this.filterEngine = new FilterEngine(this);

        getServer().getPluginManager().registerEvents(new ChatListener(this), this);

        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            placeholderExpansion = new me.lovelace.lovechatfilter.expansion.FilterPlaceholderExpansion(this);
            placeholderExpansion.register();
        }

        this.commandTabFilterListener = new CommandTabFilterListener(this);
        getServer().getPluginManager().registerEvents(commandTabFilterListener, this);

        Commands cmdHandler = new Commands(this);

        PluginCommand lcfCmd = getCommand("lcf");
        if (lcfCmd != null) {
            lcfCmd.setExecutor(cmdHandler);
            lcfCmd.setTabCompleter(cmdHandler);
        }

        PluginCommand grammarCmd = getCommand("grammar");
        if (grammarCmd != null) {
            grammarCmd.setExecutor(cmdHandler);
            grammarCmd.setTabCompleter(cmdHandler);
        }

        LoveChatFilterAdminCommand adminCmdHandler = new LoveChatFilterAdminCommand(this);
        PluginCommand adminCmd = getCommand("lovechatfilteradmin");
        if (adminCmd != null) {
            adminCmd.setExecutor(adminCmdHandler);
            adminCmd.setTabCompleter(adminCmdHandler);
        }

        getServer().getServicesManager().register(LoveChatFilterAPI.class, this, this, ServicePriority.Normal);
    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll(this);
        if (placeholderExpansion != null) {
            placeholderExpansion.unregister();
            placeholderExpansion = null;
        }
        if (wordLists != null) wordLists.flush();
        if (databaseManager != null) databaseManager.close();
    }

    public void reloadPlugin() {
        if (configManager != null) configManager.reloadConfig();
        if (grammarManager != null) grammarManager.reload();
        if (filterEngine != null) filterEngine.reloadCache();
        if (commandTabFilterListener != null) commandTabFilterListener.reload();
    }

    public ConfigManager getConfigManager() { return configManager; }
    public DatabaseManager getDatabaseManager() { return databaseManager; }
    public FilterEngine getFilterEngine() { return filterEngine; }
    public GrammarManager getGrammarManager() { return grammarManager; }
    public WordLists getWordLists() { return wordLists; }
    public CommandTabFilterListener getCommandTabFilterListener() { return commandTabFilterListener; }

    @Override
    public boolean isProfane(String text) {
        return filterEngine.isProfane(text);
    }

    @Override
    public boolean containsAdvertising(String text) {
        return filterEngine.hasAdvertising(text);
    }
}
