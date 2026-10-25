package server.living.population;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.AgentPartyQuestHandback;
import server.agents.monitoring.AgentSchedulerMetrics;
import server.agents.runtime.scheduler.AgentLoadSheddingLevel;
import server.agents.runtime.scheduler.AgentLoadSheddingState;
import server.living.population.CitizenSession.Activity;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static server.living.population.CitizenLobbyFixture.ids;

/**
 * Citizens run party quests themselves (docs/CITIZEN_PARTY_QUESTS_PLAN.md): at Kerning City's KPQ lobby they form a
 * party, go in through the quest's admission, book the run and go back to their day; a human who takes their invite
 * runs it with them. Player first: a human gets the seat before a citizen does, a human party waiting or a human
 * leading keeps the citizens out, and the caps, the scheduler and the switch bound it all.
 */
class CitizenPartyQuestsTest {
    private static final long MINUTE = 60_000L;

    private final CitizenLobbyFixture lobby = new CitizenLobbyFixture();
    private final CitizenLobbyFixture.Door door = new CitizenLobbyFixture.Door();
    private final CitizenPartyQuests quests = lobby.quests(door);

    @AfterEach
    void tearDown() {
        AgentSchedulerMetrics.clearLoadSheddingShard(901);
        lobby.close();
    }

    @Test
    void fourCitizensAtLakelisFormAPartyAndGoInLedByTheFirst() {
        Character lia = lobby.citizen(1, "Lia", 25);
        Character mira = lobby.citizen(2, "Mira", 22);
        Character pip = lobby.citizen(3, "Pip", 28);
        Character tobi = lobby.citizen(4, "Tobi", 30);

        quests.tick(0L);

        assertEquals(List.of(ids(lia, mira, pip, tobi)), door.admitted);
        assertEquals(ids(lia, mira, pip, tobi), lobby.parties.memberIds(lia));
        for (Character citizen : List.of(lia, mira, pip, tobi)) {
            assertTrue(quests.holds(citizen.getId()), citizen.getName());
            assertEquals(AgentPartyQuestHandback.Handback.RELEASE, quests.handback(citizen.getId()));
        }
        assertEquals(List.of("hold Lia", "hold Mira", "hold Pip", "hold Tobi"), lobby.asked);
        assertEquals(List.of("pq.lf", "pq.join", "pq.join", "pq.join", "pq.go"), lobby.triggers());
        assertTrue(lobby.session(mira).journal.summary(200).contains("went into KPQ with Lia, Pip, Tobi"));
        assertTrue(quests.status().startsWith("pq runs: 1 in, 0 forming; kpq 1 runs 0 cleared 0 failed"), quests.status());
    }

    @Test
    void threeWaitForAFourthAWhileAndThenGoInShort() {
        Character lia = lobby.citizen(1, "Lia", 25);
        Character mira = lobby.citizen(2, "Mira", 22);
        Character pip = lobby.citizen(3, "Pip", 28);

        quests.tick(0L);
        quests.tick(MINUTE);
        assertTrue(door.admitted.isEmpty());

        quests.tick(CitizenPartyQuests.FILL_WAIT_MS);

        assertEquals(List.of(ids(lia, mira, pip)), door.admitted);
        assertTrue(lobby.count("pq.lf") >= 1);
    }

    @Test
    void aHumanAtLakelisIsInvitedFirstAndTheSeatWaitsForTheAnswer() {
        Character lia = lobby.citizen(1, "Lia", 25);
        Character mira = lobby.citizen(2, "Mira", 22);
        Character pip = lobby.citizen(3, "Pip", 28);
        Character tobi = lobby.citizen(4, "Tobi", 26);
        Character ann = lobby.human(10, "Ann", 24);

        quests.tick(0L);

        // Lia invited Ann, and of the three other citizens only two got a seat: the fourth is Ann's.
        assertTrue(lobby.parties.hasPendingPartyInvite(ann.getId()));
        assertEquals(1, lobby.count("party.invite"));
        assertEquals(3, lobby.parties.memberIds(lia).size());
        assertTrue(lobby.parties.memberIds(tobi).isEmpty());

        quests.tick(30_000L);
        assertTrue(door.admitted.isEmpty(), "never in while the invite to a human is out");
        assertTrue(lobby.parties.memberIds(tobi).isEmpty());

        lobby.parties.accept(ann);
        quests.tick(40_000L);

        assertEquals(List.of(List.of(lia.getId(), mira.getId(), pip.getId(), ann.getId())), door.admitted);
        assertFalse(quests.holds(ann.getId()), "the human is nobody's to hold");
        assertTrue(lobby.session(lia).journal.summary(200).contains("Ann"));
    }

    @Test
    void aDeclinedInviteGivesTheSeatToTheNextCitizen() {
        Character lia = lobby.citizen(1, "Lia", 25);
        lobby.citizen(2, "Mira", 22);
        lobby.citizen(3, "Pip", 28);
        Character tobi = lobby.citizen(4, "Tobi", 26);
        Character ann = lobby.human(10, "Ann", 24);
        quests.tick(0L);

        lobby.parties.decline(ann);
        quests.tick(10_000L);

        assertEquals(1, door.admitted.size());
        assertTrue(door.admitted.getFirst().contains(tobi.getId()));
        assertFalse(door.admitted.getFirst().contains(ann.getId()));
        assertEquals(1, lobby.count("party.invite"), "Ann is not asked again at once");
        assertTrue(lobby.parties.memberIds(lia).contains(tobi.getId()));
    }

    @Test
    void aHumanWhoDoesNotAnswerLosesTheSeatOnceTheHoldIsOver() {
        lobby.citizen(1, "Lia", 25);
        lobby.citizen(2, "Mira", 22);
        lobby.citizen(3, "Pip", 28);
        Character tobi = lobby.citizen(4, "Tobi", 26);
        Character ann = lobby.human(10, "Ann", 24);
        quests.tick(0L);

        quests.tick(lobby.config.PQ_HUMAN_SEAT_HOLD_MS - 1_000L);
        assertTrue(door.admitted.isEmpty());
        quests.tick(lobby.config.PQ_HUMAN_SEAT_HOLD_MS);

        assertEquals(1, door.admitted.size());
        assertTrue(door.admitted.getFirst().contains(tobi.getId()));
        assertFalse(door.admitted.getFirst().contains(ann.getId()));
        assertFalse(lobby.parties.hasPendingPartyInvite(ann.getId()), "a late yes cannot make a fifth");
    }

    @Test
    void aHumanOutOfTheBandIsNeverInvited() {
        lobby.citizen(1, "Lia", 25);
        lobby.citizen(2, "Mira", 22);
        lobby.citizen(3, "Pip", 28);
        lobby.citizen(4, "Tobi", 26);
        Character ann = lobby.human(10, "Ann", 45);

        quests.tick(0L);

        assertFalse(lobby.parties.hasPendingPartyInvite(ann.getId()));
        assertEquals(1, door.admitted.size());
    }

    @Test
    void aHumanPartyWaitingAtLakelisKeepsTheCitizensOut() {
        lobby.citizen(1, "Lia", 25);
        lobby.citizen(2, "Mira", 22);
        lobby.citizen(3, "Pip", 28);
        lobby.citizen(4, "Tobi", 26);
        Character ann = lobby.human(10, "Ann", 24);
        Character bo = lobby.human(11, "Bo", 25);
        lobby.parties.lead(ann, bo);

        quests.tick(0L);

        assertTrue(door.admitted.isEmpty());
        assertEquals(1, door.lastHumanPartiesWaiting);
        assertFalse(lobby.parties.hasPendingPartyInvite(ann.getId()), "a human in a party is filling their own");
    }

    @Test
    void citizensInAPartyAHumanLeadsAreTheHumansBusiness() {
        Character ann = lobby.human(10, "Ann", 24);
        Character mira = lobby.citizen(2, "Mira", 22);
        Character pip = lobby.citizen(3, "Pip", 28);
        Character tobi = lobby.citizen(4, "Tobi", 26);
        lobby.parties.lead(ann, mira, pip, tobi);

        quests.tick(0L);
        quests.tick(CitizenPartyQuests.FILL_WAIT_MS * 2);

        assertTrue(door.admitted.isEmpty());
        assertEquals(List.of(ann.getId(), mira.getId(), pip.getId(), tobi.getId()), lobby.parties.memberIds(ann));
        assertEquals(0, lobby.count("pq.lf"));
    }

    @Test
    void oneCitizenRunAChannelAtATime() {
        lobby.citizen(1, "Lia", 25);
        lobby.citizen(2, "Mira", 22);
        lobby.citizen(3, "Pip", 28);
        lobby.citizen(4, "Tobi", 26);
        quests.tick(0L);
        for (int id = 5; id <= 8; id++) {
            lobby.citizen(id, "Late" + id, 25);
        }

        quests.tick(10_000L);

        assertEquals(1, door.admitted.size());
        assertEquals(4, lobby.parties.memberIds(lobby.sessions.get(5).agent()).size(), "the second party forms");
    }

    @Test
    void theSwitchOffKeepsEveryoneOut() {
        lobby.config.PQ_RUNS_ENABLED = false;
        Character lia = lobby.citizen(1, "Lia", 25);
        lobby.citizen(2, "Mira", 22);
        lobby.citizen(3, "Pip", 28);

        quests.tick(0L);

        assertTrue(door.admitted.isEmpty());
        assertTrue(lobby.parties.memberIds(lia).isEmpty());
        assertTrue(lobby.said.isEmpty());
        assertNull(quests.waitingSpot(lobby.session(lia), lobby.lobbyMap), "they stroll the town as before");
    }

    @Test
    void aSchedulerSheddingLoadKeepsThePartyWaiting() {
        lobby.citizen(1, "Lia", 25);
        lobby.citizen(2, "Mira", 22);
        lobby.citizen(3, "Pip", 28);
        lobby.citizen(4, "Tobi", 26);
        AgentSchedulerMetrics.recordLoadSheddingState(901,
                new AgentLoadSheddingState(AgentLoadSheddingLevel.values()[1], Set.of(), 0L, 1L));

        quests.tick(0L);
        assertTrue(door.admitted.isEmpty());

        AgentSchedulerMetrics.clearLoadSheddingShard(901);
        quests.tick(10_000L);
        assertEquals(1, door.admitted.size());
    }

    @Test
    void aHumanMemberTooHighHearsWhyAndThePartyWaits() {
        lobby.citizen(1, "Lia", 25);
        lobby.citizen(2, "Mira", 22);
        lobby.citizen(3, "Pip", 28);
        Character ann = lobby.human(10, "Ann", 24);
        quests.tick(0L);
        lobby.parties.accept(ann);
        door.problems.put(ann.getId(), "Ann must be level 21-30 for KPQ");

        quests.tick(10_000L);
        quests.tick(20_000L);

        assertTrue(door.admitted.isEmpty());
        List<ChatterEngine.Event> told = lobby.said.stream().filter(event -> event.trigger.equals("pq.wait")).toList();
        assertEquals(1, told.size());
        assertEquals("{name} kpq is lv21-30 only, sorry", told.getFirst().text);
    }

    @Test
    void aClearIsBookedTalkedAboutAndEveryoneGoesBackToTheirDay() {
        Character lia = lobby.citizen(1, "Lia", 25);
        Character mira = lobby.citizen(2, "Mira", 22);
        Character pip = lobby.citizen(3, "Pip", 28);
        Character ann = lobby.human(10, "Ann", 24);
        quests.tick(0L);
        lobby.parties.accept(ann);
        quests.tick(10_000L);
        assertEquals(1, door.admitted.size());
        for (Character member : List.of(lia, mira, pip)) {
            lobby.inInstance(member, true);
        }
        lobby.said.clear();
        lobby.asked.clear();

        assertTrue(quests.runEnded(new AgentPartyQuestHandback.RunEnded("kpq", "kpq-run-1", lia.getId(), true,
                "completed", 20 * MINUTE)));
        door.release("kpq-run-1");
        quests.tick(20 * MINUTE);

        assertTrue(lobby.triggers().contains("pq.clear"));
        assertEquals(1, lobby.said.stream().filter(event -> event.trigger.equals("pq.thanks")
                && "Ann".equals(event.subjectName)).count());
        assertTrue(lobby.session(pip).journal.summary(200).contains("cleared KPQ with Lia, Mira, Ann"));
        assertTrue(quests.status().contains("kpq 1 runs 1 cleared 0 failed"), quests.status());
        assertTrue(quests.recentRuns().getFirst().startsWith("KPQ cleared in "));
        assertTrue(quests.coolingDown(mira.getId(), 21 * MINUTE));
        assertTrue(quests.holds(mira.getId()), "still in the instance: on its way back");
        assertTrue(lobby.asked.isEmpty(), "nobody moves while still inside");

        for (Character member : List.of(lia, mira, pip)) {
            lobby.inInstance(member, false);
            lobby.away(member, 103_000_890);
        }
        quests.tick(20 * MINUTE + 10_000L);

        assertEquals(Set.of("replan Lia", "replan Mira", "replan Pip"), Set.copyOf(lobby.asked));
        assertEquals(3, lobby.asked.size());
        assertFalse(quests.holds(mira.getId()));
        assertNull(quests.handback(mira.getId()));
        assertTrue(lobby.parties.memberIds(ann).isEmpty(), "the quest's party broke up after it");
        assertFalse(quests.coolingDown(mira.getId(), 20 * MINUTE + 10_000L + lobby.config.PQ_RUN_COOLDOWN_MS));
    }

    @Test
    void aSessionThatRanOutDuringTheRunLogsOffAfterIt() {
        Character lia = lobby.citizen(1, "Lia", 25);
        lobby.citizen(2, "Mira", 22);
        lobby.citizen(3, "Pip", 28);
        lobby.citizen(4, "Tobi", 26);
        quests.tick(0L);
        lobby.session(lia).endsAtMs = 5 * MINUTE;

        quests.runEnded(new AgentPartyQuestHandback.RunEnded("kpq", "kpq-run-1", lia.getId(), false,
                "No KPQ progress for 15 minutes in stage 1", 20 * MINUTE));
        quests.tick(20 * MINUTE);

        assertTrue(lobby.asked.contains("logout Lia"));
        assertTrue(lobby.asked.contains("replan Mira"));
        assertTrue(lobby.triggers().contains("pq.fail"));
        assertTrue(quests.status().contains("kpq 1 runs 0 cleared 1 failed"));
    }

    @Test
    void aDownedCitizenTapsOutLeavesThePartyAndGoesOn() {
        Character lia = lobby.citizen(1, "Lia", 25);
        Character mira = lobby.citizen(2, "Mira", 22);
        lobby.citizen(3, "Pip", 28);
        lobby.citizen(4, "Tobi", 26);
        quests.tick(0L);

        assertFalse(quests.tapOut(new AgentPartyQuestHandback.TapOut("kpq", "kpq-run-1", lia.getId(), 103_000_890, 1L)),
                "the leader's death ends the run");
        assertFalse(quests.tapOut(new AgentPartyQuestHandback.TapOut("kpq", "kpq-other", mira.getId(), 103_000_890, 1L)));
        assertTrue(quests.tapOut(new AgentPartyQuestHandback.TapOut("kpq", "kpq-run-1", mira.getId(), 103_000_890, 1L)));
        lobby.away(mira, 103_000_890);
        quests.tick(5 * MINUTE);

        assertFalse(lobby.parties.memberIds(lia).contains(mira.getId()));
        assertTrue(lobby.triggers().contains("pq.down"));
        assertTrue(lobby.asked.contains("replan Mira"));
        assertTrue(quests.holds(lia.getId()), "the run goes on without her");
    }

    @Test
    void aRunGoneFromTheRegistryWithoutItsEndIsEndedAfterAMinute() {
        Character lia = lobby.citizen(1, "Lia", 25);
        lobby.citizen(2, "Mira", 22);
        lobby.citizen(3, "Pip", 28);
        lobby.citizen(4, "Tobi", 26);
        quests.tick(0L);
        door.release("kpq-run-1");

        quests.tick(10_000L);
        assertTrue(quests.holds(lia.getId()));
        quests.tick(10_000L + CitizenPartyQuests.LOST_RUN_MS);

        assertTrue(quests.recentRuns().getFirst().contains("the run was lost"));
        assertTrue(lobby.asked.contains("replan Lia"));
    }

    @Test
    void aPartyNobodyJoinsGivesUpAndGoesTraining() {
        Character lia = lobby.citizen(1, "Lia", 25);

        quests.tick(0L);
        quests.tick(CitizenPartyQuests.LF_EVERY_MS);
        quests.tick(lobby.config.PQ_FORM_PATIENCE_MS);

        assertTrue(lobby.count("pq.lf") >= 2);
        assertTrue(lobby.triggers().contains("pq.giveup"));
        assertEquals(List.of("replan Lia"), lobby.asked);
        assertTrue(quests.coolingDown(lia.getId(), lobby.config.PQ_FORM_PATIENCE_MS + MINUTE));
        assertTrue(lobby.parties.memberIds(lia).isEmpty());
    }

    @Test
    void aShortPartyCallsACircleMateWhoIsTraining() {
        Character lia = lobby.citizen(1, "Lia", 25);
        lobby.citizen(2, "Mira", 22);
        Character kai = lobby.citizen(3, "Kai", 27);
        lobby.away(kai, 103_000_100);
        CitizenSession kaiSession = lobby.session(kai);
        kaiSession.activity = CitizenSession.Activity.TRAIN;
        kaiSession.mapId = 103_000_100;
        Citizen inCircle = lobby.session(lia).citizen;
        lobby.session(lia).citizen = new Citizen(inCircle.characterId(), inCircle.name(), inCircle.cohort(), 77, 0,
                inCircle.homeRegion(), inCircle.career(), inCircle.personality(), inCircle.voice(), inCircle.pace(), 0,
                inCircle.channel(), inCircle.flags(), inCircle.state(), 0L, 0L, 0L, 0L, 0L, 0, "");
        Citizen kaiCitizen = kaiSession.citizen;
        kaiSession.citizen = new Citizen(kaiCitizen.characterId(), kaiCitizen.name(), kaiCitizen.cohort(), 77, 0,
                kaiCitizen.homeRegion(), kaiCitizen.career(), kaiCitizen.personality(), kaiCitizen.voice(),
                kaiCitizen.pace(), 0, kaiCitizen.channel(), kaiCitizen.flags(), kaiCitizen.state(), 0L, 0L, 0L, 0L, 0L,
                0, "");

        quests.tick(0L);
        quests.tick(CitizenPartyQuests.CALL_AFTER_MS);
        quests.tick(CitizenPartyQuests.CALL_AFTER_MS + 10_000L);

        assertEquals(List.of("travel Kai PQ 103000000"), lobby.asked);
        assertEquals(1, lobby.count("pq.call"));
        assertEquals(1, lobby.count("pq.call.answer"));
    }

    @Test
    void citizensWaitBesideLakelis() {
        Character lia = lobby.citizen(1, "Lia", 25);

        assertEquals(new java.awt.Point(-470, 421), quests.waitingSpot(lobby.session(lia), lobby.lobbyMap));
        assertTrue(quests.manages(lobby.session(lia)));
        when(lobby.lobbyMap.getId()).thenReturn(100_000_000);
        assertNull(quests.waitingSpot(lobby.session(lia), lobby.lobbyMap));
    }

    // ---- recruiting (round 2): live, a party that only ever called one mate gave up "nobody came" ----

    private List<String> travels() {
        return lobby.asked.stream().filter(line -> line.startsWith("travel ")).toList();
    }

    @Test
    void aShortPartyCallsAsManyMatesAsItNeedsOneAMinute() {
        Character lia = lobby.citizen(1, "Lia", 25);
        lobby.citizen(2, "Mira", 22);
        Character kai = lobby.elsewhere(3, "Kai", 27, Activity.TRAIN, 103_000_100);
        Character ivy = lobby.elsewhere(4, "Ivy", 24, Activity.TOWN, 100_000_000);
        Character rex = lobby.elsewhere(5, "Rex", 29, Activity.TRAIN, 103_000_100);
        lobby.circle(77, lia, kai, ivy, rex);

        quests.tick(0L);
        quests.tick(CitizenPartyQuests.CALL_AFTER_MS);
        quests.tick(CitizenPartyQuests.CALL_AFTER_MS + 10_000L);
        assertEquals(1, travels().size(), "one a minute");
        quests.tick(2 * MINUTE);
        quests.tick(3 * MINUTE);

        assertEquals(2, travels().size(), "two on their way are all its two open seats need: " + lobby.asked);
        assertEquals(2, lobby.count("pq.call.answer"));
        assertTrue(travels().stream().allMatch(line -> line.endsWith(" PQ 103000000")));
    }

    @Test
    void oneThatArrivesJoinsAndTheNextCallFillsTheLastSeat() {
        Character lia = lobby.citizen(1, "Lia", 25);
        lobby.citizen(2, "Mira", 22);
        Character kai = lobby.elsewhere(3, "Kai", 27, Activity.TRAIN, 103_000_100);
        lobby.circle(77, lia, kai);

        quests.tick(0L);
        quests.tick(MINUTE);
        assertEquals(List.of("travel Kai PQ 103000000"), travels());
        lobby.arrives(kai);
        quests.tick(MINUTE + 30_000L);
        assertTrue(lobby.parties.memberIds(lia).contains(kai.getId()), "Kai joins on arrival");

        lobby.elsewhere(4, "Ivy", 24, Activity.TOWN, 100_000_000);
        quests.tick(2 * MINUTE + 30_000L);

        assertEquals(List.of("travel Kai PQ 103000000", "travel Ivy PQ 103000000"), travels());
    }

    @Test
    void withNoMateFreeItCallsATownsfolkWhosePqThisIsAndNobodyElse() {
        lobby.citizen(1, "Lia", 25);
        lobby.citizen(2, "Mira", 22);
        Character ivy = lobby.elsewhere(4, "Ivy", 24, Activity.TOWN, 100_000_000);
        lobby.elsewhere(5, "Bo", 40, Activity.TOWN, 100_000_000); // KPQ is not his band
        lobby.elsewhere(6, "Cy", 24, Activity.TRAIN, 103_000_100); // a stranger training is left to it

        for (long at = 0L; at <= 4 * MINUTE; at += MINUTE) {
            quests.tick(at);
        }

        assertEquals(List.of("travel Ivy PQ 103000000"), travels());
        assertEquals(0, lobby.count("pq.call"), "a stranger just walks over, as a player looking for a party does");
        assertTrue(lobby.session(ivy).journal.summary(200).contains("went to KPQ to find a party"));
    }

    @Test
    void callsStopAtPqCallsPerParty() {
        lobby.config.PQ_CALLS_PER_PARTY = 1;
        lobby.citizen(1, "Lia", 25);
        lobby.citizen(2, "Mira", 22);
        lobby.elsewhere(4, "Ivy", 24, Activity.TOWN, 100_000_000);
        lobby.elsewhere(5, "Jo", 23, Activity.TOWN, 100_000_000);

        for (long at = 0L; at <= 8 * MINUTE; at += MINUTE) {
            quests.tick(at); // the one called never comes; after COMING_MS it is not counted on, but the cap holds
        }

        assertEquals(1, travels().size(), lobby.asked::toString);
    }

    @Test
    void aPartyFillsToItsDoorsPreferredSeatsNotItsMost() {
        door.min = 3;
        door.max = 6;
        door.preferred = 4;
        Character lia = lobby.citizen(1, "Lia", 25);
        Character mira = lobby.citizen(2, "Mira", 26);
        Character pip = lobby.citizen(3, "Pip", 27);
        Character tobi = lobby.citizen(4, "Tobi", 28);
        Character kai = lobby.citizen(5, "Kai", 29);

        quests.tick(0L);

        assertEquals(List.of(ids(lia, mira, pip, tobi)), door.admitted, "four (HPQ's preferred of six), at once");
        assertTrue(lobby.parties.memberIds(kai).isEmpty());
    }
}
