package server.agents.capabilities.partyquest.rjpq;

import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.BEN;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.MIRA;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.TOBI;

/**
 * Stage four, the Special Lab: Neo Huroids drop card keys and each closed Dark Lab door takes exactly one. Carriers
 * split the closed doors, companions without a key pick loose ones up while a door is closed, and once the files are
 * in they go on to the office, but wait at the exit while the leader may still want Yulete alone.
 */
class AgentRjpqKeysTest {
    private static final int KEY = AgentRjpqDefinition.CARD_KEY_ITEM_ID;

    private RjpqStageFixture fixture =
            new RjpqStageFixture(AgentRjpqSide.ZENUMIST, AgentRjpqDefinition.SPECIAL_LAB_MAP);

    @AfterEach
    void close() {
        fixture.close();
    }

    private void doors(String prefix, int labOne, int labTwo, int office) {
        fixture.reactors(fixture.reactor(prefix + "3_out1", labOne, -1_310, 176),
                fixture.reactor(prefix + "3_out2", labTwo, 812, 175),
                fixture.reactor(prefix + "3_out3", office, -292, 34));
    }

    @Test
    void aCarrierDropsExactlyOneKeyWhereItLandsInTheNearestClosedDoorAndAnotherCarrierTakesTheOtherDoor() {
        doors("rnj", 0, 0, 0);
        fixture.holds(MIRA, KEY, 3, 9);
        fixture.holds(TOBI, KEY, 1, 4);
        fixture.at(MIRA, -1_000, 176);
        fixture.at(TOBI, -1_100, 176);

        fixture.tick(MIRA, 2_000L);
        fixture.tick(TOBI, 2_000L);
        verify(fixture.actions).navigate(fixture.entry(MIRA), new Point(-1_310, 176), true);
        verify(fixture.actions).navigate(fixture.entry(TOBI), new Point(812, 175), true);

        fixture.at(MIRA, -1_300, 176);
        fixture.tick(MIRA, 2_100L);
        verify(fixture.inventory).dropItem(fixture.character(MIRA), InventoryType.ETC, (short) 9, (short) 1);
        assertEquals(List.of("the huroids drop card keys. one key opens each door."), fixture.said);
    }

    @Test
    void aKeyThatWouldLandOutsideTheDoorIsNotDropped() {
        doors("rnj", 0, 1, 0);
        fixture.holds(MIRA, KEY, 1, 9);
        fixture.at(MIRA, -1_300, 176);
        when(fixture.actions.predictDropLanding(any(), any())).thenReturn(new Point(-1_300, 260));

        fixture.tick(MIRA, 2_000L);

        verify(fixture.inventory, never()).dropItem(any(), any(), anyShort(), anyShort());
    }

    @Test
    void withoutAKeyItPicksALooseOneUpButNeverOneLyingInAClosedDoor() {
        doors("rnj", 0, 0, 0);
        fixture.holds(MIRA, KEY, 0, 9);
        fixture.at(MIRA, 0, 176);
        fixture.drop(601, KEY, 800, 170);
        fixture.drop(602, KEY, 300, 176);

        fixture.tick(MIRA, 2_000L);

        verify(fixture.actions).navigate(fixture.entry(MIRA), new Point(300, 176), true);
        fixture.floor.removeIf(drop -> drop.getObjectId() == 602);
        fixture.tick(MIRA, 2_100L);
        verify(fixture.actions).grind(fixture.entry(MIRA), Set.of(AgentRjpqDefinition.NEO_HUROID_MOB_ID));
    }

    @Test
    void withBothDoorsOpenNobodyCarriesOrCollectsKeysAndTheyKeepTheRoomClear() {
        doors("rnj", 1, 1, 0);
        fixture.holds(MIRA, KEY, 1, 9);
        fixture.holds(TOBI, KEY, 0, 9);
        fixture.drop(603, KEY, 10, 0);

        fixture.tick(MIRA, 2_000L);
        fixture.tick(TOBI, 2_000L);

        verify(fixture.inventory, never()).dropItem(any(), any(), anyShort(), anyShort());
        verify(fixture.actions, never()).navigate(any(), any(), anyBoolean());
        verify(fixture.actions).grind(fixture.entry(MIRA), Set.of(AgentRjpqDefinition.NEO_HUROID_MOB_ID));
        verify(fixture.actions).grind(fixture.entry(TOBI), Set.of(AgentRjpqDefinition.NEO_HUROID_MOB_ID));
    }

    @Test
    void onceTheFilesAreInTheyGoOnToTheOffice() {
        doors("rnj", 1, 1, 1);
        fixture.exitPortal(3, -292, 26);
        fixture.at(MIRA, -290, 26);

        fixture.tick(MIRA, 2_000L);

        verify(fixture.partyQuest).enterPortal(fixture.character(MIRA), 3);
    }

    @Test
    void whileTheLeaderMayWantYuleteAloneTheyWaitAtTheExitUntilHeIsIn() {
        doors("rnj", 1, 1, 1);
        fixture.exitPortal(3, -292, 26);
        fixture.properties.put("npcShocked", "1");
        fixture.properties.put("yuleteTalked", "0");
        fixture.at(MIRA, 500, 176);

        fixture.tick(MIRA, 2_000L);
        verify(fixture.actions).navigate(fixture.entry(MIRA), new Point(-292, 26), true);

        fixture.at(MIRA, -290, 26);
        fixture.tick(MIRA, 2_100L);
        verify(fixture.partyQuest, never()).enterPortal(any(), anyInt());

        when(fixture.character(BEN).getMapId()).thenReturn(AgentRjpqDefinition.OFFICE_MAP);
        fixture.tick(MIRA, 2_200L);
        verify(fixture.partyQuest).enterPortal(fixture.character(MIRA), 3);
    }

    @Test
    void theAlcadnoSideUsesItsOwnDoors() {
        fixture.close();
        fixture = new RjpqStageFixture(AgentRjpqSide.ALCADNO, AgentRjpqDefinition.SPECIAL_LAB_MAP);
        doors("jnr", 1, 0, 0);
        fixture.holds(MIRA, KEY, 1, 9);
        fixture.at(MIRA, -1_000, 176);

        fixture.tick(MIRA, 2_000L);

        verify(fixture.actions).navigate(eq(fixture.entry(MIRA)), eq(new Point(812, 175)), eq(true));
    }
}
