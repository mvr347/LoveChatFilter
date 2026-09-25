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
 * Filters command TAB-completion for regular players: hides English-language commands,
 * leaving only Russian-language ones (containing Cyrillic characters) and anything explicitly
 * allow-listed in the config.
 * <p>
 * Moved here from LoveWebAdmin (2026-09-23) - this is chat/UX filtering, not web-admin territory.
 * <p>
 * {@code command-tab-filter.blocked-commands} (added 2026-09-25) is an explicit block-list that
 * takes priority over the Cyrillic pass-through. The Cyrillic rule exists to declutter tab-complete
 * down to THIS ecosystem's own commands, not "anything Cyrillic from any plugin" - a third-party
 * plugin's namespaced command (e.g. {@code deluxemenus:меню}) can have a Cyrillic label purely by
 * coincidence and still be noise the server owner doesn't want suggested. A blocked command is
 * always hidden regardless of script.
 * <p>
 * A plugin-namespaced label ({@code plugin:command}) never gets the Cyrillic (or
 * {@code hide-english-commands: false}) free pass either, for the same reason: typing the
 * namespace prefix would otherwise let a player browse every command on the server one by one,
 * defeating the whole filter. Namespaced forms only show up when explicitly listed in
 * {@code command-tab-filter.allowed-commands}. See {@link #isCommandAllowed(String)}.
 */
public class CommandTabFilterListener implements Listener {

    private final LoveChatFilter plugin;

    // Written by reload() (main thread, via /lovechatfilteradmin reload) and read from
    // AsyncTabCompleteEvent handling - which Paper dispatches on the packet/netty thread, not the
    // main thread. volatile (plus a full Set swap on reload rather than in-place clear()+add())
    // so a reload becomes visible to that thread immediately instead of racing on the Java Memory
    // Model, mirroring FilterEngine's own reload fields (see FilterEngine, fixed in #13).
    private volatile boolean enabled;
    private volatile boolean hideEnglish;
    private volatile Set<String> allowedCommands = Set.of();
    private volatile Set<String> blockedCommands = Set.of();

    public CommandTabFilterListener(LoveChatFilter plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        this.enabled = plugin.getConfigManager().getConfig().getBoolean("command-tab-filter.enabled", true);
        this.hideEnglish = plugin.getConfigManager().getConfig().getBoolean("command-tab-filter.hide-english-commands", true);

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
    }

    private boolean isCyrillic(String str) {
        if (str == null)
            return false;
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if ((c >= 'а' && c <= 'я') || (c >= 'А' && c <= 'Я') || c == 'ё' || c == 'Ё') {
                return true;
            }
        }
        return false;
    }

    private boolean isCommandAllowed(String cmd) {
        if (cmd == null)
            return false;
        String fullLower = cmd.toLowerCase().trim();
        boolean namespaced = fullLower.contains(":");
        String lower = fullLower;
        if (namespaced) {
            lower = lower.substring(lower.indexOf(':') + 1);
        }
        // Block-list wins over everything below, including the Cyrillic pass-through: a
        // third-party plugin's namespaced command (e.g. "deluxemenus:меню") can be Cyrillic
        // purely by coincidence and still be exactly the noise the owner doesn't want. Matched
        // against both the full original label (to block one plugin's "menu" without blocking a
        // same-named command elsewhere) and the stripped label (to block it regardless of which
        // plugin registers it).
        if (blockedCommands.contains(fullLower) || blockedCommands.contains(lower)) {
            return false;
        }
        // A plugin-namespaced label NEVER gets the Cyrillic (or hideEnglish=false) free pass -
        // typing "anyplugin:anycommand" would otherwise reveal every command on the server one
        // by one regardless of what this filter is supposed to hide, since the stripped label is
        // checked the same as a bare command. Namespaced forms are shown only when explicitly
        // allow-listed (2026-09-25: this closed the "not just deluxemenus, you can browse ALL
        // commands this way" report - see command-tab-filter.allowed-commands).
        if (namespaced) {
            return allowedCommands.contains(lower) || allowedCommands.contains(fullLower);
        }
        if (isCyrillic(lower)) {
            return true;
        }
        if (!hideEnglish) {
            return true;
        }
        return allowedCommands.contains(lower);
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
