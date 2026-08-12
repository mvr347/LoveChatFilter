package me.lovelace.lovechatfilter.api.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired when a configured courteous phrase ("спасибо", "пожалуйста", etc.) is detected anywhere
 * in a player's chat message. This is a pure scan running independently of every filter module —
 * it never blocks, censors or otherwise alters the message, and fires even if the same message
 * also triggers a {@link ChatViolationEvent}.
 */
public class PolitePhraseEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String phrase;

    public PolitePhraseEvent(Player player, String phrase) {
        super(!org.bukkit.Bukkit.isPrimaryThread());
        this.player = player;
        this.phrase = phrase;
    }

    public Player getPlayer() {
        return player;
    }

    /** The configured phrase pattern that matched (not the raw player text). */
    public String getPhrase() {
        return phrase;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
