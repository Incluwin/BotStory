package server.agents.capabilities.partyquest.ppq;

import org.junit.jupiter.api.Test;
import server.maps.Reactor;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.BEN;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.MIRA;

/**
 * PPQ Step 1b.3 (D5; every mode): the stall watch counts what the stock stages do, not only the coordinator's own
 * steps. A kill on any member's map, a member's medals or keys changing hands (the human leader's too) and a door or
 * chest changing state are progress; a respawn is not, and ten minutes of nothing still stall the run.
 */
class AgentPpqStallTest {
    private static final long MINUTE = 60_000L;

    @Test
    void aTwelveMinuteMedalWaveWithKillsNeverStalls() {
        try (PpqRunFixture run = new PpqRunFixture(false, AgentPpqDefinition.MEDAL_MAP)) {
            run.properties.put("stage2", "1");
            AtomicInteger alive = new AtomicInteger(12);
            when(run.actions.liveMonsterCount(any(), eq(AgentPpqDefinition.COMBAT_MOBS))).thenAnswer(call -> alive.get());

            for (int minute = 1; minute <= 12; minute++) {
                // A kill on odd minutes, a respawn on even ones: the count only ever goes 12 -> 11 -> 12.
                alive.set(minute % 2 == 1 ? 11 : 12);
                run.tick(1_000L + minute * MINUTE);
                assertFalse(run.session.terminal(), "minute " + minute + ": " + run.session.failure());
            }
        }
    }

    @Test
    void aRespawnIsNotProgress() {
        try (PpqRunFixture run = new PpqRunFixture(false, AgentPpqDefinition.DECK_ONE_MAP)) {
            AtomicInteger alive = new AtomicInteger(3);
            when(run.actions.liveMonsterCount(any(), eq(AgentPpqDefinition.COMBAT_MOBS))).thenAnswer(call -> alive.get());
            run.tick(2_000L);
            long before = run.session.lastProgressAtMs();

            alive.set(8);
            run.tick(3_000L);

            assertEquals(before, run.session.lastProgressAtMs());
        }
    }

    @Test
    void theLeadersMedalHandInIsProgress() {
        try (PpqRunFixture run = new PpqRunFixture(false, AgentPpqDefinition.MEDAL_MAP)) {
            AtomicInteger medals = new AtomicInteger(20);
            when(run.character(BEN).getItemQuantity(eq(AgentPpqDefinition.ROOKIE_MEDAL), anyBoolean()))
                    .thenAnswer(call -> medals.get());
            run.tick(2_000L);
            long before = run.session.lastProgressAtMs();

            run.tick(3_000L);
            assertEquals(before, run.session.lastProgressAtMs(), "nothing changed");
            medals.set(0);
            run.tick(4_000L);

            assertEquals(4_000L, run.session.lastProgressAtMs());
        }
    }

    @Test
    void aCompanionPickingUpAKeyIsProgress() {
        try (PpqRunFixture run = new PpqRunFixture(false, AgentPpqDefinition.DOOR_MAP)) {
            AtomicInteger keys = new AtomicInteger();
            when(run.character(MIRA).getItemQuantity(eq(AgentPpqDefinition.OLD_METAL_KEY), anyBoolean()))
                    .thenAnswer(call -> keys.get());
            run.tick(2_000L);

            keys.set(1);
            run.tick(3_000L);

            assertEquals(3_000L, run.session.lastProgressAtMs());
        }
    }

    @Test
    void aDoorOpeningIsProgress() {
        try (PpqRunFixture run = new PpqRunFixture(false, AgentPpqDefinition.DOOR_MAP)) {
            Reactor door = mock(Reactor.class);
            when(door.getObjectId()).thenReturn(77);
            when(door.getState()).thenReturn((byte) 0);
            when(run.actions.reactors(any())).thenReturn(List.of(door));
            run.tick(2_000L);
            long before = run.session.lastProgressAtMs();
            run.tick(3_000L);
            assertEquals(before, run.session.lastProgressAtMs());

            when(door.getState()).thenReturn((byte) 1);
            run.tick(4_000L);

            assertEquals(4_000L, run.session.lastProgressAtMs());
        }
    }

    @Test
    void theLeadersNewStageIsProgress() {
        try (PpqRunFixture run = new PpqRunFixture(false, AgentPpqDefinition.DECK_ONE_MAP)) {
            run.tick(2_000L);
            run.onMap(BEN, AgentPpqDefinition.DECK_TWO_MAP);

            run.tick(3_000L);

            assertEquals(3_000L, run.session.lastProgressAtMs());
        }
    }

    @Test
    void pinTenMinutesOfNothingStillStall() {
        try (PpqRunFixture run = new PpqRunFixture(false, AgentPpqDefinition.MEDAL_MAP)) {
            run.properties.put("stage2", "1");
            when(run.actions.liveMonsterCount(any(), eq(AgentPpqDefinition.COMBAT_MOBS))).thenReturn(12);
            // The first pass finds the leader on the medal map at the start (a stage change, at 1 s): then nothing.
            for (int minute = 0; minute <= 10; minute++) {
                run.tick(1_000L + minute * MINUTE);
            }

            assertEquals("PPQ stalled", run.session.failure());
        }
    }
}
