package me.lovelace.lovechatfilter.expansion;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.lovelace.lovechatfilter.LoveChatFilter;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/** Exposes per-player filter state to PlaceholderAPI, e.g. for the LoveEssential profile menu. */
public class FilterPlaceholderExpansion extends PlaceholderExpansion {
    private final LoveChatFilter plugin;

    public FilterPlaceholderExpansion(LoveChatFilter plugin) { this.plugin = plugin; }

    @Override public @NotNull String getIdentifier() { return "lovechatfilter"; }
    @Override public @NotNull String getAuthor() { return "Lovelace"; }
    @Override public @NotNull String getVersion() { return plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null || !params.equalsIgnoreCase("grammar")) return "";
        // Empty while the flag is still loading: callers show "unknown" instead of a false "off".
        Boolean state = plugin.getDatabaseManager().grammarState(player.getUniqueId());
        return state == null ? "" : state.toString();
    }
}
