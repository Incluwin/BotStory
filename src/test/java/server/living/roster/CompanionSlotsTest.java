package server.living.roster;

import client.Character;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import net.server.world.PartyEvents.PartyChangeEvent;
import net.server.world.PartyEvents.Snapshot;
import net.server.world.PartyOperation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.resident.ResidentRuntime;
import server.living.roster.CompanionSlots.Claim;
import server.living.roster.CompanionSlots.Kind;
import server.living.roster.CompanionSlots.MakeRoom;
import server.living.roster.CompanionSlots.Member;
import server.living.roster.CompanionSlots.Reservation;
import server.living.roster.CompanionSlots.RoomOutcome;
import server.living.roster.CompanionSlots.View;
import server.living.store.LivingStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The slot authority (docs/BOT_MANAGEMENT_PLAN.md P2a, CompanionSlotsTest): solo, 2 and 3 humans, the leftover
 * seats, HOLD/AWAY/TRAVEL/GUEST reservations, leader caps, and make-room with guests first.
 */
class CompanionSlotsTest {
    private static final int ANN = 10;
    private static final int BEN = 20;
    private static final int CLEO = 30;
    private static final int DAN = 40;
    private static final long FOREVER = Long.MAX_VALUE;

    // ---- the pure seat arithmetic ---------------------------------------------------------

    @Test
    void aSoloOwnerHasFiveSeatsLessCompanionsAndEveryKindOfReservation() {
        View alone = view(List.of(human(ANN), companion("hadrik", 101, ANN)));
        assertEquals(4, CompanionSlots.free(alone, ANN));

        View reserved = view(List.of(human(ANN), companion("hadrik", 101, ANN)),
                held(ANN, "olwen", Kind.AWAY, 1), held(ANN, "mira", Kind.HOLD, 2),
                held(ANN, "tobi", Kind.TRAVEL, 3), held(ANN, CompanionSlots.characterKey(950), Kind.GUEST, 4));
        assertEquals(0, CompanionSlots.free(reserved, ANN));
    }

    @Test
    void aFullSoloPartyHasNoSeat() {
        View full = view(List.of(human(ANN), companion("a", 101, ANN), companion("b", 102, ANN),
                companion("c", 103, ANN), companion("d", 104, ANN), companion("e", 105, ANN)));
        assertEquals(0, CompanionSlots.free(full, ANN));
        assertTrue(CompanionSlots.allocate(full).overflow().isEmpty());
    }

    @Test
    void twoHumansGetTwoSeatsEach() {
        View party = view(List.of(human(ANN), companion("hadrik", 101, ANN), human(BEN), companion("brin", 201, BEN),
                companion("vex", 102, ANN)));
        assertEquals(0, CompanionSlots.free(party, ANN));
        assertEquals(1, CompanionSlots.free(party, BEN));
        assertEquals(2, CompanionSlots.allocate(party).shareOf(ANN));
        assertEquals(2, CompanionSlots.allocate(party).shareOf(BEN));
    }

    @Test
    void afterAMergeTheBackOfTheLineupOverflows() {
        View merged = new View(List.of(human(ANN), companion("a", 101, ANN), companion("b", 102, ANN),
                companion("c", 103, ANN), companion("d", 104, ANN), human(BEN)), List.of(), Map.of(),
                Map.of("a", 1, "b", 2, "c", 3, "d", 4));

        CompanionSlots.Allocation seats = CompanionSlots.allocate(merged);

        assertEquals(List.of("d", "c"), keys(seats.overflow()));
        assertEquals(2, seats.shareOf(ANN));
        assertEquals(4, seats.usageOf(ANN));
        assertEquals(0, CompanionSlots.free(merged, ANN));
        assertEquals(0, CompanionSlots.free(merged, BEN)); // the party is physically full until the overflow steps out
    }

    @Test
    void theUsualLineupKeepsItsSeatsWhateverTheJoinOrder() {
        // d joined first but is 1st in the lineup: it keeps a seat, the later-joined 3rd and 4th overflow
        View merged = new View(List.of(human(ANN), companion("d", 104, ANN), companion("c", 103, ANN),
                companion("b", 102, ANN), companion("a", 101, ANN), human(BEN)), List.of(), Map.of(),
                Map.of("d", 1, "a", 2, "b", 3, "c", 4));

        assertEquals(List.of("c", "b"), keys(CompanionSlots.allocate(merged).overflow()));
    }

    @Test
    void guestsCountAgainstTheHostAndStepOutFirst() {
        View party = view(List.of(human(ANN), guest(950, ANN), companion("a", 101, ANN), companion("b", 102, ANN),
                human(BEN)));

        CompanionSlots.Allocation seats = CompanionSlots.allocate(party);

        assertEquals(3, seats.usageOf(ANN));
        assertEquals(List.of(CompanionSlots.characterKey(950)), keys(seats.overflow()));
    }

    @Test
    void threeHumansGetOneSeatEach() {
        View party = view(List.of(human(ANN), companion("a", 101, ANN), companion("b", 102, ANN), human(BEN),
                human(CLEO)));
        CompanionSlots.Allocation seats = CompanionSlots.allocate(party);

        assertEquals(1, seats.shareOf(ANN));
        assertEquals(List.of("b"), keys(seats.overflow()));
        assertEquals(1, CompanionSlots.free(party, BEN));
        assertEquals(0, CompanionSlots.free(party, ANN));
    }

    @Test
    void withFourHumansTheTwoLeftoverSeatsGoFirstCome() {
        View party = view(List.of(human(ANN), human(BEN), human(CLEO), human(DAN), companion("a", 101, ANN)),
                held(CLEO, "c", Kind.TRAVEL, 1), held(BEN, "b", Kind.TRAVEL, 2));

        CompanionSlots.Allocation seats = CompanionSlots.allocate(party);

        // Ann's member came first, then Cleo's earlier reservation; Ben's later one is past the leftover
        assertEquals(1, seats.shareOf(ANN));
        assertEquals(1, seats.shareOf(CLEO));
        assertEquals(0, seats.shareOf(BEN));
        assertEquals(List.of("b"), keys(seats.overflow()));
        assertEquals(0, CompanionSlots.free(party, DAN));
    }

    @Test
    void anUnheldLeftoverSeatIsFreeForAnyHuman() {
        View party = view(List.of(human(ANN), human(BEN), human(CLEO), human(DAN), companion("a", 101, ANN)));

        assertEquals(1, CompanionSlots.free(party, BEN));
        assertEquals(1, CompanionSlots.free(party, ANN));
    }

    @Test
    void offlineHumansAndNobodysAgentsOnlyOccupyASeat() {
        Member offlineBen = new Member(CompanionSlots.characterKey(BEN), BEN, false, 0, false);
        Member plainAgent = new Member(CompanionSlots.characterKey(960), 960, false, 0, false);
        View party = view(List.of(human(ANN), offlineBen, plainAgent, companion("a", 101, ANN)));

        // Ann is the only online human, so her share is 5, but only 2 seats are left in the party
        assertEquals(2, CompanionSlots.free(party, ANN));
        assertEquals(0, CompanionSlots.free(party, BEN));
    }

    @Test
    void aLeaderCapLowersThatOwnersShareInThatParty() {
        View capped = new View(List.of(human(ANN), human(BEN), companion("a", 101, ANN)), List.of(),
                Map.of(ANN, 1), Map.of());

        assertEquals(0, CompanionSlots.free(capped, ANN));
        assertEquals(2, CompanionSlots.free(capped, BEN));
    }

    // ---- make-room and admission ----------------------------------------------------------

    @Test
    void makeRoomIsNotNeededWhileAMemberSeatIsOpen() {
        View party = view(List.of(human(ANN), companion("a", 101, ANN)), held(ANN, "b", Kind.HOLD, 1),
                held(ANN, "c", Kind.AWAY, 2), held(ANN, "d", Kind.TRAVEL, 3), held(ANN, "e", Kind.TRAVEL, 4));

        assertEquals(RoomOutcome.NOT_NEEDED, CompanionSlots.makeRoom(party, BEN).outcome());
    }

    @Test
    void makeRoomSendsTheGuestOutFirst() {
        View full = view(List.of(human(ANN), companion("a", 101, ANN), guest(950, ANN), companion("b", 102, ANN),
                companion("c", 103, ANN), companion("d", 104, ANN)));

        MakeRoom room = CompanionSlots.makeRoom(full, BEN);

        assertEquals(RoomOutcome.STEP_OUT, room.outcome());
        assertEquals(950, room.stepOut().characterId());
        assertTrue(room.stepOut().guest());
    }

    @Test
    void makeRoomWithoutGuestsSendsTheLastOfTheUsualLineupOut() {
        View full = new View(List.of(human(ANN), companion("a", 101, ANN), companion("b", 102, ANN),
                companion("c", 103, ANN), companion("d", 104, ANN), companion("e", 105, ANN)), List.of(), Map.of(),
                Map.of("a", 1, "b", 2, "c", 5, "d", 3, "e", 4));

        MakeRoom room = CompanionSlots.makeRoom(full, BEN);

        assertEquals(RoomOutcome.STEP_OUT, room.outcome());
        assertEquals("c", room.stepOut().key());
        assertEquals(ANN, room.stepOut().ownerId());
    }

    @Test
    void makeRoomTakesFromTheCrewOverItsShareOnceTheNewHumanIsIn() {
        // Ann 3 + Ben 1 in a full party; with Cleo in, each share is 1: Ann's back-of-lineup companion goes
        View full = new View(List.of(human(ANN), companion("a", 101, ANN), companion("b", 102, ANN),
                companion("c", 103, ANN), human(BEN), companion("brin", 201, BEN)), List.of(), Map.of(),
                Map.of("a", 1, "b", 2, "c", 3, "brin", 1));

        assertEquals("c", CompanionSlots.makeRoom(full, CLEO).stepOut().key());
    }

    @Test
    void nobodyCanStepOutOfAPartyOfHumansAndNobodysAgents() {
        Member plainAgent = new Member(CompanionSlots.characterKey(960), 960, false, 0, false);
        View full = view(List.of(human(ANN), human(BEN), human(CLEO), human(DAN), human(50), plainAgent));

        assertEquals(RoomOutcome.IMPOSSIBLE, CompanionSlots.makeRoom(full, 60).outcome());
        assertFalse(CompanionSlots.canAdmit(full, false));
    }

    @Test
    void humansComeFirstUnlessItIsAStarterParty() {
        View full = view(List.of(human(ANN), companion("a", 101, ANN), companion("b", 102, ANN),
                companion("c", 103, ANN), companion("d", 104, ANN), companion("e", 105, ANN)));

        assertTrue(CompanionSlots.canAdmit(full, false));
        assertFalse(CompanionSlots.canAdmit(full, true));
        assertTrue(CompanionSlots.canAdmit(view(List.of(human(ANN))), false));
    }

    // ---- live reservations, views and party events ----------------------------------------

    private final LivingWorld world = mock(LivingWorld.class);
    private final ResidentRuntime residents = mock(ResidentRuntime.class);
    private final RosterService roster = mock(RosterService.class);
    private final LivingStore store = mock(LivingStore.class);
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private CompanionSlots slots;

    @BeforeEach
    void wire() {
        when(world.residents()).thenReturn(residents);
        when(world.roster()).thenReturn(roster);
        when(world.store()).thenReturn(store);
        when(store.lineupSlots(anyInt())).thenReturn(Map.of());
        persona(101, "hadrik", ANN);
        persona(102, "vex", ANN);
        persona(201, "brin", BEN);
        slots = new CompanionSlots(world, now::get);
    }

    @Test
    void aReservationBelongsToOneOwnerAndLapses() {
        assertTrue(slots.reserve(ANN, "hadrik", Kind.TRAVEL, now.get() + 1_000L));
        assertFalse(slots.reserve(BEN, "hadrik", Kind.GUEST, FOREVER));
        assertTrue(slots.reserve(ANN, "hadrik", Kind.AWAY, FOREVER)); // the owner's own is replaced
        assertEquals(Kind.AWAY, slots.reservation("hadrik").kind());

        assertTrue(slots.reserve(ANN, "vex", Kind.TRAVEL, now.get() + 1_000L));
        now.addAndGet(1_000L);
        assertNull(slots.reservation("vex"));
        assertTrue(slots.reserve(BEN, "vex", Kind.HOLD, FOREVER)); // a lapsed seat is anyone's
    }

    @Test
    void releaseByKindLeavesOtherKindsAlone() {
        slots.reserve(ANN, "hadrik", Kind.AWAY, FOREVER);

        assertFalse(slots.release("hadrik", Kind.TRAVEL));
        assertEquals(Kind.AWAY, slots.reservation("hadrik").kind());
        assertTrue(slots.release("hadrik", Kind.AWAY));
        assertNull(slots.reservation("hadrik"));
    }

    @Test
    void aSoloOwnersFreeSeatsCountTheirReservations() {
        Character ann = character(ANN, null);
        slots.reserve(ANN, "hadrik", Kind.AWAY, FOREVER);
        slots.reserve(BEN, "brin", Kind.AWAY, FOREVER);

        assertEquals(4, slots.free(ann));
    }

    @Test
    void aReservationOfAMemberIsNotCountedTwice() {
        Party party = party(member(ANN, false), member(101, true));
        Character ann = character(ANN, party);
        slots.reserve(ANN, "hadrik", Kind.TRAVEL, FOREVER); // held for its join, which has happened

        assertEquals(4, slots.free(ann));
        slots.reserve(ANN, "vex", Kind.TRAVEL, FOREVER); // still on its way: it holds a seat
        assertEquals(3, slots.free(ann));
    }

    @Test
    void aGuestMemberIsChargedToItsHost() {
        Party party = party(member(ANN, false), member(BEN, false), member(950, true));
        slots.reserve(ANN, CompanionSlots.characterKey(950), Kind.GUEST, FOREVER);

        assertEquals(1, slots.free(character(ANN, party)));
        assertEquals(2, slots.free(character(BEN, party)));
        assertEquals(1, slots.shareFor(character(CLEO, null), party)); // Cleo would make it 3 humans
    }

    @Test
    void aJoinUsesUpTheJoinersReservationButAGuestKeepsItsSeat() {
        Party party = party(member(ANN, false), member(101, true), member(950, true));
        Character hadrik = character(101, party);
        Character guest = character(950, party);
        when(roster.onlineCharacter(101)).thenReturn(hadrik);
        when(roster.onlineCharacter(950)).thenReturn(guest);
        slots.reserve(ANN, "hadrik", Kind.TRAVEL, FOREVER);
        slots.reserve(ANN, CompanionSlots.characterKey(950), Kind.GUEST, FOREVER);

        slots.onPartyChanged(event(party, PartyOperation.JOIN, 101));
        slots.onPartyChanged(event(party, PartyOperation.JOIN, 950));

        assertNull(slots.reservation("hadrik"));
        assertEquals(Kind.GUEST, slots.reservation(CompanionSlots.characterKey(950)).kind());

        slots.onPartyChanged(event(party, PartyOperation.LEAVE, 950));
        assertNull(slots.reservation(CompanionSlots.characterKey(950)));
    }

    @Test
    void aReservationTakenAgainAfterTheJoinerLeftIsKept() {
        Party party = party(member(ANN, false));
        Character hadrik = character(101, null); // it joined, then left before the event was handled
        when(roster.onlineCharacter(101)).thenReturn(hadrik);
        slots.reserve(ANN, "hadrik", Kind.AWAY, FOREVER);

        slots.onPartyChanged(event(party, PartyOperation.JOIN, 101));

        assertEquals(Kind.AWAY, slots.reservation("hadrik").kind());
    }

    @Test
    void aLeaderCapLastsUntilThePartyDisbands() {
        Party party = party(member(ANN, false), member(BEN, false), member(101, true));
        Character ann = character(ANN, party);
        assertEquals(1, slots.free(ann));

        slots.recordLeaderExpel(party, ANN);
        assertEquals(1, slots.leaderCut(party.getId(), ANN));
        assertEquals(0, slots.free(ann));

        slots.onPartyChanged(new PartyChangeEvent(party.getId(), PartyOperation.DISBAND, BEN, BEN,
                new Snapshot(List.of(ANN, BEN, 101), BEN), Snapshot.EMPTY, null, true));
        assertEquals(0, slots.leaderCut(party.getId(), ANN));
        assertEquals(1, slots.free(ann));
    }

    @Test
    void aDisbandGivesEveryGuestSeatBack() {
        Party party = party(member(ANN, false), member(950, true));
        slots.reserve(ANN, CompanionSlots.characterKey(950), Kind.GUEST, FOREVER);
        slots.reserve(ANN, "hadrik", Kind.AWAY, FOREVER);

        slots.onPartyChanged(new PartyChangeEvent(party.getId(), PartyOperation.DISBAND, ANN, ANN,
                new Snapshot(List.of(ANN, 950), ANN), Snapshot.EMPTY, null, true));

        assertNull(slots.reservation(CompanionSlots.characterKey(950)));
        assertEquals(Kind.AWAY, slots.reservation("hadrik").kind()); // an errand's seat goes with its owner
    }

    @Test
    void theLiveMakeRoomReadsTheUsualLineup() {
        Party party = party(member(ANN, false), member(101, true), member(102, true), member(103, true),
                member(104, true), member(105, true));
        persona(103, "c", ANN);
        persona(104, "d", ANN);
        persona(105, "e", ANN);
        when(store.lineupSlots(ANN)).thenReturn(Map.of("hadrik", 1, "vex", 5, "c", 2, "d", 3, "e", 4));

        MakeRoom room = slots.makeRoom(party, character(BEN, null));

        assertEquals("vex", room.stepOut().key());
        assertTrue(slots.canAdmit(party, character(BEN, null)));
    }

    // ---- helpers -------------------------------------------------------------------------

    private static Member human(int id) {
        return new Member(CompanionSlots.characterKey(id), id, true, 0, false);
    }

    private static Member companion(String personaId, int id, int ownerId) {
        return new Member(personaId, id, false, ownerId, false);
    }

    private static Member guest(int id, int hostId) {
        return new Member(CompanionSlots.characterKey(id), id, false, hostId, true);
    }

    private static Reservation held(int ownerId, String key, Kind kind, long seq) {
        return new Reservation(ownerId, key, kind, FOREVER, seq);
    }

    private static View view(List<Member> members, Reservation... reservations) {
        return new View(members, List.of(reservations), Map.of(), Map.of());
    }

    private static List<String> keys(List<Claim> claims) {
        List<String> keys = new ArrayList<>();
        for (Claim claim : claims) {
            keys.add(claim.key());
        }
        return keys;
    }

    private void persona(int characterId, String personaId, int ownerId) {
        Persona persona = mock(Persona.class);
        when(persona.id()).thenReturn(personaId);
        when(residents.personaOfCharacterId(characterId)).thenReturn(persona);
        when(roster.cachedOwnerOf(personaId)).thenReturn(ownerId);
    }

    private static PartyCharacter member(int id, boolean bot) {
        PartyCharacter member = mock(PartyCharacter.class);
        when(member.getId()).thenReturn(id);
        when(member.isBot()).thenReturn(bot);
        when(member.isOnline()).thenReturn(true);
        return member;
    }

    private static Party party(PartyCharacter leader, PartyCharacter... others) {
        Party party = new Party(77, leader);
        party.addMember(leader);
        for (PartyCharacter other : others) {
            party.addMember(other);
        }
        return party;
    }

    private static Character character(int id, Party party) {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(id);
        when(character.getParty()).thenReturn(party);
        return character;
    }

    private static PartyChangeEvent event(Party party, PartyOperation op, int targetId) {
        Snapshot now = new Snapshot(List.of(), party.getLeaderId());
        return new PartyChangeEvent(party.getId(), op, targetId, 0, now, now, null, true);
    }
}
