package server.agents.capabilities.partyquest.epq;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.maps.Reactor;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Step 3.1: every stock line site says its stock text in a stock run, byte for byte, in its stock channel; in a
 * companion run it says the companions' own words in map chat (the thorns' progress) or nothing (the status lines),
 * and never a stock string.
 */
class AgentEpqCoordinatorVoiceTest {
    private static final Point SPINE = new Point(-400, 0);
    private static final Point POND = new Point(400, 0);

    private final EpqRunFixture run = new EpqRunFixture();
    private final Character ben = run.human(1, "Ben", AgentEpqDefinition.STAGE_TWO_MAP);
    private final Character mira = run.companion(2, "Mira", AgentEpqDefinition.STAGE_TWO_MAP);
    private final Character tobi = run.companion(3, "Tobi", AgentEpqDefinition.STAGE_TWO_MAP);
    private final Character una = run.companion(4, "Una", AgentEpqDefinition.STAGE_TWO_MAP);

    AgentEpqCoordinatorVoiceTest() {
        when(run.actions.footing(any())).thenReturn(PrimitiveCapabilityGateway.Footing.GROUND);
        run.party(ben, mira, tobi, una);
        run.inventory();
    }

    @AfterEach
    void tearDown() {
        run.close();
    }

    private AgentEpqSession session(boolean companionRun, AgentEpqSession.Phase phase) {
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, companionRun, phase,
                ben, mira, tobi, una);
        session.setMemberCoordinationChatEnabled(true);
        return session;
    }

    private void tick(AgentEpqSession session, Character member, long nowMs) {
        session.member(member.getId()).deferUntil(0L);
        AgentEpqCoordinator.tickMember(session, run.entry(member), member, nowMs);
    }

    private Reactor reactor(int id, Point position, byte state) {
        Reactor reactor = mock(Reactor.class);
        when(reactor.getPosition()).thenReturn(position);
        when(reactor.getState()).thenReturn(state);
        when(reactor.getArea()).thenReturn(new Rectangle(position.x - 30, position.y - 30, 60, 60));
        when(run.map.getReactorById(id)).thenReturn(reactor);
        return reactor;
    }

    private void thorns(byte state) {
        reactor(AgentEpqDefinition.SPINE_REACTOR, SPINE, state);
        reactor(AgentEpqDefinition.POND_REACTOR, POND, (byte) 0);
        when(run.actions.portalPosition(any(), eq(3))).thenReturn(new Point(900, 0));
    }

    private static void holdsBottle(Character member) {
        Inventory etc = mock(Inventory.class);
        Item item = mock(Item.class);
        when(item.getQuantity()).thenReturn((short) 1);
        when(item.getPosition()).thenReturn((short) 1);
        when(etc.findById(AgentEpqDefinition.PURIFIED_POISON)).thenReturn(item);
        when(member.getInventory(InventoryType.ETC)).thenReturn(etc);
    }

    /** Runs every stock line site once for this run, the speaker its work Agent Mira. */
    private void everySite(AgentEpqSession session) {
        for (byte state = 1; state <= 4; state++) {
            thorns(state);
            tick(session, mira, 2_000L + state);
        }
        thorns((byte) 0);
        // A diluted poison dropped at the thorns: Mira stands by the spine with one.
        when(mira.getPosition()).thenReturn(new Point(SPINE));
        when(run.actions.itemCount(mira, AgentEpqDefinition.PURIFIED_POISON)).thenReturn(1);
        holdsBottle(mira);
        tick(session, mira, 3_000L);
        when(run.actions.itemCount(mira, AgentEpqDefinition.PURIFIED_POISON)).thenReturn(0);
        // Four bottles reachable: the secured line.
        when(run.actions.itemCount(tobi, AgentEpqDefinition.PURIFIED_POISON)).thenReturn(4);
        tick(session, mira, 4_000L);
        // Stage 4: five marbles on the floor, counted by the work Agent.
        for (Character member : List.of(ben, mira, tobi, una)) run.onMap(member, AgentEpqDefinition.STAGE_FOUR_MAP);
        when(run.actions.itemCount(tobi, AgentEpqDefinition.PURIFIED_POISON)).thenReturn(0);
        run.drop(AgentEpqDefinition.MONSTER_MARBLE, 5, new Point(700, 0), false, 0, 4_500L);
        tick(session, mira, 5_000L);
        // The golem's map, a golem alive.
        for (Character member : List.of(ben, mira, tobi, una)) run.onMap(member, AgentEpqDefinition.BOSS_MAP);
        when(run.actions.liveMonsterCount(any(), any())).thenReturn(1);
        tick(session, mira, 6_000L);
    }

    @Test
    void pinAStockRunSaysEveryStockLineAsBefore() {
        AgentEpqSession session = session(false, AgentEpqSession.Phase.STAGE_TWO);

        everySite(session);

        for (String line : List.of("Filled bottles applied: 1/4.", "Filled bottles applied: 2/4.",
                "Filled bottles applied: 3/4.", "Filled bottles applied: 4/4. The thorns are open.",
                "Filled bottle dropped at the thorns. Returning to the tree.",
                "Four filled bottles secured. Everyone head to the right-side exit.")) {
            verify(run.party).sendPartyChat(mira, line);
            verify(run.packets).broadcastChatText(mira, line, false, 0);
        }
        verify(run.party).sendPartyChat(mira, "Monster Marbles collected: 5/20.");
        verify(run.party).sendPartyChat(mira, "Poison Golem ahead. Everyone resume combat!");
    }

    @Test
    void aCompanionRunHearsItsCompanionsOwnWordsAndNoStatusLine() {
        AgentEpqSession session = session(true, AgentEpqSession.Phase.STAGE_TWO);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqDialogue.class)) {
            everySite(session);

            assertEquals(List.of(
                    "EPQ line: session=" + session.sessionId() + " key=stage2-progress-1 speaker=Mira text="
                            + "first poison's on the thorns. keep them coming.",
                    "EPQ line: session=" + session.sessionId() + " key=stage2-progress-2 speaker=Mira text="
                            + "halfway through the thorns.",
                    "EPQ line: session=" + session.sessionId() + " key=stage2-progress-3 speaker=Mira text="
                            + "one more and the thorns are gone.",
                    "EPQ line: session=" + session.sessionId() + " key=stage2-progress-4 speaker=Mira text="
                            + "the thorns are gone! the exit's on the right."), lines.startingWith("EPQ line:"));
        }
        verify(run.party, never()).sendPartyChat(any(), any());
        verify(run.packets, never()).broadcastChatText(any(), eq("Filled bottles applied: 1/4."), anyBoolean(),
                anyInt());
    }

    @Test
    void theVoiceTable() {
        assertEquals("first poison's on the thorns. keep them coming.", AgentEpqCompanionPlay.voice("stage2-progress-1"));
        assertEquals("the thorns are gone! the exit's on the right.", AgentEpqCompanionPlay.voice("stage2-progress-4"));
        for (String key : List.of("stage2-bottle-dropped", "stage2-bottles-secured", "stage2-collected",
                "stage4-progress-5", "stage4-handoff", "stage5-stone-found", "stage5-human-handoff", "boss-combat",
                "boss-human-fragment", "lobby-entry")) {
            assertEquals(null, AgentEpqCompanionPlay.voice(key), key);
        }
    }
}
