package server.living.crew;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one table that reads authored relationship kinds (free text) as crew ties (docs/BOT_CONCEPT_PLAN.md F6.4); the
 * story arcs' rival chapters read rivals through it too, so its RIVAL set is pinned here.
 */
class TieKindTest {

    @Test
    void everyRivalKindAgreedWithTheStoryArcsReadsRival() {
        for (String kind : new String[] {"RIVAL", "RIVALS", "RIVAL_OF", "OLD_RIVAL_OF", "SPARS_WITH", "SPARRING_PARTNER",
                "TEASES", "TEASED_BY", "IS_TEASED_BY", "ARGUES_WITH", "OUTSHOOTS", "LOSES_TO_AT_CARDS", "CHALLENGES",
                "RACES", "ENVIES", "WON_A_BET_OFF", "LOST_A_BET_TO"}) {
            assertEquals(TieKind.RIVAL, TieKind.of(kind), kind);
        }
        assertEquals(17, TieKind.RIVAL_KINDS.size(), "the set sent to the coordinator 2026-10-28");
        assertEquals(TieKind.RIVAL, TieKind.of(" rivals "), "case and spaces are ignored");
    }

    @Test
    void debtsAndGrudgesHaveADirectionAndTheRestAreFriends() {
        assertEquals(TieKind.OWES, TieKind.of("OWES"));
        assertEquals(TieKind.OWES, TieKind.of("OWES_LIFE"));
        assertEquals(TieKind.OWES, TieKind.of("OWES_A_FAVOUR_TO"));
        assertEquals(TieKind.OWED, TieKind.of("IS_OWED_A_FAVOUR_BY"));
        assertEquals(TieKind.OWED, TieKind.of("SAVED"), "thane saved orrin: thane is owed");
        assertEquals(TieKind.BLAMES, TieKind.of("BLAMES"));
        assertEquals(TieKind.BLAMES, TieKind.of("BLAMES_FOR_THE_PIGS"));
        assertEquals(TieKind.BLAMES, TieKind.of("RESENTS"));
        assertEquals(TieKind.BLAMED, TieKind.of("DISTRUSTED_BY"));
        assertEquals(TieKind.FRIEND, TieKind.of("SHARES_THE_ROAD_WITH"));
        assertEquals(TieKind.FRIEND, TieKind.of("ADMIRES"));
        assertNull(TieKind.of("NEVER_MET"), "never met is no tie");
        assertNull(TieKind.of(null));
    }

    @Test
    void mirrorsSwapTheDirectionalTiesOnly() {
        assertEquals(TieKind.OWED, TieKind.OWES.mirror());
        assertEquals(TieKind.OWES, TieKind.OWED.mirror());
        assertEquals(TieKind.BLAMED, TieKind.BLAMES.mirror());
        assertEquals(TieKind.RIVAL, TieKind.RIVAL.mirror());
        assertEquals(TieKind.SPOUSE, TieKind.SPOUSE.mirror());
    }

    @Test
    void theLintKnowsWhichFriendsAreFriendsOnPurpose() {
        assertTrue(TieKind.named("STANDS_WATCH_WITH"));
        assertTrue(TieKind.named("RIVAL_OF"));
        assertFalse(TieKind.named("WATERS_THE_ROSES_WITH"), "read as FRIEND only by the default");
    }
}
