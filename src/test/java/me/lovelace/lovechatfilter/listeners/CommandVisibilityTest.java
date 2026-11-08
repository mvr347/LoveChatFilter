package me.lovelace.lovechatfilter.listeners;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class CommandVisibilityTest {
    private static final Set<String> SERVICE = Set.copyOf(CommandVisibility.DEFAULT_SERVICE_COMMANDS);

    private static boolean visible(String label) {
        return CommandVisibility.isVisible(label, Set.of(), Set.of(), SERVICE);
    }

    @Test
    void englishCommandsAreShownButLovePrefixedOnesAreHidden() {
        assertTrue(visible("auth"));
        assertTrue(visible("clan"));
        assertTrue(visible("msg"));
        assertFalse(visible("loveauth"));
        assertFalse(visible("loveclansadmin"));
    }

    @Test
    void serviceCommandsAreHiddenEvenInEnglish() {
        for (String label : new String[] {"npc", "calamity", "hologram", "heal", "gamemode", "chatclear", "ладмин"}) {
            assertFalse(visible(label), label);
        }
        assertTrue(visible("nick"));
        assertTrue(visible("suffixes"));
    }

    @Test
    void namespacedLabelsStayHiddenUnlessAllowed() {
        assertFalse(visible("deluxemenus:menu"));
        assertTrue(CommandVisibility.isVisible("loveauth:auth", Set.of("loveauth:auth"), Set.of(), SERVICE));
    }

    @Test
    void allowListWinsOverServiceAndNamespaceRulesForEquivalentLabels() {
        var allowed = Set.of("heal", "lcf");
        assertTrue(CommandVisibility.isVisible("heal", allowed, Set.of(), SERVICE));
        assertTrue(CommandVisibility.isVisible("loveessential:heal", allowed, Set.of(), SERVICE));
        assertTrue(visible("lcf"), "the player-facing command is not a service command");
    }

    @Test
    void blockedWinsOverEverything() {
        assertFalse(CommandVisibility.isVisible("меню", Set.of("меню"), Set.of("меню"), SERVICE));
    }
}
