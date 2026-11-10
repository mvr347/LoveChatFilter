package me.lovelace.lovechatfilter.managers;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The allowed ("safe") words in a SQLite file of their own ({@code safe-words.db}), apart from {@code database.db}: the shipped list
 * holds thousands of rows and is rewritten by plugin updates, the main database holds what admins and the web panel change.
 *
 * <ul>
 *   <li>{@code bundled_words}: words shipped with the plugin, filled from the jar when its content changes. A new release adds
 *       its new words and drops the ones it no longer ships; the {@code disabled} flag of a word that stays is never touched.</li>
 *   <li>{@code game_words}: safe words admins added in the game or from the web panel.</li>
 *   <li>{@code meta}: bookkeeping (fingerprint of the last shipped list, whether old rows were moved over).</li>
 * </ul>
 * One connection, every access serialised. Reads for the chat go through the in-memory copies kept by the callers.
 */
public final class SafeWordStore implements AutoCloseable {

    private static final String FINGERPRINT = "bundled_fingerprint";
    private static final String MIGRATED = "migrated_from_database_db";

    private final Connection connection;

    private SafeWordStore(Connection connection) {
        this.connection = connection;
    }

    /** Opens (creating when missing) the file; throws when the database cannot be used. */
    public static SafeWordStore open(File file) throws SQLException {
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS bundled_words (word TEXT PRIMARY KEY, disabled INTEGER NOT NULL DEFAULT 0)");
            stmt.execute("CREATE TABLE IF NOT EXISTS game_words (word TEXT PRIMARY KEY, added_by TEXT NOT NULL, added_at INTEGER NOT NULL)");
            stmt.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
        } catch (SQLException broken) {
            connection.close();
            throw broken;
        }
        return new SafeWordStore(connection);
    }

    /** Stable identity of a shipped list, so an unchanged jar costs no database writes at startup. */
    public static String fingerprint(List<String> words) {
        return words.size() + ":" + words.hashCode();
    }

    /**
     * Brings the shipped words in line with the jar. Does nothing when {@code fingerprint} matches the last run.
     *
     * @return true when the table was rewritten
     */
    public synchronized boolean seedBundled(List<String> words, String fingerprint) throws SQLException {
        if (fingerprint.equals(meta(FINGERPRINT))) return false;
        Set<String> shipped = new LinkedHashSet<>(words);
        boolean auto = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            Set<String> stored = new HashSet<>();
            try (PreparedStatement ps = connection.prepareStatement("SELECT word FROM bundled_words");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) stored.add(rs.getString(1));
            }
            try (PreparedStatement insert = connection.prepareStatement("INSERT OR IGNORE INTO bundled_words (word, disabled) VALUES (?, 0)")) {
                for (String word : shipped) {
                    if (stored.contains(word)) continue;
                    insert.setString(1, word);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            try (PreparedStatement drop = connection.prepareStatement("DELETE FROM bundled_words WHERE word = ?")) {
                for (String word : stored) {
                    if (shipped.contains(word)) continue;
                    drop.setString(1, word);
                    drop.addBatch();
                }
                drop.executeBatch();
            }
            setMeta(FINGERPRINT, fingerprint);
            connection.commit();
            return true;
        } catch (SQLException failed) {
            connection.rollback();
            throw failed;
        } finally {
            connection.setAutoCommit(auto);
        }
    }

    /** Shipped words that are switched on. */
    public synchronized Set<String> activeBundled() {
        Set<String> out = new LinkedHashSet<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT word FROM bundled_words WHERE disabled = 0 ORDER BY word");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(rs.getString(1));
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
        return out;
    }

    public synchronized int bundledCount() {
        try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM bundled_words");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /** Switches a shipped word off (true) or on (false); false when the word is not a shipped one. */
    public synchronized boolean setBundledDisabled(String word, boolean disabled) {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE bundled_words SET disabled = ? WHERE word = ?")) {
            ps.setInt(1, disabled ? 1 : 0);
            ps.setString(2, word);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    public synchronized List<WordLists.Entry> loadGameWords() {
        List<WordLists.Entry> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT word, added_by, added_at FROM game_words");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new WordLists.Entry(WordRules.Kind.SAFE, rs.getString(1), rs.getString(2), rs.getLong(3)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
        return out;
    }

    public synchronized void saveGameWord(WordLists.Entry entry) {
        try (PreparedStatement ps = connection.prepareStatement("INSERT OR REPLACE INTO game_words (word, added_by, added_at) VALUES (?, ?, ?)")) {
            ps.setString(1, entry.word());
            ps.setString(2, entry.addedBy());
            ps.setLong(3, entry.addedAt());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    public synchronized void deleteGameWord(String word) {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM game_words WHERE word = ?")) {
            ps.setString(1, word);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /** True once the safe words that used to live in {@code database.db} have been copied here. */
    public synchronized boolean migrated() {
        return "1".equals(meta(MIGRATED));
    }

    public synchronized void markMigrated() {
        try {
            setMeta(MIGRATED, "1");
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private String meta(String key) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT value FROM meta WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private void setMeta(String key, String value) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    @Override
    public synchronized void close() {
        try {
            if (!connection.isClosed()) connection.close();
        } catch (SQLException ignored) {
            // shutting down
        }
    }
}
