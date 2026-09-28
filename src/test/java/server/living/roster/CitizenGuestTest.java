package server.living.roster;

import client.Character;
import client.Client;
import net.packet.Packet;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.party.AgentPartyLifecycleService;
import server.living.LivingWorld;
import server.living.population.CitizenInviteDecision;
import server.living.population.CitizenInviteDecision.Answer;
import server.living.population.CitizenSession;
import server.living.population.Population;
import server.living.population.PresenceScheduler;
import server.living.store.LivingStore;
import tools.PacketCreator;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Citizen guests (docs/BOT_MANAGEMENT_PLAN.md, "Citizen guest"): a citizen invited by a human answers on its own
 * terms in stock words (the level gap, busy); on yes it holds a GUEST seat charged to its host, which counts against
 * the host's share, and it is the first to step out when room is short ("you've got a full crew, catch you later").
 * The op 4 routing of the invite itself is in PartyInviteRoutingTest.
 */
class CitizenGuestTest {
    private static final int ANN = 1;
    private static final int BEN = 2;
    private static final int MIRA = 950;
    private static final int GAP = 5;

    // ---- the citizen's answer ---------------------------------------------------------------

    @Test
    void aCitizenAnswersOnItsOwnTermsInStockWords() {
        assertEquals(Answer.ACCEPT, decide(30, 35, false));
        assertEquals(Answer.ACCEPT, decide(35, 30, false));
        assertEquals(Answer.TOO_FAR, decide(30, 36, false));
        assertEquals(Answer.BUSY, decide(30, 30, true));
        assertEquals(23, Answer.TOO_FAR.status()); // "... have denied request to the party."
        assertEquals(22, Answer.BUSY.status()); // "... is taking care of another invitation."
    }

    @Test
    void aCitizenInAnEventInstanceOrMindingTheMarketIsBusy() {
        Character ann = character(ANN, 30);
        Character mira = character(MIRA, 30);
        Population population = mock(Population.class);
        PresenceScheduler presence = mock(PresenceScheduler.class);
        CitizenSession session = mock(CitizenSession.class);
        when(population.presence()).thenReturn(presence);
        try (MockedStatic<Population> populations = mockStatic(Population.class)) {
            populations.when(Population::get).thenReturn(population);
            assertEquals(Answer.ACCEPT, CitizenInviteDecision.decide(ann, mira));

            when(presence.session(MIRA)).thenReturn(session);
            session.stallOpen = true;
            assertEquals(Answer.BUSY, CitizenInviteDecision.decide(ann, mira));
            session.stallOpen = false;
            session.activity = CitizenSession.Activity.MARKET;
            assertEquals(Answer.BUSY, CitizenInviteDecision.decide(ann, mira));
            session.activity = CitizenSession.Activity.TRAIN;
            assertEquals(Answer.ACCEPT, CitizenInviteDecision.decide(ann, mira));

            when(mira.getEventInstance()).thenReturn(mock(EventInstanceManager.class));
            assertEquals(Answer.BUSY, CitizenInviteDecision.decide(ann, mira));
        }
    }

    // ---- the guest seat ---------------------------------------------------------------------

    @Test
    void aGuestSeatIsChargedToItsHostAndCountsAgainstTheirShare() {
        CompanionSlots.Member ann = new CompanionSlots.Member(CompanionSlots.characterKey(ANN), ANN, true, 0, false);
        CompanionSlots.Member ben = new CompanionSlots.Member(CompanionSlots.characterKey(BEN), BEN, true, 0, false);
        CompanionSlots.Member vex = new CompanionSlots.Member("vex", 101, false, ANN, false);
        CompanionSlots.Member mira = new CompanionSlots.Member(CompanionSlots.characterKey(MIRA), MIRA, false, ANN, true);
        CompanionSlots.View before = new CompanionSlots.View(List.of(ann, vex, ben), List.of(), Map.of(), Map.of());
        CompanionSlots.View with = new CompanionSlots.View(List.of(ann, vex, ben, mira), List.of(), Map.of(), Map.of());

        assertEquals(1, CompanionSlots.free(before, ANN)); // 2 humans: 2 seats each
        assertEquals(0, CompanionSlots.free(with, ANN)); // her guest took her second
        assertEquals(2, CompanionSlots.free(with, BEN)); // and none of Ben's
        // a guest whose join is still on its way holds the seat as a reservation
        CompanionSlots.Reservation held = new CompanionSlots.Reservation(ANN, CompanionSlots.characterKey(MIRA),
                CompanionSlots.Kind.GUEST, Long.MAX_VALUE, 1);
        assertEquals(0, CompanionSlots.free(new CompanionSlots.View(List.of(ann, vex, ben), List.of(held), Map.of(),
                Map.of()), ANN));
    }

    @Test
    void theHostCannotCallACompanionIntoTheSeatTheirGuestHolds() {
        FakeParties world = world();
        int anns = world.create(ANN);
        assertTrue(world.call(ANN, "vex"));
        world.accept(BEN, anns);
        assertTrue(world.inviteCitizen(ANN, MIRA));
        world.settle();

        assertFalse(world.call(ANN, "olwen"));
        assertEquals(List.of(ANN, 101, BEN, MIRA), world.members(anns));
    }

    @Test
    void aGuestStepsOutFirstWhenAFriendJoinsPastItsHostsShare() {
        FakeParties world = world();
        int anns = world.create(ANN);
        assertTrue(world.call(ANN, "vex"));
        assertTrue(world.call(ANN, "olwen"));
        assertTrue(world.inviteCitizen(ANN, MIRA));
        world.settle();
        world.changes.clear();

        world.accept(BEN, anns);
        world.settle();

        assertEquals(List.of("guest " + MIRA), world.changes); // her companions keep their seats
        assertEquals(List.of(ANN, 101, 102, BEN), world.members(anns));
        assertFalse(world.guests.containsKey(MIRA));
    }

    @Test
    void aGuestDoesNotFollowItsHostOutOfTheParty() {
        FakeParties world = world();
        int bens = world.create(BEN);
        world.accept(ANN, bens);
        assertTrue(world.inviteCitizen(ANN, MIRA));
        world.settle();

        world.quit(ANN);
        world.settle();

        assertEquals(0, world.partyOf(MIRA));
        assertEquals(List.of(BEN), world.members(bens));
    }

    @Test
    void aGuestSteppingOutSaysSoAndGivesItsSeatBack() {
        LivingWorld living = mock(LivingWorld.class);
        RosterService roster = mock(RosterService.class);
        CompanionSlots slots = mock(CompanionSlots.class);
        LivingStore store = mock(LivingStore.class);
        when(living.roster()).thenReturn(roster);
        when(living.slots()).thenReturn(slots);
        when(living.store()).thenReturn(store);
        when(slots.keyOf(MIRA)).thenReturn(CompanionSlots.characterKey(MIRA));
        Character ann = character(ANN, 30);
        Character mira = character(MIRA, 30);
        when(mira.getName()).thenReturn("Mira");
        Client miraClient = mock(Client.class);
        when(miraClient.getChannel()).thenReturn(1);
        when(mira.getClient()).thenReturn(miraClient);
        when(roster.onlineCharacter(ANN)).thenReturn(ann);
        when(roster.onlineCharacter(MIRA)).thenReturn(mira);

        try (MockedStatic<AgentPartyLifecycleService> lifecycle = mockStatic(AgentPartyLifecycleService.class)) {
            new LiveCrew(living).guestLeaves(ANN, MIRA, CompanionPartySync.GUEST_LINE);

            lifecycle.verify(() -> AgentPartyLifecycleService.leaveAgentParty(mira));
        }
        ArgumentCaptor<Packet> whisper = ArgumentCaptor.forClass(Packet.class);
        verify(ann).sendPacket(whisper.capture());
        assertArrayEquals(PacketCreator.getWhisperReceive("Mira", 0, false, "you've got a full crew, catch you later!")
                .getBytes(), whisper.getValue().getBytes());
        verify(slots).release(CompanionSlots.characterKey(MIRA), CompanionSlots.Kind.GUEST);
        verify(store).event(eq(ANN), eq(null), eq("companion.guest"), anyString());
    }

    @Test
    void theHostOfACharacterIsTheOwnerOfItsGuestSeat() {
        LivingWorld living = mock(LivingWorld.class);
        CompanionSlots slots = mock(CompanionSlots.class);
        when(living.slots()).thenReturn(slots);
        when(slots.keyOf(MIRA)).thenReturn("character:950");
        when(slots.keyOf(101)).thenReturn("vex");
        when(slots.reservation("character:950"))
                .thenReturn(new CompanionSlots.Reservation(ANN, "character:950", CompanionSlots.Kind.GUEST, Long.MAX_VALUE, 1));
        when(slots.reservation("vex"))
                .thenReturn(new CompanionSlots.Reservation(ANN, "vex", CompanionSlots.Kind.TRAVEL, Long.MAX_VALUE, 2));

        assertEquals(ANN, new LiveCrew(living).hostOf(MIRA));
        assertEquals(0, new LiveCrew(living).hostOf(101)); // a companion on its way is nobody's guest
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static Answer decide(int inviter, int citizen, boolean busy) {
        return CitizenInviteDecision.decide(inviter, citizen, GAP, busy);
    }

    private static FakeParties world() {
        return new FakeParties().human(ANN).human(BEN)
                .companion("vex", 101, ANN, 0).companion("olwen", 102, ANN, 1).companion("brin", 103, ANN, 2)
                .citizen(MIRA);
    }

    private static Character character(int id, int level) {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(id);
        when(character.getLevel()).thenReturn(level);
        return character;
    }
}
