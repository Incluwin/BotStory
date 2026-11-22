package server.living.population;

import client.Character;
import client.Client;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.agents.monitoring.AgentSchedulerMetrics;
import server.living.standing.Standing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Whispered invitations to a citizen run (docs/WORLD_SYSTEMS_PLAN.md N3.1, decision D6) and the bandit over when to
 * ask (N3.6): a citizen whose party forms at Lakelis whispers a human it knows who trains elsewhere; the gates (known,
 * channel, band, no party, not busy, not just logged in), one invitation per human per 30 minutes across all citizens,
 * a yes that holds the seat while they walk over, a no that is taken well and lengthens the gap.
 */
class CitizenRunInvitesTest {
    private static final long MINUTE = 60_000L;
    /** Every clock here starts an hour past the humans' login, out of the login quiet. */
    private static final long T0 = 3_600_000L;
    private static final int FIELD = 103_000_101;

    private final CitizenLobbyFixture lobby = new CitizenLobbyFixture();
    private final CitizenLobbyFixture.Door door = new CitizenLobbyFixture.Door();
    private final CitizenPartyQuests quests = lobby.quests(door);
    private final List<Character> online = new ArrayList<>();
    /** "citizen:human" -> warmth. */
    private final Map<String, Integer> warmth = new HashMap<>();
    /** Whispers out: "Lia->Ann run: line" (invitations) and "Lia->Ann reply: trigger" (answers). */
    private final List<String> whispers = new ArrayList<>();
    /** Companion id -> its owner. */
    private final Map<Integer, Integer> owners = new HashMap<>();
    private final CitizenRunInvites invites = new CitizenRunInvites(lobby.population, quests, () -> online,
            (citizen, human) -> warmth.getOrDefault(citizen + ":" + human, 0), id -> owners.getOrDefault(id, 0));

    @BeforeEach
    void setUp() {
        lobby.config.CITIZEN_RUN_INVITES_ENABLED = true;
        lobby.config.CITIZEN_WHISPERS_ENABLED = true;
        quests.useInvites(invites);
        ChatterEngine chatter = lobby.population.chatter();
        when(chatter.compose(any())).thenAnswer(call -> this.<ChatterEngine.Event>arg(call.getArgument(0)).trigger);
        doAnswer(call -> whispers.add(this.<CitizenSession>arg(call.getArgument(0)).name() + "->"
                + this.<Character>arg(call.getArgument(1)).getName() + " " + call.getArgument(3) + ": "
                + call.getArgument(2))).when(chatter).whisper(any(), any(), anyString(), anyString());
        doAnswer(call -> whispers.add(this.<CitizenSession>arg(call.getArgument(0)).name() + "->"
                + this.<Character>arg(call.getArgument(1)).getName() + " reply: " + call.getArgument(2)))
                .when(chatter).whisperReply(any(), any(), anyString());
    }

    @AfterEach
    void tearDown() {
        AgentSchedulerMetrics.clearLoadSheddingShard(901);
        lobby.close();
    }

    @SuppressWarnings("unchecked")
    private <T> T arg(Object value) {
        return (T) value;
    }

    /** A human training on a field of Kerning, online on {@code channel} since time 0. */
    private Character trainer(int id, String name, int level, int channel) {
        Character human = lobby.human(id, name, level);
        lobby.away(human, FIELD);
        Client client = mock(Client.class, withSettings().stubOnly());
        when(client.getChannel()).thenReturn(channel);
        when(human.getClient()).thenReturn(client);
        when(human.getLoginTime()).thenReturn(0L);
        online.add(human);
        return human;
    }

    private void knows(Character citizen, Character human, int felt) {
        warmth.put(citizen.getId() + ":" + human.getId(), felt);
    }

    private List<String> invitationsTo(String name) {
        return whispers.stream().filter(line -> line.contains("->" + name + " run:")).toList();
    }

    /** Three citizens at Lakelis (KPQ's minimum), one seat open; Lia leads. */
    private Character threeAtLakelis() {
        Character lia = lobby.citizen(1, "Lia", 25);
        lobby.citizen(2, "Mira", 22);
        lobby.citizen(3, "Pip", 28);
        return lia;
    }

    // ---- asking and its gates --------------------------------------------------------------------------

    @Test
    void aCitizenWhispersAHumanItKnowsTrainingElsewhere() {
        Character lia = threeAtLakelis();
        Character ann = trainer(10, "Ann", 24, CitizenLobbyFixture.CHANNEL);
        knows(lia, ann, 20);

        quests.tick(T0);

        assertEquals(List.of("Lia->Ann run: run.invite"), invitationsTo("Ann"));
        assertTrue(invites.pending(ann.getId()));
        assertTrue(lobby.session(lia).journal.summary(200).contains("asked Ann along to KPQ"));
    }

    @Test
    void aMemberWhoKnowsThemAsksAndNamesTheLeader() {
        threeAtLakelis();
        Character mira = lobby.sessions.get(2).agent();
        Character ann = trainer(10, "Ann", 24, CitizenLobbyFixture.CHANNEL);
        knows(mira, ann, 45);

        quests.tick(T0);

        assertEquals(List.of("Mira->Ann run: run.invite.member"), invitationsTo("Ann"));
    }

    @Test
    void strangersAndGrudgesAreNeverAsked() {
        Character lia = threeAtLakelis();
        Character mira = lobby.sessions.get(2).agent();
        Character ann = trainer(10, "Ann", 24, CitizenLobbyFixture.CHANNEL);
        Character bo = trainer(11, "Bo", 24, CitizenLobbyFixture.CHANNEL);
        knows(lia, ann, 14); // just under REGULAR
        knows(lia, bo, 30);
        knows(mira, bo, -30); // one of the party holds a grudge against Bo

        quests.tick(T0);

        assertTrue(invitationsTo("Ann").isEmpty());
        assertTrue(invitationsTo("Bo").isEmpty());
    }

    @Test
    void theGatesKeepBusyOrUnfittingHumansOut() {
        Character lia = threeAtLakelis();
        Character other = trainer(10, "OtherCh", 24, 2);
        Character high = trainer(11, "High", 45, CitizenLobbyFixture.CHANNEL);
        Character fresh = trainer(12, "Fresh", 24, CitizenLobbyFixture.CHANNEL);
        when(fresh.getLoginTime()).thenReturn(T0 - 9 * MINUTE);
        Character partied = trainer(13, "Partied", 24, CitizenLobbyFixture.CHANNEL);
        Character pal = trainer(14, "Pal", 24, CitizenLobbyFixture.CHANNEL);
        lobby.parties.lead(partied, pal);
        Character inPq = trainer(15, "InPq", 24, CitizenLobbyFixture.CHANNEL);
        lobby.inInstance(inPq, true);
        for (Character human : List.of(other, high, fresh, partied, pal, inPq)) {
            knows(lia, human, 50);
        }
        PopulationContent.Lobby kpq = CitizenLobbyFixture.KPQ;

        assertEquals("another channel", invites.gate(other, kpq, 1, kpq.mapId(), T0));
        assertEquals("level", invites.gate(high, kpq, 1, kpq.mapId(), T0));
        assertEquals("just logged in", invites.gate(fresh, kpq, 1, kpq.mapId(), T0));
        assertEquals("in a party", invites.gate(partied, kpq, 1, kpq.mapId(), T0));
        assertEquals("in a party quest or boss run", invites.gate(inPq, kpq, 1, kpq.mapId(), T0));
        quests.tick(T0);
        assertTrue(whispers.isEmpty(), whispers.toString());
    }

    @Test
    void aHumanAtTheLobbyGetsTheLobbyInviteNotAWhisper() {
        Character lia = threeAtLakelis();
        Character ann = lobby.human(10, "Ann", 24); // standing at Lakelis
        Client client = mock(Client.class, withSettings().stubOnly());
        when(client.getChannel()).thenReturn(CitizenLobbyFixture.CHANNEL);
        when(ann.getClient()).thenReturn(client);
        online.add(ann);
        knows(lia, ann, 50);

        quests.tick(T0);

        assertTrue(invitationsTo("Ann").isEmpty());
        assertTrue(lobby.parties.hasPendingPartyInvite(ann.getId()), "the lobby's own invite");
    }

    @Test
    void theSwitchOffOrWhispersOffAsksNobody() {
        Character lia = threeAtLakelis();
        Character ann = trainer(10, "Ann", 24, CitizenLobbyFixture.CHANNEL);
        knows(lia, ann, 50);
        lobby.config.CITIZEN_WHISPERS_ENABLED = false;

        quests.tick(T0);
        lobby.config.CITIZEN_WHISPERS_ENABLED = true;
        lobby.config.CITIZEN_RUN_INVITES_ENABLED = false;
        quests.tick(T0 + MINUTE);

        assertTrue(whispers.isEmpty());
    }

    // ---- players with their crew out (coordinator 2026-11-22: invitable, the seat is theirs alone) -------------

    /** {@code owner}'s companion, out with them in their party, training beside them. */
    private Character crewOf(Character owner, int id, String name) {
        Character companion = lobby.companion(id, name, 24);
        lobby.away(companion, FIELD);
        owners.put(id, owner.getId());
        return companion;
    }

    @Test
    void aPlayerWithOnlyTheirOwnCrewIsInvitedForThemselvesAlone() {
        Character lia = threeAtLakelis();
        Character ann = trainer(10, "Ann", 24, CitizenLobbyFixture.CHANNEL);
        lobby.parties.lead(ann, crewOf(ann, 20, "Brannock"), crewOf(ann, 21, "Fenna"));
        knows(lia, ann, 30);

        quests.tick(T0);

        assertEquals(List.of("Lia->Ann run: run.invite.crew"), invitationsTo("Ann"));
    }

    @Test
    void aPlayerPartiedWithAnotherPlayerOrAnotherOwnersBotIsNotInvited() {
        Character lia = threeAtLakelis();
        Character ann = trainer(10, "Ann", 24, CitizenLobbyFixture.CHANNEL);
        Character bo = trainer(11, "Bo", 24, CitizenLobbyFixture.CHANNEL);
        Character cy = trainer(12, "Cy", 24, CitizenLobbyFixture.CHANNEL);
        lobby.parties.lead(ann, crewOf(ann, 20, "Brannock"), bo); // another player
        lobby.parties.lead(cy, crewOf(ann, 21, "Fenna")); // Ann's companion on loan in Cy's party
        for (Character human : List.of(ann, bo, cy)) {
            knows(lia, human, 30);
        }
        PopulationContent.Lobby kpq = CitizenLobbyFixture.KPQ;

        assertEquals("in a party", invites.gate(ann, kpq, 1, kpq.mapId(), T0));
        assertEquals("in a party", invites.gate(cy, kpq, 1, kpq.mapId(), T0));
        quests.tick(T0);
        assertTrue(whispers.isEmpty(), whispers.toString());
    }

    @Test
    void aYesWithTheCrewOutHoldsTheSeatUntilTheyBenchItAndNobodyMovesTheCrew() {
        Character lia = threeAtLakelis();
        Character ann = trainer(10, "Ann", 24, CitizenLobbyFixture.CHANNEL);
        Character brannock = crewOf(ann, 20, "Brannock");
        lobby.parties.lead(ann, brannock);
        knows(lia, ann, 30);
        quests.tick(T0);
        assertEquals(1, invitationsTo("Ann").size());

        assertTrue(invites.replied(lobby.session(lia), ann, "sure", T0 + 5_000L));
        assertTrue(whispers.contains("Lia->Ann reply: whisper.run.yes.crew"), whispers.toString());
        Character tobi = lobby.citizen(4, "Tobi", 26); // walks up: it would take the seat if the seat were not held
        quests.tick(T0 + 10_000L);
        quests.tick(T0 + 10_000L + CitizenPartyQuests.FILL_WAIT_MS);
        assertTrue(door.admitted.isEmpty(), "the seat waits while Ann benches her crew");
        assertFalse(lobby.parties.memberIds(lia).contains(tobi.getId()), "Ann's seat is not given away");
        assertEquals(List.of(ann.getId(), brannock.getId()), lobby.parties.memberIds(ann), "nobody moved her crew");

        lobby.parties.leaveCurrentParty(ann); // she benched Brannock (her party is gone)
        quests.tick(T0 + 2 * MINUTE);
        assertTrue(lobby.parties.hasPendingPartyInvite(ann.getId()), "the leader's invite once she is free");
        assertTrue(whispers.contains("Lia->Ann reply: whisper.join.invited"), whispers.toString());
    }

    @Test
    void aCrewThatFollowsTheirOwnerInKeepsTheRunOutAndTheLeaderSaysSoOnce() {
        Character lia = threeAtLakelis();
        Character ann = lobby.human(10, "Ann", 24); // at Lakelis, took the lobby invite
        quests.tick(T0);
        lobby.parties.accept(ann);
        Character brannock = lobby.companion(20, "Brannock", 24);
        owners.put(20, ann.getId());
        lobby.parties.joinAgentParty(brannock, lobby.parties.snapshot(lia).id()); // the roster re-seated her crew

        quests.tick(T0 + 10_000L);
        quests.tick(T0 + 20_000L);

        assertTrue(door.admitted.isEmpty(), "never with a companion in a citizen run");
        assertEquals(1, lobby.said.stream().filter(event -> event.trigger.equals("pq.wait")
                && "{name} ur crew has to sit this one out, bench them n we go".equals(event.text)).count());
        assertEquals(List.of(lia.getId(), 2, 3, ann.getId(), brannock.getId()), lobby.parties.memberIds(lia));
    }

    // ---- rate limits -------------------------------------------------------------------------------------

    @Test
    void oneInvitationPerHumanPerHalfHourAcrossAllCitizens() {
        Character lia = lobby.citizen(1, "Lia", 25);
        Character ann = trainer(10, "Ann", 24, CitizenLobbyFixture.CHANNEL);
        knows(lia, ann, 50);
        CitizenSession leader = lobby.session(lia);
        PopulationContent.Lobby kpq = CitizenLobbyFixture.KPQ;

        assertTrue(invites.ask(forming(1), leader, List.of(leader), kpq, 2, T0));
        invites.expire(T0 + lobby.config.CITIZEN_RUN_INVITE_ANSWER_MS); // no answer
        assertFalse(invites.pending(ann.getId()));
        // another party, another citizen who knows Ann: still within the 30 minutes
        Character tobi = lobby.citizen(4, "Tobi", 26);
        knows(tobi, ann, 50);
        CitizenSession other = lobby.session(tobi);
        assertFalse(invites.ask(forming(4), other, List.of(other), kpq, 2, T0 + 29 * MINUTE));
        assertTrue(invites.ask(forming(4), other, List.of(other), kpq, 2, T0 + 30 * MINUTE));
        assertEquals(2, invitationsTo("Ann").size());
    }

    @Test
    void aPartyWhispersAtMostItsShareAndOneAMinute() {
        Character lia = lobby.citizen(1, "Lia", 25);
        CitizenSession leader = lobby.session(lia);
        for (int id = 10; id < 14; id++) {
            knows(lia, trainer(id, "H" + id, 24, CitizenLobbyFixture.CHANNEL), 50);
        }
        CitizenPartyQuests.Forming party = forming(1);
        PopulationContent.Lobby kpq = CitizenLobbyFixture.KPQ;

        assertTrue(invites.ask(party, leader, List.of(leader), kpq, 3, T0));
        assertFalse(invites.ask(party, leader, List.of(leader), kpq, 3, T0 + 30_000L), "one a minute");
        assertTrue(invites.ask(party, leader, List.of(leader), kpq, 3, T0 + MINUTE));
        assertFalse(invites.ask(party, leader, List.of(leader), kpq, 3, T0 + 5 * MINUTE),
                "CITIZEN_RUN_INVITES_PER_PARTY is 2");
    }

    // ---- answers -----------------------------------------------------------------------------------------

    @Test
    void aNoIsTakenWellAndDoublesTheGap() {
        Character lia = lobby.citizen(1, "Lia", 25);
        Character ann = trainer(10, "Ann", 24, CitizenLobbyFixture.CHANNEL);
        knows(lia, ann, 50);
        CitizenSession leader = lobby.session(lia);
        assertTrue(invites.ask(forming(1), leader, List.of(leader), CitizenLobbyFixture.KPQ, 2, T0));

        assertTrue(invites.replied(leader, ann, "nah im good ty", T0 + 20_000L));

        assertTrue(whispers.contains("Lia->Ann reply: whisper.run.no"), whispers.toString());
        assertFalse(invites.pending(ann.getId()));
        assertEquals(2L * lobby.config.CITIZEN_RUN_INVITE_GAP_MS, invites.gapMs(ann.getId()));
        assertTrue(lobby.parties.memberIds(ann).isEmpty());
        assertTrue(lobby.session(lia).journal.summary(200).contains("Ann could not come along"));
    }

    @Test
    void anAnswerToSomeoneElsesInvitationOrSmallTalkIsNotTaken() {
        Character lia = lobby.citizen(1, "Lia", 25);
        Character mira = lobby.citizen(2, "Mira", 22);
        Character ann = trainer(10, "Ann", 24, CitizenLobbyFixture.CHANNEL);
        knows(lia, ann, 50);
        CitizenSession leader = lobby.session(lia);
        assertTrue(invites.ask(forming(1), leader, List.of(leader), CitizenLobbyFixture.KPQ, 2, T0));

        assertFalse(invites.replied(lobby.session(mira), ann, "yes", T0 + 1_000L), "Mira asked nothing");
        assertFalse(invites.replied(leader, ann, "lol what map", T0 + 1_000L), "neither yes nor no");
        assertTrue(invites.pending(ann.getId()));
    }

    @Test
    void aYesFromAfarHoldsTheSeatWhileTheyWalkOverAndThePartySaysSo() {
        Character lia = threeAtLakelis();
        Character ann = trainer(10, "Ann", 24, CitizenLobbyFixture.CHANNEL);
        knows(lia, ann, 50);
        quests.tick(T0);
        assertEquals(1, invitationsTo("Ann").size());

        assertTrue(invites.replied(lobby.session(lia), ann, "ya omw", T0 + 10_000L));
        assertTrue(whispers.contains("Lia->Ann reply: whisper.run.yes"), whispers.toString());
        assertTrue(lobby.parties.hasPendingPartyInvite(ann.getId()), "the leader's party invite");
        lobby.parties.accept(ann);

        // she walks over from her field; the party keeps her seat past its fill wait
        quests.tick(T0 + 20_000L);
        quests.tick(T0 + 20_000L + CitizenPartyQuests.FILL_WAIT_MS + 10_000L);
        assertTrue(door.admitted.isEmpty(), "no run without the player who said yes");
        assertEquals(1, lobby.count("pq.where"));
        assertEquals(1, lobby.count("pq.coming"));

        lobby.arrives(ann);
        quests.tick(T0 + 4 * MINUTE);
        assertEquals(List.of(List.of(1, 2, 3, ann.getId())), door.admitted);
        assertEquals(1, invites.bandit().counts(InviteBandit.Context.of(T0, false, Standing.Rung.FRIEND, null))[1]);
    }

    @Test
    void aYesAfterThePartyFilledGetsAnApology() {
        Character lia = lobby.citizen(1, "Lia", 25);
        Character ann = trainer(10, "Ann", 24, CitizenLobbyFixture.CHANNEL);
        knows(lia, ann, 50);
        CitizenSession leader = lobby.session(lia);
        assertTrue(invites.ask(forming(1), leader, List.of(leader), CitizenLobbyFixture.KPQ, 2, T0));

        assertTrue(invites.replied(leader, ann, "sure", T0 + 5_000L)); // no party forms at Lakelis any more

        assertTrue(whispers.contains("Lia->Ann reply: whisper.run.gone"), whispers.toString());
        assertFalse(lobby.parties.hasPendingPartyInvite(ann.getId()));
    }

    @Test
    void askToJoinFromTheBoardInvitesThePlayer() {
        Character lia = threeAtLakelis();
        Character ann = trainer(10, "Ann", 24, CitizenLobbyFixture.CHANNEL);
        when(lobby.fields.name(CitizenLobbyFixture.LOBBY)).thenReturn("Kerning City");
        quests.tick(T0);

        String page = quests.askToJoin(ann, lia.getId(), T0 + 1_000L);

        assertTrue(page.startsWith("#eLia#n sent you a party invite"), page);
        assertTrue(lobby.parties.hasPendingPartyInvite(ann.getId()));
        assertEquals("Someone else leads that party now.", quests.askToJoin(ann, 2, T0 + 2_000L));
        assertEquals("They are away right now.", quests.askToJoin(ann, 99, T0 + 3_000L));
    }

    @Test
    void answerWordsReadAsPlayersWriteThem() {
        assertEquals(CitizenRunInvites.Answer.YES, CitizenRunInvites.answer("ya omw"));
        assertEquals(CitizenRunInvites.Answer.YES, CitizenRunInvites.answer("sure!"));
        assertEquals(CitizenRunInvites.Answer.NO, CitizenRunInvites.answer("nah busy"));
        assertEquals(CitizenRunInvites.Answer.NO, CitizenRunInvites.answer("can't rn sorry"));
        assertEquals(CitizenRunInvites.Answer.OTHER, CitizenRunInvites.answer("what map?"));
    }

    // ---- the bandit (N3.6) ---------------------------------------------------------------------------------

    @Test
    void theBanditStopsAskingAContextThatNeverSaysYes() {
        InviteBandit bandit = new InviteBandit();
        InviteBandit.Context never = new InviteBandit.Context(0, InviteBandit.Activity.FIELD, Standing.Rung.REGULAR,
                InviteBandit.SinceRan.RECENT);
        InviteBandit.Context keen = new InviteBandit.Context(3, InviteBandit.Activity.TOWN, Standing.Rung.FRIEND,
                InviteBandit.SinceRan.LONG);
        assertTrue(bandit.worthAsking(never, 0.3d, 0.15d), "never asked: always asked");
        int asked = 0;
        while (bandit.worthAsking(never, 0.3d, 0.15d) && asked < 100) {
            bandit.answered(never, false);
            bandit.answered(keen, asked % 2 == 0);
            asked++;
        }
        assertTrue(asked > 3 && asked < 40, "stopped after " + asked);
        assertTrue(bandit.worthAsking(keen, 0.3d, 0.15d), "a context that says yes half the time keeps being asked");
    }

    @Test
    void contextsBandTheHourActivityRungAndLastRun() {
        long sixThirtyUtc = 6 * 3_600_000L + 30 * MINUTE;
        InviteBandit.Context context = InviteBandit.Context.of(sixThirtyUtc, true, Standing.Rung.FRIEND,
                sixThirtyUtc - 10 * MINUTE);
        assertEquals(new InviteBandit.Context(1, InviteBandit.Activity.TOWN, Standing.Rung.FRIEND,
                InviteBandit.SinceRan.RECENT), context);
        assertEquals(InviteBandit.SinceRan.LONG, InviteBandit.Context.of(sixThirtyUtc, false, Standing.Rung.REGULAR,
                0L).sinceRan());
        assertEquals(0.3d * Math.sqrt(Math.log(1.0d) / 1.0d), InviteBandit.optimistic(0, 0, 0, 0.3d), 1e-9);
    }

    @Test
    void theBanditCanHoldBackAnInvitation() {
        Character lia = lobby.citizen(1, "Lia", 25);
        Character ann = trainer(10, "Ann", 24, CitizenLobbyFixture.CHANNEL);
        knows(lia, ann, 50);
        InviteBandit.Context context = InviteBandit.Context.of(T0, false, Standing.Rung.FRIEND, null);
        for (int i = 0; i < 60; i++) {
            invites.bandit().answered(context, false);
        }
        CitizenSession leader = lobby.session(lia);

        assertFalse(invites.ask(forming(1), leader, List.of(leader), CitizenLobbyFixture.KPQ, 2, T0));
        assertTrue(whispers.isEmpty());
    }

    private static CitizenPartyQuests.Forming forming(int leaderId) {
        return new CitizenPartyQuests.Forming("kpq", CitizenLobbyFixture.CHANNEL, CitizenLobbyFixture.LOBBY, leaderId, T0);
    }
}
