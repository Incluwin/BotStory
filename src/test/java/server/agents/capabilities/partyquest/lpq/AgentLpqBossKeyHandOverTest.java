package server.agents.capabilities.partyquest.lpq;

import client.Character;
import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.plans.AgentScriptItemActionService;
import server.maps.MapItem;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P6g LPQ C2.7 (stage 9): the key never reached the leader live (a KEY_AWARD rescue gave it after ~4 min). In a
 * companion run the companion nearest to Alishar's dropped key becomes its carrier, loots it first, walks it to the
 * leader and drops it at his feet (K1; K1p while the violet balloon still has to explain itself), even while Alishar's
 * other drops still lie; the stock key recovery never runs. S9 opens the stage; a member out of potions says R1 as it
 * backs off.
 */
class AgentLpqBossKeyHandOverTest {
    private static final int S9 = AgentLpqDefinition.stage(9).mapId();
    private final LpqTickFixture fx = new LpqTickFixture();
    private List<Character> crew;
    private Character kes;

    @AfterEach
    void tearDown() {
        fx.close();
    }

    private AgentLpqSession afterAlishar(boolean companion) {
        fx.npcAt(AgentLpqDefinition.stage(9).npcId(), new Point(222, 70));
        crew = fx.crew(S9, new Point(300, 184));
        kes = crew.get(2);
        fx.moveTo(crew.getFirst(), new Point(400, 184));
        fx.moveTo(kes, new Point(950, 184));
        fx.drop(S9, AgentLpqDefinition.BOSS_KEY, 1, new Point(980, 184), false, 0, 1_000L);
        fx.drop(S9, 4_000_018, 1, new Point(960, 184), false, 0, 1_000L);
        AgentLpqSession session = fx.session(companion, AgentLpqSession.Phase.STAGE_9, crew.toArray(Character[]::new));
        session.narrateOnce("s9-start");
        return session;
    }

    @Test
    void theNearestCompanionCarriesTheKeyToHisFeet() {
        AgentLpqSession session = afterAlishar(true);
        try (LpqLogLines log = new LpqLogLines(AgentLpqCoordinator.class)) {
            fx.tick(session, 3_000L);
            assertEquals(kes.getId(), session.keyCarrierId());
            assertEquals(1, kes.getItemQuantity(AgentLpqDefinition.BOSS_KEY, false), "it loots the key first");
            assertEquals(0, kes.getItemQuantity(4_000_018, false));

            fx.tick(session, 4_000L);
            verify(fx.actions).navigate(fx.entry(kes), new Point(400, 184), true);
            fx.moveTo(kes, new Point(410, 184));
            fx.tick(session, 5_000L);

            fx.items.verify(() -> AgentScriptItemActionService.dropItem(fx.entry(kes), InventoryType.ETC,
                    AgentLpqDefinition.BOSS_KEY, (short) 1));
            fx.said(session, "s9-key", kes,
                    "Crewfriend, alishar's key is at your feet. grab it and talk to the violet balloon.");
            assertEquals(1, log.startingWith("LPQ hand-over: session=" + session.sessionId() + " member=Kes(3)"
                    + " stage=9 item=4001023 quantity=1 place=FEET").size());

            fx.tickEvery(session, 6_000L, 120_000L, 1_000L);
            verify(fx.inventory, never()).addItem(any(), anyInt(), anyShort());
            assertEquals(List.of(), log.startingWith("LPQ boss-key recovery"));
            fx.said(session, "s9-key-nudge", crew.get(1), "Crewfriend, the key's still by your feet.");
        }
    }

    @Test
    void beforeTheBalloonExplainsItselfTheKeyLineSaysToTalkTwice() {
        AgentLpqSession session = afterAlishar(true);
        fx.property("statusStg9", "-1");
        fx.tick(session, 3_000L);
        fx.moveTo(kes, new Point(410, 184));
        fx.tick(session, 4_000L);

        fx.said(session, "s9-key", kes, "Crewfriend, alishar's key is at your feet. grab it and talk to the violet"
                + " balloon.\nit explains first, then talk to it again.");
    }

    @Test
    void theLeaderHoldingTheKeyIsRemindedOnce() {
        AgentLpqSession session = afterAlishar(true);
        fx.holds(crew.getFirst(), AgentLpqDefinition.BOSS_KEY, 1);
        fx.tickEvery(session, 3_000L, 34_000L, 1_000L);

        fx.said(session, "s9-key-held", crew.get(1), "Crewfriend, take the key to the violet balloon up on the platform.");
    }

    @Test
    void stageNineOpensWithItsLineAndARestingMemberSaysSo() {
        fx.npcAt(AgentLpqDefinition.stage(9).npcId(), new Point(222, 70));
        crew = fx.crew(S9, new Point(300, 184));
        kes = crew.get(2);
        fx.monster(S9, AgentLpqDefinition.ALISHAR, new Point(700, 184));
        when(kes.getHp()).thenReturn(50);
        when(fx.actions.hpPotionCount(kes)).thenReturn(0);
        AgentLpqSession session = fx.session(true, AgentLpqSession.Phase.STAGE_9, crew.toArray(Character[]::new));

        fx.tickEvery(session, 3_000L, 4_000L, 1_000L);

        fx.said(session, "s9-start", crew.get(1),
                "shoot the black ratz on the ledge first, then alishar comes. Crewfriend, stay out of his reach.");
        fx.said(session, "rest-3", kes, "out of pots, backing off for a bit.");
    }

    @Test
    void theStockKeyRecoveryNeverRuns() {
        fx.npcAt(AgentLpqDefinition.stage(9).npcId(), new Point(222, 70));
        crew = fx.crew(S9, new Point(300, 184));
        kes = crew.get(2);
        fx.moveTo(crew.getFirst(), new Point(400, 184));
        fx.moveTo(kes, new Point(950, 184));
        fx.holds(kes, AgentLpqDefinition.BOSS_KEY, 1);
        AgentLpqSession session = fx.session(true, AgentLpqSession.Phase.STAGE_9, crew.toArray(Character[]::new));
        session.setKeyCarrierId(kes.getId());
        try (LpqLogLines log = new LpqLogLines(AgentLpqCoordinator.class)) {
            fx.tickEvery(session, 3_000L, 60_000L, 1_000L);

            assertEquals(List.of(), log.startingWith("LPQ boss-key"));
        }
        verify(fx.inventory, never()).addItem(any(), anyInt(), anyShort());
        verify(fx.actions, never()).stagePosition(any(), any(), any());
    }

    @Test
    void theStockRuleForTheKeyIsUnchanged() {
        assertTrue(AgentLpqCoordinator.stageNineAgentMayCollectItem(100, 100, AgentLpqDefinition.BOSS_KEY));
        assertFalse(AgentLpqCoordinator.stageNineAgentMayCollectItem(101, 100, AgentLpqDefinition.BOSS_KEY));
        assertTrue(AgentLpqCoordinator.stageNineAgentMayCollectItem(101, 100, 101, AgentLpqDefinition.BOSS_KEY));
        assertFalse(AgentLpqCoordinator.stageNineAgentMayCollectItem(102, 100, 101, AgentLpqDefinition.BOSS_KEY));
    }

    @Test
    void aStockRunStillWaitsForItsDropsAndRecoversTheKey() {
        AgentLpqSession session = afterAlishar(false);
        fx.tick(session, 3_000L);
        assertEquals(0, session.keyCarrierId());
        assertEquals(0, kes.getItemQuantity(AgentLpqDefinition.BOSS_KEY, false), "no Agent may loot the key");
        fx.saidNothing();
        MapItem key = fx.map(S9).getDroppedItems().stream()
                .filter(drop -> drop.getItemId() == AgentLpqDefinition.BOSS_KEY).findFirst().orElseThrow();
        assertFalse(key.isPickedUp());
    }
}
