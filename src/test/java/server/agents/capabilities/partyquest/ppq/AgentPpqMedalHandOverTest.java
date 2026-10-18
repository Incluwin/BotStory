package server.agents.capabilities.partyquest.ppq;

import client.Character;
import client.inventory.InventoryType;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.plans.AgentScriptItemActionService;
import server.maps.MapItem;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atMostOnce;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.BEN;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.MIRA;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.TOBI;

/**
 * PPQ medal hand-over (PQ retrospective, 2026-10-17): only the event leader may hand Guon a wave's twenty marks, so in
 * a companion run the companions gather the current wave's marks and drop them at the human leader's feet (or by
 * Guon when he cannot be reached), saying so in words, never by item id. The guide line is said once per run, not
 * once by every companion.
 */
class AgentPpqMedalHandOverTest {
    private static final int ROOKIE = AgentPpqDefinition.ROOKIE_MEDAL;

    @Test
    void aBatchGoesWhileHeIsNearAndEveryCarrierGoesAtTwenty() {
        assertTrue(AgentPpqMedalHandOver.handOverDue(5, 5, true, 5, 20));
        assertFalse(AgentPpqMedalHandOver.handOverDue(5, 5, false, 5, 20));
        assertFalse(AgentPpqMedalHandOver.handOverDue(4, 10, true, 5, 20));
        assertTrue(AgentPpqMedalHandOver.handOverDue(1, 20, false, 5, 20));
        assertFalse(AgentPpqMedalHandOver.handOverDue(0, 25, true, 5, 20));
    }

    @Test
    void theCarrierWalksDropsAtHisFeetBesideHimOrGoesToGuon() {
        Point him = new Point(100, 50);
        assertEquals(AgentPpqMedalHandOver.HandOver.TO_NPC, AgentPpqMedalHandOver.handOver(
                new Point(110, 50), him, true, true, null, false, 0L, 20_000L, 60));
        assertEquals(AgentPpqMedalHandOver.HandOver.TO_NPC, AgentPpqMedalHandOver.handOver(
                new Point(500, 50), him, true, true, null, true, 20_001L, 20_000L, 60));
        assertEquals(AgentPpqMedalHandOver.HandOver.WALK, AgentPpqMedalHandOver.handOver(
                new Point(500, 50), him, true, true, null, true, 1_000L, 20_000L, 60));
        assertEquals(AgentPpqMedalHandOver.HandOver.DROP_AT_FEET, AgentPpqMedalHandOver.handOver(
                new Point(110, 50), him, true, true, new Point(108, 50), true, 1_000L, 20_000L, 60));
        assertEquals(AgentPpqMedalHandOver.HandOver.DROP_BESIDE, AgentPpqMedalHandOver.handOver(
                new Point(150, 50), him, true, true, new Point(170, 50), true, 1_000L, 20_000L, 60));
        // a platform above catches the drop: walk on rather than drop it out of his reach
        assertEquals(AgentPpqMedalHandOver.HandOver.WALK, AgentPpqMedalHandOver.handOver(
                new Point(110, 50), him, true, true, new Point(110, -40), true, 1_000L, 20_000L, 60));
    }

    @Test
    void reCollectsAndNudgesFollowTheirDelaysAndCap() {
        assertFalse(AgentPpqMedalHandOver.recollectDue(1_000L, 100_000L, 120_000L, 0, 2));
        assertTrue(AgentPpqMedalHandOver.recollectDue(1_000L, 121_000L, 120_000L, 1, 2));
        assertFalse(AgentPpqMedalHandOver.recollectDue(1_000L, 500_000L, 120_000L, 2, 2));
        assertTrue(AgentPpqMedalHandOver.nudgeDue(1_000L, 16_000L, 15_000L));
        assertEquals(170_000L, AgentPpqMedalHandOver.recollectAfterMs(200_000L, 170_000L));
        assertTrue(AgentPpqMedalHandOver.makesTwenty(5, 12, 3, 20));
        assertFalse(AgentPpqMedalHandOver.makesTwenty(5, 12, 2, 20));
    }

    @Test
    void theLinesNameTheMarksNotTheirItemIds() {
        assertEquals("Ben, we'll gather the Rookie Pirate marks for you. Talk to Guon at twenty.",
                AgentPpqMedalHandOver.waveLine(ROOKIE, "Ben"));
        assertEquals("Rising Pirate marks at your feet, Ben.",
                AgentPpqMedalHandOver.atFeetLine(AgentPpqDefinition.RISING_MEDAL, "Ben", true));
        assertEquals("Ben, that makes twenty Veteran Pirate marks. Talk to Guon.",
                AgentPpqMedalHandOver.twentyLine(AgentPpqDefinition.VETERAN_MEDAL, "Ben"));
        for (int medal : AgentPpqDefinition.MEDALS) {
            for (String line : new String[] {AgentPpqMedalHandOver.waveLine(medal, "Ben"),
                    AgentPpqMedalHandOver.byGuonLine(medal, "Ben"), AgentPpqMedalHandOver.nudgeLine(medal, "Ben"),
                    AgentPpqMedalHandOver.recollectedLine(medal), AgentPpqMedalHandOver.twentyLine(medal, "Ben")}) {
                assertFalse(line.contains(String.valueOf(medal)), line);
                assertTrue(line.length() <= 100, line);
            }
        }
    }

    @Test
    void aCompanionMayGatherTheWavesNaturalMarksOnly() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.MEDAL_MAP)) {
            AgentPpqSessionRegistry.registerComplete(run.session);
            try {
                run.properties.put("stage2", "1");
                Character mira = run.character(MIRA);
                assertTrue(AgentPpqSessionRegistry.canLootExclusive(mira, drop(AgentPpqDefinition.RISING_MEDAL,
                        false, 7)));
                // last wave's marks are no use to anyone now
                assertFalse(AgentPpqSessionRegistry.canLootExclusive(mira, drop(ROOKIE, false, 8)));
                // a stack dropped for the leader is his, but for the companion that claimed it back
                MapItem handedOver = drop(AgentPpqDefinition.RISING_MEDAL, true, 9);
                assertFalse(AgentPpqSessionRegistry.canLootExclusive(mira, handedOver));
                assertTrue(run.session.beginRecollect(9, MIRA));
                assertTrue(AgentPpqSessionRegistry.canLootExclusive(mira, handedOver));
                assertFalse(AgentPpqSessionRegistry.canLootExclusive(run.character(TOBI), handedOver));
            } finally {
                AgentPpqSessionRegistry.remove(run.session);
            }
        }
    }

    @Test
    void outsideACompanionRunOnlyTheLeaderLootsMarks() {
        try (PpqRunFixture run = new PpqRunFixture(false, AgentPpqDefinition.MEDAL_MAP,
                AgentPpqSession.Mode.AUTONOMOUS)) {
            run.session.addMember(14, AgentPpqMemberState.MemberType.AGENT);
            run.session.addMember(15, AgentPpqMemberState.MemberType.AGENT);
            AgentPpqSessionRegistry.registerComplete(run.session);
            try {
                assertTrue(AgentPpqSessionRegistry.canLootExclusive(run.character(MIRA), drop(ROOKIE, false, 7)));
                assertFalse(AgentPpqSessionRegistry.canLootExclusive(run.character(TOBI), drop(ROOKIE, false, 7)));
            } finally {
                AgentPpqSessionRegistry.remove(run.session);
            }
        }
    }

    @Test
    void aBatchIsDroppedAtTheNearLeadersFeet() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.MEDAL_MAP);
             MockedStatic<AgentScriptItemActionService> drops = mockStatic(AgentScriptItemActionService.class)) {
            Character mira = carrier(run, 5, new Point(110, 50));
            drops.when(() -> AgentScriptItemActionService.dropItem(run.entries.get(MIRA), InventoryType.ETC, ROOKIE,
                    (short) 5)).thenReturn(true);

            AgentPpqCoordinator.tickMember(run.session, run.entries.get(MIRA), mira, 5_000L);

            drops.verify(() -> AgentScriptItemActionService.dropItem(run.entries.get(MIRA), InventoryType.ETC,
                    ROOKIE, (short) 5));
            verify(run.party).sendPartyChat(mira, "Rookie Pirate marks at your feet, c865.");
        }
    }

    @Test
    void theHandOverThatMakesTwentyTellsHimToTalkToGuon() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.MEDAL_MAP);
             MockedStatic<AgentScriptItemActionService> drops = mockStatic(AgentScriptItemActionService.class)) {
            Character mira = carrier(run, 3, new Point(110, 50));
            when(run.actions.itemCount(run.character(BEN), ROOKIE)).thenReturn(17);
            drops.when(() -> AgentScriptItemActionService.dropItem(run.entries.get(MIRA), InventoryType.ETC, ROOKIE,
                    (short) 3)).thenReturn(true);

            AgentPpqCoordinator.tickMember(run.session, run.entries.get(MIRA), mira, 5_000L);

            verify(run.party).sendPartyChat(mira, "c865, that makes twenty Rookie Pirate marks. Talk to Guon.");
        }
    }

    @Test
    void aLeaderItCannotReachGetsTheMarksByGuon() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.MEDAL_MAP);
             MockedStatic<AgentScriptItemActionService> drops = mockStatic(AgentScriptItemActionService.class)) {
            Character mira = carrier(run, 5, new Point(110, 50));
            when(run.actions.canReach(any(), any(), any())).thenReturn(false);
            when(run.actions.npcPosition(mira, AgentPpqDefinition.GUIDE_NPC)).thenReturn(new Point(120, 50));
            drops.when(() -> AgentScriptItemActionService.dropItem(run.entries.get(MIRA), InventoryType.ETC, ROOKIE,
                    (short) 5)).thenReturn(true);

            AgentPpqCoordinator.tickMember(run.session, run.entries.get(MIRA), mira, 5_000L);

            verify(run.party).sendPartyChat(mira, "c865, I left the Rookie Pirate marks by Guon.");
        }
    }

    @Test
    void aFarLeaderWithFewMarksIsNotWalkedTo() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.MEDAL_MAP);
             MockedStatic<AgentScriptItemActionService> drops = mockStatic(AgentScriptItemActionService.class)) {
            Character mira = carrier(run, 5, new Point(900, 50));
            when(run.actions.liveMonsterCount(any(), any())).thenReturn(4);

            AgentPpqCoordinator.tickMember(run.session, run.entries.get(MIRA), mira, 5_000L);

            drops.verifyNoInteractions();
            verify(run.actions).grind(run.entries.get(MIRA), AgentPpqDefinition.COMBAT_MOBS);
        }
    }

    @Test
    void theWaveLineIsSaidOnceNotOnceByEveryCompanion() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.MEDAL_MAP)) {
            run.properties.put("stage2", "0");

            run.tick(5_000L);
            run.tick(6_000L);
            run.tick(7_000L);

            verify(run.party, times(1)).sendPartyChat(any(),
                    eq("c865, we'll gather the Rookie Pirate marks for you. Talk to Guon at twenty."));
            verify(run.party, never()).sendPartyChat(any(), eq("c865, loot 20 of item " + ROOKIE
                    + " and talk to Guon. We will keep killing."));
            // the fixture's run never left PREPARING: its entry line too comes once, not once per companion
            verify(run.party, atMostOnce()).sendPartyChat(any(), eq("c865, talk to Guon to enter Pirate PQ."));
        }
    }

    /** Mira holds {@code held} Rookie marks at {@code at}; Ben stands at (100,50); both on the ground, reachable. */
    private static Character carrier(PpqRunFixture run, int held, Point at) {
        run.properties.put("stage2", "0");
        Character mira = run.character(MIRA);
        Character ben = run.character(BEN);
        when(mira.getPosition()).thenReturn(at);
        when(ben.getPosition()).thenReturn(new Point(100, 50));
        when(run.actions.itemCount(mira, ROOKIE)).thenReturn(held);
        when(run.actions.footing(any())).thenReturn(PrimitiveCapabilityGateway.Footing.GROUND);
        when(run.actions.canReach(any(), any(), any())).thenReturn(true);
        return mira;
    }

    private static MapItem drop(int itemId, boolean playerDrop, int objectId) {
        MapItem drop = mock(MapItem.class);
        when(drop.getItemId()).thenReturn(itemId);
        when(drop.isPlayerDrop()).thenReturn(playerDrop);
        when(drop.getObjectId()).thenReturn(objectId);
        return drop;
    }
}
