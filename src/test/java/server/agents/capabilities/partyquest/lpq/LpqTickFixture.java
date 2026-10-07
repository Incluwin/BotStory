package server.agents.capabilities.partyquest.lpq;

import client.Character;
import client.inventory.Item;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.combat.AgentCombatTargetRuntime;
import server.agents.capabilities.combat.AgentGrindTargetStateRuntime;
import server.agents.capabilities.inventory.AgentInventoryStateRuntime;
import server.agents.capabilities.looting.AgentGrindLootStateRuntime;
import server.agents.capabilities.movement.AgentMovementStateRuntime;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.integration.AgentCharacterGatewayRuntime;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.integration.AgentMapGatewayRuntime;
import server.agents.integration.AgentPacketGatewayRuntime;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.integration.AgentPartyMemberSnapshot;
import server.agents.integration.AgentPartyQuestGatewayRuntime;
import server.agents.integration.AgentPartySnapshot;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.integration.CharacterGateway;
import server.agents.integration.InventoryGateway;
import server.agents.integration.MapGateway;
import server.agents.integration.PacketGateway;
import server.agents.integration.PartyGateway;
import server.agents.integration.PartyQuestGateway;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.perception.AgentMapPerception;
import server.agents.plans.AgentScriptItemActionService;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.simulation.AgentSimulationState;
import server.life.Monster;
import server.maps.FootholdTree;
import server.maps.MapItem;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * One LPQ run as the coordinator ticks it (P6g LPQ): mocked members standing on the run's maps in one bound event,
 * and every gateway and runtime the coordinator reaches through, each a mock it resolves per call
 * (AgentLpqGateways). Companions are loaded Agents with a runtime entry; humans are only online. Maps are created on
 * first use with their own drops, monsters, portals and areas; positions, bags, properties and NPC spots are set
 * per test. The event stays live (timer running, leader registered) unless a test says otherwise.
 */
final class LpqTickFixture implements AutoCloseable {
    final EventInstanceManager event = mock(EventInstanceManager.class);
    final CharacterGateway characters = mock(CharacterGateway.class);
    final PartyGateway party = mock(PartyGateway.class);
    final PrimitiveCapabilityGateway actions = mock(PrimitiveCapabilityGateway.class);
    final PartyQuestGateway lpq = mock(PartyQuestGateway.class);
    final PacketGateway packets = mock(PacketGateway.class);
    final MapGateway mapGateway = mock(MapGateway.class);
    final InventoryGateway inventory = mock(InventoryGateway.class);
    final MockedStatic<AgentPartyQuestLifecycleRuntime> lifecycle;
    final MockedStatic<AgentScriptItemActionService> items;
    final MockedStatic<AgentGrindLootStateRuntime> loot;
    final MockedStatic<AgentInventoryStateRuntime> lootInhibit;
    final MockedStatic<AgentLpqDialogue> dialogue;
    final Map<Integer, AgentRuntimeEntry> entries = new HashMap<>();
    private final List<MockedStatic<?>> statics = new ArrayList<>();
    private final MockedStatic<AgentRuntimeRegistry> registry;
    private final MockedStatic<AgentRuntimeIdentityRuntime> identity;
    private final Map<Integer, MapleMap> maps = new HashMap<>();
    private final Map<Integer, List<MapItem>> drops = new HashMap<>();
    private final Map<Integer, List<Monster>> monsters = new HashMap<>();
    private final Map<Integer, List<Rectangle>> areas = new HashMap<>();
    private final Map<String, Integer> portalIds = new HashMap<>();
    private final Map<String, Point> portalPositions = new HashMap<>();
    private final Map<Integer, Point> npcs = new HashMap<>();
    private final Map<String, String> properties = new HashMap<>();
    private final Map<Integer, Integer> mapOf = new HashMap<>();
    private final Map<Integer, Point> positionOf = new HashMap<>();
    private final Map<String, Integer> bags = new HashMap<>();
    private final Set<Integer> outside = new HashSet<>();
    private final List<Character> partyMembers = new ArrayList<>();
    private final List<Character> members = new ArrayList<>();
    private final List<AgentLpqSession> sessions = new ArrayList<>();
    private int partyLeaderId;
    /** The time of the tick being run (drops made during it carry it). */
    long clock;
    private int nextObjectId = 10_000;

    LpqTickFixture() {
        registry = open(AgentRuntimeRegistry.class);
        identity = open(AgentRuntimeIdentityRuntime.class);
        open(AgentCharacterGatewayRuntime.class).when(AgentCharacterGatewayRuntime::characters).thenReturn(characters);
        open(AgentPartyGatewayRuntime.class).when(AgentPartyGatewayRuntime::party).thenReturn(party);
        open(AgentPrimitiveCapabilityGatewayRuntime.class).when(AgentPrimitiveCapabilityGatewayRuntime::gateway)
                .thenReturn(actions);
        open(AgentPartyQuestGatewayRuntime.class).when(AgentPartyQuestGatewayRuntime::partyQuest).thenReturn(lpq);
        open(AgentPacketGatewayRuntime.class).when(AgentPacketGatewayRuntime::packets).thenReturn(packets);
        open(AgentMapGatewayRuntime.class).when(AgentMapGatewayRuntime::map).thenReturn(mapGateway);
        open(AgentInventoryGatewayRuntime.class).when(AgentInventoryGatewayRuntime::inventory).thenReturn(inventory);
        open(AgentMapPerception.class).when(() -> AgentMapPerception.items(any()))
                .thenAnswer(call -> {
                    MapleMap map = call.getArgument(0);
                    return map == null ? List.of() : List.copyOf(drops.getOrDefault(map.getId(), List.of()));
                });
        open(AgentMovementStateRuntime.class);
        open(AgentGrindTargetStateRuntime.class);
        open(AgentCombatTargetRuntime.class);
        lifecycle = open(AgentPartyQuestLifecycleRuntime.class);
        items = open(AgentScriptItemActionService.class);
        loot = open(AgentGrindLootStateRuntime.class);
        lootInhibit = open(AgentInventoryStateRuntime.class);
        dialogue = open(AgentLpqDialogue.class);
        when(event.getName()).thenReturn("LudiPQ_test");
        when(event.getMapInstance(anyInt())).thenAnswer(call -> map(call.getArgument(0)));
        when(event.isTimerStarted()).thenReturn(true);
        when(event.getTimeLeft()).thenReturn(1_800_000L);
        when(event.getPlayers()).thenAnswer(call -> members.stream()
                .filter(member -> !outside.contains(member.getId())).toList());
        when(event.getPlayerById(anyInt())).thenAnswer(call -> members.stream()
                .filter(member -> member.getId() == (int) call.getArgument(0)
                        && !outside.contains(member.getId())).findFirst().orElse(null));
        when(party.hasParty(any())).thenReturn(true);
        when(party.sendPartyChat(any(), anyString())).thenReturn(true);
        when(party.snapshot(any())).thenAnswer(call -> snapshotFor(call.getArgument(0)));
        when(lpq.property(any(), anyString())).thenAnswer(call -> properties.get((String) call.getArgument(1)));
        when(lpq.event(any())).thenAnswer(call -> {
            Character member = call.getArgument(0);
            return member == null || outside.contains(member.getId()) ? null : event;
        });
        when(lpq.sameEvent(any(), any())).thenAnswer(call -> {
            Character first = call.getArgument(0);
            Character second = call.getArgument(1);
            return first != null && second != null
                    && !outside.contains(first.getId()) && !outside.contains(second.getId());
        });
        when(actions.npcPosition(any(), anyInt())).thenAnswer(call -> copy(npcs.get((int) call.getArgument(1))));
        when(actions.groundPoint(any(), any())).thenAnswer(call -> copy(call.getArgument(1)));
        when(actions.predictDropLanding(any(), any())).thenAnswer(call -> copy(call.getArgument(1)));
        when(actions.footing(any())).thenReturn(PrimitiveCapabilityGateway.Footing.GROUND);
        when(actions.grounded(any())).thenReturn(true);
        when(actions.canReach(any(), any(), any())).thenReturn(true);
        when(actions.hpPotionCount(any())).thenReturn(40);
        when(actions.directPortalIdTo(any(), anyInt())).thenAnswer(call -> {
            Character agent = call.getArgument(0);
            return portalIds.get(agent.getMapId() + ">" + call.getArgument(1));
        });
        when(actions.portalPosition(any(), anyInt())).thenAnswer(call -> {
            Character agent = call.getArgument(0);
            return copy(portalPositions.get(agent.getMapId() + "#" + call.getArgument(1)));
        });
        // A drop leaves the dropper's bag and lies where it stands, a player drop of its own, at the tick's time.
        items.when(() -> AgentScriptItemActionService.dropItem(any(), any(), anyInt(), anyShort())).thenAnswer(call -> {
            AgentRuntimeEntry entry = call.getArgument(0);
            int itemId = call.getArgument(2);
            short quantity = call.getArgument(3);
            Character dropper = members.stream().filter(member -> entries.get(member.getId()) == entry)
                    .findFirst().orElse(null);
            if (dropper == null) return true;
            String bag = dropper.getId() + ":" + itemId;
            bags.put(bag, Math.max(0, bags.getOrDefault(bag, 0) - quantity));
            Point landing = actions.predictDropLanding(dropper, dropper.getPosition());
            drop(dropper.getMapId(), itemId, quantity, landing == null ? dropper.getPosition() : landing, true,
                    dropper.getId(), clock);
            return true;
        });
    }

    private static Point copy(Point point) {
        return point == null ? null : new Point(point);
    }

    /** The run's instance of this map, made on first use. */
    MapleMap map(int mapId) {
        return maps.computeIfAbsent(mapId, id -> {
            MapleMap map = mock(MapleMap.class);
            when(map.getId()).thenReturn(id);
            when(map.getEventInstance()).thenReturn(event);
            when(map.getDroppedItems()).thenAnswer(call -> List.copyOf(drops.getOrDefault(id, List.of())));
            when(map.getAllMonsters()).thenAnswer(call -> List.copyOf(monsters.getOrDefault(id, List.of())));
            when(map.getFootholds()).thenReturn(new FootholdTree(new Point(-2000, -2000), new Point(2000, 2000)));
            when(map.getAreas()).thenAnswer(call -> List.copyOf(areas.getOrDefault(id, List.of())));
            Portal spawn = mock(Portal.class);
            when(spawn.getPosition()).thenReturn(new Point(0, 0));
            when(map.getRandomPlayerSpawnpoint()).thenReturn(spawn);
            when(map.getPortal(anyInt())).thenAnswer(call -> {
                int portalId = call.getArgument(0);
                Point at = portalPositions.get(id + "#" + portalId);
                if (at == null) return null;
                Portal portal = mock(Portal.class);
                when(portal.getId()).thenReturn(portalId);
                when(portal.getPosition()).thenReturn(new Point(at));
                return portal;
            });
            return map;
        });
    }

    /** An online human party member on {@code mapId} at {@code position}, in the event. */
    Character human(int id, String name, int mapId, Point position) {
        Character human = member(id, name, mapId, position);
        when(characters.findOnlineCharacterById(id)).thenReturn(human);
        return human;
    }

    /** A loaded companion Agent on {@code mapId} at {@code position}, in the event. */
    Character companion(int id, String name, int mapId, Point position) {
        Character companion = member(id, name, mapId, position);
        AgentRuntimeEntry entry = mock(AgentRuntimeEntry.class);
        when(entry.simulationState()).thenReturn(new AgentSimulationState());
        registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(id)).thenReturn(entry);
        identity.when(() -> AgentRuntimeIdentityRuntime.bot(entry)).thenReturn(companion);
        when(characters.findOnlineCharacterById(id)).thenReturn(companion);
        entries.put(id, entry);
        return companion;
    }

    /**
     * The usual crew: Crewfriend (a human, leading; id 1) and the companions Mira, Kes, Pel, Hal and Cas (ids 2-6),
     * all on {@code mapId}, 5 px apart from {@code at}.
     */
    List<Character> crew(int mapId, Point at) {
        List<Character> crew = new ArrayList<>();
        crew.add(human(1, "Crewfriend", mapId, at));
        String[] names = {"Mira", "Kes", "Pel", "Hal", "Cas"};
        for (int i = 0; i < names.length; i++) {
            crew.add(companion(2 + i, names[i], mapId, new Point(at.x + 5 * (i + 1), at.y)));
        }
        return crew;
    }

    AgentRuntimeEntry entry(Character companion) {
        return entries.get(companion.getId());
    }

    private Character member(int id, String name, int mapId, Point position) {
        Character member = mock(Character.class);
        mapOf.put(id, mapId);
        positionOf.put(id, new Point(position));
        when(member.getId()).thenReturn(id);
        when(member.getName()).thenReturn(name);
        when(member.getHp()).thenReturn(2_000);
        when(member.getMaxHp()).thenReturn(2_000);
        when(member.getCurrentMaxHp()).thenReturn(2_000);
        when(member.getMapId()).thenAnswer(call -> mapOf.get(id));
        when(member.getMap()).thenAnswer(call -> map(mapOf.get(id)));
        when(member.getPosition()).thenAnswer(call -> new Point(positionOf.get(id)));
        when(member.getEventInstance()).thenAnswer(call -> outside.contains(id) ? null : event);
        when(member.getItemQuantity(anyInt(), eq(false))).thenAnswer(
                call -> bags.getOrDefault(id + ":" + call.getArgument(0), 0));
        // A pickup takes the whole stack into the bag.
        doAnswer(call -> {
            MapItem picked = call.getArgument(0);
            if (picked == null || picked.isPickedUp()) return null;
            int quantity = picked.getItem() == null ? 1 : picked.getItem().getQuantity();
            bags.merge(id + ":" + picked.getItemId(), quantity, Integer::sum);
            pickUp(picked);
            return null;
        }).when(member).pickupItem(any());
        members.add(member);
        return member;
    }

    void onMap(Character member, int mapId, Point position) {
        mapOf.put(member.getId(), mapId);
        positionOf.put(member.getId(), new Point(position));
    }

    void moveTo(Character member, Point position) {
        positionOf.put(member.getId(), new Point(position));
    }

    /** The member is out of the event, on this map. */
    void outside(Character member, int mapId) {
        outside.add(member.getId());
        mapOf.put(member.getId(), mapId);
    }

    void inside(Character member) {
        outside.remove(member.getId());
    }

    void holds(Character member, int itemId, int quantity) {
        bags.put(member.getId() + ":" + itemId, quantity);
    }

    void npcAt(int npcId, Point position) {
        npcs.put(npcId, new Point(position));
    }

    /** A portal on {@code mapId} at {@code position} leading to {@code toMapId}. */
    void portal(int mapId, int portalId, Point position, int toMapId) {
        portalIds.put(mapId + ">" + toMapId, portalId);
        portalPositions.put(mapId + "#" + portalId, new Point(position));
    }

    void property(String key, String value) {
        if (value == null) properties.remove(key);
        else properties.put(key, value);
    }

    void areas(int mapId, List<Rectangle> rectangles) {
        areas.put(mapId, List.copyOf(rectangles));
    }

    /** A drop of {@code quantity} lying on {@code mapId}. */
    MapItem drop(int mapId, int itemId, int quantity, Point position, boolean playerDrop, int ownerId,
                 long droppedAtMs) {
        MapItem drop = mock(MapItem.class);
        Item item = mock(Item.class);
        when(item.getQuantity()).thenReturn((short) quantity);
        when(item.getItemId()).thenReturn(itemId);
        int objectId = nextObjectId++;
        when(drop.getItem()).thenReturn(item);
        when(drop.getItemId()).thenReturn(itemId);
        when(drop.getPosition()).thenReturn(new Point(position));
        when(drop.isPlayerDrop()).thenReturn(playerDrop);
        when(drop.getOwnerId()).thenReturn(ownerId);
        when(drop.getDropTime()).thenReturn(droppedAtMs);
        when(drop.getObjectId()).thenReturn(objectId);
        drops.computeIfAbsent(mapId, ignored -> new ArrayList<>()).add(drop);
        return drop;
    }

    void pickUp(MapItem drop) {
        when(drop.isPickedUp()).thenReturn(true);
        drops.values().forEach(list -> list.remove(drop));
    }

    Monster monster(int mapId, int mobId, Point position) {
        Monster monster = mock(Monster.class);
        when(monster.getId()).thenReturn(mobId);
        when(monster.isAlive()).thenReturn(true);
        when(monster.getPosition()).thenReturn(new Point(position));
        when(monster.getHp()).thenReturn(1_000L);
        when(monster.getMaxHp()).thenReturn(1_000L);
        when(monster.getObjectId()).thenReturn(nextObjectId++);
        monsters.computeIfAbsent(mapId, ignored -> new ArrayList<>()).add(monster);
        return monster;
    }

    void kill(Monster monster) {
        when(monster.isAlive()).thenReturn(false);
        monsters.values().forEach(list -> list.remove(monster));
    }

    /** The live party: these members, the first leading. */
    void party(Character... partyOf) {
        partyMembers.clear();
        partyMembers.addAll(List.of(partyOf));
        partyLeaderId = partyOf.length == 0 ? 0 : partyOf[0].getId();
    }

    void leaveParty(Character member) {
        partyMembers.remove(member);
    }

    void passLead(Character member) {
        partyLeaderId = member.getId();
    }

    /** The member logged off: no runtime entry and not online. */
    void offline(Character member) {
        registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(member.getId())).thenReturn(null);
        when(characters.findOnlineCharacterById(member.getId())).thenReturn(null);
    }

    private AgentPartySnapshot snapshotFor(Character asking) {
        if (asking == null || !partyMembers.contains(asking)) return null;
        return new AgentPartySnapshot(1, partyMembers.stream()
                .map(member -> new AgentPartyMemberSnapshot(member.getId(), member.getName(),
                        member.getId() == partyLeaderId, member.getMapId()))
                .toList());
    }

    /**
     * A registered PRODUCTION session in {@code phase} (entered at 2,000 ms), bound to the live event: the first
     * member leads the event and the live party, the first companion holds the execution lease. A companion run
     * also has its members' coordination chat on, as its host turns it on.
     */
    AgentLpqSession session(boolean companionRun, AgentLpqSession.Phase phase, Character... runMembers) {
        AgentLpqSession session = new AgentLpqSession(
                AgentLpqSession.Mode.PRODUCTION, 7L, runMembers[0].getId(), runMembers.length, 1_000L);
        if (companionRun) session.setCompanionAdopted(true);
        session.setMemberCoordinationChatEnabled(companionRun);
        Integer execution = null;
        for (Character member : runMembers) {
            boolean agent = entries.containsKey(member.getId());
            session.addMember(member.getId(), agent
                    ? AgentLpqMemberState.MemberType.AGENT : AgentLpqMemberState.MemberType.HUMAN);
            if (agent && execution == null) execution = member.getId();
            if (agent && companionRun) session.addCompanionOfRun(member.getId());
        }
        session.setLeadership(runMembers[0].getId(), execution);
        boolean agentLeader = entries.containsKey(runMembers[0].getId());
        session.setPartyOwnership(agentLeader
                ? AgentLpqSession.PartyOwnership.LPQ_OWNED : AgentLpqSession.PartyOwnership.EXTERNAL);
        session.setBonusMode(agentLeader ? AgentLpqSession.BonusMode.ENTER : AgentLpqSession.BonusMode.HUMAN_CHOICE);
        session.bindEventInstance(event);
        if (phase != AgentLpqSession.Phase.PREPARING) session.transition(phase, 2_000L);
        AgentLpqSessionRegistry.registerComplete(session);
        sessions.add(session);
        Character leader = runMembers[0];
        int leaderId = leader.getId();
        when(event.getLeaderId()).thenReturn(leaderId);
        when(leader.isPartyLeader()).thenReturn(true);
        party(runMembers);
        return session;
    }

    /**
     * This companion line was said (map chat through AgentLpqDialogue), by this speaker, under this key; a line with
     * a line break was said as its parts, each its own chat line.
     */
    void said(AgentLpqSession session, String key, Character speaker, String text) {
        for (String part : text.split(String.valueOf(AgentLpqCompanionPlay.THEN))) {
            dialogue.verify(() -> AgentLpqDialogue.sayMapNow(session.sessionId(), key, speaker, part));
        }
    }

    void neverSaid(String key) {
        dialogue.verify(() -> AgentLpqDialogue.sayMapNow(anyString(), eq(key), any(), anyString()), never());
    }

    void saidNothing() {
        dialogue.verify(() -> AgentLpqDialogue.sayMapNow(anyString(), anyString(), any(), anyString()), never());
    }

    void tick(AgentLpqSession session, long nowMs) {
        clock = nowMs;
        AgentLpqCoordinator.tick(session, nowMs);
    }

    /** Ticks every {@code stepMs} from {@code fromMs} to {@code toMs}, both included. */
    void tickEvery(AgentLpqSession session, long fromMs, long toMs, long stepMs) {
        for (long now = fromMs; now <= toMs; now += stepMs) tick(session, now);
    }

    Map<Integer, Point> positions() {
        return new LinkedHashMap<>(positionOf);
    }

    private <T> MockedStatic<T> open(Class<T> type) {
        MockedStatic<T> mocked = mockStatic(type);
        statics.add(mocked);
        return mocked;
    }

    @Override
    public void close() {
        sessions.forEach(AgentLpqSessionRegistry::remove);
        for (int i = statics.size() - 1; i >= 0; i--) {
            statics.get(i).close();
        }
    }
}
