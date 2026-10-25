package server.agents.commands;

/**
 * A queued line. {@code route}, for a reply to a line that came on a known channel, fixes where it goes; null reads
 * the Agent's reply channel when it is sent.
 */
public record AgentQueuedMessage(String text, boolean ownerDirected, AgentReplyRoute route) {
    public AgentQueuedMessage(String text, boolean ownerDirected) {
        this(text, ownerDirected, null);
    }
}
