package server.knowledge;

import config.KnowledgeConfig;
import server.knowledge.ContentAnswers.Rates;

/**
 * Server knowledge for tests in other packages (the chat hosts): the real knowledge file with the seed overlay,
 * published as {@link WorldKnowledge#current()} while a test needs it, and a {@link ContentChat} over it with every
 * host on.
 */
public final class KnowledgeTestKit {
    private static final Rates RATES = new Rates(10, 3, 3, 3, 4f, 4);

    private KnowledgeTestKit() {
    }

    /** Publishes the seed snapshot; closing puts the previous one back. */
    public static AutoCloseable publishSeed() {
        WorldKnowledge before = WorldKnowledge.current();
        WorldKnowledge.publish(SeedOverlay.knowledge());
        return () -> WorldKnowledge.publish(before);
    }

    /** A fresh content chat (its own conversation memory) over the seed snapshot. */
    public static ContentChat chat() {
        return new ContentChat(new ContentAnswers(SeedOverlay::knowledge, world -> RATES, item -> null, 10, 70, false),
                SeedOverlay::knowledge, new KnowledgeConfig());
    }
}
