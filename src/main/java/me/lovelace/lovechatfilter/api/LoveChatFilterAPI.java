package me.lovelace.lovechatfilter.api;

/**
 * Public cross-plugin contract, registered in Bukkit's {@code ServicesManager}. Mirrors the
 * reflection-bridge pattern LoveCore uses for its own optional integrations (see
 * {@code dev.lovelace.lovecore.integration.Neighbour}): neighboring plugins reflect into this
 * interface rather than depending on LoveChatFilter's jar at compile time, so LoveChatFilter
 * stays a true soft-dependency — everything keeps working, minus the check, if it's absent.
 */
public interface LoveChatFilterAPI {

    /**
     * Whether {@code text} contains content the badwords filter would catch. Independent of
     * caps/ads/goodwords modules and of any per-player bypass permission — a public broadcast
     * (e.g. a paid server-wide announcement) is a different trust context than one player's own
     * chat line, so a player's personal bypass doesn't extend to text that gets shown to everyone.
     */
    boolean isProfane(String text);

    /**
     * Whether {@code text} contains a link, address or server name the ads filter would catch,
     * including a dot written as "(точка)", "[.]" or "(dot)" before a domain zone. Like
     * {@link #isProfane}, it ignores per-player bypass permissions and is {@code false} when the
     * ads-filter module is off. Default {@code false} so older implementations keep working.
     */
    default boolean containsAdvertising(String text) {
        return false;
    }

    // ---- Administration (LoveWebAdmin). Everything below uses plain Java types so the reflection bridge needs no
    // shared classes. Call these on the server main thread. Defaults keep older implementations working. ----

    /**
     * Words of one kind ({@code bad}, {@code safe} or {@code ad}): each row has {@code word}, {@code source}
     * ({@code game} - added from the game or the panel and removable, {@code config} - from config.yml, read-only),
     * {@code addedBy} and {@code addedAt} (Unix seconds, game words only). Newest game words first.
     *
     * @throws IllegalArgumentException for an unknown kind
     */
    default java.util.List<java.util.Map<String, Object>> listWords(String kind) {
        return java.util.List.of();
    }

    /**
     * Adds a word and rebuilds the filters at once.
     *
     * @return the normalised word that was stored
     * @throws IllegalArgumentException with a readable reason (unknown kind, invalid or already known word)
     */
    default String addWord(String kind, String word, String addedBy) {
        throw new UnsupportedOperationException();
    }

    /** Removes a word added from the game or the panel; {@code false} when it was not in the list. */
    default boolean removeWord(String kind, String word) {
        return false;
    }

    /**
     * What the filters would do to {@code text}, without sending or recording anything: {@code text} (as the chat
     * would show it), {@code clean}, {@code profane}, {@code layers} (evasion layers that caught it), {@code ads}, {@code caps}.
     */
    default java.util.Map<String, Object> checkText(String text) {
        return java.util.Map.of();
    }

    /** Settings the panel may change: {@code key}, {@code type}, {@code group}, {@code label}, limits, {@code value}, {@code overridden}. */
    default java.util.List<java.util.Map<String, Object>> settings() {
        return java.util.List.of();
    }

    /**
     * Changes one setting from the list returned by {@link #settings()}. The value is kept as an override of
     * config.yml (config.yml itself is not rewritten) and survives a restart.
     *
     * @throws IllegalArgumentException with a readable reason for an unknown key or a value out of range
     */
    default void changeSetting(String key, String value) {
        throw new UnsupportedOperationException();
    }

    /** Drops the panel's override of one setting: the value from config.yml applies again. */
    default void resetSetting(String key) {
    }

    /** Violation counters for the last {@code days} days: {@code days}, {@code totals} by type, {@code perDay} rows. */
    default java.util.Map<String, Object> statistics(int days) {
        return java.util.Map.of();
    }
}
