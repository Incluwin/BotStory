package server.living.story;

import server.living.config.Content.Persona;
import server.living.config.Content.Relationship;
import server.living.crew.TieKind;

/**
 * Whether two personas are authored rivals (F6.6): the race chapter's gate and its lint. Relationship kinds are free
 * text; what counts as a rival is crew-banter's one table, {@link TieKind#of} reading RIVAL (F6.4), so a rival chapter
 * and a rival banter always agree. Either side's relationship to the other is enough, as in CrewTies.
 */
public final class StoryRivals {

    private StoryRivals() {
    }

    /** True when either persona's authored relationship to the other has a rival kind. */
    public static boolean rivals(Persona a, Persona b) {
        return a != null && b != null && !a.id().equals(b.id()) && (names(a, b) || names(b, a));
    }

    private static boolean names(Persona from, Persona to) {
        for (Relationship relationship : from.relationships()) {
            if (to.id().equals(relationship.persona()) && TieKind.of(relationship.kind()) == TieKind.RIVAL) {
                return true;
            }
        }
        return false;
    }
}
