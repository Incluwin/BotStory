package server.agents.commands;

import client.Character;

/**
 * Where one reply goes, fixed when the line it answers was handled: the channel that line came on and, for a whisper,
 * whom to whisper back. A reply that waits (a model call, a judgment, a typing delay, the reply queue) keeps it, so a
 * party or map line from the owner in the meantime, which moves the Agent's reply channel, cannot send a whispered
 * answer to the party or the map (deploy review 2026-10-10).
 */
public record AgentReplyRoute(AgentReplyChannel channel, Character whisperTo) {
    /** The route of a line {@code speaker} said on {@code channel}; null (read when sent) without a channel. */
    public static AgentReplyRoute of(AgentReplyChannel channel, Character speaker) {
        return channel == null ? null
                : new AgentReplyRoute(channel, channel == AgentReplyChannel.WHISPER ? speaker : null);
    }
}
