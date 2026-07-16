package me.lovelace.lovechatfilter;

import me.lovelace.lovechatfilter.commands.Commands;
import me.lovelace.lovechatfilter.filters.FilterEngine;
import me.lovelace.lovechatfilter.listeners.ChatListener;
import me.lovelace.lovechatfilter.managers.ConfigManager;
import me.lovelace.lovechatfilter.managers.DatabaseManager;
import me.lovelace.lovechatfilter.managers.GrammarManager;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

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

        PluginCommand acfCmd = getCommand("acf");
        if (acfCmd != null) {
            acfCmd.setExecutor(cmdHandler);
            acfCmd.setTabCompleter(cmdHandler);
        }

    }

    public void exportLogsAsync(int numLines, Consumer<String> callback) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            List<String> lines = readLastLines(numLines);
            if (lines.isEmpty()) {
                callback.accept(null);
                return;
            }

            File logDir = new File(getDataFolder(), "logs");
            if (!logDir.exists()) logDir.mkdirs();

            String timeStamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
            String fileName = "log_" + timeStamp + "_" + lines.size() + "lines.txt";
            File logFile = new File(logDir, fileName);

            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(logFile), StandardCharsets.UTF_8))) {
                for (String line : lines) {
                    writer.write(line.replaceAll("\u001B\\[[;\\d]*m", ""));
                    writer.newLine();
                }
                callback.accept(logFile.getName());
            } catch (IOException e) {
                callback.accept(null);
            }
        });
    }

    private List<String> readLastLines(int numLines) {
        File file = new File("logs" + File.separator + "latest.log");
        if (!file.exists()) return Collections.emptyList();

        List<String> result = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long pointer = file.length() - 1;
            List<Byte> byteBuffer = new ArrayList<>();

            while (pointer >= 0 && result.size() < numLines) {
                raf.seek(pointer);
                int b = raf.read();
                if (b == '\n') {
                    if (!byteBuffer.isEmpty()) {
                        Collections.reverse(byteBuffer);
                        byte[] arr = new byte[byteBuffer.size()];
                        for (int i = 0; i < byteBuffer.size(); i++) arr[i] = byteBuffer.get(i);
                        String line = new String(arr, StandardCharsets.UTF_8);

                        // Если это НЕ команда авторизации, добавляем в результат
                        if (!isAuthCommandLog(line)) {
                            result.add(line);
                        }
                        byteBuffer.clear();
                    }
                } else if (b != '\r') {
                    byteBuffer.add((byte) b);
                }
                pointer--;
            }
            if (!byteBuffer.isEmpty() && result.size() < numLines) {
                Collections.reverse(byteBuffer);
                byte[] arr = new byte[byteBuffer.size()];
                for (int i = 0; i < byteBuffer.size(); i++) arr[i] = byteBuffer.get(i);
                String line = new String(arr, StandardCharsets.UTF_8);
                if (!isAuthCommandLog(line)) {
                    result.add(line);
                }
            }
            Collections.reverse(result);
        } catch (IOException e) {
            return Collections.emptyList();
        }
        return result;
    }

    // Метод для выявления команд ввода паролей
    private boolean isAuthCommandLog(String line) {
        String lowerLine = line.toLowerCase();

        // В логах Paper/Spigot команды игроков всегда пишутся по такому паттерну:
        if (!lowerLine.contains("issued server command: /")) return false;

        List<String> ignoredCmds = new ArrayList<>();
        if (configManager != null && configManager.getConfig() != null) {
            ignoredCmds.addAll(configManager.getConfig().getStringList("ignore-commands"));
        }

        // Жестко вшиваем команды LibreLogin и AuthMe для надежности, даже если их случайно удалят из конфига
        List<String> defaultAuthCmds = List.of("l", "login", "reg", "register", "cp", "changepassword", "auth");
        for (String def : defaultAuthCmds) {
            if (!ignoredCmds.contains(def)) ignoredCmds.add(def);
        }

        // Проверяем, есть ли совпадение
        for (String cmd : ignoredCmds) {
            if (lowerLine.contains("issued server command: /" + cmd + " ") ||
                    lowerLine.endsWith("issued server command: /" + cmd)) {
                return true;
            }
        }
        return false;
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