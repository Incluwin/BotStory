package server.agents.capabilities.partyquest.epq;

import client.Character;
import client.Disease;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.combat.AgentCombatSkillConstraintState;
import server.life.Monster;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Live 2026-10-08 (session ad7e9a29): 9300182 lived 14 min. Its seal (MobSkill 120 level 3: 70 % a cast, 30 s, every
 * character within 450 px) takes every attack skill, and EPQ requires an attack skill of each member every tick, so a
 * sealed companion had no attack at all while it stood in melee drinking potions. A companion run's sealed member now
 * falls back to its basic attack, and the requirement comes back once the seal ends; a stock run keeps today's rule.
 * While a golem lives, 'EPQ boss hp:' says every BOSS_HP_LOG_INTERVAL_MS how much HP it has left and who is sealed.
 */
class AgentEpqSealedCompanionTest {
    private static final int GOLEM = AgentEpqDefinition.SUPER_CHARGED_POISON_GOLEM;

    private final EpqRunFixture run = new EpqRunFixture();
    private final Character ben = run.human(1, "Ben", AgentEpqDefinition.BOSS_MAP);
    private final Character mira = run.companion(2, "Mira", AgentEpqDefinition.BOSS_MAP);
    private final Character tobi = run.companion(3, "Tobi", AgentEpqDefinition.BOSS_MAP);
    private final Character una = run.companion(4, "Una", AgentEpqDefinition.BOSS_MAP);
    private final Monster golem;

    AgentEpqSealedCompanionTest() {
        when(run.event.getPlayers()).thenReturn(List.of(ben, mira, tobi, una));
        when(run.actions.liveMonsterCount(any(), eq(AgentEpqDefinition.BOSS_MOBS))).thenReturn(1);
        golem = run.monster(GOLEM);
        when(golem.getHp()).thenReturn(61_290L);
        when(golem.getMaxHp()).thenReturn(113_500L);
    }

    @AfterEach
    void tearDown() {
        run.close();
    }

    private AgentEpqSession session(boolean companionRun) {
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, companionRun,
                AgentEpqSession.Phase.BOSS, ben, mira, tobi, una);
        session.setMemberCoordinationChatEnabled(true);
        return session;
    }

    private void tick(AgentEpqSession session, Character member, long nowMs) {
        AgentEpqMemberState state = session.member(member.getId());
        if (state != null) state.deferUntil(0L);
        AgentEpqCoordinator.tickMember(session, run.entry(member), member, nowMs);
    }

    private boolean attackSkillRequired(Character companion) {
        return run.entry(companion).capabilityStates().require(AgentCombatSkillConstraintState.STATE_KEY)
                .attackSkillRequired();
    }

    private void sealed(Character member, boolean sealed) {
        when(member.hasDisease(Disease.SEAL)).thenReturn(sealed);
    }

    @Test
    void aSealedCompanionOfACompanionRunMayFallBackToItsBasicAttackAndFightsOn() {
        AgentEpqSession session = session(true);
        sealed(mira, true);

        tick(session, mira, 2_000L);
        tick(session, tobi, 2_000L);

        assertFalse(attackSkillRequired(mira), "sealed: its basic attack is its only attack");
        assertTrue(attackSkillRequired(tobi), "not sealed: today's rule");
        verify(run.actions).grind(run.entry(mira), AgentEpqDefinition.BOSS_COMBAT_TARGETS);
    }

    @Test
    void theRequirementComesBackOnceTheSealEnds() {
        AgentEpqSession session = session(true);
        sealed(mira, true);
        tick(session, mira, 2_000L);
        assertFalse(attackSkillRequired(mira));

        sealed(mira, false);
        tick(session, mira, 32_000L);

        assertTrue(attackSkillRequired(mira));
    }

    @Test
    void pinAStockRunKeepsTheRequirementWhileSealed() {
        AgentEpqSession session = session(false);
        sealed(mira, true);

        tick(session, mira, 2_000L);

        assertTrue(attackSkillRequired(mira));
    }

    @Test
    void whileAGolemLivesTheBossHpLineComesEveryInterval() {
        AgentEpqSession session = session(true);
        sealed(mira, true);
        sealed(una, true);
        long interval = AgentEpqCoordinator.BOSS_HP_LOG_INTERVAL_MS;
        String line = "EPQ boss hp: session=" + session.sessionId() + " golem=" + GOLEM
                + " hp=61290/113500 pct=54 members=4 sealed=Mira,Una";
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickSession(session, 2_000L);
            AgentEpqCoordinator.tickSession(session, 2_000L + interval - 1L);
            assertEquals(List.of(line), lines.startingWith("EPQ boss hp:"));

            sealed(mira, false);
            sealed(una, false);
            when(tobi.getHp()).thenReturn(0); // down: not counted
            AgentEpqCoordinator.tickSession(session, 2_000L + interval);

            assertEquals(List.of(line, "EPQ boss hp: session=" + session.sessionId() + " golem=" + GOLEM
                    + " hp=61290/113500 pct=54 members=3 sealed=-"), lines.startingWith("EPQ boss hp:"));
        }
    }

    @Test
    void noBossHpLineWithoutALiveGolemAndTheLineInAStockRunToo() {
        AgentEpqSession stock = session(false);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickSession(stock, 2_000L);
            assertEquals(1, lines.startingWith("EPQ boss hp: session=" + stock.sessionId()).size());

            when(golem.isAlive()).thenReturn(false);
            AgentEpqCoordinator.tickSession(stock, 2_000L + AgentEpqCoordinator.BOSS_HP_LOG_INTERVAL_MS);

            assertEquals(1, lines.startingWith("EPQ boss hp:").size());
        }
    }
}
