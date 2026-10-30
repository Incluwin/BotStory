package server.agents.capabilities.partyquest.opq;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.integration.AgentPacketGatewayRuntime;
import server.agents.integration.InventoryGateway;
import server.agents.integration.PacketGateway;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.runtime.state.AgentCapabilityStateRegistry;
import server.life.Monster;
import server.maps.MapItem;
import server.maps.MapObject;
import server.maps.Reactor;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-10-18, the garden, the Root and Minerva in a companion run its human leader leads (OPQ plan Step 2d,
 * AgentOpqConductedGarden): the stock flow made the event leader plant the seeds and carry the Root, so with Ben
 * leading nobody planted, and the run waited on Ben holding the Root. Now Mira (the lowest-id companion standing in the
 * garden) plants, brings him the Root at his feet ("only you can take it through the exit": party3_gardenin.js lets
 * only the event leader holding it out), and in the centre a companion takes the Root he drops up to Minerva's statue
 * (decision O5). The claims begin with the clear: they wait in the centre while he is there, follow him into the hidden
 * room and fight beside him, and claim once he goes on, claims, or leaves.
 */
class AgentOpqHumanLedGardenTest {
    private static final int ROOT = AgentOpqDefinition.ROOT_OF_LIFE;
    private static final int SEED = AgentOpqDefinition.STRANGE_SEED;

    private final OpqRunFixture run = new OpqRunFixture();
    private final Character ben = run.human(1, "Ben");
    private final Character mira = run.companion(2, "Mira");
    private final Character kes = run.companion(3, "Kes");
    private final Character pel = run.companion(4, "Pel");
    private final Character hal = run.companion(5, "Hal");
    private final Character gus = run.companion(6, "Gus");
    private final PacketGateway packets = mock(PacketGateway.class);
    private final InventoryGateway inventory = mock(InventoryGateway.class);
    private final List<MockedStatic<?>> statics = new ArrayList<>();
    private final List<MapObject> lying = new ArrayList<>();
    private final Map<Integer, Inventory> etcs = new HashMap<>();
    private AgentOpqSession session;

    @BeforeEach
    void setUp() {
        MockedStatic<AgentPacketGatewayRuntime> packetRuntime = mockStatic(AgentPacketGatewayRuntime.class);
        packetRuntime.when(AgentPacketGatewayRuntime::packets).thenReturn(packets);
        statics.add(packetRuntime);
        MockedStatic<AgentInventoryGatewayRuntime> inventoryRuntime = mockStatic(AgentInventoryGatewayRuntime.class);
        inventoryRuntime.when(AgentInventoryGatewayRuntime::inventory).thenReturn(inventory);
        statics.add(inventoryRuntime);
        when(run.actions.footing(any())).thenReturn(PrimitiveCapabilityGateway.Footing.GROUND);
        when(run.actions.canReach(any(), any(), any())).thenReturn(true);
        when(run.map.getItems()).thenReturn(lying);
        everyoneIn(AgentOpqDefinition.GARDEN_MAP);
        // A drop the run does not own outright asks the companion's inventory reservations.
        run.entries.values().forEach(entry -> when(entry.capabilityStates())
                .thenReturn(new AgentCapabilityStateRegistry()));
    }

    @AfterEach
    void tearDown() {
        for (int i = statics.size() - 1; i >= 0; i--) statics.get(i).close();
        run.close();
    }

    private AgentOpqSession benLeads(AgentOpqSession.Phase phase) {
        session = run.session(AgentOpqSession.Mode.HUMAN_ASSISTED, true, phase, ben, mira, kes, pel, hal, gus);
        session.setMemberCoordinationChatEnabled(true);
        return session;
    }

    private void stand(Character member, int mapId, int x, int y) {
        when(member.getMapId()).thenReturn(mapId);
        when(member.getPosition()).thenReturn(new Point(x, y));
    }

    private void everyoneIn(int mapId) {
        for (Character member : List.of(ben, mira, kes, pel, hal, gus)) stand(member, mapId, 0, 0);
    }

    private void holds(Character member, int itemId, int quantity) {
        when(member.getItemQuantity(itemId, false)).thenReturn(quantity);
        Inventory etc = etcs.computeIfAbsent(member.getId(), id -> {
            Inventory created = mock(Inventory.class);
            when(member.getInventory(InventoryType.ETC)).thenReturn(created);
            return created;
        });
        Item item = mock(Item.class);
        when(item.getQuantity()).thenReturn((short) quantity);
        when(item.getPosition()).thenReturn((short) 1);
        when(etc.findById(itemId)).thenReturn(quantity > 0 ? item : null);
    }

    private MapItem drop(int objectId, int itemId, int ownerId, boolean playerDrop, Point at, long droppedAt) {
        MapItem drop = mock(MapItem.class);
        Item item = mock(Item.class);
        when(item.getQuantity()).thenReturn((short) 1);
        when(drop.getObjectId()).thenReturn(objectId);
        when(drop.getItemId()).thenReturn(itemId);
        when(drop.getItem()).thenReturn(item);
        when(drop.isPlayerDrop()).thenReturn(playerDrop);
        when(drop.getOwnerId()).thenReturn(ownerId);
        when(drop.getPosition()).thenReturn(at);
        when(drop.getDropTime()).thenReturn(droppedAt);
        lying.add(drop);
        return drop;
    }

    private void minervaFreed() {
        when(run.event.isEventCleared()).thenReturn(true);
        when(run.event.getProperty("statusStg8")).thenReturn("1");
        when(run.partyQuest.property(any(), eq("statusStg8"))).thenReturn("1");
    }

    /** One member pass of Ben's run for this companion. */
    private void tick(Character companion, long nowMs) {
        AgentOpqCoordinator.tickMember(session, run.entries.get(companion.getId()), companion, nowMs);
    }

    // ---- the garden --------------------------------------------------------------------------------------------

    @Test
    void theLowestIdCompanionStandingInTheGardenSeedsNotBen() {
        AgentOpqSession session = benLeads(AgentOpqSession.Phase.GARDEN);

        AgentOpqCoordinator.tickSession(session, 10_000L);

        assertEquals(AgentOpqMemberState.Role.GARDEN_SEEDER, session.member(2).role());
        for (int other : List.of(3, 4, 5, 6)) {
            assertEquals(AgentOpqMemberState.Role.BOSS_ATTACKER, session.member(other).role());
        }
        verify(packets).broadcastChatText(mira, "i'll do the seeds. everyone else on the plants.", false, 0);

        when(mira.getHp()).thenReturn(0); // down: Kes takes the seeds over
        AgentOpqCoordinator.tickSession(session, 11_000L);

        assertEquals(AgentOpqMemberState.Role.GARDEN_SEEDER, session.member(3).role());
        assertEquals(AgentOpqMemberState.Role.BOSS_ATTACKER, session.member(2).role());
        verify(packets, times(1)).broadcastChatText(any(), eq("i'll do the seeds. everyone else on the plants."),
                eq(false), eq(0));
    }

    @Test
    void aStockRunStillSeedsWithItsAgentLeader() {
        Character[] agents = {run.companion(11, "Lea"), run.companion(12, "Ari"), run.companion(13, "Bo"),
                run.companion(14, "Cy"), run.companion(15, "Di"), run.companion(16, "Ed")};
        for (Character agent : agents) stand(agent, AgentOpqDefinition.GARDEN_MAP, 0, 0);
        // Bo (13) leads: the stock seeder is the event leader, not the lowest id.
        AgentOpqSession stock = run.session(AgentOpqSession.Mode.AUTONOMOUS, AgentOpqSession.Phase.GARDEN,
                agents[2], agents[0], agents[1], agents[3], agents[4], agents[5]);

        AgentOpqCoordinator.tickSession(stock, 10_000L);

        assertEquals(AgentOpqMemberState.Role.GARDEN_SEEDER, stock.member(13).role());
        assertEquals(AgentOpqMemberState.Role.BOSS_ATTACKER, stock.member(11).role());
    }

    @Test
    void papaPixiesArrivalIsCalledOnceAndTheStockFightRolesApply() {
        AgentOpqSession session = benLeads(AgentOpqSession.Phase.GARDEN);
        Monster papa = mock(Monster.class);
        when(papa.getId()).thenReturn(AgentOpqDefinition.PAPA_PIXIE);
        when(papa.getHp()).thenReturn(672_000L);
        when(run.map.getAllMonsters()).thenReturn(List.of(papa));

        AgentOpqCoordinator.tickSession(session, 10_000L);
        AgentOpqCoordinator.tickSession(session, 11_000L);

        verify(packets, times(1)).broadcastChatText(mira, "papa pixie's out!", false, 0);
        assertEquals(AgentOpqMemberState.Role.BOSS_ATTACKER, session.member(2).role());
        assertEquals(AgentOpqMemberState.Role.BOSS_SUMMON_CLEARER, session.member(6).role());
    }

    @Test
    void theSeederPlantsItsStrangeSeedAndThenLootsTheNextOne() {
        AgentOpqSession session = benLeads(AgentOpqSession.Phase.GARDEN);
        AgentOpqCoordinator.tickSession(session, 10_000L);
        MapItem first = drop(31, SEED, 0, false, new Point(0, 0), 10_000L);
        holds(mira, SEED, 1); // what the pickup leaves in her bag

        tick(mira, 10_500L);
        verify(mira).pickupItem(first);

        lying.remove(first);
        Reactor pot = mock(Reactor.class);
        when(pot.getId()).thenReturn(2_001_000);
        when(pot.isAlive()).thenReturn(true);
        when(pot.isActive()).thenReturn(true);
        when(pot.getPosition()).thenReturn(new Point(10, 0));
        when(run.actions.reactors(mira)).thenReturn(List.of(pot));
        tick(mira, 11_000L);
        verify(inventory).dropItem(mira, InventoryType.ETC, (short) 1, (short) 1);

        holds(mira, SEED, 0); // planted
        MapItem second = drop(32, SEED, 0, false, new Point(0, 0), 11_500L);
        tick(mira, 12_000L);
        verify(mira).pickupItem(second);
    }

    @Test
    void aNewSeederTakesOverWhatTheLastOneNoLongerHolds() {
        AgentOpqSession session = benLeads(AgentOpqSession.Phase.GARDEN);
        session.loot().reserve(SEED, mira.getId(), AgentOpqDefinition.GARDEN_MAP, 9_000L);
        session.loot().reserve(AgentOpqDefinition.TRANSPARENT_TRIGGER, mira.getId(), AgentOpqDefinition.GARDEN_MAP,
                9_000L);
        session.loot().pickedUp(AgentOpqDefinition.TRANSPARENT_TRIGGER, mira.getId(), 9_500L);
        holds(mira, AgentOpqDefinition.TRANSPARENT_TRIGGER, 1);

        AgentOpqConductedGarden.reserveSeeds(session, kes, 10_000L);

        assertTrue(session.loot().canLoot(SEED, kes.getId(), AgentOpqDefinition.GARDEN_MAP));
        assertTrue(session.loot().canLoot(ROOT, kes.getId(), AgentOpqDefinition.GARDEN_MAP));
        assertEquals(mira.getId(), session.loot().reservation(AgentOpqDefinition.TRANSPARENT_TRIGGER).ownerId(),
                "the trigger in Mira's bag stays hers");
    }

    // ---- the Root ----------------------------------------------------------------------------------------------

    @Test
    void theRootGoesToBenAtHisFeetWithTheExitLine() {
        AgentOpqSession session = benLeads(AgentOpqSession.Phase.GARDEN);
        AgentOpqCoordinator.tickSession(session, 10_000L);
        stand(mira, AgentOpqDefinition.GARDEN_MAP, 400, 516);
        stand(ben, AgentOpqDefinition.GARDEN_MAP, 430, 516);
        holds(mira, ROOT, 1);

        try (OpqLogLines lines = new OpqLogLines(AgentOpqHandOver.class)) {
            tick(mira, 11_000L);
            assertEquals(1, lines.startingWith("OPQ handover: session=" + session.sessionId()
                    + " item=4001055 member=Mira count=1 place=FEET recollect=false leader=Ben").size());
        }
        verify(inventory).dropItem(mira, InventoryType.ETC, (short) 1, (short) 1);
        verify(packets).broadcastChatText(mira,
                "Ben, the root's at your feet. only you can take it through the exit.", false, 0);
        verify(run.partyQuest, never()).enterPortal(any(), anyInt());
    }

    @Test
    void aBenOutOfReachGetsTheRootByTheExit() {
        AgentOpqSession session = benLeads(AgentOpqSession.Phase.GARDEN);
        AgentOpqCoordinator.tickSession(session, 10_000L);
        when(run.actions.canReach(any(), any(), any())).thenReturn(false);
        stand(ben, AgentOpqDefinition.GARDEN_MAP, 600, 200);
        stand(mira, AgentOpqDefinition.GARDEN_MAP, 300, 516);
        holds(mira, ROOT, 1);

        tick(mira, 11_000L);
        verify(run.actions).navigate(run.entries.get(2), AgentOpqConductedGarden.GARDEN_EXIT, true);

        stand(mira, AgentOpqDefinition.GARDEN_MAP, -109, 560);
        session.member(2).deferUntil(0L);
        tick(mira, 12_000L);
        verify(inventory).dropItem(mira, InventoryType.ETC, (short) 1, (short) 1);
        verify(packets).broadcastChatText(mira, "Ben, i left the root by the exit. only you can take it through.",
                false, 0);
    }

    @Test
    void theRootLyingForBenIsHisThenPointedOutAndPickedBackUpBeforeItExpires() {
        AgentOpqSession session = benLeads(AgentOpqSession.Phase.GARDEN);
        AgentOpqCoordinator.tickSession(session, 10_000L);
        stand(mira, AgentOpqDefinition.GARDEN_MAP, 450, 516);
        stand(ben, AgentOpqDefinition.GARDEN_MAP, 430, 516);
        MapItem root = drop(41, ROOT, mira.getId(), true, new Point(430, 516), 20_000L);

        for (Character companion : List.of(mira, kes, pel)) {
            assertFalse(AgentOpqSessionRegistry.canLootExclusive(companion, root), companion.getName());
        }

        tick(mira, 20_000L + AgentOpqHandOver.PICKUP_NUDGE_MS);
        verify(packets).broadcastChatText(mira, "Ben, the root's still on the floor by you.", false, 0);

        session.member(2).deferUntil(0L);
        stand(mira, AgentOpqDefinition.GARDEN_MAP, 430, 516); // a step closer: no stall
        tick(mira, 20_000L + AgentOpqHandOver.RECOLLECT_MS);
        assertEquals(mira.getId(), session.claimerOf(root.getObjectId()));
        assertTrue(AgentOpqSessionRegistry.canLootExclusive(mira, root));
        assertFalse(AgentOpqSessionRegistry.canLootExclusive(kes, root));
        verify(mira).pickupItem(root);
    }

    @Test
    void aRootBenDropsInTheGardenIsPickedBackUpBeforeItExpiresAndHandedBackToHim() {
        // Only Ben can take the Root through the garden's exit; one he dropped there lay for no one and would expire.
        AgentOpqSession session = benLeads(AgentOpqSession.Phase.GARDEN);
        AgentOpqCoordinator.tickSession(session, 10_000L);
        stand(mira, AgentOpqDefinition.GARDEN_MAP, 450, 516);
        stand(ben, AgentOpqDefinition.GARDEN_MAP, 430, 516);
        MapItem root = drop(42, ROOT, ben.getId(), true, new Point(430, 516), 20_000L);
        assertFalse(AgentOpqSessionRegistry.canLootExclusive(mira, root), "his drop: only its claimer takes it");

        tick(mira, 20_000L + AgentOpqHandOver.RECOLLECT_MS);

        assertEquals(mira.getId(), session.claimerOf(root.getObjectId()));
        verify(mira).pickupItem(root);

        lying.remove(root);
        holds(mira, ROOT, 1);
        session.member(2).deferUntil(0L);
        tick(mira, 21_000L + AgentOpqHandOver.RECOLLECT_MS);
        verify(inventory).dropItem(mira, InventoryType.ETC, (short) 1, (short) 1);
        verify(packets).broadcastChatText(mira, "picked the root back up so it doesn't vanish. here it is.", false, 0);
    }

    // ---- the centre --------------------------------------------------------------------------------------------

    @Test
    void benInTheCentreWithTheRootIsAskedForItAndTheRunMovesOn() {
        AgentOpqSession session = benLeads(AgentOpqSession.Phase.GARDEN);
        everyoneIn(AgentOpqDefinition.CENTER_MAP);
        holds(ben, ROOT, 1);

        AgentOpqCoordinator.tickSession(session, 10_000L);
        AgentOpqCoordinator.tickSession(session, 11_000L);

        assertEquals(AgentOpqSession.Phase.RESTORING_MINERVA, session.phase());
        verify(packets, times(1)).broadcastChatText(mira, "Ben, drop the root here. i'll take it up to the statue.",
                false, 0);
    }

    @Test
    void aRootBenDroppedInTheCentreMovesTheRunOnWithoutHisBag() {
        AgentOpqSession session = benLeads(AgentOpqSession.Phase.GARDEN);
        everyoneIn(AgentOpqDefinition.CENTER_MAP);
        drop(51, ROOT, ben.getId(), true, new Point(0, 0), 9_000L);

        AgentOpqCoordinator.tickSession(session, 10_000L);

        assertEquals(AgentOpqSession.Phase.RESTORING_MINERVA, session.phase());
    }

    @Test
    void aCompanionTakesTheRootBenDroppedUpToMinervasStatue() {
        AgentOpqSession session = benLeads(AgentOpqSession.Phase.RESTORING_MINERVA);
        everyoneIn(AgentOpqDefinition.CENTER_MAP);
        MapItem root = drop(51, ROOT, ben.getId(), true, new Point(0, 0), 9_000L);
        assertFalse(AgentOpqSessionRegistry.canLootExclusive(kes, root), "his Root, until a companion claims it");

        tick(kes, 10_000L);

        assertEquals(kes.getId(), session.claimerOf(root.getObjectId()));
        assertTrue(AgentOpqSessionRegistry.canLootExclusive(kes, root));
        assertFalse(AgentOpqSessionRegistry.canLootExclusive(pel, root));
        verify(kes).pickupItem(root);

        lying.remove(root);
        holds(kes, ROOT, 1);
        stand(kes, AgentOpqDefinition.CENTER_MAP, -48, -916);
        try (OpqLogLines lines = new OpqLogLines(AgentOpqConductedGarden.class)) {
            tick(kes, 11_000L);
            assertEquals(List.of("OPQ root: session=" + session.sessionId() + " by=Kes"),
                    lines.startingWith("OPQ root:"));
        }
        verify(inventory).dropItem(kes, InventoryType.ETC, (short) 1, (short) 1);
        verify(run.partyQuest, never()).runNpc(any(), eq(AgentOpqDefinition.MINERVA_NPC));
    }

    // ---- the clear and the claims ------------------------------------------------------------------------------

    @Test
    void theClearStartsTheClaimsAndTheCompanionsWaitInTheCentreWhileBenIsThere() {
        AgentOpqSession session = benLeads(AgentOpqSession.Phase.RESTORING_MINERVA);
        everyoneIn(AgentOpqDefinition.CENTER_MAP);
        minervaFreed();
        when(run.actions.npcPosition(any(), eq(AgentOpqDefinition.MINERVA_NPC))).thenReturn(new Point(0, 0));

        AgentOpqCoordinator.tickSession(session, 10_000L);
        tick(mira, 10_500L);

        assertEquals(AgentOpqSession.Phase.CLAIMING_REWARD, session.phase());
        verify(packets).broadcastChatText(mira,
                "minerva's free! the hidden room has goodies if you want, we'll come with you.", false, 0);
        verify(run.partyQuest, never()).runNpc(any(), eq(AgentOpqDefinition.MINERVA_NPC));
        verify(run.actions).stop(run.entries.get(2));
    }

    @Test
    void theyFollowBenIntoTheHiddenRoomAndFightBesideHim() {
        benLeads(AgentOpqSession.Phase.CLAIMING_REWARD);
        minervaFreed();
        stand(ben, AgentOpqDefinition.HIDDEN_ROOM_MAP, 81, 3087);
        stand(mira, AgentOpqDefinition.CENTER_MAP, 180, -316);
        when(run.actions.portalPosition(mira, AgentOpqDefinition.HIDDEN_ROOM_PORTAL)).thenReturn(new Point(180, -316));

        tick(mira, 10_000L);
        verify(run.partyQuest).enterPortal(mira, AgentOpqDefinition.HIDDEN_ROOM_PORTAL);

        stand(kes, AgentOpqDefinition.HIDDEN_ROOM_MAP, 81, 3087);
        when(run.actions.liveMonsterCount(kes, AgentOpqDefinition.HIDDEN_ROOM_MOBS)).thenReturn(4);
        tick(kes, 10_000L);
        verify(run.actions).grind(run.entries.get(3), AgentOpqDefinition.HIDDEN_ROOM_MOBS);
        verify(run.partyQuest, never()).runNpc(any(), anyInt());
    }

    @Test
    void onceBenGoesOnTheyLeaveTheHiddenRoomAndClaim() {
        AgentOpqSession session = benLeads(AgentOpqSession.Phase.CLAIMING_REWARD);
        minervaFreed();
        stand(ben, 920_011_100, 236, 714); // Eak took him on to the treasure room
        stand(kes, AgentOpqDefinition.HIDDEN_ROOM_MAP, -39, 3183);
        when(run.actions.portalPosition(kes, AgentOpqDefinition.HIDDEN_ROOM_EXIT_PORTAL))
                .thenReturn(new Point(-39, 3183));
        stand(mira, AgentOpqDefinition.CENTER_MAP, 0, 0);
        when(run.actions.npcPosition(mira, AgentOpqDefinition.MINERVA_NPC)).thenReturn(new Point(10, 0));

        AgentOpqCoordinator.tickSession(session, 10_000L);
        tick(kes, 10_000L);
        tick(mira, 10_000L);

        verify(run.party).sendPartyChat(mira, "we'll grab our rewards and wait outside.");
        verify(run.partyQuest).enterPortal(kes, AgentOpqDefinition.HIDDEN_ROOM_EXIT_PORTAL);
        verify(run.partyQuest).runNpc(mira, AgentOpqDefinition.MINERVA_NPC);
    }

    @Test
    void aCompanionAlreadyOnTheRewardMapClaimsEvenWithBenInTheCentre() {
        AgentOpqSession session = benLeads(AgentOpqSession.Phase.CLAIMING_REWARD);
        minervaFreed();
        stand(ben, AgentOpqDefinition.CENTER_MAP, 0, 0);
        stand(mira, AgentOpqDefinition.CLEAR_MAP, 0, 0);
        when(run.actions.npcPosition(mira, AgentOpqDefinition.MINERVA_NPC)).thenReturn(new Point(10, 0));

        tick(mira, 10_000L);

        verify(run.partyQuest).runNpc(mira, AgentOpqDefinition.MINERVA_NPC);
        assertEquals(AgentOpqSession.Phase.CLAIMING_REWARD, session.phase());
    }

    @Test
    void afterTheClearTheyWaitFollowOrClaimByWhereBenIs() {
        assertEquals(AgentOpqConductedGarden.AfterClear.WAIT,
                AgentOpqConductedGarden.afterClear(true, 100, AgentOpqDefinition.CENTER_MAP));
        assertEquals(AgentOpqConductedGarden.AfterClear.FOLLOW,
                AgentOpqConductedGarden.afterClear(true, 100, AgentOpqDefinition.HIDDEN_ROOM_MAP));
        for (int elsewhere : List.of(AgentOpqDefinition.CLEAR_MAP, 920_011_100, 920_010_900,
                AgentOpqDefinition.RECRUIT_MAP)) {
            assertEquals(AgentOpqConductedGarden.AfterClear.CLAIM,
                    AgentOpqConductedGarden.afterClear(true, 100, elsewhere), String.valueOf(elsewhere));
        }
        assertEquals(AgentOpqConductedGarden.AfterClear.CLAIM,
                AgentOpqConductedGarden.afterClear(true, 0, AgentOpqDefinition.CENTER_MAP), "down");
        assertEquals(AgentOpqConductedGarden.AfterClear.CLAIM,
                AgentOpqConductedGarden.afterClear(false, 100, AgentOpqDefinition.CENTER_MAP), "out of the event");
    }

    @Test
    void aStockRunsAgentsStillTalkToTheCentresMinervaOnceSheIsFree() {
        Character[] agents = {run.companion(11, "Lea"), run.companion(12, "Ari"), run.companion(13, "Bo"),
                run.companion(14, "Cy"), run.companion(15, "Di"), run.companion(16, "Ed")};
        AgentOpqSession session = run.session(AgentOpqSession.Mode.AUTONOMOUS,
                AgentOpqSession.Phase.RESTORING_MINERVA, agents);
        minervaFreed();
        when(agents[1].getPosition()).thenReturn(new Point(0, 0));
        when(run.actions.npcPosition(agents[1], AgentOpqDefinition.MINERVA_NPC)).thenReturn(new Point(10, 0));

        AgentOpqCoordinator.tickMember(session, run.entries.get(12), agents[1], 10_000L);

        verify(run.partyQuest).runNpc(agents[1], AgentOpqDefinition.MINERVA_NPC);
        verify(inventory, never()).dropItem(any(), any(), anyShort(), anyShort());
    }
}
