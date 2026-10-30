package me.lovelace.lovechatfilter.managers;

import me.lovelace.lovechatfilter.LoveChatFilter;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.bukkit.Bukkit;

import java.io.File;
import java.sql.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@SuppressWarnings({"SqlNoDataSourceInspection", "SqlResolve"})
public class DatabaseManager implements WordLists.WordStore {
    private final LoveChatFilter plugin;
    private Connection connection;
    private volatile boolean isShuttingDown = false;
    // Plain cache (not LoadingCache): a miss must never run SQL on the chat/command thread.
    private final Cache<UUID, Boolean> grammarCache = Caffeine.newBuilder()
            .maximumSize(10000)
            .expireAfterAccess(1, TimeUnit.HOURS)
            .build();
    private final java.util.Set<UUID> grammarLoading = java.util.concurrent.ConcurrentHashMap.newKeySet();

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
                stmt.execute("CREATE TABLE IF NOT EXISTS custom_words (kind TEXT NOT NULL, word TEXT NOT NULL, added_by TEXT NOT NULL, added_at INTEGER NOT NULL, PRIMARY KEY (kind, word))");
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to connect to database or create table: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void close() {
        isShuttingDown = true;
        // Brief sleep to allow in-flight async operations to complete
        try {
            Thread.sleep(200);
        } catch (InterruptedException ignored) {}

        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Error closing database connection: " + e.getMessage());
        }
    }

    /** Hot-path read: never touches the database. Unknown state reads as false and triggers an async load. */
    public boolean isGrammarEnabled(UUID uuid) {
        Boolean cached = grammarCache.getIfPresent(uuid);
        if (cached != null) return cached;
        preloadGrammarEnabled(uuid);
        return false;
    }

    /** Cached grammar flag, or null while it is still loading (a load is started on a miss). */
    public Boolean grammarState(UUID uuid) {
        Boolean cached = grammarCache.getIfPresent(uuid);
        if (cached == null) preloadGrammarEnabled(uuid);
        return cached;
    }

    /** Loads the player's grammar flag asynchronously (called on join and on cache miss). */
    public void preloadGrammarEnabled(UUID uuid) {
        if (isShuttingDown || grammarCache.getIfPresent(uuid) != null || !grammarLoading.add(uuid)) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                boolean enabled = loadGrammarEnabledFromDatabase(uuid);
                // putIfAbsent: a toggle made while the load was running must win over the stale DB value.
                grammarCache.asMap().putIfAbsent(uuid, enabled);
            } finally {
                grammarLoading.remove(uuid);
            }
        });
    }

    private boolean loadGrammarEnabledFromDatabase(UUID uuid) {
        if (isShuttingDown || connection == null) {
            return false;
        }

        try (PreparedStatement pstmt = connection.prepareStatement("SELECT enabled FROM grammar_users WHERE uuid = ?")) {
            pstmt.setString(1, uuid.toString());
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getBoolean("enabled");
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to load grammar state for " + uuid + ": " + e.getMessage());
        }
        return false;
    }

    public void setGrammarEnabled(UUID uuid, boolean enabled) {
        grammarCache.put(uuid, enabled);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (isShuttingDown || connection == null) {
                return;
            }

            try (PreparedStatement pstmt = connection.prepareStatement("INSERT OR REPLACE INTO grammar_users (uuid, enabled) VALUES (?, ?)")) {
                pstmt.setString(1, uuid.toString());
                pstmt.setBoolean(2, enabled);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to save grammar state for " + uuid + ": " + e.getMessage());
            }
        });
    }

    // ---- words added from the game (WordLists) ----
    // The connection is shared by async tasks, so every access here is serialised.

    @Override
    public synchronized List<WordLists.Entry> loadWords() {
        List<WordLists.Entry> result = new java.util.ArrayList<>();
        if (connection == null) return result;
        try (PreparedStatement ps = connection.prepareStatement("SELECT kind, word, added_by, added_at FROM custom_words");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                WordRules.Kind kind = WordRules.Kind.valueOf(rs.getString("kind"));
                result.add(new WordLists.Entry(kind, rs.getString("word"), rs.getString("added_by"), rs.getLong("added_at")));
            }
        } catch (SQLException | IllegalArgumentException e) {
            plugin.getLogger().warning("Не удалось прочитать добавленные слова: " + e.getMessage());
        }
        return result;
    }

    @Override
    public synchronized void saveWord(WordLists.Entry entry) {
        if (isShuttingDown || connection == null) return;
        try (PreparedStatement ps = connection.prepareStatement("INSERT OR REPLACE INTO custom_words (kind, word, added_by, added_at) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, entry.kind().name());
            ps.setString(2, entry.word());
            ps.setString(3, entry.addedBy());
            ps.setLong(4, entry.addedAt());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @Override
    public synchronized void deleteWord(WordRules.Kind kind, String word) {
        if (isShuttingDown || connection == null) return;
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM custom_words WHERE kind = ? AND word = ?")) {
            ps.setString(1, kind.name());
            ps.setString(2, word);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }
}
