package me.lovelace.lovechatfilter.api.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired once per violation type whenever {@code FilterEngine.processChat} catches profanity,
 * spam, caps or an ad/link in a player's message. Purely informational — the filtering decision
 * (censor/cancel the message) has already been made by the time this fires, so handlers cannot
 * change filter behavior through it. Consumers (e.g. LoveBehavior's auto-moderation) decide their
 * own consequence from {@link #getViolationType()} and {@link #getSeverity()}.
 */
public class ChatViolationEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    public enum ViolationType { PROFANITY, SPAM, CAPS, ADS }

    private final Player player;
    private final ViolationType violationType;
    private final int severity;

    public ChatViolationEvent(Player player, ViolationType violationType, int severity) {
        super(!org.bukkit.Bukkit.isPrimaryThread());
        this.player = player;
        this.violationType = violationType;
        this.severity = severity;
    }

    public Player getPlayer() {
        return player;
    }

    public ViolationType getViolationType() {
        return violationType;
    }

    /** 1 (light) to 3 (severe) — configured per violation type in config.yml. */
    public int getSeverity() {
        return severity;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
