package server.agents.capabilities.partyquest.lpq;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.AgentPartyQuestRuntime;
import server.agents.capabilities.partyquest.epq.AgentEpqDefinition;
import server.agents.capabilities.partyquest.epq.AgentEpqMemberState;
import server.agents.capabilities.partyquest.epq.AgentEpqSession;
import server.agents.capabilities.partyquest.epq.AgentEpqSessionRegistry;

import java.awt.Point;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generic out-of-potions grind stop said "low on pots!! walking to you" where a party quest's own play holds the
 * member: an LPQ stage-9 rester fighting a summon from its rest point (grind mode) and an EPQ stage-one member, whose
 * grind the run gives again on every pass, neither of which walked anywhere. The run now owns their potion rest
 * (AgentPartyQuestRuntime.ownsPotionRest), as the EPQ golem rest already did. A stage-9 rester also drinks up and asks
 * its party for potions while it rests, as the EPQ golem rester does.
 */
class AgentLpqStageNinePotionRestTest {
    private static final int STAGE_NINE = AgentLpqDefinition.stage(9).mapId();

    @Test
    void anLpqStageNineResterOwnsItsPotionRestOnAlisharsFloorOnly() {
        AgentLpqSession session = new AgentLpqSession(AgentLpqSession.Mode.PRODUCTION, 1L, 742_700, 6, 0L);
        for (int id = 742_700; id < 742_706; id++) session.addMember(id, AgentLpqMemberState.MemberType.AGENT);
        session.setLeadership(742_700, 742_700);
        AgentLpqSessionRegistry.registerComplete(session);
        try {
            assertFalse(AgentPartyQuestRuntime.ownsPotionRest(742_701, STAGE_NINE), "fighting");
            session.member(742_701).applyStageNine(new AgentLpqStageNineBossPolicy.MemberState(null, null, null, null,
                    true, new Point(-250, 184)));
            assertTrue(AgentPartyQuestRuntime.ownsPotionRest(742_701, STAGE_NINE), "resting");
            assertFalse(AgentPartyQuestRuntime.ownsPotionRest(742_701, AgentLpqDefinition.stage(8).mapId()),
                    "another stage");
            assertFalse(AgentPartyQuestRuntime.ownsPotionRest(742_702, STAGE_NINE), "another member, not resting");
            assertFalse(AgentPartyQuestRuntime.ownsPotionRest(742_799, STAGE_NINE), "in no run");
        } finally {
            AgentLpqSessionRegistry.remove(session);
        }
    }

    @Test
    void anEpqRunOwnsItsStageOneGrind() {
        AgentEpqSession session = new AgentEpqSession(AgentEpqSession.Mode.AUTONOMOUS, 1L, 742_710, 0L);
        for (int id = 742_710; id < 742_715; id++) session.addMember(id, AgentEpqMemberState.MemberType.AGENT);
        AgentEpqSessionRegistry.registerComplete(session);
        try {
            assertTrue(AgentPartyQuestRuntime.ownsPotionRest(742_711, AgentEpqDefinition.STAGE_ONE_MAP));
            assertFalse(AgentPartyQuestRuntime.ownsPotionRest(742_711, AgentEpqDefinition.ENTRANCE_MAP),
                    "outside the stage-one grind");
            assertFalse(AgentPartyQuestRuntime.ownsPotionRest(742_799, AgentEpqDefinition.STAGE_ONE_MAP), "in no run");
        } finally {
            AgentEpqSessionRegistry.remove(session);
        }
    }

    @Test
    void aStageNineResterDrinksUpAndAsksForPotionsWhileItRests() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/server/agents/capabilities/partyquest/lpq/AgentLpqCoordinator.java"))
                .replace("\r\n", "\n");
        String fight = AgentLpqStageNineStationTest.method(source, "    private static void fightAlishar(");
        int apply = fight.indexOf("member.applyStageNine(order.state());");
        int rest = fight.indexOf("if (order.state().resting()) restUp(session, member, entry, agent, potions, potStop, nowMs);");
        assertTrue(apply > 0 && rest > apply && fight.indexOf("switch (order.move())") > rest, fight);
        String restUp = AgentLpqStageNineStationTest.method(source, "    private static void restUp(").replaceAll("\\s+", "");
        assertTrue(restUp.contains("potions>0&&agent.getHp()<agent.getMaxHp()*AgentLpqStageNineBossPolicy"
                + ".resumeHpFraction()&&member.claimStageNineRestDrink(nowMs,AgentLpqStageNineBossPolicy"
                + ".REST_DRINK_INTERVAL_MS)"), restUp);
        assertTrue(restUp.contains("AgentPotionService.selectAutopotForCurrentDeficit(agent,true)"), restUp);
        assertTrue(restUp.contains("potions<potStop&&member.claimStageNineRestAsk(nowMs,AgentLpqStageNineBossPolicy"
                + ".DRY_ASK_INTERVAL_MS)"), restUp);
        assertTrue(restUp.contains("AgentPotionService.requestPotShareAt(entry,agent,true,nowMs)"), restUp);
    }

    @Test
    void aResterDrinksAndAsksAtMostOncePerInterval() {
        AgentLpqMemberState member = new AgentLpqMemberState(742_720, AgentLpqMemberState.MemberType.AGENT);
        assertTrue(member.claimStageNineRestDrink(1_000L, 600L));
        assertFalse(member.claimStageNineRestDrink(1_599L, 600L));
        assertTrue(member.claimStageNineRestDrink(1_600L, 600L));
        assertTrue(member.claimStageNineRestAsk(1_000L, 30_000L), "the first ask as the dry rest starts");
        assertFalse(member.claimStageNineRestAsk(30_999L, 30_000L));
        assertTrue(member.claimStageNineRestAsk(31_000L, 30_000L));
    }
}
