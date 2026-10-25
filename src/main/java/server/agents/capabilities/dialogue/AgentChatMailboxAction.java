package server.agents.capabilities.dialogue;

import client.Character;
import server.agents.commands.AgentReplyChannel;
import server.agents.commands.AgentReplyChannelStateRuntime;
import server.agents.commands.AgentReplyRoute;
import server.agents.runtime.AgentChatOrchestratorContext;
import server.agents.runtime.AgentMailboxAction;
import server.agents.runtime.AgentRuntimeEntry;

/**
 * One chat line for one Agent. {@code speaker}: who said it, whom a whispered line's answers go back to. The line's
 * route ({@link AgentReplyRoute}) is in scope while the Agent handles it, so the answers its flows say, queue or
 * schedule for later keep the line's channel and speaker (second deploy review 2026-10-10).
 */
public record AgentChatMailboxAction(String message,
                                     AgentReplyChannel replyChannel,
                                     Character speaker) implements AgentMailboxAction<Boolean> {
    public AgentChatMailboxAction(String message) {
        this(message, null, null);
    }

    public AgentChatMailboxAction(String message, AgentReplyChannel replyChannel) {
        this(message, replyChannel, null);
    }

    @Override
    public Boolean execute(AgentRuntimeEntry entry) {
        if (replyChannel != null) {
            AgentReplyChannelStateRuntime.setReplyChannel(entry, replyChannel);
        }
        return AgentReplyRoute.within(entry, AgentReplyRoute.of(replyChannel, speaker),
                () -> AgentChatRuntime.handleChat(message, new AgentChatOrchestratorContext(entry)));
    }
}
