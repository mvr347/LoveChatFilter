package me.lovelace.lovechatfilter.listeners;

import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;
import me.lovelace.lovechatfilter.LoveChatFilter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandSendEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Filters command TAB-completion for regular players. Since 2026-11-08 English commands are no longer hidden
 * wholesale: only the plugins' own {@code love*} labels, the service/admin commands and namespaced
 * ({@code plugin:command}) labels are. See {@link CommandVisibility} for the exact rules.
 * <p>
 * Moved here from LoveWebAdmin (2026-09-23) - this is chat/UX filtering, not web-admin territory.
 */
public class CommandTabFilterListener implements Listener {

    private final LoveChatFilter plugin;

    // Written by reload() (main thread, via /lovechatfilteradmin reload) and read from
    // AsyncTabCompleteEvent handling - which Paper dispatches on the packet/netty thread, not the
    // main thread. volatile (plus a full Set swap on reload rather than in-place clear()+add())
    // so a reload becomes visible to that thread immediately instead of racing on the Java Memory
    // Model, mirroring FilterEngine's own reload fields (see FilterEngine, fixed in #13).
    private volatile boolean enabled;
    private volatile Set<String> allowedCommands = Set.of();
    private volatile Set<String> blockedCommands = Set.of();
    private volatile Set<String> serviceCommands = Set.copyOf(CommandVisibility.DEFAULT_SERVICE_COMMANDS);

    public CommandTabFilterListener(LoveChatFilter plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        this.enabled = plugin.getConfigManager().getConfig().getBoolean("command-tab-filter.enabled", true);

        List<String> list = plugin.getConfigManager().getConfig().getStringList("command-tab-filter.allowed-commands");
        Set<String> normalized = new HashSet<>();
        for (String s : list) {
            normalized.add(s.toLowerCase().trim());
        }
        this.allowedCommands = Set.copyOf(normalized);

        List<String> blockedList = plugin.getConfigManager().getConfig().getStringList("command-tab-filter.blocked-commands");
        Set<String> normalizedBlocked = new HashSet<>();
        for (String s : blockedList) {
            normalizedBlocked.add(s.toLowerCase().trim());
        }
        this.blockedCommands = Set.copyOf(normalizedBlocked);

        // Missing key = the built-in list; a present key (even an empty list) is the owner's decision.
        var config = plugin.getConfigManager().getConfig();
        List<String> serviceList = config.isList("command-tab-filter.service-commands")
                ? config.getStringList("command-tab-filter.service-commands")
                : CommandVisibility.DEFAULT_SERVICE_COMMANDS;
        Set<String> normalizedService = new HashSet<>();
        for (String s : serviceList) {
            normalizedService.add(s.toLowerCase().trim());
        }
        this.serviceCommands = Set.copyOf(normalizedService);
    }

    private boolean isCommandAllowed(String cmd) {
        return CommandVisibility.isVisible(cmd, allowedCommands, blockedCommands, serviceCommands);
    }

    // event.getCommands() entries have NO leading slash (vanilla Bukkit builds this list from raw
    // command labels), so they can be fed into isCommandAllowed() as-is.
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerCommandSend(PlayerCommandSendEvent event) {
        if (!enabled)
            return;
        Player player = event.getPlayer();
        if (player.isOp() || player.hasPermission("lovechatfilter.reload")
                || player.hasPermission("lovechatfilter.tabcomplete.bypass")) {
            return;
        }

        event.getCommands().removeIf(cmd -> !isCommandAllowed(cmd));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAsyncTabComplete(AsyncTabCompleteEvent event) {
        // Note: no early return on !hideEnglish here (unlike before blocked-commands existed) -
        // isCommandAllowed() already re-implements the hideEnglish pass-through internally, and
        // the block-list must apply even when hideEnglish is off, since it's an explicit block on
        // specific commands, not part of the English-hiding heuristic.
        if (!enabled)
            return;
        if (!(event.getSender() instanceof Player player))
            return;
        if (player.isOp() || player.hasPermission("lovechatfilter.reload")
                || player.hasPermission("lovechatfilter.tabcomplete.bypass")) {
            return;
        }

        // The buffer is what the player has typed so far, WITH the leading "/" (e.g. "/hel").
        // No space yet means they're still completing the command name itself - once a space
        // appears we're into argument completion, which this filter must never touch.
        String buffer = event.getBuffer();
        if (buffer != null && buffer.startsWith("/")) {
            if (!buffer.substring(1).contains(" ")) {
                event.getCompletions().removeIf(completion -> {
                    // Unlike PlayerCommandSendEvent, top-level completions here DO carry a
                    // leading "/" - strip it before reusing the same isCommandAllowed() check.
                    String clean = completion.startsWith("/") ? completion.substring(1) : completion;
                    return !isCommandAllowed(clean);
                });
            }
        }
    }
}
