package server.knowledge;

import config.KnowledgeConfig;
import config.YamlConfig;

/**
 * The gate for bots acting on server knowledge (docs/SERVER_KNOWLEDGE_PLAN.md 8): FARM_ITEM sites and the honest bench
 * ledger, healer fields, off-Victoria travel times and citizen gear. Every call site keeps today's code for a null
 * {@link #active()}.
 */
public final class Decisions {
    private Decisions() {
    }

    /** The knowledge snapshot bots decide with, or null: knowledge or DECISIONS off, or nothing loaded yet. */
    public static WorldKnowledge active() {
        KnowledgeConfig config = YamlConfig.config.knowledge;
        WorldKnowledge k = WorldKnowledge.current();
        return config.ENABLED && config.DECISIONS && k.ready() ? k : null;
    }

    /** Generated citizens get level-fitting gear when they load: {@link #active()} and CITIZEN_GEAR. */
    public static boolean citizenGear() {
        return active() != null && YamlConfig.config.knowledge.CITIZEN_GEAR;
    }
}
