package server.living.population;

import client.Character;
import config.PopulationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.expedition.boss.AgentBossRunHost;
import server.agents.capabilities.expedition.boss.AgentBossRunMember;
import server.agents.capabilities.expedition.boss.AgentBossRunService;
import server.agents.capabilities.expedition.boss.AgentBossRunSession;
import server.living.population.PopulationContent.Lobby;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static server.living.population.CitizenLobbyFixture.ids;

/**
 * Citizens run Balrog themselves (docs/CITIZEN_EXPEDITIONS_PLAN.md): at Mu Young's room they form a party as at a PQ
 * lobby, their leader makes the list (CitizenBossDoor, here with a fake boss-run engine), the population asks the run
 * to start once its citizens and humans are listed, and the run's reports bring them back to their day. Player first:
 * no list while a Balrog list or fight is open on the channel or a human who could lead one stands in her room; one
 * citizen expedition in all; a citizen short of the boss's potions does not sign up.
 */
class CitizenBossRunsTest {
    private static final long MINUTE = 60_000L;
    private static final Lobby BALROG = new Lobby("balrog", 105_100_100, 50, 120, "Balrog");
    private static final long SESSION = 40L;

    private final CitizenLobbyFixture lobby = new CitizenLobbyFixture(BALROG);
    private final Engine engine = new Engine();
    private final CitizenBossDoor door = new CitizenBossDoor(lobby.config, engine);
    private final CitizenPartyQuests quests = lobby.quests(door);

    @AfterEach
    void tearDown() {
        lobby.close();
    }

    @Test
    void citizensAtMuYoungFormAPartyAndTheirLeaderMakesTheList() {
        lobby.config.BOSS_RUN_SEATS = 3;
        Character lia = lobby.citizen(1, "Lia", 70);
        Character mira = lobby.citizen(2, "Mira", 65);
        Character pip = lobby.citizen(3, "Pip", 72);

        quests.tick(0L);

        assertEquals(List.of("easy_balrog " + ids(lia, mira, pip)), engine.opened);
        assertEquals(ids(lia, mira, pip), lobby.parties.memberIds(lia));
        assertEquals(List.of("boss.lf", "boss.join", "boss.join"), lobby.triggers());
        assertEquals(List.of("hold Lia", "hold Mira", "hold Pip"), lobby.asked);
        for (Character citizen : List.of(lia, mira, pip)) {
            assertTrue(quests.holds(citizen.getId()), citizen.getName());
        }
        assertTrue(lobby.session(mira).journal.summary(200).contains("signed up for Easy Balrog with Lia, Pip"));
        assertTrue(quests.status().contains("easy_balrog 1 runs 0 cleared 0 failed"), quests.status());
        assertTrue(engine.starts.isEmpty(), "no Start before everyone is on the list");
    }

    @Test
    void theLeaderStartsOnceEveryCitizenIsListedAndOnlyOnce() {
        List<Character> crew = threeOpenedTheList();

        quests.tick(10_000L);
        assertTrue(engine.starts.isEmpty(), "still walking up to her");

        engine.signUp(crew);
        quests.tick(20_000L);
        assertEquals(List.of(SESSION), engine.starts);
        assertEquals(1, lobby.count("boss.go"));

        quests.tick(30_000L);
        assertEquals(List.of(SESSION), engine.starts, "asked once; her Start is the run's business from here");
    }

    @Test
    void aHumanOfThePartyIsToldToSignUpAndTheStartWaitsForThem() {
        lobby.config.BOSS_RUN_SEATS = 3;
        Character lia = lobby.citizen(1, "Lia", 70);
        Character mira = lobby.citizen(2, "Mira", 65);
        Character ann = lobby.human(10, "Ann", 60);
        quests.tick(0L);
        assertTrue(lobby.parties.hasPendingPartyInvite(ann.getId()), "the human is invited before the last seat goes");
        lobby.parties.accept(ann);
        quests.tick(10_000L);

        assertEquals(List.of("easy_balrog " + ids(lia, mira)), engine.opened, "she signs herself up");
        assertEquals(1, lobby.said.stream().filter(event -> event.trigger.equals("boss.signup")
                && "Ann".equals(event.subjectName)).count());
        engine.signUp(List.of(lia, mira));
        quests.tick(20_000L);
        assertTrue(engine.starts.isEmpty(), "not while Ann is still off the list");

        engine.listed.add(SESSION + ":" + ann.getId());
        quests.tick(30_000L);
        assertEquals(List.of(SESSION), engine.starts);
    }

    @Test
    void aHumanWhoNeverSignsUpIsNotWaitedForPastTheSignUpWait() {
        lobby.config.BOSS_RUN_SEATS = 3;
        Character lia = lobby.citizen(1, "Lia", 70);
        Character mira = lobby.citizen(2, "Mira", 65);
        Character ann = lobby.human(10, "Ann", 60);
        quests.tick(0L);
        lobby.parties.accept(ann);
        quests.tick(10_000L);
        engine.signUp(List.of(lia, mira));

        quests.tick(10_000L + lobby.config.BOSS_SIGNUP_WAIT_MS - 1_000L);
        assertTrue(engine.starts.isEmpty());
        quests.tick(10_000L + lobby.config.BOSS_SIGNUP_WAIT_MS);

        assertEquals(List.of(SESSION), engine.starts);
    }

    @Test
    void noListWhileABalrogListOrFightIsOpenOnTheChannel() {
        lobby.config.BOSS_RUN_SEATS = 3;
        engine.expeditionOpen = true;
        lobby.citizen(1, "Lia", 70);
        lobby.citizen(2, "Mira", 65);
        lobby.citizen(3, "Pip", 72);

        quests.tick(0L);
        quests.tick(MINUTE);

        assertTrue(engine.opened.isEmpty());
    }

    @Test
    void aHumanWhoCouldLeadOneInHerRoomKeepsThemOutAndOneTooLowDoesNot() {
        lobby.config.BOSS_RUN_SEATS = 3;
        lobby.citizen(1, "Lia", 70);
        lobby.citizen(2, "Mira", 65);
        lobby.citizen(3, "Pip", 72);
        Character ann = lobby.human(10, "Ann", 85);
        lobby.parties.lead(ann); // her own party: she may be gathering one
        lobby.human(11, "Bo", 30); // below Mu Young's gate

        quests.tick(0L);
        quests.tick(MINUTE);
        assertTrue(engine.opened.isEmpty(), "Ann may want to lead one herself");

        lobby.away(ann, 105_000_000);
        quests.tick(2 * MINUTE);
        assertEquals(1, engine.opened.size());
    }

    @Test
    void oneCitizenExpeditionInAll() {
        threeOpenedTheList();
        lobby.citizen(4, "Kai", 80);
        lobby.citizen(5, "Ivy", 81);
        lobby.citizen(6, "Rex", 82);

        quests.tick(10_000L);
        quests.tick(MINUTE);

        assertEquals(1, engine.opened.size(), "BOSS_RUNS_MAX 1");
        assertEquals(3, lobby.parties.memberIds(lobby.sessions.get(4).agent()).size(), "the second party waits");
    }

    @Test
    void aCitizenThatCannotAffordThePotionsSaysSoAndGoes() {
        lobby.config.BOSS_RUN_SEATS = 3;
        Character lia = lobby.citizen(1, "Lia", 70);
        lobby.citizen(2, "Mira", 65);
        Character pip = lobby.citizen(3, "Pip", 72);
        engine.broke.add(pip.getId());

        quests.tick(0L);
        quests.tick(10_000L);

        assertTrue(engine.opened.isEmpty(), "two are not enough");
        assertEquals(1, lobby.count("boss.broke"));
        assertTrue(lobby.asked.contains("replan Pip"));
        assertTrue(quests.coolingDown(pip.getId(), 5 * MINUTE));
        assertTrue(lobby.session(pip).journal.summary(200).contains("could not afford potions"));
        assertEquals(1L, engine.provisioned.stream().filter(line -> line.startsWith(lia.getId() + " ")).count(),
                "one that bought them is not sent to the store again this outing");
    }

    @Test
    void aDownedCitizenSaysSoLeavesThePartyAndTheRunGoesOn() {
        List<Character> crew = threeOpenedTheList();
        Character lia = crew.get(0);
        Character pip = crew.get(2);
        lobby.said.clear();
        lobby.asked.clear();

        quests.bossHost().memberOut(seat(pip), AgentBossRunHost.Out.DIED);
        quests.tick(5 * MINUTE);

        assertTrue(lobby.triggers().contains("boss.down"));
        assertFalse(lobby.parties.memberIds(lia).contains(pip.getId()));
        assertTrue(lobby.asked.contains("replan Pip"));
        assertFalse(quests.holds(pip.getId()));
        assertTrue(quests.holds(lia.getId()), "the fight goes on without him");
        assertTrue(lobby.session(pip).journal.summary(200).contains("went down fighting Easy Balrog"));
    }

    @Test
    void aClearIsBookedTalkedAboutAndEveryoneGoesBackToTheirDay() {
        lobby.config.BOSS_RUN_SEATS = 3;
        Character lia = lobby.citizen(1, "Lia", 70);
        Character mira = lobby.citizen(2, "Mira", 65);
        Character ann = lobby.human(10, "Ann", 60);
        quests.tick(0L);
        lobby.parties.accept(ann);
        quests.tick(10_000L);
        engine.signUp(List.of(lia, mira));
        engine.listed.add(SESSION + ":" + ann.getId());
        quests.tick(20_000L);
        engine.phases.put(SESSION, AgentBossRunSession.Phase.FIGHTING);
        for (Character citizen : List.of(lia, mira)) {
            lobby.inInstance(citizen, true);
        }
        lobby.said.clear();
        lobby.asked.clear();

        quests.bossHost().runEnded(ended(true, true, "members-out"));
        engine.phases.remove(SESSION);
        for (Character citizen : List.of(lia, mira)) {
            lobby.inInstance(citizen, false);
            lobby.away(citizen, BALROG.mapId());
        }
        quests.tick(30 * MINUTE);

        assertTrue(lobby.triggers().contains("boss.clear"));
        assertEquals(1, lobby.said.stream().filter(event -> event.trigger.equals("boss.thanks")
                && "Ann".equals(event.subjectName)).count());
        assertTrue(lobby.session(mira).journal.summary(200).contains("cleared Easy Balrog with Lia, Ann"));
        assertTrue(quests.status().contains("easy_balrog 1 runs 1 cleared 0 failed"), quests.status());
        assertTrue(quests.recentRuns().getFirst().startsWith("Easy Balrog cleared in "), quests.recentRuns().toString());
        assertEquals(Set.of("replan Lia", "replan Mira"), Set.copyOf(lobby.asked));
        assertFalse(quests.holds(mira.getId()));
        assertTrue(quests.coolingDown(mira.getId(), 30 * MINUTE + lobby.config.PQ_RUN_COOLDOWN_MS),
                "an expedition keeps them out longer than a party quest");
        assertFalse(quests.coolingDown(mira.getId(), 30 * MINUTE + lobby.config.BOSS_RUN_COOLDOWN_MS));
        assertTrue(lobby.parties.memberIds(ann).isEmpty(), "the expedition's party broke up after it");
    }

    @Test
    void aListThatClosedWithoutAStartIsBookedAsAFailure() {
        threeOpenedTheList();

        quests.bossHost().runEnded(ended(false, false, "disbanded"));
        quests.tick(6 * MINUTE);

        assertTrue(lobby.triggers().contains("boss.fail"));
        assertTrue(quests.recentRuns().getFirst().contains("the list closed (disbanded)"),
                quests.recentRuns().toString());
        assertTrue(quests.status().contains("easy_balrog 1 runs 0 cleared 1 failed"), quests.status());
    }

    @Test
    void theKillSwitchOrABossTheServerDoesNotMoveKeepsThemFromSigningUp() {
        lobby.config.BOSS_RUN_SEATS = 3;
        lobby.config.BOSS_RUNS_ENABLED = false;
        Character lia = lobby.citizen(1, "Lia", 70);
        lobby.citizen(2, "Mira", 65);
        lobby.citizen(3, "Pip", 72);

        quests.tick(0L);
        assertTrue(engine.opened.isEmpty());
        assertTrue(lobby.parties.memberIds(lia).isEmpty());
        assertTrue(lobby.said.isEmpty());

        lobby.config.BOSS_RUNS_ENABLED = true;
        engine.carried.clear(); // living_world.COMPANION_BOSS_SERVER_AUTHORITY off, or Balrog not switched on
        quests.tick(MINUTE);
        assertTrue(engine.opened.isEmpty());
        assertFalse(door.enabled());
    }

    // ---- the door on its own ---------------------------------------------------------------------

    @Test
    void sixAtTheNormalLevelWithNormalsPotionsGoForNormalElseEasy() {
        List<Character> six = new ArrayList<>();
        for (int id = 1; id <= 6; id++) {
            six.add(lobby.citizen(id, "C" + id, 85));
        }

        assertEquals("balrog_normal", door.bossFor(six));
        engine.broke.add(3);
        assertEquals("easy_balrog", door.bossFor(six), "one short of Normal's potions takes them to Easy");
        engine.broke.clear();
        lobby.config.BOSS_RUN_BOSSES = "easy_balrog";
        assertEquals("easy_balrog", door.bossFor(six));
        lobby.config.BOSS_RUN_BOSSES = "easy_balrog,balrog_normal";
        assertEquals("easy_balrog", door.bossFor(six.subList(0, 5)), "Normal takes six");
        assertNull(door.bossFor(six.subList(0, 2)), "Easy takes three");
        six.set(5, lobby.citizen(7, "Low", 79));
        assertEquals("easy_balrog", door.bossFor(six));
    }

    @Test
    void itHoldsAMemberWhileTheRunHasItAndReadsTheRunsStage() {
        String run = CitizenBossDoor.sessionKey(SESSION);
        engine.phases.put(SESSION, AgentBossRunSession.Phase.REGISTERING);
        engine.members.put(SESSION + ":1", AgentBossRunMember.Phase.INSIDE);
        engine.members.put(SESSION + ":2", AgentBossRunMember.Phase.OUT);
        engine.members.put(SESSION + ":3", AgentBossRunMember.Phase.REFUSED);

        assertTrue(door.holds(1, run));
        assertFalse(door.holds(2, run));
        assertFalse(door.holds(3, run));
        assertFalse(door.holds(4, run));
        assertEquals(CitizenPqDoor.ExpeditionDoor.Stage.SIGNING_UP, door.stage(run));
        engine.phases.put(SESSION, AgentBossRunSession.Phase.POST_CLEAR);
        assertEquals(CitizenPqDoor.ExpeditionDoor.Stage.STARTED, door.stage(run));
        engine.phases.put(SESSION, AgentBossRunSession.Phase.ENDED);
        assertEquals(CitizenPqDoor.ExpeditionDoor.Stage.GONE, door.stage(run));
        assertEquals(CitizenPqDoor.ExpeditionDoor.Stage.GONE, door.stage("kpq-run-1"));
        assertEquals(12L, CitizenBossDoor.sessionNumber("boss:12"));
        assertEquals(0L, CitizenBossDoor.sessionNumber("boss:x"));
        assertEquals(0L, CitizenBossDoor.sessionNumber("kpq-run-1"));
    }

    @Test
    void bossRunBossesNamesOnlyBossesTheServerMoves() {
        PopulationConfig config = new PopulationConfig();
        config.BOSS_RUN_BOSSES = "easy_balrog, zakum";
        assertThrows(IllegalArgumentException.class, config::validate);
        config.BOSS_RUN_BOSSES = " Easy_Balrog ,balrog_normal";
        config.validate();
        assertEquals(Set.of("easy_balrog", "balrog_normal"), config.citizenBosses());
        config.BOSS_RUNS_ENABLED = false;
        assertEquals(Set.of(), config.citizenBosses());
        config.BOSS_RUN_SEATS = 2;
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /** Lia, Mira and Pip (Lv65-72) formed a party of three at Mu Young and Lia made the list. */
    private List<Character> threeOpenedTheList() {
        lobby.config.BOSS_RUN_SEATS = 3;
        List<Character> crew = List.of(lobby.citizen(1, "Lia", 70), lobby.citizen(2, "Mira", 65),
                lobby.citizen(3, "Pip", 72));
        quests.tick(0L);
        assertEquals(1, engine.opened.size());
        return crew;
    }

    private static AgentBossRunHost.Seat seat(Character member) {
        return new AgentBossRunHost.Seat("easy_balrog", SESSION, member.getId(), 1);
    }

    private static AgentBossRunHost.RunEnded ended(boolean started, boolean cleared, String reason) {
        return new AgentBossRunHost.RunEnded(SESSION, "easy_balrog", started, cleared, reason, 1L, 2L, 3, List.of());
    }

    /** The boss runs as the door sees them: a list each open call makes, phases the test sets. */
    private static final class Engine implements CitizenBossDoor.Engine {
        final Set<String> carried = new HashSet<>(Set.of("easy_balrog", "balrog_normal"));
        boolean expeditionOpen;
        /** Each list made: "easy_balrog [1, 2, 3]". */
        final List<String> opened = new ArrayList<>();
        final Map<Long, AgentBossRunSession.Phase> phases = new HashMap<>();
        /** "session:character" -> its phase in the run. */
        final Map<String, AgentBossRunMember.Phase> members = new HashMap<>();
        /** "session:character" of the humans on a run's list. */
        final Set<String> listed = new HashSet<>();
        final List<Long> starts = new ArrayList<>();
        final Set<Integer> broke = new HashSet<>();
        /** Each store visit: "character boss". */
        final List<String> provisioned = new ArrayList<>();
        private long nextSession = SESSION;

        /** Every member of the open run is on the list now. */
        void signUp(List<Character> citizens) {
            citizens.forEach(citizen -> members.put(SESSION + ":" + citizen.getId(),
                    AgentBossRunMember.Phase.REGISTERED));
        }

        @Override
        public boolean carried(String bossKey) {
            return carried.contains(bossKey);
        }

        @Override
        public boolean expeditionOpen(Character anchor) {
            return expeditionOpen; // the other difficulty's list may be open while one registers: the cap decides
        }

        @Override
        public AgentBossRunService.CitizenOpened open(Character leader, String bossKey, List<Integer> citizenIds,
                                                     long nowMs) {
            long id = nextSession++;
            opened.add(bossKey + " " + citizenIds);
            phases.put(id, AgentBossRunSession.Phase.REGISTERING);
            citizenIds.forEach(citizen -> members.put(id + ":" + citizen, AgentBossRunMember.Phase.WAITING));
            return new AgentBossRunService.CitizenOpened(id, null, citizenIds, List.of(), "");
        }

        @Override
        public boolean start(long sessionId) {
            if (phases.get(sessionId) != AgentBossRunSession.Phase.REGISTERING) {
                return false;
            }
            starts.add(sessionId);
            return true;
        }

        @Override
        public AgentBossRunSession.Phase phase(long sessionId) {
            return phases.get(sessionId);
        }

        @Override
        public AgentBossRunMember.Phase memberPhase(long sessionId, int characterId) {
            return members.get(sessionId + ":" + characterId);
        }

        @Override
        public boolean listed(long sessionId, int characterId) {
            return listed.contains(sessionId + ":" + characterId);
        }

        @Override
        public boolean busy(int characterId) {
            return false;
        }

        @Override
        public boolean provision(Character agent, String bossKey) {
            provisioned.add(agent.getId() + " " + bossKey);
            return !broke.contains(agent.getId());
        }

        @Override
        public boolean supplied(Character agent, String bossKey) {
            return !broke.contains(agent.getId());
        }
    }
}
