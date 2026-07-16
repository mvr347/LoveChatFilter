package me.lovelace.lovechatfilter.managers;

import me.lovelace.lovechatfilter.LoveChatFilter;
import org.bukkit.Bukkit;

import java.io.File;
import java.sql.*;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings({"SqlNoDataSourceInspection", "SqlResolve"})
public class DatabaseManager {
    private final LoveChatFilter plugin;
    private Connection connection;
    private final ConcurrentHashMap<UUID, Boolean> grammarCache = new ConcurrentHashMap<>();

    public DatabaseManager(LoveChatFilter plugin) {
        this.plugin = plugin;
        connect();
    }

    private void connect() {
        try {
            File dataFolder = plugin.getDataFolder();
            if (!dataFolder.exists()) dataFolder.mkdirs();
            File dbFile = new File(dataFolder, "database.db");
            String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
            connection = DriverManager.getConnection(url);

            try (Statement stmt = connection.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS grammar_users (uuid VARCHAR(36) PRIMARY KEY, enabled BOOLEAN)");
            }
        } catch (SQLException ignored) {}
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException ignored) {}
    }

    public boolean isGrammarEnabled(UUID uuid) {
        Boolean cached = grammarCache.get(uuid);
        if (cached != null) {
            return cached;
        }
        preloadGrammarEnabled(uuid);
        return false;
    }

    public void preloadGrammarEnabled(UUID uuid) {
        if (grammarCache.containsKey(uuid)) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean enabled = false;
            try (PreparedStatement pstmt = connection.prepareStatement("SELECT enabled FROM grammar_users WHERE uuid = ?")) {
                pstmt.setString(1, uuid.toString());
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        enabled = rs.getBoolean("enabled");
                    }
                }
            } catch (SQLException ignored) {}
            grammarCache.put(uuid, enabled);
        });
    }

    public void setGrammarEnabled(UUID uuid, boolean enabled) {
        grammarCache.put(uuid, enabled);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (PreparedStatement pstmt = connection.prepareStatement("INSERT OR REPLACE INTO grammar_users (uuid, enabled) VALUES (?, ?)")) {
                pstmt.setString(1, uuid.toString());
                pstmt.setBoolean(2, enabled);
                pstmt.executeUpdate();
            } catch (SQLException ignored) {}
        });
    }
}