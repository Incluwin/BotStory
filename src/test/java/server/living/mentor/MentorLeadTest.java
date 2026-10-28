package server.living.mentor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lead (docs/BOT_CONCEPT_PLAN.md F4.3): companions follow, never lead, so this is the one place one goes first. On a
 * two-way portal it walks ahead, goes through and waits on the far side about 20 s; its newcomer not coming, it comes
 * back for them ("you coming?") and tries again, then gives up and follows. On a one-way portal or an NPC it waits
 * beside it and lets its newcomer go first, so it is never stranded ahead. Lith's ship deck: it walks through itself
 * once its newcomer is in town. Its newcomer going another way ends it.
 */
class MentorLeadTest {
    private static final long WAIT = 20_000L;
    private static final MentorLead.Leg TWO_WAY = new MentorLead.Leg(true, false, "lead.oneway");
    private static final MentorLead.Leg ONE_WAY = new MentorLead.Leg(false, false, "lead.oneway");
    private static final MentorLead.Leg DECK = new MentorLead.Leg(false, true, "lead.deck");

    private static MentorLead.View see(long now, boolean atDoor, boolean walking, boolean mentorAcross,
                                       boolean newcomerAcross) {
        return new MentorLead.View(now, atDoor, walking, mentorAcross, newcomerAcross, false);
    }

    @Test
    void onATwoWayPortalItGoesAheadWaitsBeyondAndItsNewcomerComing() {
        MentorLead lead = new MentorLead(TWO_WAY, WAIT, 2, 0L);
        assertEquals(new MentorLead.Move(MentorLead.Act.GO_DOOR, "lead.start"), lead.start());
        assertEquals(MentorLead.Act.NONE, lead.step(see(1_000, false, true, false, false)).act(), "still walking");
        assertEquals(MentorLead.Act.ENTER, lead.step(see(2_000, true, false, false, false)).act(), "at the portal: through");
        assertEquals(MentorLead.Act.NONE, lead.step(see(3_000, false, false, true, false)).act(), "beyond: it waits");
        assertEquals(MentorLead.Phase.BEYOND, lead.phase());
        assertEquals(MentorLead.Act.NONE, lead.step(see(3_000 + WAIT - 1, false, false, true, false)).act());
        assertEquals(MentorLead.Act.FOLLOW, lead.step(see(23_500, false, false, true, true)).act(), "they came");
        assertTrue(lead.over());
        assertFalse(lead.gaveUp());
        assertEquals(MentorLead.Act.NONE, lead.step(see(16_000, false, false, true, true)).act(), "over is over");
    }

    @Test
    void notFollowedWithinTheWaitItComesBackForThemAndTriesAgain() {
        MentorLead lead = new MentorLead(TWO_WAY, WAIT, 2, 0L);
        lead.start();
        lead.step(see(1_000, true, false, false, false));
        lead.step(see(2_000, false, false, true, false)); // beyond since 2 s
        MentorLead.Move back = lead.step(see(2_000 + WAIT, false, false, true, false));
        assertEquals(MentorLead.Act.COME_BACK, back.act(), "20 s and nobody: back through");
        assertEquals(MentorLead.Act.NONE, lead.step(see(23_000, false, true, true, false)).act(), "walking back");
        MentorLead.Move call = lead.step(see(24_000, false, false, false, false));
        assertEquals(new MentorLead.Move(MentorLead.Act.GO_DOOR, "lead.come"), call, "on their side: 'you coming?'");
        assertEquals(MentorLead.Act.ENTER, lead.step(see(25_000, true, false, false, false)).act(), "and ahead again");
        lead.step(see(26_000, false, false, true, false));
        assertEquals(MentorLead.Act.COME_BACK, lead.step(see(26_000 + WAIT, false, false, true, false)).act(), "try 2");
        lead.step(see(47_000, false, false, false, false));
        lead.step(see(48_000, true, false, false, false));
        lead.step(see(49_000, false, false, true, false));
        assertEquals(MentorLead.Act.FOLLOW, lead.step(see(49_000 + WAIT, false, false, true, false)).act(),
                "after its tries it gives up and follows");
        assertTrue(lead.gaveUp());
    }

    @Test
    void theirCrossingWhileItCameBackEndsIt() {
        MentorLead lead = new MentorLead(TWO_WAY, WAIT, 2, 0L);
        lead.start();
        lead.step(see(1_000, true, false, false, false));
        lead.step(see(2_000, false, false, true, false));
        lead.step(see(2_000 + WAIT, false, false, true, false)); // coming back
        assertEquals(MentorLead.Act.FOLLOW, lead.step(see(23_000, false, true, false, true)).act());
        assertFalse(lead.gaveUp());
    }

    @Test
    void onAOneWayPortalItWaitsBesideItAndTheyGoFirst() {
        MentorLead lead = new MentorLead(ONE_WAY, WAIT, 2, 0L);
        lead.start();
        MentorLead.Move wait = lead.step(see(1_000, true, false, false, false));
        assertEquals(new MentorLead.Move(MentorLead.Act.NONE, "lead.oneway"), wait, "it says why it waits, never goes");
        assertEquals(MentorLead.Phase.WAITING_NEAR, lead.phase());
        for (long t = 2_000; t < 1_000 + WAIT; t += 1_000) {
            assertEquals(MentorLead.Act.NONE, lead.step(see(t, true, false, false, false)).act(), "never ENTER at " + t);
        }
        MentorLead.Move nudge = lead.step(see(1_000 + WAIT, true, false, false, false));
        assertEquals(new MentorLead.Move(MentorLead.Act.NONE, "lead.nudge"), nudge, "a nudge, still on this side");
        assertEquals(MentorLead.Act.FOLLOW, lead.step(see(30_000, true, false, false, true)).act(),
                "they went through: the follow brings it right behind");
    }

    @Test
    void aOneWayWaitThatNobodyTakesEndsInTheFollow() {
        MentorLead lead = new MentorLead(ONE_WAY, WAIT, 1, 0L);
        lead.start();
        lead.step(see(1_000, true, false, false, false));
        assertEquals("lead.nudge", lead.step(see(1_000 + WAIT, true, false, false, false)).line());
        assertEquals(MentorLead.Act.FOLLOW, lead.step(see(1_000 + 2 * WAIT, true, false, false, false)).act());
        assertTrue(lead.gaveUp());
    }

    @Test
    void offLithsShipDeckItWalksThroughItselfOnceTheyAreInTown() {
        MentorLead lead = new MentorLead(DECK, WAIT, 2, 0L);
        lead.start();
        assertEquals("lead.deck", lead.step(see(1_000, true, false, false, false)).line());
        assertEquals(MentorLead.Act.ENTER, lead.step(see(5_000, true, false, false, true)).act(),
                "the hidden portal is on the same map: no follow warp brings it, so it goes itself");
        assertEquals(MentorLead.Act.NONE, lead.step(see(6_000, false, true, false, true)).act());
        assertEquals(MentorLead.Act.FOLLOW, lead.step(see(7_000, false, false, true, true)).act());
    }

    @Test
    void aNewcomerGoingAnotherWayEndsTheLeadAndItFollowsThem() {
        MentorLead lead = new MentorLead(TWO_WAY, WAIT, 2, 0L);
        lead.start();
        MentorLead.Move move = lead.step(new MentorLead.View(1_000, false, true, false, false, true));
        assertEquals(MentorLead.Act.FOLLOW, move.act());
        assertNull(move.line());
        assertTrue(lead.gaveUp());
    }

    @Test
    void aStalledWalkSetsOffAgain() {
        MentorLead lead = new MentorLead(TWO_WAY, WAIT, 2, 0L);
        lead.start();
        assertEquals(MentorLead.Act.GO_DOOR, lead.step(see(1_000, false, false, false, false)).act());
        lead.step(see(2_000, true, false, false, false)); // entering
        assertEquals(MentorLead.Act.ENTER, lead.step(see(3_000, true, false, false, false)).act(), "the portal again");
    }

    @Test
    void theyGoingThroughFirstOnATwoWayPortalEndsIt() {
        MentorLead lead = new MentorLead(TWO_WAY, WAIT, 2, 0L);
        lead.start();
        assertEquals(MentorLead.Act.FOLLOW, lead.step(see(1_000, false, true, false, true)).act(),
                "they are ahead: it follows right behind");
        assertFalse(lead.gaveUp());
    }
}
