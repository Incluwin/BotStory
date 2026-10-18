package server.agents.capabilities.partyquest.rjpq;

import client.BuffStat;
import client.inventory.InventoryType;
import constants.skills.Bandit;
import constants.skills.FPWizard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.maps.MapItem;
import server.maps.Reactor;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.BEN;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.MIRA;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.TOBI;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.UNA;

/**
 * RJPQ Dark Labs (PQ retrospective, 2026-10-17): once a card key opens a door, the companion whose jump reaches 120
 * with its Haste runs the left lab and one with Teleport the right; a lab nobody can reach is the leader's, and he is
 * told. A runner breaks the nearest box first (never picked by which one is real), leaves with the files, gives a lab
 * up when the gas would take it down, and drops the files at the leader's feet; only the runner, or a stack's
 * re-collector, may take the files.
 */
class AgentRjpqDarkLabsTest {
    private static final int ALCADNO_FILES = AgentRjpqDefinition.ALCADNO_FILES_ITEM_ID;
    private static final int ZENUMIST_FILES = AgentRjpqDefinition.ZENUMIST_FILES_ITEM_ID;
    private RjpqStageFixture fixture;
    private MockedStatic<client.SkillFactory> skills;

    private RjpqStageFixture on(int zenumistMap) {
        skills = mockStatic(client.SkillFactory.class);
        fixture = new RjpqStageFixture(AgentRjpqSide.ZENUMIST, zenumistMap);
        return fixture;
    }

    @AfterEach
    void close() {
        if (fixture != null) fixture.close();
        if (skills != null) skills.close();
    }

    private void doors(int labOne, int labTwo, Reactor... more) {
        Reactor[] all = new Reactor[3 + more.length];
        all[0] = fixture.reactor("rnj3_out1", labOne, -1_310, 176);
        all[1] = fixture.reactor("rnj3_out2", labTwo, 812, 175);
        all[2] = fixture.reactor("rnj3_out3", 0, -292, 34);
        System.arraycopy(more, 0, all, 3, more.length);
        fixture.reactors(all);
    }

    @Test
    void theHasteThiefAndTheTeleporterTakeTheLabsOnceADoorOpens() {
        on(AgentRjpqDefinition.SPECIAL_LAB_MAP);
        when(fixture.character(MIRA).getSkillLevel(FPWizard.TELEPORT)).thenReturn(10);
        when(fixture.character(TOBI).getTotalJumpStat()).thenReturn(100);
        when(fixture.character(TOBI).getSkillLevel(Bandit.HASTE)).thenReturn(20);
        when(fixture.character(TOBI).getBuffedValue(BuffStat.JUMP)).thenReturn(null);
        when(fixture.character(UNA).getTotalJumpStat()).thenReturn(110);
        when(fixture.character(UNA).getBuffedValue(BuffStat.JUMP)).thenReturn(null);
        doors(0, 0);

        fixture.tick(UNA, 2_000L);
        assertFalse(fixture.session.labRunnersChosen(), "no door is open yet");

        doors(1, 0);
        fixture.tick(UNA, 3_000L);

        assertEquals(TOBI, fixture.session.labRunner(AgentRjpqDarkLabs.LAB_ONE));
        assertEquals(MIRA, fixture.session.labRunner(AgentRjpqDarkLabs.LAB_TWO));
        assertTrue(fixture.said.contains(AgentRjpqCompanionPlay.labMine(AgentRjpqDarkLabs.LAB_ONE)));
        assertTrue(fixture.said.contains(AgentRjpqCompanionPlay.labMine(AgentRjpqDarkLabs.LAB_TWO)));
    }

    @Test
    void aLabNobodyCanReachIsTheLeadersAndHeIsTold() {
        on(AgentRjpqDefinition.SPECIAL_LAB_MAP);
        doors(1, 1);

        fixture.tick(UNA, 2_000L);

        assertEquals(AgentRjpqDarkLabs.NO_RUNNER, fixture.session.labRunner(AgentRjpqDarkLabs.LAB_ONE));
        assertEquals(AgentRjpqDarkLabs.NO_RUNNER, fixture.session.labRunner(AgentRjpqDarkLabs.LAB_TWO));
        assertTrue(fixture.said.contains(AgentRjpqCompanionPlay.labNobody(AgentRjpqDarkLabs.LAB_ONE, "Ben")));
        assertTrue(fixture.said.contains(AgentRjpqCompanionPlay.labNobody(AgentRjpqDarkLabs.LAB_TWO, "Ben")));
    }

    @Test
    void theRunnerGoesIntoItsOpenLab() {
        on(AgentRjpqDefinition.SPECIAL_LAB_MAP);
        fixture.session.chooseLabRunners(AgentRjpqDarkLabs.NO_RUNNER, MIRA);
        doors(0, 1);
        fixture.portal(AgentRjpqDarkLabs.LAB_TWO_ENTRY_PORTAL, 5, 808, 174);
        fixture.at(MIRA, 808, 174);
        when(fixture.partyQuest.enterPortal(fixture.character(MIRA), 5)).thenReturn(true);

        fixture.tick(MIRA, 2_000L);

        verify(fixture.partyQuest).enterPortal(fixture.character(MIRA), 5);
    }

    @Test
    void inTheLabTheRunnerBreaksTheNearestBoxNotTheRealOne() {
        on(AgentRjpqDefinition.DARK_LAB_TWO_MAP);
        fixture.session.chooseLabRunners(AgentRjpqDarkLabs.NO_RUNNER, MIRA);
        Reactor real = box(51, AgentRjpqDefinition.REAL_BOX_LAB_TWO_REACTOR_ID, 400, 0);
        Reactor decoy = box(52, AgentRjpqDefinition.DECOY_BOX_REACTOR_ID, 40, 0);
        fixture.reactors(real, decoy);
        fixture.at(MIRA, 0, 0);

        fixture.tick(MIRA, 2_000L);

        verify(fixture.actions).hitReactor(fixture.character(MIRA), 52);
        verify(fixture.actions, never()).hitReactor(fixture.character(MIRA), 51);
    }

    @Test
    void withTheFilesTheRunnerLeavesTheLab() {
        on(AgentRjpqDefinition.DARK_LAB_TWO_MAP);
        fixture.session.chooseLabRunners(AgentRjpqDarkLabs.NO_RUNNER, MIRA);
        fixture.holds(MIRA, ZENUMIST_FILES, 1, 3);
        fixture.portal(AgentRjpqDarkLabs.LAB_EXIT_PORTAL, 1, -1_092, 144);
        fixture.at(MIRA, -1_092, 144);
        when(fixture.partyQuest.enterPortal(fixture.character(MIRA), 1)).thenReturn(true);

        fixture.tick(MIRA, 2_000L);

        verify(fixture.partyQuest).enterPortal(fixture.character(MIRA), 1);
        verify(fixture.actions, never()).hitReactor(any(), anyInt());
    }

    @Test
    void aRunnerAboutToGoDownInTheGasGivesTheLabUp() {
        on(AgentRjpqDefinition.DARK_LAB_TWO_MAP);
        fixture.session.chooseLabRunners(AgentRjpqDarkLabs.NO_RUNNER, MIRA);
        fixture.reactors(box(52, AgentRjpqDefinition.DECOY_BOX_REACTOR_ID, 40, 0));
        when(fixture.character(MIRA).getHp()).thenReturn(200);
        when(fixture.character(MIRA).getCurrentMaxHp()).thenReturn(1_000);
        when(fixture.actions.hpPotionCount(fixture.character(MIRA))).thenReturn(0);
        fixture.portal(AgentRjpqDarkLabs.LAB_EXIT_PORTAL, 1, 0, 0);
        fixture.at(MIRA, 0, 0);

        fixture.tick(MIRA, 2_000L);

        assertTrue(fixture.session.labGivenUp(AgentRjpqDarkLabs.LAB_TWO));
        assertTrue(fixture.said.contains(AgentRjpqCompanionPlay.labGaveUp(AgentRjpqDarkLabs.LAB_TWO, "Ben")));
        verify(fixture.partyQuest).enterPortal(fixture.character(MIRA), 1);
        assertFalse(AgentRjpqDarkLabs.labWanted(fixture.session, fixture.character(MIRA), AgentRjpqDarkLabs.LAB_TWO));
    }

    @Test
    void theFilesAreDroppedAtTheLeadersFeetAndTheSecondMakesBoth() {
        on(AgentRjpqDefinition.SPECIAL_LAB_MAP);
        fixture.session.chooseLabRunners(TOBI, MIRA);
        doors(1, 1);
        fixture.holds(TOBI, ALCADNO_FILES, 1, 6);
        when(fixture.actions.itemCount(fixture.character(BEN), ZENUMIST_FILES)).thenReturn(1);
        when(fixture.actions.footing(any())).thenReturn(PrimitiveCapabilityGateway.Footing.GROUND);
        when(fixture.actions.canReach(any(), any(), any())).thenReturn(true);
        fixture.at(BEN, 100, 30);
        fixture.at(TOBI, 110, 30);

        fixture.tick(TOBI, 2_000L);

        verify(fixture.inventory).dropItem(fixture.character(TOBI), InventoryType.ETC, (short) 6, (short) 1);
        assertTrue(fixture.said.contains(AgentRjpqCompanionPlay.bothFiles(AgentRjpqSide.ZENUMIST, "Ben")),
                fixture.said.toString());
    }

    @Test
    void aFarLeaderIsWalkedToAndAnUnreachableOneGetsTheFilesByRomeo() {
        on(AgentRjpqDefinition.SPECIAL_LAB_MAP);
        fixture.session.chooseLabRunners(TOBI, MIRA);
        doors(1, 1);
        fixture.holds(TOBI, ALCADNO_FILES, 1, 6);
        when(fixture.actions.footing(any())).thenReturn(PrimitiveCapabilityGateway.Footing.GROUND);
        when(fixture.actions.canReach(any(), any(), any())).thenReturn(true);
        fixture.at(BEN, 600, 30);
        fixture.at(TOBI, 110, 30);

        fixture.tick(TOBI, 2_000L);
        verify(fixture.actions).navigate(fixture.entry(TOBI), new Point(600, 30), true);

        when(fixture.actions.canReach(any(), any(), any())).thenReturn(false);
        when(fixture.actions.npcPosition(fixture.character(TOBI), AgentRjpqSide.ZENUMIST.claimNpcId()))
                .thenReturn(new Point(120, 30));
        fixture.tick(TOBI, 3_000L);

        verify(fixture.inventory).dropItem(fixture.character(TOBI), InventoryType.ETC, (short) 6, (short) 1);
        assertTrue(fixture.said.contains(AgentRjpqCompanionPlay.filesByNpc(AgentRjpqSide.ZENUMIST, "Ben")));
    }

    @Test
    void onlyTheRunnerOrAStacksReCollectorMayTakeTheFiles() {
        on(AgentRjpqDefinition.DARK_LAB_TWO_MAP);
        fixture.session.chooseLabRunners(AgentRjpqDarkLabs.NO_RUNNER, MIRA);
        MapItem natural = files(ZENUMIST_FILES, false, 0, 0L);
        assertTrue(AgentRjpqDarkLabs.mayTakeFiles(fixture.session, fixture.character(MIRA), natural, 5_000L));
        assertFalse(AgentRjpqDarkLabs.mayTakeFiles(fixture.session, fixture.character(TOBI), natural, 5_000L));
        assertFalse(AgentRjpqDarkLabs.mayTakeFiles(fixture.session, fixture.character(MIRA),
                files(ALCADNO_FILES, false, 0, 0L), 5_000L), "the other lab's files never drop here");

        MapItem handedOver = files(ZENUMIST_FILES, true, MIRA, 1_000L);
        assertFalse(AgentRjpqDarkLabs.mayTakeFiles(fixture.session, fixture.character(MIRA), handedOver, 60_000L));
        long due = 1_000L + AgentRjpqDarkLabs.FILES_RECOLLECT_MS;
        assertTrue(AgentRjpqDarkLabs.mayTakeFiles(fixture.session, fixture.character(MIRA), handedOver, due));
        assertFalse(AgentRjpqDarkLabs.mayTakeFiles(fixture.session, fixture.character(TOBI), handedOver, due));

        assertFalse(AgentRjpqLootRule.allows(new AgentRjpqLootRule.Facts(ZENUMIST_FILES, false, 0, 5, false, false)));
        assertTrue(AgentRjpqLootRule.allows(new AgentRjpqLootRule.Facts(ZENUMIST_FILES, false, 0, 5, false, false,
                true)));
    }

    @Test
    void aRunnerThatWentDownGivesItsLabToTheLeader() {
        on(AgentRjpqDefinition.SPECIAL_LAB_MAP);
        fixture.session.chooseLabRunners(TOBI, MIRA);
        doors(1, 1);
        when(fixture.actions.alive(fixture.character(MIRA))).thenReturn(false);

        fixture.tick(UNA, 2_000L);

        assertTrue(fixture.session.labGivenUp(AgentRjpqDarkLabs.LAB_TWO));
        assertFalse(fixture.session.labGivenUp(AgentRjpqDarkLabs.LAB_ONE));
        assertTrue(fixture.said.contains(AgentRjpqCompanionPlay.labRunnerDown(AgentRjpqDarkLabs.LAB_TWO, "Ben")));
    }

    @Test
    void aBaseJumpWithMaxHasteMakesOneHundredTwenty() {
        on(AgentRjpqDefinition.SPECIAL_LAB_MAP);
        when(fixture.character(TOBI).getTotalJumpStat()).thenReturn(100);
        when(fixture.character(TOBI).getSkillLevel(Bandit.HASTE)).thenReturn(20);
        when(fixture.character(TOBI).getBuffedValue(BuffStat.JUMP)).thenReturn(null);
        assertEquals(120, AgentRjpqDarkLabs.jumpWithHaste(fixture.character(TOBI)));
        when(fixture.character(TOBI).getSkillLevel(Bandit.HASTE)).thenReturn(10);
        assertEquals(110, AgentRjpqDarkLabs.jumpWithHaste(fixture.character(TOBI)));
    }

    private Reactor box(int objectId, int reactorId, int x, int y) {
        Reactor box = fixture.reactor("box", 0, x, y);
        when(box.getObjectId()).thenReturn(objectId);
        when(box.getId()).thenReturn(reactorId);
        when(box.isAlive()).thenReturn(true);
        return box;
    }

    private static MapItem files(int itemId, boolean playerDrop, int ownerId, long droppedAtMs) {
        MapItem drop = mock(MapItem.class);
        when(drop.getItemId()).thenReturn(itemId);
        when(drop.isPlayerDrop()).thenReturn(playerDrop);
        when(drop.getOwnerId()).thenReturn(ownerId);
        when(drop.getDropTime()).thenReturn(droppedAtMs);
        when(drop.getObjectId()).thenReturn(90);
        when(drop.getPosition()).thenReturn(new Point(0, 0));
        return drop;
    }
}
