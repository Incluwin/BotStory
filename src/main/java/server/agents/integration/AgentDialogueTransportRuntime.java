package server.agents.integration;

import server.agents.capabilities.dialogue.AgentChatReplyRuntime;
import server.agents.capabilities.dialogue.AgentChatTextSanitizer;
import server.agents.runtime.AgentSchedulerRuntime;
import client.Character;
import server.agents.commands.AgentQueuedMessage;
import server.agents.commands.AgentReplyQueue;
import server.agents.commands.AgentReplyChannel;
import server.agents.commands.AgentReplyRoute;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.commands.AgentMessageQueueStateRuntime;
import server.agents.commands.AgentReplyChannelStateRuntime;

/**
 * Boundary adapter from Agent reply queues to Cosmic chat, whisper, party, and
 * packet delivery.
 */
public final class AgentDialogueTransportRuntime {
    private AgentDialogueTransportRuntime() {
    }

    public static void queueSay(AgentRuntimeEntry entry, String message) {
        if (!transportEnabled()) {
            return;
        }
        AgentChatReplyRuntime.queueSay(state(entry), message, dispatcher(entry));
    }

    public static void queueReply(AgentRuntimeEntry entry, String message) {
        if (!transportEnabled()) {
            return;
        }
        AgentChatReplyRuntime.queueReply(state(entry), message, dispatcher(entry));
    }

    public static long queueSayWithEstimatedDelay(AgentRuntimeEntry entry, String message) {
        if (!transportEnabled()) {
            return 0L;
        }
        return AgentChatReplyRuntime.queueSayWithEstimatedDelay(state(entry), message, dispatcher(entry));
    }

    public static long queueReplyWithEstimatedDelay(AgentRuntimeEntry entry, String message) {
        if (!transportEnabled()) {
            return 0L;
        }
        return AgentChatReplyRuntime.queueReplyWithEstimatedDelay(state(entry), message, dispatcher(entry));
    }

    /**
     * A reply that goes on {@code route}, the route of the line it answers (a whisper back to its speaker), when the
     * queue sends it, whatever the Agent's reply channel is by then; null: {@link #queueReply(AgentRuntimeEntry, String)}.
     */
    public static void queueReply(AgentRuntimeEntry entry, String message, AgentReplyRoute route) {
        if (!transportEnabled()) {
            return;
        }
        AgentChatReplyRuntime.queueReply(state(entry), message, route, dispatcher(entry));
    }

    /**
     * {@link #replyNow(AgentRuntimeEntry, String)} on {@code route}, the route of the line it answers, for a reply sent
     * after a wait (a model call, a judgment, a typing delay): a party or map line in between moves the Agent's reply
     * channel, never this reply. Null reads the channel now.
     */
    public static void replyNow(AgentRuntimeEntry entry, String message, AgentReplyRoute route) {
        if (route == null) {
            replyNow(entry, message);
            return;
        }
        if (!transportEnabled()) {
            return;
        }
        send(entry, message, route.channel(), route.whisperTo());
    }

    public static void replyNow(AgentRuntimeEntry entry, String message) {
        if (!transportEnabled()) {
            return;
        }
        send(entry, message, AgentReplyChannelStateRuntime.replyChannel(entry), null);
    }

    /** {@code whisperTo} null: a whisper goes to the Agent's interaction target. */
    private static void send(AgentRuntimeEntry entry, String message, AgentReplyChannel channel, Character whisperTo) {
        switch (channel) {
            case PARTY -> sayPartyNow(AgentRuntimeIdentityRuntime.bot(entry), message);
            case WHISPER -> {
                Character owner = whisperTo != null ? whisperTo : AgentRelationshipRuntime.interactionTarget(entry);
                Character bot = AgentRuntimeIdentityRuntime.bot(entry);
                if (owner != null && AgentClientGatewayRuntime.clients().hasClient(owner)) {
                    AgentPacketGatewayRuntime.packets().sendWhisperReceive(
                            owner,
                            bot.getName(),
                            AgentClientGatewayRuntime.clients().channel(bot) - 1,
                            false,
                            AgentChatTextSanitizer.sanitize(message));
                }
            }
            default -> sayMapNow(AgentRuntimeIdentityRuntime.bot(entry), message);
        }
    }

    public static void visibleSayNow(AgentRuntimeEntry entry, String message) {
        if (!transportEnabled()) {
            return;
        }
        sayNow(AgentRuntimeIdentityRuntime.bot(entry), AgentReplyChannelStateRuntime.replyChannel(entry), message);
    }

    public static void sayNow(Character bot, AgentReplyChannel channel, String message) {
        if (!transportEnabled()) {
            return;
        }
        switch (channel) {
            case PARTY, WHISPER -> sayPartyNow(bot, message);
            default -> sayMapNow(bot, message);
        }
    }

    public static void sayMapNow(Character bot, String message) {
        if (!transportEnabled()) {
            return;
        }
        AgentPacketGatewayRuntime.packets().broadcastChatText(
                bot,
                AgentChatTextSanitizer.sanitize(message),
                false,
                0);
    }

    public static void sayPartyNow(Character bot, String message) {
        if (!transportEnabled()) {
            return;
        }
        if (!AgentPartyGatewayRuntime.party().sendPartyChat(bot, AgentChatTextSanitizer.sanitize(message))) {
            sayMapNow(bot, message);
        }
    }

    private static boolean transportEnabled() {
        return config.AgentYamlConfig.config.agent.AGENT_DIALOGUE_TRANSPORT_ENABLED;
    }

    private static AgentReplyQueue.State state(AgentRuntimeEntry entry) {
        return new AgentReplyQueue.State() {
            @Override
            public Object lock() {
                return AgentMessageQueueStateRuntime.lock(entry);
            }

            @Override
            public int size() {
                return AgentMessageQueueStateRuntime.size(entry);
            }

            @Override
            public void enqueue(AgentQueuedMessage message) {
                AgentMessageQueueStateRuntime.enqueue(entry, message);
            }

            @Override
            public boolean contains(AgentQueuedMessage message) {
                return AgentMessageQueueStateRuntime.contains(entry, message);
            }

            @Override
            public boolean removeOldestUndirected() {
                return AgentMessageQueueStateRuntime.removeOldestUndirected(entry);
            }

            @Override
            public AgentQueuedMessage poll() {
                return AgentMessageQueueStateRuntime.poll(entry);
            }

            @Override
            public boolean isSending() {
                return AgentMessageQueueStateRuntime.isSending(entry);
            }

            @Override
            public void setSending(boolean sending) {
                AgentMessageQueueStateRuntime.setSending(entry, sending);
            }
        };
    }

    private static AgentReplyQueue.Dispatcher dispatcher(AgentRuntimeEntry entry) {
        return new AgentReplyQueue.Dispatcher() {
            @Override
            public void dispatch(AgentQueuedMessage message) {
                if (message.ownerDirected()) {
                    replyNow(entry, message.text(), message.route());
                } else {
                    visibleSayNow(entry, message.text());
                }
            }

            @Override
            public void scheduleNext(Runnable task, int delayMs) {
                AgentSchedulerRuntime.afterDelay(entry, delayMs, task);
            }
        };
    }
}
