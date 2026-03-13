package me.lovelace.advancedchatfilter;

import org.bukkit.Bukkit;

import java.io.File;
import java.sql.*;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

@SuppressWarnings({"SqlNoDataSourceInspection", "SqlResolve"})
public class DatabaseManager {
    private final AdvancedChatFilter plugin;
    private Connection connection;
    private final ConcurrentHashMap<UUID, Boolean> grammarCache = new ConcurrentHashMap<>();

    public DatabaseManager(AdvancedChatFilter plugin) {
        this.plugin = plugin;
        connect();
    }

    private void connect() {
        try {
            File dataFolder = plugin.getDataFolder();
            if (!dataFolder.exists()) {
                if (!dataFolder.mkdirs()) {
                    plugin.getLogger().warning("Не удалось создать папку плагина для базы данных!");
                }
            }
            File dbFile = new File(dataFolder, "database.db");
            String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
            connection = DriverManager.getConnection(url);

            try (Statement stmt = connection.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS grammar_users (uuid VARCHAR(36) PRIMARY KEY, enabled BOOLEAN)");
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Не удалось подключиться к базе данных SQLite!", e);
        }
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Ошибка при закрытии соединения с базой данных!", e);
        }
    }

    public boolean isGrammarEnabled(UUID uuid) {
        if (grammarCache.containsKey(uuid)) {
            return grammarCache.get(uuid);
        }
        boolean enabled = false;
        try (PreparedStatement pstmt = connection.prepareStatement("SELECT enabled FROM grammar_users WHERE uuid = ?")) {
            pstmt.setString(1, uuid.toString());
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    enabled = rs.getBoolean("enabled");
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Ошибка при получении данных игрока из БД!", e);
        }
        grammarCache.put(uuid, enabled);
        return enabled;
    }

    public void setGrammarEnabled(UUID uuid, boolean enabled) {
        grammarCache.put(uuid, enabled);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (PreparedStatement pstmt = connection.prepareStatement("INSERT OR REPLACE INTO grammar_users (uuid, enabled) VALUES (?, ?)")) {
                pstmt.setString(1, uuid.toString());
                pstmt.setBoolean(2, enabled);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Ошибка при сохранении данных игрока в БД!", e);
            }
        });
    }
}