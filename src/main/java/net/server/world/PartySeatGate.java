package net.server.world;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The seat gate {@link Party#joinParty} asks before a bot joins (docs/BOT_MANAGEMENT_PLAN.md, "Party.joinParty seat
 * gate"). The living world installs the rule (a companion or guest must hold a reserved seat); with none installed
 * every join is let through, as before.
 */
public final class PartySeatGate {
    private static final Logger log = LoggerFactory.getLogger(PartySeatGate.class);
    private static volatile Rule rule;

    private PartySeatGate() {
    }

    @FunctionalInterface
    public interface Rule {
        /** True when {@code bot} may join {@code party} now. Must not block and must not change any party. */
        boolean admits(Character bot, Party party);
    }

    /** Installs the rule (null removes it). */
    public static void install(Rule installed) {
        rule = installed;
    }

    /** The installed rule's answer; true with none installed or when the rule fails. */
    static boolean admitted(Character bot, Party party) {
        Rule current = rule;
        if (current == null) {
            return true;
        }
        try {
            return current.admits(bot, party);
        } catch (RuntimeException failure) {
            log.warn("party seat gate failed for {}", bot.getName(), failure);
            return true;
        }
    }
}
