package server.agents.capabilities.partyquest.epq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.life.Monster;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The base smoke's evidence lines, logged in every mode and changing nothing: a member seen down (once per death), the
 * golems as they show and the clear, and the coordinator's assists ('EPQ rescue:', the first of each kind, every one
 * counted into the released line).
 */
class AgentEpqProgressLogTest {
    private final EpqRunFixture run = new EpqRunFixture();
    private final Character ben = run.human(1, "Ben", AgentEpqDefinition.STAGE_ONE_MAP);
    private final Character mira = run.companion(2, "Mira", AgentEpqDefinition.STAGE_ONE_MAP);
    private final Character tobi = run.companion(3, "Tobi", AgentEpqDefinition.STAGE_ONE_MAP);
    private final Character una = run.companion(4, "Una", AgentEpqDefinition.STAGE_ONE_MAP);

    @AfterEach
    void tearDown() {
        run.close();
    }

    @Test
    void aMemberDownIsLoggedOncePerDeath() {
        AgentEpqSession session = session(AgentEpqSession.Phase.STAGE_ONE);
        when(tobi.getHp()).thenReturn(0);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickSession(session, 2_000L);
            AgentEpqCoordinator.tickSession(session, 3_000L); // still down: no second line
            when(tobi.getHp()).thenReturn(250);
            AgentEpqCoordinator.tickSession(session, 4_000L);
            when(tobi.getHp()).thenReturn(0);
            AgentEpqCoordinator.tickSession(session, 5_000L);

            String down = "EPQ member down: session=" + session.sessionId()
                    + " member=3 type=AGENT map=930000100 phase=STAGE_ONE";
            assertEquals(List.of(down, down), lines.startingWith("EPQ member down:"));
        }
    }

    @Test
    void anOfflineMemberIsNotSeenDown() {
        AgentEpqSession session = session(AgentEpqSession.Phase.STAGE_ONE);
        when(una.getHp()).thenReturn(0);
        run.offline(una);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickSession(session, 2_000L);

            assertEquals(List.of(), lines.startingWith("EPQ member down:"));
        }
    }

    @Test
    void eachGolemIsLoggedTheFirstTimeItShows() {
        for (Character member : List.of(ben, mira, tobi, una)) run.onMap(member, AgentEpqDefinition.BOSS_MAP);
        AgentEpqSession session = session(AgentEpqSession.Phase.BOSS);
        run.monster(9_300_181);
        Monster dead = run.monster(9_300_182);
        when(dead.isAlive()).thenReturn(false);
        run.monster(9_300_177); // an add, not a golem
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickSession(session, 61_000L);
            AgentEpqCoordinator.tickSession(session, 62_000L);

            assertEquals(List.of("EPQ boss: session=" + session.sessionId() + " golem=9300181 runMs=60000"),
                    lines.startingWith("EPQ boss:"));
        }
    }

    @Test
    void theClearIsLoggedOnceWhereverTheLeaderIs() {
        for (Character member : List.of(ben, mira, tobi, una)) run.onMap(member, AgentEpqDefinition.BOSS_MAP);
        AgentEpqSession session = session(AgentEpqSession.Phase.BOSS);
        run.onMap(ben, AgentEpqDefinition.REWARD_MAP);
        when(run.event.isEventCleared()).thenReturn(true);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickSession(session, 91_000L);
            AgentEpqCoordinator.tickSession(session, 92_000L);

            assertEquals(List.of("EPQ boss: session=" + session.sessionId() + " cleared runMs=90000"),
                    lines.startingWith("EPQ boss:"));
        }
    }

    @Test
    void theFirstFlowerRespawnIsLoggedAndEveryOneIsCounted() {
        for (Character member : List.of(ben, mira, tobi, una)) {
            run.onMap(member, AgentEpqDefinition.STAGE_FOUR_MAP);
        }
        AgentEpqSession session = stockSession(AgentEpqSession.Phase.STAGE_FOUR);
        when(run.actions.itemCount(mira, AgentEpqDefinition.PURIFICATION_MARBLE)).thenReturn(1);
        try (EpqLogLines coordinator = new EpqLogLines(AgentEpqCoordinator.class);
             EpqLogLines termination = new EpqLogLines(AgentEpqTerminationService.class)) {
            AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 2_000L);
            AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 3_000L);

            assertEquals(List.of("EPQ rescue: session=" + session.sessionId()
                            + " kind=respawn-flowers agent=Mira map=930000400 first=true"),
                    coordinator.startingWith("EPQ rescue:"));
            verify(run.map, times(2)).instanceMapForceRespawn();
            assertEquals(2, session.rescueCounts().get("respawn-flowers"));

            AgentEpqTerminationService.release(session, "stopped by operator", 4_000L, false);
            assertEquals(1, termination.startingWith("EPQ session released:").size());
            assertTrue(termination.startingWith("EPQ session released:").getFirst()
                    .endsWith(" rescues={respawn-flowers=2}"), termination.startingWith("EPQ session released:")::toString);
        }
    }

    @Test
    void killingTheDummyAfterTheClearIsAnAssistAndStillHappens() {
        for (Character member : List.of(ben, mira, tobi, una)) run.onMap(member, AgentEpqDefinition.BOSS_MAP);
        AgentEpqSession session = stockSession(AgentEpqSession.Phase.BOSS);
        when(run.event.isEventCleared()).thenReturn(true);
        Monster dummy = run.monster(AgentEpqDefinition.POST_DEATH_DUMMY);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 2_000L);

            assertEquals(List.of("EPQ rescue: session=" + session.sessionId()
                            + " kind=kill-dummy agent=Mira map=930000600 first=true"),
                    lines.startingWith("EPQ rescue:"));
        }
        verify(run.map).killMonster(dummy, null, false, (short) 0);
        assertEquals(1, session.rescueCounts().get("kill-dummy"));
    }

    private AgentEpqSession session(AgentEpqSession.Phase phase) {
        return run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, true, phase, ben, mira, tobi, una);
    }

    /** The same run outside a companion run: its subject is every mode's behaviour. */
    private AgentEpqSession stockSession(AgentEpqSession.Phase phase) {
        return run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, false, phase, ben, mira, tobi, una);
    }
}
