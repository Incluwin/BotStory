package server.agents.capabilities.partyquest.rjpq;

import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.looting.AgentLootEligibility;
import server.maps.MapItem;

import java.awt.Point;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.MIRA;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.TOBI;

/**
 * Stage three, the beakers: a beaker counts only after seven liquids in a row, each before the last one wears off, so
 * the whole party feeds one beaker at a time (the one closest to full, the leftmost on a tie). A companion carrying a
 * liquid walks onto that beaker and drops exactly one where it lands in the beaker; one with none picks up a loose
 * liquid it may take (never one lying in a beaker, never another player's) or fights Homunculi for one.
 */
class AgentRjpqBeakersTest {
    private static final int LIQUID = AgentRjpqDefinition.LIQUID_ITEM_ID;

    private final RjpqStageFixture fixture =
            new RjpqStageFixture(AgentRjpqSide.ZENUMIST, AgentRjpqDefinition.BEAKER_MAP);

    @AfterEach
    void close() {
        fixture.close();
    }

    private void standardBeakers(int left, int middle, int right) {
        fixture.reactors(fixture.beaker("beaker1", left, -440, 23), fixture.beaker("beaker2", middle, 36, 25),
                fixture.beaker("beaker3", right, 544, 21));
    }

    @Test
    void theTargetIsTheUncountedBeakerClosestToFullAndTheLeftmostOnATie() {
        List<AgentRjpqCompanionPlay.Beaker> beakers = List.of(
                new AgentRjpqCompanionPlay.Beaker(0, 2, null), new AgentRjpqCompanionPlay.Beaker(1, 5, null),
                new AgentRjpqCompanionPlay.Beaker(2, 5, null));
        assertEquals(1, AgentRjpqCompanionPlay.targetBeaker(beakers));
        assertEquals(0, AgentRjpqCompanionPlay.targetBeaker(List.of(
                new AgentRjpqCompanionPlay.Beaker(0, 3, null), new AgentRjpqCompanionPlay.Beaker(1, 7, null),
                new AgentRjpqCompanionPlay.Beaker(2, 8, null))));
        assertEquals(-1, AgentRjpqCompanionPlay.targetBeaker(List.of(
                new AgentRjpqCompanionPlay.Beaker(0, 7, null), new AgentRjpqCompanionPlay.Beaker(1, 8, null))));
    }

    @Test
    void aCompanionWithALiquidWalksOntoTheTargetAndDropsExactlyOneWhereItLandsInTheBeaker() {
        standardBeakers(0, 4, 2);
        fixture.holds(MIRA, LIQUID, 2, 7);
        fixture.at(MIRA, -400, 23);

        fixture.tick(MIRA, 2_000L);
        verify(fixture.actions).navigate(fixture.entry(MIRA), new Point(36, 25), true);
        verify(fixture.inventory, never()).dropItem(any(), any(), anyShort(), anyShort());

        fixture.at(MIRA, 40, 25);
        when(fixture.actions.predictDropLanding(fixture.character(MIRA), new Point(40, 25)))
                .thenReturn(new Point(40, 25));
        fixture.tick(MIRA, 2_100L);
        verify(fixture.inventory).dropItem(fixture.character(MIRA), InventoryType.ETC, (short) 7, (short) 1);
    }

    @Test
    void itDoesNotDropWhereTheLiquidWouldFallOutsideTheBeaker() {
        standardBeakers(0, 4, 2);
        fixture.holds(MIRA, LIQUID, 1, 7);
        fixture.at(MIRA, 40, 25);
        when(fixture.actions.predictDropLanding(any(), any())).thenReturn(new Point(40, 80));

        fixture.tick(MIRA, 2_000L);

        verify(fixture.inventory, never()).dropItem(any(), any(), anyShort(), anyShort());
        verify(fixture.actions).navigate(fixture.entry(MIRA), new Point(36, 25), true);
    }

    @Test
    void withoutALiquidItTakesALooseOneButNeverOneLyingInABeaker() {
        standardBeakers(0, 4, 2);
        fixture.holds(MIRA, LIQUID, 0, 7);
        fixture.at(MIRA, 0, 25);
        fixture.drop(501, LIQUID, 30, 25);
        fixture.drop(502, LIQUID, 200, 25);

        fixture.tick(MIRA, 2_000L);
        verify(fixture.actions).navigate(fixture.entry(MIRA), new Point(200, 25), true);

        fixture.at(MIRA, 190, 25);
        fixture.tick(MIRA, 2_100L);
        verify(fixture.actions).lootItem(fixture.character(MIRA), 502, 60);
        verify(fixture.actions, never()).lootItem(any(), eq(501), anyInt());
    }

    @Test
    void anotherPlayersLiquidIsLeftAloneAndTheCompanionFightsForItsOwn() {
        standardBeakers(0, 4, 2);
        fixture.holds(MIRA, LIQUID, 0, 7);
        MapItem theirs = fixture.drop(503, LIQUID, 200, 25);
        fixture.loot.when(() -> AgentLootEligibility.canBotTargetLoot(any(), any(), any(), eq(theirs), anyLong()))
                .thenReturn(false);

        fixture.tick(MIRA, 2_000L);

        verify(fixture.actions, never()).navigate(any(), any(), anyBoolean());
        verify(fixture.actions).grind(fixture.entry(MIRA), Set.of(AgentRjpqDefinition.HOMUNCULUS_MOB_ID));
    }

    @Test
    void theFirstTargetIsAnnouncedAndEachChangeOnceAfterIt() {
        standardBeakers(0, 0, 0);
        fixture.holds(MIRA, LIQUID, 0, 7);
        fixture.holds(TOBI, LIQUID, 0, 7);

        fixture.tick(MIRA, 2_000L);
        fixture.tick(TOBI, 2_000L);
        standardBeakers(8, 2, 0);
        fixture.tick(MIRA, 3_000L);
        fixture.tick(TOBI, 3_000L);

        assertEquals(List.of(
                "one liquid each, and they all go in the same beaker. starting with the left one.",
                "middle beaker next."), fixture.said);
    }

    @Test
    void whenAllThreeCountTheyLeaveThroughTheStageExit() {
        standardBeakers(8, 8, 8);
        fixture.properties.put("statusStg3", "3");
        fixture.exitPortal(4, 1_048, 159);
        fixture.at(MIRA, 1_040, 159);

        fixture.tick(MIRA, 2_000L);

        verify(fixture.partyQuest).enterPortal(fixture.character(MIRA), 4);
    }

    @Test
    void theLinesAreLowerCasePlayerChatWithNoDigitsOrMarkup() {
        List<String> lines = List.of(AgentRjpqCompanionPlay.booksStart(), AgentRjpqCompanionPlay.passFound(),
                AgentRjpqCompanionPlay.letterFound(AgentRjpqSide.ZENUMIST),
                AgentRjpqCompanionPlay.letterFound(AgentRjpqSide.ALCADNO), AgentRjpqCompanionPlay.tunnelStart(),
                AgentRjpqCompanionPlay.beakersStart(0), AgentRjpqCompanionPlay.beakersStart(2),
                AgentRjpqCompanionPlay.beakerNext(1));
        for (String line : lines) {
            assertEquals(line.toLowerCase(java.util.Locale.ROOT), line, line);
            assertEquals(-1, indexOfAny(line, "0123456789#[<{"), line);
            assertEquals(true, line.length() <= 100, line);
        }
    }

    private static int indexOfAny(String line, String characters) {
        for (int i = 0; i < line.length(); i++) {
            if (characters.indexOf(line.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }
}
