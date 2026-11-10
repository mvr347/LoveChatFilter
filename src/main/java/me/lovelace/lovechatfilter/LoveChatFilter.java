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
import me.lovelace.lovechatfilter.managers.ViolationStats;
import me.lovelace.lovechatfilter.managers.WebSettings;
import me.lovelace.lovechatfilter.managers.WordLists;
import me.lovelace.lovechatfilter.managers.WordRules;
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
    private ViolationStats violationStats;
    /** Settings changed from the web panel, kept on top of config.yml (config.yml itself is never rewritten). */
    private final java.util.Map<String, String> overrides = new java.util.concurrent.ConcurrentHashMap<>();
    private java.util.concurrent.ExecutorService io;

    @Override
    public void onEnable() {
        // Apply the bundled style update before any module reads its documents.
        try {
            Class.forName("dev.lovelace.lovecore.gui.GuiStyles")
                    .getMethod("migrate", org.bukkit.plugin.java.JavaPlugin.class).invoke(null, this);
        } catch (java.lang.reflect.InvocationTargetException error) {
            throw new IllegalStateException("Обновление оформления остановлено; проверьте конфиги и резервные копии", error.getCause());
        } catch (ReflectiveOperationException error) {
            getLogger().warning("Оформление не обновлено: обновите LoveCore. Причина: " + error);
        }

        this.configManager = new ConfigManager(this);
        this.databaseManager = new DatabaseManager(this);
        this.grammarManager = new GrammarManager(this);
        this.wordLists = new WordLists(
                databaseManager,
                task -> getServer().getScheduler().runTaskAsynchronously(this, task),
                getLogger()::warning);
        this.wordLists.load();
        this.io = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "LoveChatFilter-io");
            thread.setDaemon(true);
            return thread;
        });
        this.overrides.putAll(databaseManager.loadOverrides());
        applyOverrides();
        this.violationStats = new ViolationStats(databaseManager);
        this.violationStats.load(java.time.LocalDate.now());
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> violationStats.flush(), 1200L, 1200L);
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
        if (io != null) {
            // Settings written a moment before shutdown still reach the database, in order.
            io.shutdown();
            try {
                io.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (violationStats != null) violationStats.flush();
        if (databaseManager != null) databaseManager.close();
    }

    public void reloadPlugin() {
        if (configManager != null) {
            configManager.reloadConfig();
            applyOverrides();
        }
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
    public ViolationStats getViolationStats() { return violationStats; }

    @Override
    public boolean isProfane(String text) {
        return filterEngine.isProfane(text);
    }

    @Override
    public boolean containsAdvertising(String text) {
        return filterEngine.hasAdvertising(text);
    }

    // ---- Administration API (LoveWebAdmin) ----

    private static WordRules.Kind kindOf(String kind) {
        WordRules.Kind parsed = WordRules.Kind.parse(kind);
        if (parsed == null) throw new IllegalArgumentException("Неизвестный вид слов: используйте bad, safe или ad.");
        return parsed;
    }

    @Override
    public java.util.List<java.util.Map<String, Object>> listWords(String kind) {
        WordRules.Kind parsed = kindOf(kind);
        java.util.List<java.util.Map<String, Object>> rows = new java.util.ArrayList<>();
        for (WordLists.Entry entry : wordLists.list(parsed)) {
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("word", entry.word());
            row.put("source", "game");
            row.put("addedBy", entry.addedBy());
            row.put("addedAt", entry.addedAt());
            rows.add(row);
        }
        for (String word : new java.util.TreeSet<>(filterEngine.configWords(parsed))) {
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("word", word);
            row.put("source", "config");
            rows.add(row);
        }
        return rows;
    }

    @Override
    public String addWord(String kind, String word, String addedBy) {
        WordRules.Kind parsed = kindOf(kind);
        String stored = wordLists.add(parsed, word, addedBy, filterEngine.configWords(parsed));
        filterEngine.rebuildFilters();
        return stored;
    }

    @Override
    public boolean removeWord(String kind, String word) {
        boolean removed = wordLists.remove(kindOf(kind), word);
        if (removed) filterEngine.rebuildFilters();
        return removed;
    }

    @Override
    public java.util.Map<String, Object> checkText(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Введите текст для проверки.");
        if (text.length() > 500) throw new IllegalArgumentException("Текст длиннее 500 символов.");
        var preview = filterEngine.preview(text);
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("text", preview.text());
        result.put("clean", !preview.profane() && !preview.ads() && !preview.caps());
        result.put("profane", preview.profane());
        result.put("layers", new java.util.ArrayList<>(preview.layers()));
        result.put("ads", preview.ads());
        result.put("caps", preview.caps());
        return result;
    }

    @Override
    public java.util.List<java.util.Map<String, Object>> settings() {
        return WebSettings.describe(key -> getConfig().get(key), java.util.Set.copyOf(overrides.keySet()));
    }

    @Override
    public void changeSetting(String key, String value) {
        Object checked = WebSettings.validate(key, value);
        getConfig().set(key, checked);
        overrides.put(key, checked.toString());
        io.execute(() -> {
            try {
                databaseManager.saveOverride(key, checked.toString());
            } catch (RuntimeException failed) {
                getLogger().warning("Настройка " + key + " не сохранена в базе: " + failed.getMessage());
            }
        });
        filterEngine.rebuildFilters();
    }

    @Override
    public void resetSetting(String key) {
        if (WebSettings.find(key) == null) throw new IllegalArgumentException("Эту настройку менять нельзя.");
        if (overrides.remove(key) == null) return;
        io.execute(() -> {
            try {
                databaseManager.deleteOverride(key);
            } catch (RuntimeException failed) {
                getLogger().warning("Настройка " + key + " не сброшена в базе: " + failed.getMessage());
            }
        });
        // The file value comes back by re-reading config.yml; the other overrides are put on top again.
        configManager.reloadConfig();
        applyOverrides();
        filterEngine.rebuildFilters();
    }

    @Override
    public java.util.Map<String, Object> statistics(int days) {
        return violationStats.summary(days, java.time.LocalDate.now());
    }

    /** Puts every saved panel override on top of the loaded config; one that no longer validates is skipped. */
    private void applyOverrides() {
        overrides.forEach((key, value) -> {
            try {
                getConfig().set(key, WebSettings.validate(key, value));
            } catch (IllegalArgumentException invalid) {
                getLogger().warning("Сохранённая настройка " + key + " пропущена: " + invalid.getMessage());
            }
        });
    }
}
