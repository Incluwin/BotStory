package server.agents.capabilities.partyquest.rjpq;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.life.Monster;

import java.awt.Point;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.BEN;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.MIRA;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.TOBI;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.UNA;

/**
 * Stage seven, the Center Lab: companions guard the captive until the leader is in, then the one nearest Yulete warns
 * the party and talks to him (the boss call); while the boss lives everyone fights it except the captive's guard.
 */
class AgentRjpqCenterTest {
    private final RjpqStageFixture fixture =
            new RjpqStageFixture(AgentRjpqSide.ZENUMIST, AgentRjpqDefinition.CENTER_LAB_MAP);

    @BeforeEach
    void leaderStillOutside() {
        when(fixture.character(BEN).getMapId()).thenReturn(AgentRjpqDefinition.CENTER_ENTRANCE_MAP);
    }

    @AfterEach
    void close() {
        fixture.close();
    }

    @Test
    void beforeTheLeaderIsInTheyStandGuardAroundTheCaptiveAndNobodyCallsTheBoss() {
        fixture.monster(9_300_137, 50_000, -414, -118);
        fixture.at(MIRA, -414, -118);
        fixture.at(TOBI, 0, 100);
        fixture.at(UNA, 0, 100);

        fixture.tick(MIRA, 2_000L);
        fixture.tick(TOBI, 2_000L);
        fixture.tick(UNA, 2_000L);

        verify(fixture.actions).stop(fixture.entry(MIRA));
        verify(fixture.actions).navigate(fixture.entry(TOBI), new Point(-524, -118), true);
        verify(fixture.actions).navigate(fixture.entry(UNA), new Point(-304, -118), true);
        verify(fixture.partyQuest, never()).runNpc(any(), anyInt());
    }

    @Test
    void aNeoHuroidNearTheCaptiveWakesTheGuards() {
        fixture.monster(9_300_137, 50_000, -414, -118);
        fixture.monster(AgentRjpqDefinition.GUARD_HUROID_MOB_ID, 9_000, -300, -118);
        fixture.at(TOBI, 0, 100);

        fixture.tick(TOBI, 2_000L);

        verify(fixture.actions).grindFromAnchor(eq(fixture.entry(TOBI)), any(),
                eq(Set.of(AgentRjpqDefinition.GUARD_HUROID_MOB_ID)), eq(Set.of(AgentRjpqDefinition.GUARD_HUROID_MOB_ID)));
    }

    @Test
    void withTheLeaderInTheCompanionNearestYuleteWarnsThePartyAndCallsTheBossAMomentLater() {
        fixture.monster(9_300_137, 50_000, -414, -118);
        when(fixture.character(BEN).getMapId()).thenReturn(AgentRjpqDefinition.CENTER_LAB_MAP);
        fixture.at(MIRA, 190, 100);
        fixture.at(TOBI, -400, -118);

        fixture.tick(MIRA, 2_000L);
        fixture.tick(TOBI, 2_000L);
        fixture.tick(MIRA, 4_000L);
        verify(fixture.partyQuest, never()).runNpc(any(), anyInt());

        fixture.tick(MIRA, 6_000L);
        verify(fixture.partyQuest).runNpc(fixture.character(MIRA), 2_112_000);
        assertEquals(List.of("everyone ready? i'll talk to yulete."), fixture.said);
    }

    @Test
    void whileTheBossLivesEveryoneFightsItButTheCaptivesGuard() {
        fixture.monster(9_300_137, 50_000, -414, -118);
        fixture.monster(9_300_139, 600_000, 250, 100);
        fixture.at(MIRA, -410, -118);
        fixture.at(TOBI, 100, 100);

        fixture.tick(MIRA, 2_000L);
        fixture.tick(TOBI, 2_000L);

        verify(fixture.actions).grind(fixture.entry(TOBI), Set.of(9_300_139, 9_300_140));
        verify(fixture.actions, never()).grind(eq(fixture.entry(MIRA)), any());
        assertEquals(List.of("watch juliet! keep them off her."), fixture.said);
    }

    @Test
    void theCaptivesGuardCallsOutWhenSheIsHitAFewTimesAtMost() {
        Monster juliet = fixture.monster(9_300_137, 50_000, -414, -118);
        fixture.monster(9_300_139, 600_000, 250, 100);
        fixture.at(MIRA, -410, -118);
        long now = 2_000L;
        fixture.tick(MIRA, now);
        for (int hit = 1; hit <= 5; hit++) {
            when(juliet.getHp()).thenReturn(50_000L - hit * 1_000L);
            now += 31_000L;
            fixture.tick(MIRA, now);
        }

        assertEquals(3, fixture.said.stream().filter("juliet's getting hit, on it!"::equals).count());
    }

    @Test
    void whenTheLeaderWentDownTheyCallTheBossWithoutHim() {
        fixture.monster(9_300_137, 50_000, -414, -118);
        fixture.session.markLeaderWentDown();
        fixture.at(MIRA, 200, 100);

        fixture.tick(MIRA, 2_000L);
        fixture.tick(MIRA, 7_000L);

        verify(fixture.partyQuest).runNpc(fixture.character(MIRA), 2_112_000);
    }
}
