package server.agents.integration;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-load seam. Every Agent character the engine loads from the database passes here once, after
 * {@code Character.loadCharFromDB} and before it is registered with its channel, world and map, so nothing else
 * reaches it yet. The host (the living world) installs the rule that brings what it keeps outside the character
 * row up to date (a lore couple's marriage). With nothing installed a load is unchanged.
 */
public final class AgentLoadHook {
    private static final Logger log = LoggerFactory.getLogger(AgentLoadHook.class);

    /** The installed load rule. */
    public interface Rule {
        /** {@code agent} was just loaded and is not registered anywhere yet; it may be changed in place. */
        void loaded(Character agent);
    }

    private static volatile Rule rule;

    private AgentLoadHook() {
    }

    public static void install(Rule installed) {
        rule = installed;
    }

    /** {@link Rule#loaded}; never fails the load. */
    public static void loaded(Character agent) {
        Rule current = rule;
        if (current == null || agent == null) {
            return;
        }
        try {
            current.loaded(agent);
        } catch (RuntimeException failure) {
            log.warn("load rule failed for character {}", agent.getId(), failure);
        }
    }
}
