package server.living.story;

import server.living.config.Content.Persona;
import server.living.config.Content.Relationship;

import java.util.Set;

/**
 * Whether two personas are authored rivals (F6.6): the race chapter's gate and its lint. Relationship kinds are free
 * text, so the rival kinds are a fixed set: crew-banter's RIVAL kinds exactly (the coordinator's list, 2026-10-28), an
 * exact match on the upper-case kind, either side's relationship to the other. At integration the body of
 * {@link #rivals} becomes crew-banter's {@code TieKind.of(kind)} with the same results; keep the set here only.
 */
public final class StoryRivals {
    static final Set<String> RIVAL_KINDS = Set.of("RIVAL", "RIVALS", "RIVAL_OF", "OLD_RIVAL_OF", "SPARS_WITH",
            "SPARRING_PARTNER", "TEASES", "TEASED_BY", "IS_TEASED_BY", "ARGUES_WITH", "OUTSHOOTS", "LOSES_TO_AT_CARDS",
            "CHALLENGES", "RACES", "ENVIES", "WON_A_BET_OFF", "LOST_A_BET_TO");

    private StoryRivals() {
    }

    /** True when either persona's authored relationship to the other has a rival kind. */
    public static boolean rivals(Persona a, Persona b) {
        return a != null && b != null && !a.id().equals(b.id()) && (names(a, b) || names(b, a));
    }

    private static boolean names(Persona from, Persona to) {
        for (Relationship relationship : from.relationships()) {
            if (to.id().equals(relationship.persona()) && RIVAL_KINDS.contains(relationship.kind())) {
                return true;
            }
        }
        return false;
    }
}
