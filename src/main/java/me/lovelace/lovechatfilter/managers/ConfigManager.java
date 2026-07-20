package me.lovelace.lovechatfilter.managers;

import me.lovelace.lovechatfilter.LoveChatFilter;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class ConfigManager {
    private final LoveChatFilter plugin;
    private FileConfiguration config;
    private FileConfiguration messages;

    public ConfigManager(LoveChatFilter plugin) {
        this.plugin = plugin;
        loadFiles();
    }

    public void loadFiles() {
        plugin.saveDefaultConfig();
        File msgFile = new File(plugin.getDataFolder(), "messages.yml");
        if (!msgFile.exists()) plugin.saveResource("messages.yml", false);

        plugin.reloadConfig();
        config = plugin.getConfig();
        messages = YamlConfiguration.loadConfiguration(msgFile);

        // Подтягиваем дефолты из встроенного ресурса, чтобы новые ключи
        // работали даже если файл на диске был создан старой версией плагина
        try (InputStream stream = plugin.getResource("messages.yml")) {
            if (stream != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(stream, StandardCharsets.UTF_8));
                messages.setDefaults(defaults);
            }
        } catch (IOException ignored) {}
    }

    public void reloadConfig() {
        loadFiles();
    }

    public boolean isModuleEnabled(String module) {
        return config.getBoolean("modules." + module, false);
    }

    public void sendMessage(CommandSender sender, String path, String placeholder, String value) {
        String raw = messages.getString(path, "");

        if (raw.isEmpty() || raw.equalsIgnoreCase("NONE")) return;

        if (placeholder != null && value != null) {
            raw = raw.replace(placeholder, value);
        }

        String prefix = messages.getString("prefix", "");
        if (prefix.equalsIgnoreCase("NONE")) prefix = "";

        sender.sendMessage(MiniMessage.miniMessage().deserialize(prefix + raw));
    }

    public FileConfiguration getConfig() { return config; }

    @SuppressWarnings("unused")
    public FileConfiguration getMessages() { return messages; }
}
