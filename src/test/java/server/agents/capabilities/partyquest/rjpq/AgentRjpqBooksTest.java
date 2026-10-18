package server.agents.capabilities.partyquest.rjpq;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.agents.integration.NpcSighting;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.MIRA;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.TOBI;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.UNA;

/**
 * Stage one, the books: companions split the hidden books between them, each walks to the nearest one nobody has
 * checked or claimed and reads it from beside it (by its own object, the book the script keys the result by), the one
 * whose book holds the pass says so and every companion then leaves through pt00 without waiting.
 */
class AgentRjpqBooksTest {
    private static final NpcSighting WEST_BOOK = new NpcSighting(102, 2_112_007, new Point(-300, 100));
    private static final NpcSighting EAST_BOOK = new NpcSighting(101, 2_112_007, new Point(500, 100));

    private RjpqStageFixture fixture = new RjpqStageFixture(AgentRjpqSide.ZENUMIST, AgentRjpqDefinition.BOOKS_MAP);

    @AfterEach
    void close() {
        fixture.close();
    }

    @Test
    void aCompanionWalksToTheNearestBookAndNeverReadsItFromAfar() {
        fixture.books(EAST_BOOK, WEST_BOOK);
        fixture.at(MIRA, 0, 100);

        fixture.tick(MIRA, 2_000L);

        verify(fixture.actions).navigate(fixture.entry(MIRA), new Point(-300, 100), true);
        verify(fixture.partyQuest, never()).runNpcObject(any(), anyInt(), anyInt());
    }

    @Test
    void besideTheBookItStopsReadsThatBookObjectAndThenMovesOnAtAPlayersPace() {
        fixture.books(EAST_BOOK, WEST_BOOK);
        fixture.at(MIRA, -290, 100);

        fixture.tick(MIRA, 2_000L);
        verify(fixture.actions).stop(fixture.entry(MIRA));
        verify(fixture.partyQuest).runNpcObject(fixture.character(MIRA), 2_112_007, 102);
        assertEquals(java.util.Set.of(102), fixture.session.checkedBooks());

        fixture.tick(MIRA, 2_500L);
        verify(fixture.actions, never()).navigate(any(), any(), anyBoolean());

        fixture.tick(MIRA, 2_900L);
        verify(fixture.actions).navigate(fixture.entry(MIRA), new Point(500, 100), true);
        verify(fixture.partyQuest, times(1)).runNpcObject(any(), anyInt(), anyInt());
    }

    @Test
    void twoCompanionsSplitTheBooksAndAClaimLapsesWhenItsHolderStopsWalking() {
        fixture.books(EAST_BOOK, WEST_BOOK);
        fixture.at(MIRA, 0, 100);
        fixture.at(TOBI, 10, 100);

        fixture.tick(MIRA, 2_000L);
        fixture.tick(TOBI, 2_000L);

        verify(fixture.actions).navigate(fixture.entry(MIRA), new Point(-300, 100), true);
        verify(fixture.actions).navigate(fixture.entry(TOBI), new Point(500, 100), true);

        fixture.tick(TOBI, 2_000L + 15_001L);
        verify(fixture.actions).navigate(fixture.entry(TOBI), new Point(-300, 100), true);
    }

    @Test
    void theCompanionWhoseBookHoldsThePassSaysSoAndEveryoneLeavesThroughPtZeroZero() {
        fixture.books(WEST_BOOK);
        fixture.at(MIRA, -290, 100);
        fixture.at(TOBI, 900, 300);
        fixture.exitPortal(1, 10, 306);
        when(fixture.partyQuest.runNpcObject(fixture.character(MIRA), 2_112_007, 102)).thenAnswer(call -> {
            fixture.properties.put("statusStg1", "1");
            return true;
        });

        fixture.tick(MIRA, 2_000L);
        verify(fixture.packets).broadcastChatText(fixture.character(MIRA),
                "found the pass! the way out is open.", false, 0);

        fixture.tick(TOBI, 2_000L);
        verify(fixture.actions).navigate(fixture.entry(TOBI), new Point(10, 306), true);
        verify(fixture.partyQuest, never()).enterPortal(any(), anyInt());

        fixture.at(TOBI, 20, 306);
        fixture.tick(TOBI, 2_100L);
        verify(fixture.partyQuest).enterPortal(fixture.character(TOBI), 1);
        verify(fixture.partyQuest, times(1)).runNpcObject(any(), anyInt(), anyInt());
    }

    @Test
    void aLetterIsCalledOutOnceForTheRunWithTheCaptivesName() {
        fixture.books(WEST_BOOK, EAST_BOOK);
        fixture.at(MIRA, -290, 100);
        fixture.at(TOBI, 490, 100);
        when(fixture.actions.itemCount(fixture.character(MIRA), 4_001_131)).thenReturn(0, 1);
        when(fixture.actions.itemCount(fixture.character(TOBI), 4_001_131)).thenReturn(0, 1);

        fixture.tick(MIRA, 2_000L);
        fixture.tick(TOBI, 2_000L);

        assertEquals(List.of("the pass is hidden in one of these books. let's split up and check them.",
                "found juliet's letter in this book. should i give it to romeo? yes or no",
                "if i do, yulete sends an angrier boss, but it pays more if juliet stays safe."), fixture.said);
        assertEquals(MIRA, fixture.session.letterHolderId(), "the first finder holds it");
        assertTrue(fixture.session.letterQuestionOpen(), "the leader was asked (2026-10-17)");
    }

    @Test
    void theAlcadnoSideReadsItsOwnBooksAndNamesRomeo() {
        fixture.close();
        fixture = new RjpqStageFixture(AgentRjpqSide.ALCADNO, AgentRjpqDefinition.BOOKS_MAP);
        NpcSighting book = new NpcSighting(201, 2_112_013, new Point(-300, 100));
        fixture.books(book);
        fixture.at(MIRA, -290, 100);
        when(fixture.actions.itemCount(fixture.character(MIRA), 4_001_130)).thenReturn(0, 1);

        fixture.tick(MIRA, 2_000L);

        verify(fixture.partyQuest).runNpcObject(fixture.character(MIRA), 2_112_013, 201);
        assertTrue(fixture.said.contains("found romeo's letter in this book. should i give it to juliet? yes or no"));
    }

    @Test
    void aDeadCompanionOrOneOutsideTheRunsEventDoesNotPlay() {
        fixture.books(WEST_BOOK);
        fixture.at(MIRA, -290, 100);
        fixture.at(UNA, -290, 100);
        when(fixture.actions.alive(fixture.character(MIRA))).thenReturn(false);
        EventInstanceManager elsewhere = mock(EventInstanceManager.class);
        when(fixture.character(UNA).getEventInstance()).thenReturn(elsewhere);

        fixture.tick(MIRA, 2_000L);
        fixture.tick(UNA, 2_000L);

        verify(fixture.partyQuest, never()).runNpcObject(any(), anyInt(), anyInt());
        verify(fixture.actions, never()).navigate(any(), any(), anyBoolean());
        assertTrue(fixture.said.isEmpty());
    }

    @Test
    void withPqGuidanceOffTheCompanionsPlayInSilence() {
        fixture.session.setMemberCoordinationChatEnabled(false);
        fixture.books(WEST_BOOK);
        fixture.at(MIRA, -290, 100);
        when(fixture.partyQuest.runNpcObject(fixture.character(MIRA), 2_112_007, 102)).thenAnswer(call -> {
            fixture.properties.put("statusStg1", "1");
            return true;
        });

        fixture.tick(MIRA, 2_000L);

        verify(fixture.partyQuest).runNpcObject(fixture.character(MIRA), 2_112_007, 102);
        verify(fixture.packets, never()).broadcastChatText(any(), any(), anyBoolean(), anyInt());
    }

    @Test
    void theOpeningLineIsSaidOnceForTheWholeParty() {
        fixture.books(WEST_BOOK, EAST_BOOK);
        for (int id : RjpqStageFixture.COMPANIONS) {
            fixture.at(id, 0, 100);
            fixture.tick(id, 2_000L);
        }

        assertEquals(1, fixture.said.stream()
                .filter("the pass is hidden in one of these books. let's split up and check them."::equals).count());
        verify(fixture.packets, times(1)).broadcastChatText(any(), any(), eq(false), eq(0));
    }
}
