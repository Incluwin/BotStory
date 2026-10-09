package server.agents.capabilities.partyquest.rjpq;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.BEN;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.MIRA;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.TOBI;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.UNA;

/**
 * The departure gate's short-handed clause (8.4): a dying companion with another companion standing is held and the run
 * goes on; the last one standing going down ends the run on that first hold, in its own words, and is still held until
 * the release.
 */
class AgentRjpqShortHandedGateTest {
    private final RjpqStageFixture fixture =
            new RjpqStageFixture(AgentRjpqSide.ZENUMIST, AgentRjpqDefinition.CENTER_LAB_MAP);
    private final AtomicInteger respawns = new AtomicInteger();

    @AfterEach
    void close() {
        fixture.close();
    }

    private AgentRjpqDeathDepartureGate.EventView event() {
        return new AgentRjpqDeathDepartureGate.EventView() {
            @Override
            public boolean disposed() {
                return false;
            }

            @Override
            public boolean cleared() {
                return false;
            }

            @Override
            public int leaderId() {
                return BEN;
            }

            @Override
            public Set<Integer> registeredIds() {
                return Set.of(BEN, MIRA, TOBI, UNA);
            }
        };
    }

    private boolean die(int id, int standingOthers) {
        return AgentRjpqDeathDepartureGate.respawnOrHold(fixture.session, id, RjpqStageFixture.name(id), event(),
                respawns::incrementAndGet, dying -> standingOthers, 2_000L);
    }

    @Test
    void aCompanionGoingDownWithAFriendStandingIsHeldAndTheRunGoesOn() {
        assertFalse(die(TOBI, 1));

        assertFalse(fixture.session.terminal());
        assertEquals(0, respawns.get());
        assertTrue(fixture.said.isEmpty());
    }

    @Test
    void theLastOneStandingGoingDownEndsTheRunInItsOwnWordsAndStaysHeld() {
        assertFalse(die(TOBI, 0));

        assertTrue(fixture.session.terminal());
        assertEquals(AgentRjpqSession.Phase.FAILED, fixture.session.phase());
        assertEquals(0, respawns.get());
        assertEquals(List.of("i'm down too... that's it for us."), fixture.said);
        org.mockito.Mockito.verify(fixture.packets).broadcastChatText(fixture.character(TOBI),
                "i'm down too... that's it for us.", false, 0);
    }

    @Test
    void theClauseSpeaksOnlyOnTheFirstHold() {
        assertFalse(die(TOBI, 1));
        assertFalse(die(TOBI, 0));

        assertFalse(fixture.session.terminal());
    }
}
