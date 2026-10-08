package server.living.roster;

import client.Character;
import net.server.channel.Channel;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.expedition.boss.AgentBossRunDefinition;
import server.agents.capabilities.expedition.boss.AgentBossRunMember;
import server.agents.capabilities.expedition.boss.AgentBossRunService;
import server.agents.integration.AgentDialogueTransportRuntime;
import server.expeditions.Expedition;
import server.expeditions.ExpeditionType;
import server.integration.AgentPresence;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntConsumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;

/**
 * A living world for the bench-ally tests (P7 Part B, CompanionBossAllies): {@link LifecycleHarness} with Ann on
 * Mu Young's Easy Balrog list in her room (105100100), both switches listing easy_balrog, the real budget, leases,
 * commitments and crew cache, and benched companions of Ann's at Lv70 camping in the Sleepywood dungeon (40 s away by
 * the recall estimate). The Agent layer's run ({@link FakeOpen}) and the game's loads ({@link FakePorts}) are fakes;
 * whispers, map chat and the bench calls are recorded. The list closes 5 min from {@link #now}.
 */
final class BossAllyHarness implements AutoCloseable {
    static final int ROOM = 105100100;
    /** The Ant Tunnel Park camp under Sleepywood: 40 s from her room (same region, a field). */
    static final int SLEEPYWOOD_CAMP = 105040300;
    /** The expedition's channel, not the living one (1). */
    static final int CHANNEL = 2;
    static final long SID = 7L;
    static final String KEY = CompanionBossRun.key("easy_balrog", SID, ANN);

    final LifecycleHarness game = new LifecycleHarness();
    final FakeOpen open = new FakeOpen();
    final FakePorts ports = new FakePorts();
    final Expedition expedition = mock(Expedition.class);
    final Map<String, CompanionBossRun> runs = new ConcurrentHashMap<>();
    /** Every whisper, as "Persona -> Player: line". */
    final List<String> whispers = new ArrayList<>();
    /** Every map-chat line, as "Name: line". */
    final List<String> mapChat = new ArrayList<>();
    /** Each bench slice ended by a call, as "end <persona> lease=<its lease then>", in order with the loads. */
    final List<String> bench = new ArrayList<>();
    final MapleMap room;
    long now = 1_000_000_000L;
    final CompanionBossAllies allies;

    private final MockedStatic<AgentDialogueTransportRuntime> transport;
    private final boolean mapChatWasOn;

    BossAllyHarness() {
        try {
            room = game.map(ROOM);
            stub();
        } catch (RuntimeException | Error failure) {
            game.close(); // its static mocks would leak into every later test class on this thread
            throw failure;
        }
        transport = mockStatic(AgentDialogueTransportRuntime.class);
        transport.when(() -> AgentDialogueTransportRuntime.sayMapNow(any(), anyString())).thenAnswer(inv ->
                mapChat.add(((Character) inv.getArgument(0)).getName() + ": " + inv.getArgument(1)));
        mapChatWasOn = config.AgentYamlConfig.config.agent.AGENT_DIALOGUE_TRANSPORT_ENABLED;
        config.AgentYamlConfig.config.agent.AGENT_DIALOGUE_TRANSPORT_ENABLED = true;
        allies = new CompanionBossAllies(game.world, open, runs, ports, () -> now);
    }

    private void stub() {
        game.config.COMPANION_BOSS_ENABLED_TYPES = "easy_balrog";
        game.config.COMPANION_BOSS_ALLY_TYPES = "easy_balrog";
        game.moveAnn(ROOM, 617, 246);
        Portal top = mock(Portal.class);
        when(top.getPosition()).thenReturn(new Point(-65, -264)); // WZ 105100100 portal 1
        when(room.getPortal("top00")).thenReturn(top);
        Channel channel = mock(Channel.class);
        when(channel.getId()).thenReturn(CHANNEL);
        when(room.getChannelServer()).thenReturn(channel);
        when(room.getCharacterById(ANN)).thenAnswer(inv -> game.ann.getMap() == room ? game.ann : null);
        when(game.ann.getClient().getChannelServer().getExpedition(ExpeditionType.BALROG_EASY)).thenReturn(expedition);
        when(expedition.isRegistering()).thenReturn(true);
        when(expedition.getLeader()).thenReturn(game.ann);
        when(expedition.contains(game.ann)).thenReturn(true);
        when(expedition.getMaxSize()).thenReturn(ExpeditionType.BALROG_EASY.getMaxSize());
        when(expedition.getMembers()).thenAnswer(inv -> new HashMap<>(Map.of(ANN, "Ann")));
        when(expedition.getRecruitingMap()).thenReturn(room);
        when(expedition.getType()).thenReturn(ExpeditionType.BALROG_EASY);
        closesIn(300_000L);
        when(game.world.budget()).thenReturn(new CompanionBudget(game.world));
        when(game.world.views()).thenReturn(new CompanionViews(game.world, id -> "camp", id -> "item"));
        BenchDutyDispatcher duty = game.world.benchDuty();
        doAnswer(inv -> {
            String persona = inv.getArgument(0);
            int id = game.residents.characterIdOf(persona);
            bench.add("end " + persona + " lease=" + game.leases.holder(id));
            return null;
        }).when(duty).endFor(anyString(), anyString());
        doAnswer(inv -> whispers.add(((Persona) inv.getArgument(0)).name() + " -> "
                + ((Character) inv.getArgument(1)).getName() + ": " + inv.getArgument(2)))
                .when(game.residents).whisper(any(), any(), anyString());
    }

    /** A benched companion of Ann's at Lv70 (a White Knight), camping at {@code campMapId}. */
    Persona benched(String id, String name, int characterId, int campMapId) {
        Persona persona = game.companion(id, name, characterId, RosterState.BENCHED, campMapId);
        game.store.levels.put(characterId, 70);
        game.store.jobs.put(characterId, 121);
        return persona;
    }

    /** Mu Young's list closes {@code ms} from now (the run stops taking sign-ups REGISTRATION_MARGIN_MS before). */
    void closesIn(long ms) {
        when(expedition.getStartTime()).thenReturn(now + ms);
    }

    /** When the run stops taking sign-ups, as the picker reads it. */
    long deadline() {
        return AgentBossRunService.signUpDeadlineMs(expedition);
    }

    /** The whispers {@code name} said, each read back as its intent's wording ("{eta}" read as {@code eta}). */
    List<String> said(String name) {
        List<String> out = new ArrayList<>();
        for (String whisper : whispers) {
            if (whisper.startsWith(name + " -> ")) {
                out.add(whisper.substring(whisper.indexOf(": ") + 2));
            }
        }
        return out;
    }

    /** True when {@code line} is one of {@code intent}'s generic wordings, "{eta}" read as {@code eta}. */
    static boolean isLine(String intent, String eta, String line) {
        for (String wording : BossRunLines.GENERIC.get(intent)) {
            if (wording.replace("{eta}", eta == null ? "" : eta).equals(line)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() {
        config.AgentYamlConfig.config.agent.AGENT_DIALOGUE_TRANSPORT_ENABLED = mapChatWasOn;
        AgentPresence.install(null);
        transport.close();
        game.close();
    }

    /** The Agent layer's open runs as a test sets them: one run, {@link #SID}, for the expedition. */
    static final class FakeOpen implements CompanionBossRuns.OpenRuns {
        boolean registering = true;
        final Set<Integer> inRun = new HashSet<>();
        /** Ids the run turns away. */
        final Set<Integer> refuse = new HashSet<>();
        /** "enlistSelfLed <owner> <ids>", "enlist <owner> <ids>", "withdraw <id>", in order. */
        final List<String> calls = new ArrayList<>();
        /** Agent id -> its phase in the run, from its enlist on. */
        final Map<Integer, AgentBossRunMember.Phase> phases = new HashMap<>();
        /** The list without the members enlisted here: those count as companions waiting to sign up. */
        CompanionBossRuns.Seats listed = new CompanionBossRuns.Seats(1, 0, 1, 0);
        /** Told of each enlisted id, before the enlist answers (a race a test stages). */
        IntConsumer onEnlist = id -> { };
        /** Told of each withdrawn id, as it is withdrawn. */
        IntConsumer onWithdraw = id -> { };

        @Override
        public boolean open(long sessionId) {
            return sessionId == SID;
        }

        @Override
        public boolean waiting(long sessionId, int enlistedBy) {
            return false;
        }

        @Override
        public boolean registering(long sessionId) {
            return registering && sessionId == SID;
        }

        @Override
        public boolean signedUpIn(EventInstanceManager event) {
            return false;
        }

        @Override
        public boolean heldByAnother(Expedition expedition, int ownerId) {
            return false;
        }

        @Override
        public boolean inRun(int agentId) {
            return inRun.contains(agentId);
        }

        @Override
        public int waitingOrdinal(int agentId, int anchorId) {
            return -1;
        }

        @Override
        public AgentBossRunService.Enlisted enlist(Character owner, AgentBossRunDefinition definition,
                                                   Expedition expedition, List<Integer> agentIds, long nowMs) {
            calls.add("enlist " + owner.getId() + " " + agentIds);
            return new AgentBossRunService.Enlisted(0L, List.of(), agentIds);
        }

        @Override
        public boolean underway(long sessionId) {
            return false;
        }

        @Override
        public boolean inside(long sessionId, int agentId) {
            return false;
        }

        @Override
        public boolean handTo(long sessionId, int agentId, int heirId) {
            return false;
        }

        @Override
        public AgentBossRunService.Enlisted enlistSelfLed(Character owner, AgentBossRunDefinition definition,
                                                          Expedition expedition, List<Integer> agentIds,
                                                          long nowMs) {
            calls.add("enlistSelfLed " + owner.getId() + " " + agentIds);
            List<Integer> accepted = agentIds.stream().filter(id -> !refuse.contains(id)).toList();
            accepted.forEach(id -> {
                onEnlist.accept(id);
                phases.put(id, AgentBossRunMember.Phase.WAITING);
            });
            return new AgentBossRunService.Enlisted(accepted.isEmpty() ? 0L : SID, accepted,
                    agentIds.stream().filter(refuse::contains).toList());
        }

        @Override
        public boolean withdraw(long sessionId, int agentId) {
            calls.add("withdraw " + agentId);
            onWithdraw.accept(agentId);
            return phases.get(agentId) == AgentBossRunMember.Phase.WAITING;
        }

        @Override
        public AgentBossRunMember.Phase phaseOf(long sessionId, int agentId) {
            return sessionId == SID ? phases.get(agentId) : null;
        }

        @Override
        public CompanionBossRuns.Seats seats(Expedition expedition, int ownerId) {
            int pending = 0;
            for (AgentBossRunMember.Phase phase : phases.values()) {
                pending += phase == AgentBossRunMember.Phase.WAITING || phase == AgentBossRunMember.Phase.REGISTERED
                        ? 1 : 0;
            }
            return new CompanionBossRuns.Seats(listed.humansListed(), listed.botsListedOrPending() + pending,
                    listed.owners(), listed.held() + pending);
        }
    }

    /** The game's loads as a test sets them: a load puts the companion in the room copy it was asked for. */
    final class FakePorts implements CompanionBossAllies.Ports {
        /** "load <id> world <w> ch<c> at <map> <x>,<y>" per load, in order. */
        final List<String> loads = new ArrayList<>();
        /** Characters some other runtime holds. */
        final Set<Integer> elsewhere = new HashSet<>();
        /** Agents short of the boss's potion standard. */
        final Set<Integer> shortOfPotions = new HashSet<>();
        boolean loadFails;

        @Override
        public Character load(int characterId, int worldId, int channel, MapleMap map, Point at) {
            loads.add("load " + characterId + " world " + worldId + " ch" + channel + " at " + map.getId() + " "
                    + at.x + "," + at.y);
            if (loadFails) {
                return null;
            }
            Character bot = game.bots.get(characterId);
            bot.changeMap(map, at);
            game.loaded.add(characterId);
            game.selfLed.add(characterId); // registered as an idle Agent of its own
            game.calls.add("register idle " + bot.getName());
            return bot;
        }

        @Override
        public boolean loaded(int characterId) {
            return elsewhere.contains(characterId);
        }

        @Override
        public boolean shortOfPotions(Character agent, String bossKey) {
            return shortOfPotions.contains(agent.getId());
        }
    }
}
