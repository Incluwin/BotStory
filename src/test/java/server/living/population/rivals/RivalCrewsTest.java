package server.living.population.rivals;

import client.Character;
import client.Client;
import config.PopulationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.living.LivingWorld;
import server.living.population.ChatterEngine;
import server.living.population.Citizen;
import server.living.population.CitizenSession;
import server.living.population.CitizenSessions;
import server.living.population.Population;
import server.living.population.PresenceScheduler;
import server.living.population.needs.NeedLedger;
import server.living.population.records.Clear;
import server.living.population.records.ClearLog;
import server.living.population.records.QuestLabels;
import server.living.store.LivingStore;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rival crews (docs/BOT_CONCEPT_PLAN.md F7.2-F7.4a, decisions D12/D13): a player's main gets 4-5 citizens within
 * five levels as a crew with its own guild and circle (no masters, hires or other crews' members); as levels drift a
 * member out of band leaves (a loaded one says so first) and one in band joins, a leaving master hands the guild on,
 * and nobody's EXP or level is touched; a clear counts for the crew with two of its members and not its player; beating
 * the player's time is one megaphone (else a whisper; letters news while away), the player beating theirs a whispered
 * congratulation (owed until a member is loaded); banter once a gap on a shared map; and a Zakum help request is a
 * PARTY need inviting the player.
 */
class RivalCrewsTest {
    private static final int ACCOUNT = 1;
    private static final int ANN = 10;
    private static final int GUILD = 900;
    private static final int CIRCLE = 77;
    private static final int HENESYS = 100000000;
    private static final String BALROG = "BalrogBattle_Easy";
    private static final long NOW = 1_800_000_000_000L;
    private static final long MINUTE = 60_000L;

    private final PopulationConfig config = new PopulationConfig();
    private final Population population = mock(Population.class);
    private final PresenceScheduler presence = mock(PresenceScheduler.class);
    private final ChatterEngine chatter = mock(ChatterEngine.class);
    private final ClearLog clears = mock(ClearLog.class);
    private final NeedLedger needs = mock(NeedLedger.class);
    private final LivingWorld world = mock(LivingWorld.class);
    private final LivingStore livingStore = mock(LivingStore.class);
    private final MemoryRivals store = new MemoryRivals();
    private final FakePorts ports = new FakePorts();
    private final Map<Integer, Citizen> registry = new LinkedHashMap<>();
    private final Map<AgentRuntimeEntry, Character> agents = new HashMap<>();
    private final Character ann = mock(Character.class);
    private MockedStatic<AgentRuntimeIdentityRuntime> identity;
    private RivalCrews crews;

    @BeforeEach
    void setUp() {
        config.RIVALS_ENABLED = true;
        when(population.config()).thenReturn(config);
        when(population.presence()).thenReturn(presence);
        when(population.chatter()).thenReturn(chatter);
        when(population.clears()).thenReturn(clears);
        when(population.needs()).thenReturn(needs);
        when(population.world()).thenReturn(world);
        when(world.store()).thenReturn(livingStore);
        when(population.citizens()).thenAnswer(invocation -> List.copyOf(registry.values()));
        when(population.citizen(anyInt())).thenAnswer(invocation -> registry.get(invocation.<Integer>getArgument(0)));
        when(population.update(anyInt(), any())).thenAnswer(invocation -> {
            int id = invocation.getArgument(0);
            UnaryOperator<Citizen> change = invocation.getArgument(1);
            Citizen current = registry.get(id);
            if (current != null) {
                registry.put(id, change.apply(current));
            }
            return registry.get(id);
        });
        when(clears.labels()).thenReturn(new QuestLabels(List.of(new QuestLabels.Quest(BALROG, "Easy Balrog", true,
                List.of("balrog")))));
        when(chatter.compose(any())).thenReturn("a line");
        when(chatter.speakTo(any())).thenReturn(true);
        when(ann.getId()).thenReturn(ANN);
        when(ann.getName()).thenReturn("Ann");
        when(ann.getAccountID()).thenReturn(ACCOUNT);
        when(ann.getLevel()).thenReturn(30);
        when(ann.getMapId()).thenReturn(HENESYS);
        ports.online.add(ann);
        store.mains.put(ACCOUNT, new int[]{ANN, 30});
        identity = mockStatic(AgentRuntimeIdentityRuntime.class);
        identity.when(() -> AgentRuntimeIdentityRuntime.bot(any())).thenAnswer(
                invocation -> agents.get(invocation.<AgentRuntimeEntry>getArgument(0)));
        crews = new RivalCrews(population, store, ports, new Random(7));
    }

    @AfterEach
    void close() {
        identity.close();
    }

    private Citizen citizen(int id, String name, int level, String cohort) {
        return citizen(id, name, level, cohort, 0, 5);
    }

    private Citizen citizen(int id, String name, int level, String cohort, int guildId, int rank) {
        Citizen citizen = new Citizen(id, name, cohort, 0, guildId, "victoria", "hunter", "relaxed-v1", "default", 1.0d, 0, 1,
                new LinkedHashSet<>(), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
        registry.put(id, citizen);
        store.progress.put(id, new int[]{level, guildId, rank});
        return citizen;
    }

    /** The citizen loaded on {@code mapId} at {@code level}. */
    private Character load(int id, int level, int mapId) {
        CitizenSession session = CitizenSessions.loaded(registry.get(id), 0L, NOW + 600 * MINUTE);
        session.entry = mock(AgentRuntimeEntry.class);
        session.mapId = mapId;
        session.channel = 1;
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(id);
        when(agent.getLevel()).thenReturn(level);
        agents.put(session.entry, agent);
        when(presence.session(id)).thenReturn(session);
        return agent;
    }

    private void fourInBand() {
        citizen(5001, "Kim", 28, "evening");
        citizen(5002, "Lu", 33, "evening");
        citizen(5003, "Mo", 35, "night");
        citizen(5004, "Nia", 30, "evening");
    }

    private Crew formed() {
        crews.tick(NOW);
        Crew crew = crews.crewOfAccount(ACCOUNT);
        assertNotNull(crew);
        return crew;
    }

    // ---- forming -----------------------------------------------------------------------------------

    @Test
    void aMainGetsACrewInBandWithItsOwnGuildAndCircle() {
        fourInBand();
        citizen(5005, "Out", 36, "evening");                 // six levels up
        Citizen hired = citizen(5006, "Hire", 29, "evening");
        registry.put(5006, hired.withFlag(Citizen.FLAG_HIRED));
        citizen(5007, "Master", 31, "evening", 50, 1);        // another guild's master
        citizen(5008, "Junior", 31, "evening", 50, 2);        // and its jr. master

        Crew crew = formed();

        assertEquals(Set.of(5001, 5002, 5003, 5004), Set.copyOf(crew.members()));
        assertEquals("Ironclads", crew.name());
        assertEquals(GUILD, crew.guildId());
        assertEquals(CIRCLE, crew.circleId());
        assertEquals(ANN, crew.mainId());
        assertEquals(List.of(crew.leaderId()), ports.founders);
        assertEquals(3, ports.joined.size());
        for (int member : crew.members()) {
            assertEquals(CIRCLE, registry.get(member).circleId(), "the crew is a circle: they log in together");
            assertEquals(crew, crews.crewOf(member));
        }
        assertEquals(1, store.rows.size());
    }

    @Test
    void tooFewInBandFormsNothingYet() {
        citizen(5001, "Kim", 28, "evening");
        citizen(5002, "Lu", 33, "evening");
        citizen(5003, "Old", 50, "evening");

        crews.tick(NOW);

        assertNull(crews.crewOfAccount(ACCOUNT));
        assertTrue(ports.founders.isEmpty());
    }

    @Test
    void aMainBelowTheMinimumLevelHasNoCrew() {
        fourInBand();
        when(ann.getLevel()).thenReturn(15);
        store.mains.put(ACCOUNT, new int[]{ANN, 15});

        crews.tick(NOW);

        assertNull(crews.crewOfAccount(ACCOUNT));
    }

    @Test
    void theHighestCharacterOnlineBecomesTheMain() {
        fourInBand();
        Character alt = mock(Character.class);
        when(alt.getId()).thenReturn(11);
        when(alt.getAccountID()).thenReturn(ACCOUNT);
        when(alt.getLevel()).thenReturn(32);
        ports.online.add(alt);

        Crew crew = formed();

        assertEquals(11, crew.mainId());
    }

    // ---- drifting ------------------------------------------------------------------------------------

    @Test
    void membersOutOfBandLeaveAndCitizensInBandTakeTheirSeatsWithNoExpChange() {
        fourInBand();
        Crew crew = formed();
        Character kim = load(5001, 28, HENESYS);   // loaded: says it, then leaves
        Character lu = load(5002, 33, HENESYS);
        citizen(5010, "Ox", 40, "evening");
        citizen(5011, "Pia", 39, "evening");
        when(ann.getLevel()).thenReturn(38);       // Ann went up 8 levels: Kim (28) and Nia (30) are too far behind

        crews.tick(NOW + 31 * MINUTE);

        ArgumentCaptor<ChatterEngine.Event> said = ArgumentCaptor.forClass(ChatterEngine.Event.class);
        verify(chatter, atLeastOnce()).speakTo(said.capture());
        assertTrue(said.getAllValues().stream().anyMatch(event -> event.trigger().equals("rival.leave.behind")));
        ArgumentCaptor<ChatterEngine.Event> whispered = ArgumentCaptor.forClass(ChatterEngine.Event.class);
        verify(chatter, atLeastOnce()).compose(whispered.capture());
        assertTrue(whispered.getAllValues().stream().anyMatch(event -> event.trigger().equals("rival.leave.behind.whisper")));
        Crew now = crews.crewOfAccount(ACCOUNT);
        assertTrue(now.has(5001), "Kim leaves once its line is out");
        assertFalse(now.has(5004), "Nia was away: gone without a word");
        assertTrue(ports.left.contains(5004));
        assertEquals(0, registry.get(5004).circleId());
        assertTrue(now.members().size() >= 4, "refilled to four");

        crews.tick(NOW + 32 * MINUTE);

        Crew after = crews.crewOfAccount(ACCOUNT);
        assertFalse(after.has(5001));
        assertTrue(ports.left.contains(5001));
        for (Character agent : List.of(kim, lu)) {
            verify(agent, never()).gainExp(anyInt());
            verify(agent, never()).setLevel(anyInt());
        }
        assertEquals(28, store.progress.get(5001)[0], "nobody's level is touched");
        assertTrue(crew.id() > 0 && after.id() == crew.id(), "the same crew, new faces");
    }

    @Test
    void aLeavingMasterHandsTheGuildToAnotherMember() {
        fourInBand();
        Crew crew = formed();
        int leader = crew.leaderId();
        for (int member : crew.members()) {
            if (member != leader) {
                load(member, store.progress.get(member)[0], 0);
            }
        }
        store.progress.get(leader)[0] = 12; // far behind now, and away: it leaves silently

        crews.tick(NOW + 31 * MINUTE);

        assertEquals(1, ports.handOvers.size());
        assertEquals(leader, ports.handOvers.getFirst()[1]);
        Crew now = crews.crewOfAccount(ACCOUNT);
        assertEquals(ports.handOvers.getFirst()[2], now.leaderId());
        assertTrue(now.has(now.leaderId()));
        assertFalse(now.has(leader));
        assertTrue(ports.disbanded.isEmpty());
    }

    // ---- clears ---------------------------------------------------------------------------------------

    private Clear clear(int crewId, long durationMs, int... members) {
        List<Clear.Member> list = new ArrayList<>();
        for (int member : members) {
            list.add(new Clear.Member(member, member == ANN ? 'H' : 'C', member == ANN ? "Ann" : "c" + member));
        }
        return new Clear(1, BALROG, "b1", 1, 105100300, NOW - durationMs, NOW, durationMs,
                members.length > 0 && members[0] == ANN ? Clear.Kind.HUMAN : Clear.Kind.CITIZEN, crewId, list);
    }

    @Test
    void aClearIsTheCrewsWithTwoOfItsMembersAndNotItsPlayer() {
        fourInBand();
        Crew crew = formed();

        assertEquals(crew.id(), crews.creditFor(Set.of(5001, 5002, 6000)));
        assertEquals(0, crews.creditFor(Set.of(5001, 6000)), "one member is no crew run");
        assertEquals(0, crews.creditFor(Set.of(5001, 5002, ANN)), "a run with its player is no race");
    }

    @Test
    void beatingTheMainsTimeIsOneMegaphoneWhileTheyAreOn() {
        fourInBand();
        Crew crew = formed();
        load(5001, 28, 105100301);
        when(clears.playerBest(ANN, BALROG)).thenReturn(16 * MINUTE);
        when(chatter.megaphone(any(CitizenSession.class), anyString(), eq(false))).thenReturn(true);

        crews.cleared(clear(crew.id(), 14 * MINUTE, 5001, 5002), 0L, Map.of());

        ArgumentCaptor<ChatterEngine.Event> boast = ArgumentCaptor.forClass(ChatterEngine.Event.class);
        verify(chatter).compose(boast.capture());
        assertEquals("rival.beat", boast.getValue().trigger());
        assertEquals("14:00", boast.getValue().slots().get("time"));
        assertEquals("16:00", boast.getValue().slots().get("theirs"));
        assertEquals("Ironclads", boast.getValue().slots().get("crew"));
        ArgumentCaptor<CitizenSession> from = ArgumentCaptor.forClass(CitizenSession.class);
        verify(chatter, times(1)).megaphone(from.capture(), eq("a line"), eq(false)); // no megaphone stock in v1
        assertEquals(5001, from.getValue().characterId, "a member who was in the run");
        verify(chatter, never()).whisper(any(), any(), anyString(), anyString());
        verify(livingStore, never()).event(anyInt(), any(), anyString(), anyString());
    }

    @Test
    void withTheMegaphonesSpentTheNewsIsWhispered() {
        fourInBand();
        Crew crew = formed();
        load(5001, 28, 105100301);
        when(clears.playerBest(ANN, BALROG)).thenReturn(16 * MINUTE);
        when(chatter.megaphone(any(CitizenSession.class), anyString(), eq(false))).thenReturn(false); // the cap is spent

        crews.cleared(clear(crew.id(), 14 * MINUTE, 5001, 5002), 0L, Map.of());

        ArgumentCaptor<ChatterEngine.Event> line = ArgumentCaptor.forClass(ChatterEngine.Event.class);
        verify(chatter, times(2)).compose(line.capture());
        assertEquals(List.of("rival.beat", "rival.beat.whisper"),
                line.getAllValues().stream().map(ChatterEngine.Event::trigger).toList());
        verify(chatter).whisper(any(), eq(ann), eq("a line"), eq("rival"));
    }

    @Test
    void aSlowerCrewClearOrOneThatIsNotTheirBestIsNoBoast() {
        fourInBand();
        Crew crew = formed();
        load(5001, 28, 105100301);
        when(clears.playerBest(ANN, BALROG)).thenReturn(16 * MINUTE);

        crews.cleared(clear(crew.id(), 17 * MINUTE, 5001, 5002), 0L, Map.of());
        crews.cleared(clear(crew.id(), 15 * MINUTE, 5001, 5002), 13 * MINUTE, Map.of()); // they did 13 before

        verify(chatter, never()).megaphone(any(CitizenSession.class), anyString(), anyBoolean());
    }

    @Test
    void whileTheMainIsAwayTheCrewsClearsGoToTheirLetters() {
        fourInBand();
        Crew crew = formed();
        ports.online.clear();
        when(clears.playerBest(ANN, BALROG)).thenReturn(16 * MINUTE);

        crews.cleared(clear(crew.id(), 14 * MINUTE, 5001, 5002), 0L, Map.of());

        verify(livingStore).event(ANN, null, RivalCrews.NEWS_EVENT, "Ironclads|BalrogBattle_Easy|840000|960000");
        verify(chatter, never()).megaphone(any(CitizenSession.class), anyString(), anyBoolean());

        RivalCrews.Letter letter = crews.letter(ANN, "Ann", List.of("Ironclads|BalrogBattle_Easy|840000|960000"));
        assertEquals(registry.get(crew.leaderId()).name(), letter.sender());
        assertEquals("hey Ann, Ironclads here. while u were gone we did easy balrog in 14:00 (beat ur 16:00 :P)."
                + " catch up if u can lol", letter.text());
        assertTrue(letter.text().length() <= 200);
    }

    @Test
    void theMainBeatingTheCrewsTimeIsCongratulatedNowOrWhenAMemberIsOn() {
        fourInBand();
        Crew crew = formed();
        when(clears.crewBest(crew.id(), BALROG)).thenReturn(14 * MINUTE);

        crews.cleared(clear(0, 13 * MINUTE, ANN), 0L, Map.of(ANN, 15 * MINUTE));

        verify(chatter, never()).whisper(any(), any(), anyString(), anyString()); // nobody of the crew is on yet

        load(5002, 33, 0);
        crews.tick(NOW + MINUTE);

        ArgumentCaptor<ChatterEngine.Event> line = ArgumentCaptor.forClass(ChatterEngine.Event.class);
        verify(chatter, atLeastOnce()).compose(line.capture());
        assertTrue(line.getAllValues().stream().anyMatch(event -> event.trigger().equals("rival.congrats")
                && "13:00".equals(event.slots().get("time")) && "14:00".equals(event.slots().get("theirs"))));
        verify(chatter, times(1)).whisper(any(), eq(ann), eq("a line"), eq("rival"));

        crews.tick(NOW + 2 * MINUTE);
        verify(chatter, times(1)).whisper(any(), eq(ann), eq("a line"), eq("rival")); // said once
    }

    @Test
    void aMainsClearThatIsNotTheirBestIsNoNews() {
        fourInBand();
        Crew crew = formed();
        load(5002, 33, 0);
        when(clears.crewBest(crew.id(), BALROG)).thenReturn(14 * MINUTE);

        crews.cleared(clear(0, 13 * MINUTE + 30_000L, ANN), 0L, Map.of(ANN, 12 * MINUTE)); // she did 12 before

        verify(chatter, never()).whisper(any(), any(), anyString(), anyString());
    }

    // ---- being in touch -------------------------------------------------------------------------------

    @Test
    void aMemberOnTheMainsMapBantersOnceAGap() {
        fourInBand();
        formed();
        MapleMap map = mock(MapleMap.class);
        Client client = mock(Client.class);
        when(client.getChannel()).thenReturn(1);
        when(ann.getMap()).thenReturn(map);
        when(ann.getClient()).thenReturn(client);
        load(5003, 35, HENESYS);

        crews.tick(NOW + MINUTE);
        crews.tick(NOW + 2 * MINUTE);

        ArgumentCaptor<ChatterEngine.Event> said = ArgumentCaptor.forClass(ChatterEngine.Event.class);
        verify(chatter, atLeastOnce()).speakTo(said.capture());
        assertEquals(1, said.getAllValues().stream().filter(event -> event.trigger().equals("rival.banter")).count());

        crews.tick(NOW + 47 * MINUTE);

        ArgumentCaptor<ChatterEngine.Event> later = ArgumentCaptor.forClass(ChatterEngine.Event.class);
        verify(chatter, atLeastOnce()).speakTo(later.capture());
        assertEquals(2, later.getAllValues().stream().filter(event -> event.trigger().equals("rival.banter")).count());
    }

    /** A crew around Zakum's level, every member loaded in El Nath. */
    private Crew atZakum() {
        citizen(5001, "Kim", 54, "evening");
        citizen(5002, "Lu", 55, "evening");
        citizen(5003, "Mo", 58, "night");
        citizen(5004, "Nia", 52, "evening");
        when(ann.getLevel()).thenReturn(55);
        store.mains.put(ACCOUNT, new int[]{ANN, 55});
        Crew crew = formed();
        for (int member : crew.members()) {
            load(member, store.progress.get(member)[0], 211000000);
        }
        return crew;
    }

    @Test
    void aCrewAtZakumAsksItsMainAlongAsAGuestOnTheNeedsBoard() {
        config.RIVAL_HELP_REQUESTS_ENABLED = true;
        atZakum();

        crews.tick(NOW + MINUTE);
        crews.tick(NOW + 2 * MINUTE);

        verify(needs, times(1)).helpWanted(any(), eq("zakum"), eq(RivalCrews.ZAKUM_DOOR_MAP), eq(ANN),
                eq(NOW + MINUTE + 30 * MINUTE), eq(NOW + MINUTE));
        ArgumentCaptor<ChatterEngine.Event> line = ArgumentCaptor.forClass(ChatterEngine.Event.class);
        verify(chatter, atLeastOnce()).compose(line.capture());
        assertEquals(1, line.getAllValues().stream().filter(event -> event.trigger().equals("rival.help")).count());
        assertEquals(NOW + MINUTE, crews.crewOfAccount(ACCOUNT).helpAskedAtMs());
        assertEquals(NOW + MINUTE, store.rows.getFirst().helpAskedAtMs(), "stored: no second ask after a restart");
    }

    @Test
    void helpRequestsWaitForTheirOwnSwitch() {
        atZakum(); // RIVAL_HELP_REQUESTS_ENABLED off: there is no citizen Zakum run to invite anyone to yet

        crews.tick(NOW + MINUTE);

        verify(needs, never()).helpWanted(any(), anyString(), anyInt(), anyInt(), anyLong(), anyLong());
        verify(chatter, never()).whisper(any(), any(), anyString(), anyString());
    }

    @Test
    void aYesToTheHelpRequestIsSeeYouThereAndANoTakesItOffTheBoard() {
        config.RIVAL_HELP_REQUESTS_ENABLED = true;
        Crew crew = atZakum();
        crews.tick(NOW + MINUTE);
        CitizenSession asker = presence.session(crew.leaderId());

        assertTrue(crews.replied(asker, ann, "ya omw", NOW + 2 * MINUTE));
        assertTrue(crews.replied(asker, ann, "nah busy tonight", NOW + 3 * MINUTE));
        assertTrue(crews.replied(asker, ann, "ya actually", NOW + 4 * MINUTE)); // closed: just a reply

        ArgumentCaptor<ChatterEngine.Event> line = ArgumentCaptor.forClass(ChatterEngine.Event.class);
        verify(chatter, atLeastOnce()).compose(line.capture());
        assertEquals(List.of("rival.reply.yes", "rival.reply.no", "rival.reply"), line.getAllValues().stream()
                .map(ChatterEngine.Event::trigger).filter(trigger -> trigger.startsWith("rival.reply")).toList());
        verify(needs, times(1)).partyOver(crew.leaderId(), NOW + 3 * MINUTE);
    }

    @Test
    void aReplyToSomeoneElsesCrewIsNotOurs() {
        fourInBand();
        Crew crew = formed();
        CitizenSession member = CitizenSessions.loaded(registry.get(crew.members().getFirst()), 0L, NOW + 600 * MINUTE);
        Character stranger = mock(Character.class);
        when(stranger.getAccountID()).thenReturn(ACCOUNT + 1);
        CitizenSession outsider = CitizenSessions.loaded(citizen(5099, "Oz", 30, "evening"), 0L, NOW + 600 * MINUTE);

        assertFalse(crews.replied(member, stranger, "gg", NOW));
        assertFalse(crews.replied(outsider, ann, "gg", NOW));
        assertTrue(crews.replied(member, ann, "gg", NOW));
        verify(needs, never()).partyOver(anyInt(), anyLong());
    }

    @Test
    void repliesReadTheFirstYesOrNoWord() {
        assertEquals(RivalCrews.Answer.YES, RivalCrews.answer("ya omw"));
        assertEquals(RivalCrews.Answer.YES, RivalCrews.answer("Sure, I'm down"));
        assertEquals(RivalCrews.Answer.NO, RivalCrews.answer("nah busy"));
        assertEquals(RivalCrews.Answer.NO, RivalCrews.answer("can't, sorry"));
        assertEquals(RivalCrews.Answer.OTHER, RivalCrews.answer("lol gg"));
    }

    @Test
    void aCrewBelowZakumAsksNothing() {
        config.RIVAL_HELP_REQUESTS_ENABLED = true;
        fourInBand();
        Crew crew = formed();
        for (int member : crew.members()) {
            load(member, store.progress.get(member)[0], 0);
        }

        crews.tick(NOW + MINUTE);

        verify(needs, never()).helpWanted(any(), anyString(), anyInt(), anyInt(), anyLong(), anyLong());
    }

    @Test
    void theSwitchOffFormsAndSaysNothing() {
        fourInBand();
        config.RIVALS_ENABLED = false;

        crews.tick(NOW);

        assertNull(crews.crewOfAccount(ACCOUNT));
        assertEquals(0, crews.creditFor(Set.of(5001, 5002)));
        assertNull(crews.letter(ANN, "Ann", List.of("Ironclads|BalrogBattle_Easy|840000|960000")));
    }

    // ---- fakes ------------------------------------------------------------------------------------------

    static final class MemoryRivals extends RivalStore {
        final List<Crew> rows = new ArrayList<>();
        final Map<Integer, int[]> mains = new HashMap<>();
        final Map<Integer, int[]> progress = new HashMap<>();

        @Override
        public List<Crew> all() {
            return List.copyOf(rows);
        }

        @Override
        public int insert(Crew crew) {
            Crew stored = crew.withId(rows.size() + 1);
            rows.add(stored);
            return stored.id();
        }

        @Override
        public void update(Crew crew) {
            rows.replaceAll(row -> row.id() == crew.id() ? crew : row);
        }

        @Override
        public int[] mainOf(int accountId) {
            return mains.get(accountId);
        }

        @Override
        public Map<Integer, int[]> citizenProgress() {
            return progress;
        }
    }

    static final class FakePorts implements RivalCrews.Ports {
        final List<Character> online = new ArrayList<>();
        final List<Integer> founders = new ArrayList<>();
        final List<Integer> joined = new ArrayList<>();
        final List<Integer> left = new ArrayList<>();
        final List<int[]> handOvers = new ArrayList<>();
        final List<Integer> disbanded = new ArrayList<>();

        @Override
        public List<Character> onlineHumans() {
            return List.copyOf(online);
        }

        @Override
        public String freeName(Random random) {
            return "Ironclads";
        }

        @Override
        public int found(Citizen leader, String name) {
            founders.add(leader.characterId());
            return GUILD;
        }

        @Override
        public void join(Citizen member, int guildId) {
            joined.add(member.characterId());
        }

        @Override
        public void leave(Citizen member, int guildId) {
            left.add(member.characterId());
        }

        @Override
        public void handOver(int guildId, int fromId, int toId) {
            handOvers.add(new int[]{guildId, fromId, toId});
        }

        @Override
        public void disband(int guildId) {
            disbanded.add(guildId);
        }

        @Override
        public boolean guildExists(int guildId) {
            return guildId == GUILD && disbanded.isEmpty();
        }

        @Override
        public int circle(String name, String cohort) {
            return CIRCLE;
        }
    }
}
