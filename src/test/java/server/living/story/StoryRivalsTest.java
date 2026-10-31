package server.living.story;

import org.junit.jupiter.api.Test;
import server.living.config.Content.Persona;
import server.living.config.Content.Relationship;
import server.living.crew.TieKind;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The race chapter's rival gate (F6.6): crew-banter's rival kinds exactly (TieKind.of, F6.4), either side naming the
 * other.
 */
class StoryRivalsTest {

    private static Persona persona(String id, Relationship... relationships) {
        Persona persona = mock(Persona.class);
        when(persona.id()).thenReturn(id);
        when(persona.relationships()).thenReturn(List.of(relationships));
        return persona;
    }

    @Test
    void eitherSideNamingTheOtherWithARivalKindMakesThemRivals() {
        Persona fenna = persona("fenna", new Relationship("dorran", "RIVALS"));
        Persona dorran = persona("dorran");
        assertTrue(StoryRivals.rivals(fenna, dorran));
        assertTrue(StoryRivals.rivals(dorran, fenna));
        assertTrue(StoryRivals.rivals(persona("brody", new Relationship("duke", "SPARS_WITH")), persona("duke")));
        assertTrue(StoryRivals.rivals(persona("a"), persona("b", new Relationship("a", "LOST_A_BET_TO"))));
    }

    @Test
    void otherKindsAndOtherPersonasAreNot() {
        Persona ilse = persona("ilse", new Relationship("dorran", "WORRIES_ABOUT"));
        Persona dorran = persona("dorran", new Relationship("ilse", "OWES"), new Relationship("fenna", "RIVALS"));
        assertFalse(StoryRivals.rivals(ilse, dorran));
        assertFalse(StoryRivals.rivals(dorran, dorran), "nobody is their own rival");
        assertFalse(StoryRivals.rivals(null, dorran));
    }

    @Test
    void aRivalIsWhatCrewTiesReadsAsRivalTheSeventeenKindsAndNoOther() {
        List<String> rivalKinds = List.of("RIVAL", "RIVALS", "RIVAL_OF", "OLD_RIVAL_OF", "SPARS_WITH", "SPARRING_PARTNER",
                "TEASES", "TEASED_BY", "IS_TEASED_BY", "ARGUES_WITH", "OUTSHOOTS", "LOSES_TO_AT_CARDS", "CHALLENGES",
                "RACES", "ENVIES", "WON_A_BET_OFF", "LOST_A_BET_TO");
        for (String kind : rivalKinds) {
            assertTrue(StoryRivals.rivals(persona("a", new Relationship("b", kind)), persona("b")), kind);
        }
        for (String kind : List.of("OWES", "SAVED", "BLAMES", "RESENTS", "ADMIRES", "SHARES_THE_ROAD_WITH", "NEVER_MET")) {
            assertFalse(StoryRivals.rivals(persona("a", new Relationship("b", kind)), persona("b")), kind);
        }
        for (String kind : List.of("RIVALS", "OWES", "TEASED_BY", "HUNTS_WITH", "HEARD_OF")) {
            assertEquals(TieKind.of(kind) == TieKind.RIVAL,
                    StoryRivals.rivals(persona("a", new Relationship("b", kind)), persona("b")), "one table: " + kind);
        }
    }
}
