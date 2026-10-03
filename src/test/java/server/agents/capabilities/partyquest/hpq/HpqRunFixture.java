package server.agents.capabilities.partyquest.hpq;

import client.Character;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.inventory.AgentInventoryStateRuntime;
import server.agents.capabilities.looting.AgentGrindLootStateRuntime;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.integration.AgentCharacterGatewayRuntime;
import server.agents.integration.AgentPacketGatewayRuntime;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.integration.AgentPartyMemberSnapshot;
import server.agents.integration.AgentPartySnapshot;
import server.agents.integration.AgentPartyQuestGatewayRuntime;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.integration.CharacterGateway;
import server.agents.integration.PartyGateway;
import server.agents.integration.PacketGateway;
import server.agents.integration.PartyQuestGateway;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.perception.AgentMapPerception;
import server.agents.plans.AgentScriptItemActionService;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.simulation.AgentSimulationState;
import server.life.Monster;
import server.maps.MapItem;
import server.maps.MapleMap;
import server.maps.Reactor;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * One live HPQ run at session level on Primrose Hill (910010000): mocked members standing in the bound event, and the
 * gateways and runtimes the coordinator reaches through. Companions are loaded Agents; humans are only online (or
 * not). The primitive gateway ({@link #actions}) and the party-quest gateway ({@link #hpq}) are mocks the coordinator
 * resolves per call; companion lines are captured through {@link #dialogue}.
 */
final class HpqRunFixture implements AutoCloseable {
    static final int STAGE_MOBS = 9_300_062;
    static final Point BUNNY_LANDING = new Point(-183, -188);

    final EventInstanceManager event = mock(EventInstanceManager.class);
    final MapleMap map = mock(MapleMap.class);
    final CharacterGateway characters = mock(CharacterGateway.class);
    final PartyGateway party = mock(PartyGateway.class);
    final PrimitiveCapabilityGateway actions = mock(PrimitiveCapabilityGateway.class);
    final PartyQuestGateway hpq = mock(PartyQuestGateway.class);
    final PacketGateway packets = mock(PacketGateway.class);
    final MockedStatic<AgentHpqDialogue> dialogue;
    final MockedStatic<AgentPartyQuestLifecycleRuntime> lifecycle;
    final MockedStatic<AgentGrindLootStateRuntime> loot;
    final MockedStatic<AgentInventoryStateRuntime> inventory;
    final MockedStatic<AgentScriptItemActionService> items;
    final Map<Integer, AgentRuntimeEntry> entries = new HashMap<>();
    private final List<MockedStatic<?>> statics = new ArrayList<>();
    private final List<Monster> monsters = new ArrayList<>();
    private final List<MapItem> drops = new ArrayList<>();
    private final List<Reactor> reactors = new ArrayList<>();
    private final List<AgentHpqSession> sessions = new ArrayList<>();
    private final List<Character> partyMembers = new ArrayList<>();
    private int partyLeaderId;
    private final MockedStatic<AgentRuntimeRegistry> registry;
    private final MockedStatic<AgentRuntimeIdentityRuntime> identity;

    HpqRunFixture() {
        registry = open(AgentRuntimeRegistry.class);
        identity = open(AgentRuntimeIdentityRuntime.class);
        open(AgentCharacterGatewayRuntime.class).when(AgentCharacterGatewayRuntime::characters).thenReturn(characters);
        open(AgentPartyGatewayRuntime.class).when(AgentPartyGatewayRuntime::party).thenReturn(party);
        open(AgentPrimitiveCapabilityGatewayRuntime.class).when(AgentPrimitiveCapabilityGatewayRuntime::gateway)
                .thenReturn(actions);
        open(AgentPartyQuestGatewayRuntime.class).when(AgentPartyQuestGatewayRuntime::partyQuest).thenReturn(hpq);
        open(AgentPacketGatewayRuntime.class).when(AgentPacketGatewayRuntime::packets).thenReturn(packets);
        open(AgentMapPerception.class).when(() -> AgentMapPerception.monsters(map)).thenAnswer(call -> List.copyOf(monsters));
        dialogue = open(AgentHpqDialogue.class);
        lifecycle = open(AgentPartyQuestLifecycleRuntime.class);
        loot = open(AgentGrindLootStateRuntime.class);
        inventory = open(AgentInventoryStateRuntime.class);
        items = open(AgentScriptItemActionService.class);
        when(map.getDroppedItems()).thenAnswer(call -> List.copyOf(drops));
        when(actions.reactors(any())).thenAnswer(call -> List.copyOf(reactors));
        when(actions.configuredMonsterSpawnIds(any())).thenReturn(Set.of(STAGE_MOBS, AgentHpqDefinition.MOON_BUNNY));
        // Ground is wherever a point is asked about; a drop lands where it is dropped.
        when(actions.groundPoint(any(), any())).thenAnswer(call -> copy(call.getArgument(1)));
        when(actions.predictDropLanding(any(), any())).thenAnswer(call -> copy(call.getArgument(1)));
        when(actions.footing(any())).thenReturn(PrimitiveCapabilityGateway.Footing.GROUND);
        when(actions.canReach(any(), any(), any())).thenReturn(true);
        when(actions.hpPotionCount(any())).thenReturn(40);
        when(actions.npcPosition(any(), eq(AgentHpqDefinition.GROWLIE_NPC))).thenReturn(new Point(429, 83));
        when(hpq.property(any(), eq("stage"))).thenReturn("6");
    }

    private static Point copy(Point point) {
        return point == null ? null : new Point(point);
    }

    /** An online human party member standing in the bound event at {@code position}. */
    Character human(int id, String name, Point position) {
        Character human = member(id, name, position);
        when(characters.findOnlineCharacterById(id)).thenReturn(human);
        return human;
    }

    /** A loaded companion Agent standing in the bound event at {@code position}. */
    Character companion(int id, String name, Point position) {
        Character companion = member(id, name, position);
        AgentRuntimeEntry entry = mock(AgentRuntimeEntry.class);
        when(entry.simulationState()).thenReturn(new AgentSimulationState());
        registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(id)).thenReturn(entry);
        identity.when(() -> AgentRuntimeIdentityRuntime.bot(entry)).thenReturn(companion);
        when(characters.findOnlineCharacterById(id)).thenReturn(companion);
        entries.put(id, entry);
        return companion;
    }

    AgentRuntimeEntry entry(Character companion) {
        return entries.get(companion.getId());
    }

    private Character member(int id, String name, Point position) {
        Character member = mock(Character.class);
        when(member.getId()).thenReturn(id);
        when(member.getName()).thenReturn(name);
        when(member.getHp()).thenReturn(500);
        when(member.getCurrentMaxHp()).thenReturn(500);
        when(member.getMap()).thenReturn(map);
        when(member.getMapId()).thenReturn(AgentHpqDefinition.STAGE_MAP);
        when(member.getPosition()).thenReturn(new Point(position));
        when(member.getEventInstance()).thenReturn(event);
        when(hpq.event(member)).thenReturn(event);
        when(hpq.sameEvent(any(), eq(member))).thenReturn(true);
        return member;
    }

    /**
     * The live party: these members, the first leading. Asked about a character outside it, the party gateway has no
     * party for him. {@link #session} sets it to the session's members.
     */
    void party(Character... members) {
        partyMembers.clear();
        partyMembers.addAll(List.of(members));
        partyLeaderId = members.length == 0 ? 0 : members[0].getId();
        when(party.snapshot(any())).thenAnswer(call -> snapshotFor(call.getArgument(0)));
    }

    /** The member left the live party (or its host took a tapped-out companion out of it). */
    void leaveParty(Character member) {
        partyMembers.remove(member);
    }

    /** The live party's lead passes to this member. */
    void passLead(Character member) {
        partyLeaderId = member.getId();
    }

    /** The member logged off: no runtime and not online (the live party keeps him, as the game's party does). */
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

    /** Moves a member to this map, still in the bound event. */
    void onMap(Character member, int mapId) {
        when(member.getMapId()).thenReturn(mapId);
    }

    /** The member is out of the event, on this map. */
    void outside(Character member, int mapId) {
        when(member.getMapId()).thenReturn(mapId);
        when(member.getEventInstance()).thenReturn(null);
        when(hpq.event(member)).thenReturn(null);
        when(hpq.sameEvent(any(), eq(member))).thenReturn(false);
    }

    void moveTo(Character member, Point position) {
        when(member.getPosition()).thenReturn(new Point(position));
    }

    void holds(Character member, int itemId, int quantity) {
        when(member.getItemQuantity(itemId, false)).thenReturn(quantity);
    }

    Monster monster(int mobId, Point position) {
        Monster monster = mock(Monster.class);
        when(monster.getId()).thenReturn(mobId);
        when(monster.isAlive()).thenReturn(true);
        when(monster.getPosition()).thenReturn(new Point(position));
        when(monster.getHp()).thenReturn(1000L);
        when(monster.getMaxHp()).thenReturn(1000L);
        monsters.add(monster);
        return monster;
    }

    void kill(Monster monster) {
        when(monster.isAlive()).thenReturn(false);
        monsters.remove(monster);
    }

    MapItem drop(int itemId, Point position, boolean playerDrop, int ownerId, long droppedAtMs) {
        MapItem drop = mock(MapItem.class);
        when(drop.getItemId()).thenReturn(itemId);
        when(drop.getPosition()).thenReturn(new Point(position));
        when(drop.isPlayerDrop()).thenReturn(playerDrop);
        when(drop.getOwnerId()).thenReturn(ownerId);
        when(drop.getDropTime()).thenReturn(droppedAtMs);
        when(drop.getObjectId()).thenReturn(10_000 + drops.size());
        drops.add(drop);
        return drop;
    }

    void pickUp(MapItem drop) {
        when(drop.isPickedUp()).thenReturn(true);
        drops.remove(drop);
    }

    /** Drops succeed. */
    void dropsSucceed() {
        items.when(() -> AgentScriptItemActionService.dropItem(any(), any(), anyInt(), anyShort())).thenReturn(true);
    }

    /** A reactor on Primrose Hill: a moonflower pad (9108000-5) or a primrose seed source (9102002-7). */
    Reactor reactor(int reactorId, String name, Point position, boolean active) {
        Reactor reactor = mock(Reactor.class);
        when(reactor.getId()).thenReturn(reactorId);
        when(reactor.getName()).thenReturn(name);
        when(reactor.isAlive()).thenReturn(true);
        when(reactor.isActive()).thenReturn(active);
        when(reactor.getPosition()).thenReturn(new Point(position));
        when(reactor.getObjectId()).thenReturn(20_000 + reactors.size());
        reactors.add(reactor);
        return reactor;
    }

    /** This line was said, by this speaker, under this key. */
    void said(AgentHpqSession session, String key, Character speaker, String text) {
        dialogue.verify(() -> AgentHpqDialogue.sayMapNow(session.sessionId(), key, speaker, text));
    }

    void neverSaid(String key) {
        dialogue.verify(() -> AgentHpqDialogue.sayMapNow(anyString(), eq(key), any(), anyString()), never());
    }

    void saidNothing() {
        dialogue.verify(() -> AgentHpqDialogue.sayMapNow(anyString(), anyString(), any(), anyString()), never());
    }

    /**
     * A registered PRODUCTION session in {@code phase}, bound to the event: the first member leads, the first companion
     * holds the execution lease, and the members make up the live party.
     */
    AgentHpqSession session(boolean companionAdopted, AgentHpqSession.Phase phase, Character... members) {
        AgentHpqSession session = new AgentHpqSession(
                AgentHpqSession.Mode.PRODUCTION, 7L, members[0].getId(), Math.max(3, members.length), 1_000L);
        int executionAgentId = 0;
        for (Character member : members) {
            boolean companion = entries.containsKey(member.getId());
            session.addMember(member.getId(), companion
                    ? AgentHpqMemberState.MemberType.AGENT : AgentHpqMemberState.MemberType.HUMAN);
            if (companion && executionAgentId == 0) executionAgentId = member.getId();
        }
        session.setLeadership(members[0].getId(), executionAgentId);
        session.setBonusMode(entries.containsKey(members[0].getId())
                ? AgentHpqSession.BonusMode.SKIP : AgentHpqSession.BonusMode.HUMAN_CHOICE);
        session.setCompanionAdopted(companionAdopted);
        session.setMemberCoordinationChatEnabled(companionAdopted);
        session.bindEventInstance(event);
        party(members);
        session.transition(phase, 1_000L);
        AgentHpqSessionRegistry.registerComplete(session);
        sessions.add(session);
        return session;
    }

    private <T> MockedStatic<T> open(Class<T> type) {
        MockedStatic<T> mocked = mockStatic(type);
        statics.add(mocked);
        return mocked;
    }

    @Override
    public void close() {
        sessions.forEach(AgentHpqSessionRegistry::remove);
        for (int i = statics.size() - 1; i >= 0; i--) {
            statics.get(i).close();
        }
    }
}
