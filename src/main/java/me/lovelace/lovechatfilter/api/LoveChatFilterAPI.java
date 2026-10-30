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
}
