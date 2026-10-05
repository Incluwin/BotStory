package server.agents.capabilities.partyquest.lpq;

import client.Character;
import client.inventory.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.integration.AgentMapGatewayRuntime;
import server.agents.integration.InventoryGateway;
import server.agents.integration.MapGateway;
import server.maps.MapItem;
import server.maps.MapleMap;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 'LPQ rescue:' (P6d LPQ step 1, plan 1.6): one WARN line per rescue the coordinator applies, in every mode, with the
 * run, the kind, the site, the phase and the member, and whether it is a companion run. It never throws, so logging a
 * rescue can never change how the run goes. The primitives note only what they did.
 */
class AgentLpqRescueTest {
    private static final int BEN = 743_000;
    private static final int MIRA = 743_001;

    static {
        // Initialized with the real gateways before any test mocks them (the coordinator keeps them in fields).
        AgentLpqCoordinator.stageFivePassRestoreCount(0, 0);
    }

    private final AgentLpqSession session = new AgentLpqSession(
            AgentLpqSession.Mode.PRODUCTION, 7L, BEN, 6, 1_000L);
    private final Character mira = character(MIRA, "Mira");

    @AfterEach
    void tearDown() {
        AgentLpqSessionRegistry.remove(session);
    }

    private void register(boolean companionRun) {
        session.addMember(BEN, AgentLpqMemberState.MemberType.HUMAN);
        session.addMember(MIRA, AgentLpqMemberState.MemberType.AGENT);
        session.setLeadership(BEN, MIRA);
        if (companionRun) session.setCompanionAdopted(true);
        session.transition(AgentLpqSession.Phase.STAGE_4, 2_000L);
        AgentLpqSessionRegistry.registerComplete(session);
    }

    @Test
    void aRescueIsOneWarnLineInTheContractShape() {
        register(false);
        try (LpqLogLines log = new LpqLogLines(AgentLpqRescue.class)) {
            AgentLpqRescue.note(session, AgentLpqRescue.Kind.TELEPORT, "stage4-attack-assist", mira, "to=10,20");
            AgentLpqRescue.note(session, AgentLpqRescue.Kind.KILL_WITH_DROPS, "stage4-hard-recovery", null, "");

            assertEquals(List.of(
                    "LPQ rescue: session=" + session.sessionId() + " kind=TELEPORT site=stage4-attack-assist"
                            + " phase=STAGE_4 member=Mira(743001) applied=true companionRun=false detail=to=10,20",
                    "LPQ rescue: session=" + session.sessionId() + " kind=KILL_WITH_DROPS site=stage4-hard-recovery"
                            + " phase=STAGE_4 member=-(-) applied=true companionRun=false detail=-"),
                    log.startingWith("LPQ rescue: "));
        }
    }

    @Test
    void aCompanionRunsRescueSaysSo() {
        register(true);
        try (LpqLogLines log = new LpqLogLines(AgentLpqRescue.class)) {
            AgentLpqRescue.note(session, AgentLpqRescue.Kind.EVENT_REREGISTER, "dead-agent-reentry", mira,
                    "destination=922010500");

            assertEquals(List.of("LPQ rescue: session=" + session.sessionId() + " kind=EVENT_REREGISTER"
                            + " site=dead-agent-reentry phase=STAGE_4 member=Mira(743001) applied=true"
                            + " companionRun=true detail=destination=922010500"),
                    log.startingWith("LPQ rescue: "));
        }
    }

    @Test
    void aSiteWithoutASessionFindsItByTheMember() {
        register(true);
        Character stranger = character(743_009, "Nobody");
        try (LpqLogLines log = new LpqLogLines(AgentLpqRescue.class)) {
            AgentLpqRescue.note(null, AgentLpqRescue.Kind.TELEPORT, "reactor-approach", mira, "to=1,2");
            AgentLpqRescue.note(null, AgentLpqRescue.Kind.TELEPORT, "stage5-unobserved-box", stranger, "to=3,4");

            assertEquals(List.of(
                    "LPQ rescue: session=" + session.sessionId() + " kind=TELEPORT site=reactor-approach"
                            + " phase=STAGE_4 member=Mira(743001) applied=true companionRun=true detail=to=1,2",
                    "LPQ rescue: session=- kind=TELEPORT site=stage5-unobserved-box phase=- member=Nobody(743009)"
                            + " applied=true companionRun=false detail=to=3,4"),
                    log.startingWith("LPQ rescue: "));
        }
    }

    @Test
    void aNoteNeverThrows() {
        register(false);
        Character nameless = mock(Character.class);
        when(nameless.getId()).thenReturn(MIRA);
        Character broken = mock(Character.class);
        when(broken.getId()).thenThrow(new IllegalStateException("character unloaded"));
        try (LpqLogLines log = new LpqLogLines(AgentLpqRescue.class)) {
            AgentLpqRescue.note(session, AgentLpqRescue.Kind.VACUUM, "loose-passes", nameless, null);
            AgentLpqRescue.note(null, AgentLpqRescue.Kind.VACUUM, "loose-passes", broken, "item=4001022");
            AgentLpqRescue.note(null, null, null, null, null);

            assertEquals(List.of("LPQ rescue: session=" + session.sessionId() + " kind=VACUUM site=loose-passes"
                            + " phase=STAGE_4 member=null(743001) applied=true companionRun=false detail=-",
                            "LPQ rescue: session=- kind=null site=null phase=- member=-(-) applied=true"
                                    + " companionRun=false detail=-"),
                    log.startingWith("LPQ rescue: "));
            assertEquals(1, log.startingWith("LPQ rescue note failed").size());
        }
    }

    @Test
    void aVacuumNotesOnceWithItsTotalAndOnlyWhenItTookSomething() {
        register(true);
        InventoryGateway inventory = mock(InventoryGateway.class);
        when(inventory.addItem(any(), anyInt(), anyShort())).thenReturn(true);
        MapGateway maps = mock(MapGateway.class);
        MapleMap room = mock(MapleMap.class);
        MapItem three = drop(AgentLpqDefinition.PASS, 3);
        MapItem two = drop(AgentLpqDefinition.PASS, 2);
        MapItem potion = drop(AgentLpqDefinition.RED_POTION, 1);
        when(room.getDroppedItems()).thenReturn(List.of(three, potion, two));
        MapleMap empty = mock(MapleMap.class);
        when(empty.getDroppedItems()).thenReturn(List.of(potion));
        try (MockedStatic<AgentInventoryGatewayRuntime> inventories = mockStatic(AgentInventoryGatewayRuntime.class);
             MockedStatic<AgentMapGatewayRuntime> mapGateways = mockStatic(AgentMapGatewayRuntime.class);
             LpqLogLines log = new LpqLogLines(AgentLpqRescue.class)) {
            inventories.when(AgentInventoryGatewayRuntime::inventory).thenReturn(inventory);
            mapGateways.when(AgentMapGatewayRuntime::map).thenReturn(maps);

            int vacuumed = AgentLpqCoordinator.vacuumItemDrops(
                    session, "stage4-pass-loot", mira, Set.of(room), AgentLpqDefinition.PASS);
            int none = AgentLpqCoordinator.vacuumItemDrops(
                    session, "loose-passes", mira, Set.of(empty), AgentLpqDefinition.PASS);

            assertEquals(5, vacuumed);
            assertEquals(0, none);
            verify(inventory).addItem(mira, AgentLpqDefinition.PASS, (short) 3);
            verify(inventory).addItem(mira, AgentLpqDefinition.PASS, (short) 2);
            verify(maps).removeItemDrop(room, three, 2, MIRA);
            verify(maps).removeItemDrop(room, two, 2, MIRA);
            verify(maps, never()).removeItemDrop(any(), eq(potion), anyInt(), anyInt());
            assertEquals(List.of("LPQ rescue: session=" + session.sessionId() + " kind=VACUUM site=stage4-pass-loot"
                            + " phase=STAGE_4 member=Mira(743001) applied=true companionRun=true"
                            + " detail=item=4001022 quantity=5"),
                    log.startingWith("LPQ rescue: "));
        }
    }

    @Test
    void aGrantNotesOnlyWhatTheInventoryTook() {
        register(false);
        InventoryGateway inventory = mock(InventoryGateway.class);
        when(inventory.addItem(mira, AgentLpqDefinition.PASS, (short) 2)).thenReturn(true);
        when(inventory.addItem(mira, AgentLpqDefinition.BOSS_KEY, (short) 1)).thenReturn(false);
        try (MockedStatic<AgentInventoryGatewayRuntime> inventories = mockStatic(AgentInventoryGatewayRuntime.class);
             LpqLogLines log = new LpqLogLines(AgentLpqRescue.class)) {
            inventories.when(AgentInventoryGatewayRuntime::inventory).thenReturn(inventory);

            assertTrue(AgentLpqCoordinator.grantItem(session, AgentLpqRescue.Kind.PASS_AWARD, "missing-pass-award",
                    mira, AgentLpqDefinition.PASS, (short) 2));
            assertFalse(AgentLpqCoordinator.grantItem(session, AgentLpqRescue.Kind.KEY_AWARD, "boss-key-award",
                    mira, AgentLpqDefinition.BOSS_KEY, (short) 1));

            assertEquals(List.of("LPQ rescue: session=" + session.sessionId() + " kind=PASS_AWARD"
                            + " site=missing-pass-award phase=STAGE_4 member=Mira(743001) applied=true"
                            + " companionRun=false detail=item=4001022 quantity=2"),
                    log.startingWith("LPQ rescue: "));
        }
    }

    private static MapItem drop(int itemId, int quantity) {
        MapItem drop = mock(MapItem.class);
        Item item = mock(Item.class);
        when(item.getQuantity()).thenReturn((short) quantity);
        when(drop.getItem()).thenReturn(item);
        when(drop.getItemId()).thenReturn(itemId);
        return drop;
    }

    private static Character character(int id, String name) {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(id);
        when(character.getName()).thenReturn(name);
        return character;
    }
}
