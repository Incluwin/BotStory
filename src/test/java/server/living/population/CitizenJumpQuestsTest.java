package server.living.population;

import client.Character;
import config.PopulationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.integration.AgentPresence;
import server.agents.capabilities.behavior.AgentJumpNoise;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.state.AgentCapabilityStateRegistry;
import server.living.population.CitizenSession.Activity;
import server.living.population.JumpQuestCourses.Course;
import server.living.population.JumpQuestCourses.Stages;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * The jump-quest activity's state machine (CitizenJumpQuests, docs/BOT_CONCEPT_PLAN.md F5b) against a scripted engine:
 * its gates, the climb stage by stage, the falls and time caps, the no-long-idle rule, the Forest's fee, pile and
 * Sabitrama errands, and the below-map log-off. Navigation and the real jumps are JumpQuestRoutesTest's.
 */
class CitizenJumpQuestsTest {
    private static final int ID = 4401;

    private final PopulationConfig config = new PopulationConfig();
    private final Population population = mock(Population.class, withSettings().stubOnly());
    private final PresenceScheduler presence = mock(PresenceScheduler.class, withSettings().stubOnly());
    private final ChatterEngine chatter = mock(ChatterEngine.class, withSettings().stubOnly());
    private final List<String> asked = new ArrayList<>();
    private final List<String> said = new ArrayList<>();
    private final FakeEngine engine = new FakeEngine();
    private final Map<Integer, CitizenSession> sessions = new HashMap<>();
    private final Set<Character> agents = new HashSet<>();
    private MockedStatic<AgentRuntimeIdentityRuntime> identity;
    private MockedStatic<AgentPresence> agentPresence;
    private Character agent;
    private MapleMap map;
    private final List<Character> onMap = new ArrayList<>();
    private int mapId;
    private final Point position = new Point();
    private int level = 30;
    private int meso = 100_000;
    private final Map<Integer, Byte> quests = new HashMap<>();
    private final Set<Integer> items = new HashSet<>();
    private CitizenSession session;
    private CitizenJumpQuests jq;

    @BeforeEach
    void setUp() {
        config.JQ_ENABLED = true;
        when(population.config()).thenReturn(config);
        when(population.presence()).thenReturn(presence);
        when(population.chatter()).thenReturn(chatter);
        doAnswer(call -> said.add(((ChatterEngine.Event) call.getArgument(0)).trigger)).when(chatter).speakTo(any());
        when(presence.session(anyInt())).thenAnswer(call -> sessions.get((int) call.getArgument(0)));
        doAnswer(call -> asked.add("replan")).when(presence).replan(any(), anyLong());
        doAnswer(call -> {
            sessions.remove(((CitizenSession) call.getArgument(0)).characterId);
            return asked.add("logout " + call.getArgument(1));
        }).when(presence).logout(any(), anyString());
        doAnswer(call -> asked.add("travel " + call.getArgument(1) + " " + call.getArgument(2)))
                .when(presence).travel(any(), any(), anyInt(), anyLong());
        identity = mockStatic(AgentRuntimeIdentityRuntime.class);
        agentPresence = mockStatic(AgentPresence.class);
        agentPresence.when(() -> AgentPresence.isAgent(any())).thenAnswer(call -> agents.contains(call.getArgument(0)));
        map = mock(MapleMap.class, withSettings().stubOnly());
        when(map.getAllPlayers()).thenAnswer(call -> new ArrayList<>(onMap));
        agent = mock(Character.class, withSettings().stubOnly());
        agents.add(agent);
        onMap.add(agent);
        when(agent.getId()).thenReturn(ID);
        when(agent.getName()).thenReturn("Pip");
        when(agent.getMapId()).thenAnswer(call -> mapId);
        when(agent.getMap()).thenReturn(map);
        when(agent.getPosition()).thenAnswer(call -> new Point(position));
        when(agent.getLevel()).thenAnswer(call -> level);
        when(agent.getMeso()).thenAnswer(call -> meso);
        when(agent.getQuestStatus(anyInt())).thenAnswer(call -> quests.getOrDefault((int) call.getArgument(0), (byte) 0));
        when(agent.haveItem(anyInt())).thenAnswer(call -> items.contains((int) call.getArgument(0)));
        AgentRuntimeEntry entry = mock(AgentRuntimeEntry.class, withSettings().stubOnly());
        when(entry.capabilityStates()).thenReturn(new AgentCapabilityStateRegistry());
        identity.when(() -> AgentRuntimeIdentityRuntime.bot(entry)).thenReturn(agent);
        Citizen citizen = new Citizen(ID, "Pip", "evening", 0, 0, "victoria", "hunter", "relaxed-v1", "default", 1.0d,
                0, 1, new LinkedHashSet<>(), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
        session = new CitizenSession(citizen, 0L, 24 * 3_600_000L, false);
        session.entry = entry;
        session.activity = Activity.JQ;
        sessions.put(ID, session);
        jq = new CitizenJumpQuests(population, engine, new Random(3L));
    }

    @AfterEach
    void tearDown() {
        identity.close();
        agentPresence.close();
    }

    @Test
    void offWithTheSwitchOffAndOnlyForVictoria() {
        config.JQ_ENABLED = false;
        assertFalse(jq.plays(session, level, 0L));
        assertTrue(jq.courses().isEmpty());
        config.JQ_ENABLED = true;
        assertTrue(jq.plays(session, level, 0L));
        session.citizen = new Citizen(ID, "Pip", "evening", 0, 0, "orbis", "hunter", "relaxed-v1", "default", 1.0d,
                0, 1, new LinkedHashSet<>(), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
        assertFalse(jq.plays(session, level, 0L), "Orbis citizens do not come down for Victoria's jump quests");
    }

    @Test
    void aLowLevelOrShortPurseGoesForPetWalkingRoad() {
        level = 12;
        assertEquals(100000202, jq.mapFor(session, level), "the road at any level");
        level = 30;
        meso = 10_000; // under Shane's 5000 plus the reserve
        assertEquals(100000202, jq.mapFor(session, level));
        meso = 100_000;
        quests.put(2050, (byte) 2); // done already: no errand, straight to Shane
        Set<Integer> starts = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            starts.add(jq.mapFor(session, level));
        }
        assertTrue(starts.contains(JumpQuestCourses.ELLINIA), "the Forest by Shane's gate in Ellinia: " + starts);
    }

    @Test
    void climbsStageByStageAndClearsTheRoad() {
        engine.stages = twoStages(new Point(-900, 120), new Point(-1593, -1586));
        mapId = 100000202;
        position.setLocation(-2108, 236);
        assertTrue(arrive(JumpQuestCourses.PWR, 0L));
        assertEquals("move -900,120 precise", engine.last());
        assertNotNull(engine.plan, "its noise is on for the climb");
        assertTrue(engine.plan.sigmaAt(120) > 0.0d);
        assertTrue(said.contains("jq.start"));
        position.setLocation(-905, 120); // at the first stage's checkpoint
        jq.tick(10_000L);
        assertEquals("move -1593,-1586 precise", engine.last(), "on to the next stage");
        engine.portalSpots.put("h005", new Point(-1590, -1586));
        position.setLocation(-1590, -1586);
        jq.tick(20_000L); // the top: it stands a moment
        assertTrue(engine.plan == null, "no noise once it is up");
        jq.tick(40_000L);
        assertTrue(said.contains("jq.clear"));
        assertTrue(engine.calls.contains("portal h005"), "down by the road's own portal: " + engine.calls);
        jq.tick(50_000L);
        assertEquals("replan", asked.get(asked.size() - 1));
        assertFalse(jq.holds(session));
        assertFalse(jq.plays(session, level, 60_000L), "no jump quest again so soon");
        assertTrue(jq.plays(session, level, 50_000L + config.JQ_COOLDOWN_MS + 1L));
        assertTrue(session.journal.recent(10).stream().anyMatch(entry -> entry.text().contains("cleared pet walking road")));
    }

    @Test
    void givesUpAfterTheFallsCapAndLeavesTheForestByTheExitNpc() {
        quests.put(2050, (byte) 2); // its errand done before: straight onto the course
        engine.stages = twoStages(new Point(0, 500), new Point(94, -3019));
        mapId = 101000100;
        position.setLocation(-94, 938);
        assertTrue(arrive(JumpQuestCourses.FOP_A, 0L));
        for (int fall = 1; fall < config.JQ_FALLS_MAX; fall++) {
            engine.misses = fall;
            position.translate(0, fall % 2 == 0 ? 3 : -3); // climbing again after each fall
            jq.tick(fall * 10_000L);
            assertTrue(jq.holds(session));
        }
        assertFalse(said.contains("jq.giveup"));
        engine.misses = config.JQ_FALLS_MAX;
        jq.tick(90_000L);
        assertTrue(said.contains("jq.giveup"), "the falls cap: " + said);
        assertTrue(engine.plan == null, "no noise on its way out");
        engine.npcSpots.put(1032004, new Point(554, 908));
        jq.tick(100_000L);
        assertEquals("move 554,908 walk", engine.last(), "down to Shane at the foot");
        position.setLocation(550, 908);
        jq.tick(110_000L);
        assertEquals("npc 1032004", engine.last());
        mapId = JumpQuestCourses.ELLINIA; // his warp
        jq.tick(120_000L);
        assertFalse(jq.holds(session));
        assertEquals("replan", asked.get(asked.size() - 1));
    }

    @Test
    void givesUpAfterTheTimeCapOnTheRoad() {
        engine.stages = twoStages(new Point(-900, 120), new Point(-1593, -1586));
        mapId = 100000202;
        position.setLocation(-2108, 236);
        assertTrue(arrive(JumpQuestCourses.PWR, 0L));
        for (long t = 10_000L; t < config.JQ_ATTEMPT_MAX_MS; t += 10_000L) {
            position.translate(t % 20_000L == 0 ? 20 : -20, 0); // busy, never stuck
            jq.tick(t);
        }
        assertTrue(jq.holds(session));
        jq.tick(config.JQ_ATTEMPT_MAX_MS);
        assertTrue(said.contains("jq.giveup"));
        assertFalse(jq.holds(session), "the road has walkable ways out: its next plan walks it off");
        assertEquals("replan", asked.get(asked.size() - 1));
    }

    @Test
    void neverStandsStillTwoMinutesInAJumpQuest() {
        engine.stages = twoStages(new Point(-900, 120), new Point(-1593, -1586));
        mapId = 100000202;
        position.setLocation(-1200, 300);
        assertTrue(arrive(JumpQuestCourses.PWR, 0L));
        jq.tick(CitizenJumpQuests.STILL_MAX_MS - 10_000L);
        assertTrue(jq.holds(session));
        jq.tick(CitizenJumpQuests.STILL_MAX_MS);
        assertFalse(jq.holds(session), "two minutes on one spot ends the attempt");
    }

    @Test
    void theForestRunsSabitramasErrandThroughShanesGateAndThePile() {
        level = 30;
        assertTrue(jq.send(session, "fop_a", 0L).contains("QUEST_START"));
        assertEquals("travel JQ " + JumpQuestCourses.SLEEPYWOOD, asked.get(0), "2050 can start: Sabitrama first");
        // Sleepywood: walk up, a moment, the quest, then on to Ellinia.
        engine.npcSpots.put(JumpQuestCourses.SABITRAMA, new Point(765, -375));
        mapId = JumpQuestCourses.SLEEPYWOOD;
        position.setLocation(200, 255);
        assertTrue(jq.arrive(session, mapOf(), 0L));
        assertEquals("move 765,-375 walk", engine.last());
        position.setLocation(760, -375);
        jq.tick(10_000L);
        jq.tick(20_000L);
        assertTrue(engine.calls.contains("start 2050 at 1061005"), engine.calls.toString());
        assertEquals("travel JQ " + JumpQuestCourses.ELLINIA, asked.get(asked.size() - 1));
        quests.put(2050, (byte) 1);
        // Ellinia: up to Shane, his fee and warp onto course A.
        engine.npcSpots.put(1032003, new Point(168, -2879));
        mapId = JumpQuestCourses.ELLINIA;
        position.setLocation(168, -2879);
        assertTrue(jq.arrive(session, mapOf(), 30_000L));
        assertEquals("npc 1032003", engine.last());
        engine.stages = twoStages(new Point(0, 500), new Point(94, -3019));
        mapId = 101000100;
        position.setLocation(-94, 938);
        jq.tick(40_000L);
        assertEquals("move 0,500 precise", engine.last(), "climbing course A");
        // Leg 0's top, through in00 to 101000101.
        position.setLocation(0, 500);
        jq.tick(50_000L);
        position.setLocation(94, -3019);
        jq.tick(60_000L);
        jq.tick(80_000L);
        engine.portalSpots.put("in00", new Point(94, -3019));
        jq.tick(90_000L);
        assertEquals("portal in00", engine.last());
        engine.stages = twoStages(new Point(-100, -900), new Point(-386, -3536));
        mapId = 101000101;
        position.setLocation(-166, 398);
        jq.tick(100_000L);
        assertEquals("move -100,-900 precise", engine.last(), "leg 1 on the next map");
        position.setLocation(-100, -900);
        jq.tick(110_000L);
        position.setLocation(-386, -3536);
        jq.tick(120_000L);
        jq.tick(140_000L);
        engine.npcSpots.put(1043000, new Point(-386, -3502));
        jq.tick(150_000L);
        assertEquals("npc 1043000", engine.last(), "the pile: its prize and Sabitrama's item");
        items.add(4031020);
        mapId = JumpQuestCourses.ELLINIA;
        jq.tick(160_000L);
        assertTrue(said.contains("jq.clear"));
        assertEquals("travel JQ " + JumpQuestCourses.SLEEPYWOOD, asked.get(asked.size() - 1),
                "straight on to Sabitrama with the item, no cooldown first");
        // Sleepywood again: the item goes back.
        mapId = JumpQuestCourses.SLEEPYWOOD;
        position.setLocation(760, -375);
        assertTrue(jq.arrive(session, mapOf(), 170_000L));
        jq.tick(180_000L);
        assertTrue(engine.calls.contains("complete 2050 at 1061005"), engine.calls.toString());
        assertEquals("replan", asked.get(asked.size() - 1));
        assertFalse(jq.holds(session));
    }

    @Test
    void aFallBelowTheMapInFrontOfAPlayerLogsTheCitizenOff() {
        engine.stages = twoStages(new Point(-900, 120), new Point(-1593, -1586));
        mapId = 100000202;
        position.setLocation(-2108, 236);
        assertTrue(arrive(JumpQuestCourses.PWR, 0L));
        assertFalse(jq.fellBelowMap(ID, agent), "nobody watching: the recovery teleport runs unseen");
        assertEquals(1, jq.belowMapTeleports());
        Character human = mock(Character.class, withSettings().stubOnly());
        onMap.add(human);
        assertTrue(jq.fellBelowMap(ID, agent), "a player watching: no teleport");
        jq.tick(10_000L);
        assertEquals("logout disconnected in a jump quest", asked.get(asked.size() - 1));
        assertEquals(1, jq.belowMapLogoffs());
        assertTrue(jq.status().contains("1 logged off"), jq.status());
    }

    @Test
    void anExitNpcThatNeverSendsItOutEndsInALogOffNotALongIdle() {
        quests.put(2050, (byte) 2);
        engine.stages = twoStages(new Point(0, 500), new Point(94, -3019));
        mapId = 101000100;
        position.setLocation(-94, 938);
        assertTrue(arrive(JumpQuestCourses.FOP_A, 0L));
        engine.misses = config.JQ_FALLS_MAX;
        jq.tick(10_000L);
        engine.npcSpots.put(1032004, new Point(554, 908));
        position.setLocation(554, 908);
        long t = 20_000L;
        for (int tries = 0; tries < CitizenJumpQuests.EXIT_TRIES; tries++) {
            jq.tick(t);
            assertEquals("npc 1032004", engine.last());
            t += 20_000L;
        }
        jq.tick(t);
        assertEquals("logout stuck in a jump quest", asked.get(asked.size() - 1));
        assertTrue(t < 120_000L + CitizenJumpQuests.STILL_MAX_MS);
    }

    @Test
    void eachCitizensSkillIsStableAndInsideTheRange() {
        double low = 1.0d;
        double high = 0.0d;
        for (int id = 1; id < 400; id++) {
            Citizen citizen = new Citizen(id, "C" + id, "evening", 0, 0, "victoria", "hunter", "relaxed-v1", "default",
                    1.0d, 0, 1, new LinkedHashSet<>(), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
            CitizenSession other = new CitizenSession(citizen, 0L, 1L, false);
            double skill = jq.skillOf(other);
            assertTrue(skill >= config.JQ_STAGE_SUCCESS_MIN && skill <= config.JQ_STAGE_SUCCESS_MAX, "skill " + skill);
            assertEquals(skill, jq.skillOf(other));
            low = Math.min(low, skill);
            high = Math.max(high, skill);
        }
        assertTrue(low < 0.35 && high > 0.65, "spread over the range: " + low + ".." + high);
    }

    private boolean arrive(Course course, long nowMs) {
        jq.send(session, course.key(), nowMs); // plans it (the GM path); presence's travel is recorded, not run
        return jq.arrive(session, mapOf(), nowMs);
    }

    private MapleMap mapOf() {
        when(map.getId()).thenReturn(mapId);
        return map;
    }

    /** One leg in two stages: a checkpoint and the top, one 30 px landing window each. */
    private static Stages twoStages(Point checkpoint, Point top) {
        JumpWindows.Jump[] stage = {new JumpWindows.Jump(-10, 10, -15, 15), new JumpWindows.Jump(-10, 10, -15, 15)};
        return new Stages(List.of(checkpoint, top), List.of(stage, stage),
                List.of(new int[]{checkpoint.y, checkpoint.y + 300}, new int[]{top.y, checkpoint.y}));
    }

    /** The engine, scripted: moves and clicks are recorded; the test moves the citizen and changes its map. */
    private static final class FakeEngine implements CitizenJumpQuests.Engine {
        final List<String> calls = new ArrayList<>();
        final Map<Integer, Point> npcSpots = new HashMap<>();
        final Map<String, Point> portalSpots = new HashMap<>();
        Stages stages;
        AgentJumpNoise.Plan plan;
        int misses;

        String last() {
            return calls.isEmpty() ? "" : calls.get(calls.size() - 1);
        }

        @Override
        public void moveTo(CitizenSession session, Point spot, boolean precise) {
            calls.add("move " + spot.x + "," + spot.y + (precise ? " precise" : " walk"));
        }

        @Override
        public boolean moving(CitizenSession session) {
            return false;
        }

        @Override
        public void npc(CitizenSession session, int npcId) {
            calls.add("npc " + npcId);
        }

        @Override
        public void startQuest(CitizenSession session, int questId, int npcId) {
            calls.add("start " + questId + " at " + npcId);
        }

        @Override
        public void completeQuest(CitizenSession session, int questId, int npcId) {
            calls.add("complete " + questId + " at " + npcId);
        }

        @Override
        public void portal(CitizenSession session, String portal) {
            calls.add("portal " + portal);
        }

        @Override
        public Point npcSpot(CitizenSession session, int npcId) {
            return npcSpots.get(npcId);
        }

        @Override
        public Point portalSpot(CitizenSession session, String portal) {
            return portalSpots.get(portal);
        }

        @Override
        public Stages stages(Course course, int leg, CitizenSession session) {
            return stages;
        }

        @Override
        public void installNoise(CitizenSession session, AgentJumpNoise.Plan installed, long seed) {
            plan = installed;
            misses = 0;
        }

        @Override
        public void clearNoise(CitizenSession session) {
            plan = null;
        }

        @Override
        public AgentJumpNoise.Counts noise(CitizenSession session) {
            return new AgentJumpNoise.Counts(misses, misses, 0);
        }
    }
}
