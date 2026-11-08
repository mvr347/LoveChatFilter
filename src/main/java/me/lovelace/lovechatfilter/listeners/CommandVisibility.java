package me.lovelace.lovechatfilter.listeners;

import java.util.List;
import java.util.Set;

/**
 * Which command labels a regular player sees in TAB completion. Pure logic, no Bukkit.
 * <ul>
 *   <li>a plugin-namespaced label ({@code plugin:command}) is hidden unless allow-listed (otherwise typing the
 *       prefix would let a player browse every command);</li>
 *   <li>blocked labels are hidden always; an allow-listed label is shown unless blocked;</li>
 *   <li>service/admin labels are hidden in any language;</li>
 *   <li>labels that start with {@code love} (loveauth, loveclans, lovechatfilteradmin...) are the plugins' own
 *       long names: hidden unless allow-listed, while their short aliases (auth, clan) stay visible;</li>
 *   <li>everything else, English or Russian, is shown.</li>
 * </ul>
 */
public final class CommandVisibility {
    private CommandVisibility() {
    }

    /**
     * Service, administrative and staff commands of the ecosystem, hidden from regular players even when they
     * are English. Most are also permission-guarded; this list keeps those that Bukkit would still show because
     * they are registered without a plugin.yml permission, and the short aliases of admin commands.
     */
    public static final List<String> DEFAULT_SERVICE_COMMANDS = List.of(
            // LoveEssential: content and staff tools
            "npc", "нпс", "calamity", "бедствие", "беда", "secrets", "тайны", "hologram", "голограмма", "голо",
            "portal", "портал", "врата", "scenario", "scenarios", "сценарий", "dialog", "dialogs", "диалог",
            "setspawn", "установитьспавн", "starter", "startitems", "старт", "vanish", "v", "невидимка", "скрыться",
            "тень", "invsee", "инвентарь", "heal", "вылечить", "feed", "сытость", "накормить", "fly", "полёт",
            "полет", "god", "бог", "godmode", "speed", "скорость", "gamemode", "gm", "режим", "repair", "починить",
            "revive", "rv", "поднять", "reports", "жалобы", "motd", "мотд",
            // admin commands of the other plugins (short aliases; the love* names are hidden by prefix)
            "ladmin", "ладмин", "lactadmin", "laadmin", "лаадмин", "brewadmin", "ba", "ca", "clansadmin", "lca",
            "lcadmin", "lda", "dueladmin", "дуэльадмин", "lba", "lbadmin", "leaderboardadmin", "lsa", "lspa", "lssa",
            "lwa", "vs", "veso", "contracts", "contract", "контракты", "pcontract", "pcontracts",
            "playercontract", "контракт", "tpadmin", "tpa",
            // chat staff tools and the internal command behind chat buttons
            "chatclear", "cc", "spy", "scrollcancel");

    public static boolean isVisible(String label, Set<String> allowed, Set<String> blocked, Set<String> service) {
        if (label == null) return false;
        String full = label.toLowerCase().trim();
        boolean namespaced = full.contains(":");
        String bare = namespaced ? full.substring(full.indexOf(':') + 1) : full;
        if (blocked.contains(full) || blocked.contains(bare)) return false;
        // An explicit allow-list entry wins over the service/love/namespace rules (the owner decided), for the bare
        // and the namespaced label alike, so equivalent labels never disagree.
        if (allowed.contains(full) || allowed.contains(bare)) return true;
        if (namespaced) return false;
        if (service.contains(bare)) return false;
        if (bare.startsWith("love")) return false;
        return true;
    }
}
