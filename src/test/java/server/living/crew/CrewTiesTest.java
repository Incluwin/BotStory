package server.living.crew;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.living.crew.CrewTies.SharedClear;
import server.living.crew.CrewTies.Tie;
import server.living.population.records.Clear;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static server.living.crew.CrewKit.OWNER;
import static server.living.crew.CrewKit.persona;
import static server.living.crew.CrewKit.rel;

/**
 * Crew ties (docs/BOT_CONCEPT_PLAN.md F6.4): seeded from authored relationships (both sides, debts mirrored) and from
 * lore spouses while they are still married, sets rather than one kind, affinity from clears the owner shared with
 * both, the login read and the booked clears counting alike, hires as crewmates and then friends, and rivals read from
 * memory for the story arcs.
 */
class CrewTiesTest {
    private static final int FENNA = 901;
    private static final int DORRAN = 902;
    private static final int ILSE = 903;
    private static final int VESNA = 904;
    private static final int ANSEL = 905;
    private static final int HIRE = 5001;
    private static final int OTHER_OWNER = 11;

    private final CrewKit.TiePorts ports = new CrewKit.TiePorts();
    private CrewTies ties;

    @BeforeEach
    void setUp() {
        ports.add(persona("fenna", true, List.of(rel("dorran", "RIVALS")), null), FENNA, OWNER)
                .add(persona("dorran", true, List.of(rel("fenna", "RIVALS"), rel("ilse", "OWES")), null), DORRAN, OWNER)
                .add(persona("ilse", true, List.of(), null), ILSE, OWNER)
                .add(persona("vesna", true, List.of(rel("ansel", "RIVAL")), "ansel"), VESNA, OWNER)
                .add(persona("ansel", true, List.of(rel("vesna", "RIVAL")), "vesna"), ANSEL, OWNER)
                .hire(HIRE, OWNER);
        ties = new CrewTies(ports);
    }

    @Test
    void authoredRivalsAreRivalsBothWaysAtTheRivalSeed() {
        Tie tie = ties.tie(OWNER, "fenna", "dorran");
        assertEquals(Set.of(TieKind.RIVAL), tie.kinds());
        assertEquals(30, tie.affinity());
        assertEquals(Set.of(TieKind.RIVAL), ties.tie(OWNER, "dorran", "fenna").kinds());
    }

    @Test
    void aDebtIsReadFromEachSide() {
        assertEquals(Set.of(TieKind.OWES), ties.tie(OWNER, "dorran", "ilse").kinds(), "dorran owes ilse");
        assertEquals(Set.of(TieKind.OWED), ties.tie(OWNER, "ilse", "dorran").kinds(), "ilse wrote nothing: mirrored");
    }

    @Test
    void marriedRivalsAreBothAndTheSpouseSeedWins() {
        Tie tie = ties.tie(OWNER, "vesna", "ansel");
        assertEquals(Set.of(TieKind.SPOUSE, TieKind.RIVAL), tie.kinds());
        assertEquals(60, tie.affinity());
    }

    @Test
    void aLoreSpouseWhoMarriedSomeoneElseHasParted() {
        ports.partners.put(VESNA, ANSEL);
        ports.partners.put(ANSEL, VESNA);
        assertTrue(ties.tie(OWNER, "vesna", "ansel").has(TieKind.SPOUSE), "both loaded and married to each other");

        ports.partners.put(VESNA, OWNER); // vesna wed her owner at the chapel
        Tie parted = ties.tie(OWNER, "vesna", "ansel");
        assertFalse(parted.has(TieKind.SPOUSE));
        assertEquals(Set.of(TieKind.RIVAL), parted.kinds(), "the rivalry stays");
    }

    @Test
    void nothingAuthoredIsACrewmateAndClearsTheOwnerSharedRaiseIt() {
        Tie tie = ties.tie(OWNER, "fenna", "ilse");
        assertEquals(Set.of(TieKind.CREWMATE), tie.kinds());
        assertEquals(0, tie.affinity());

        ports.clears.put(OWNER, List.of(
                new SharedClear(1, OWNER, "KerningPQ", 600_000L, List.of(FENNA, ILSE, DORRAN)),
                new SharedClear(2, OWNER, "KerningPQ", 540_000L, List.of(FENNA, ILSE))));
        ties.load(OWNER);
        Tie after = ties.tie(OWNER, "fenna", "ilse");
        assertEquals(2, after.sharedClears());
        assertEquals(6, after.affinity(), "3 points a shared clear");
        assertEquals(33, ties.tie(OWNER, "fenna", "dorran").affinity(), "rival seed 30 + one clear");
        assertEquals(540_000L, ties.best(OWNER, FENNA, "KerningPQ"), "the faster of the two");
    }

    @Test
    void aBookedClearCountsOnlyForAPlayerInItWithItsOwnCompanionsAndNeverTwice() {
        ports.clears.put(OWNER, List.of(new SharedClear(7, OWNER, "KerningPQ", 600_000L, List.of(FENNA, ILSE))));
        ties.load(OWNER);
        ports.add(persona("stranger", true, List.of(), null), 950, OTHER_OWNER);
        Clear clear = new Clear(7, "KerningPQ", "k", 1, 103000805, 0L, 0L, 600_000L, Clear.Kind.HUMAN, 0, List.of(
                new Clear.Member(OWNER, Clear.AS_PLAYER, "Ann"), new Clear.Member(FENNA, Clear.AS_COMPANION, "Fenna"),
                new Clear.Member(ILSE, Clear.AS_COMPANION, "Ilse"), new Clear.Member(950, Clear.AS_COMPANION, "Stranger")));

        List<SharedClear> moments = ties.booked(clear);

        assertEquals(1, ties.tie(OWNER, "fenna", "ilse").sharedClears(), "clear 7 was read at login already");
        assertEquals(1, moments.size());
        assertEquals(List.of(FENNA, ILSE), moments.getFirst().companionIds(), "another owner's companion is not ours");

        Clear next = new Clear(8, "KerningPQ", "k", 1, 103000805, 0L, 0L, 0L, Clear.Kind.HUMAN, 0, clear.members());
        ties.booked(next);
        assertEquals(2, ties.tie(OWNER, "fenna", "ilse").sharedClears());
    }

    @Test
    void aBookedClearOfAnOwnerNotReadYetCountsNothing() {
        Clear clear = new Clear(3, "KerningPQ", "k", 1, 0, 0L, 0L, 0L, Clear.Kind.HUMAN, 0, List.of(
                new Clear.Member(OWNER, Clear.AS_PLAYER, "Ann"), new Clear.Member(FENNA, Clear.AS_COMPANION, "Fenna"),
                new Clear.Member(ILSE, Clear.AS_COMPANION, "Ilse")));
        assertTrue(ties.booked(clear).isEmpty(), "the next login reads it back from living_clear");
        assertEquals(0, ties.tie(OWNER, "fenna", "ilse").sharedClears());
    }

    @Test
    void aHireIsACrewmateUntilEnoughSharedClearsMakeItAFriendAndNeverARival() {
        List<SharedClear> clears = new java.util.ArrayList<>();
        for (int i = 1; i <= 13; i++) {
            clears.add(new SharedClear(i, OWNER, "HenesysPQ", 0L, List.of(HIRE, FENNA)));
        }
        ports.clears.put(OWNER, clears);
        ties.load(OWNER);
        assertEquals(Set.of(TieKind.CREWMATE), ties.tie(OWNER, "hire:" + HIRE, "fenna").kinds(), "39: not yet");

        ports.clears.get(OWNER).add(new SharedClear(14, OWNER, "HenesysPQ", 0L, List.of(HIRE, FENNA)));
        ties.load(OWNER);
        Tie friends = ties.tie(OWNER, "hire:" + HIRE, "fenna");
        assertEquals(Set.of(TieKind.FRIEND), friends.kinds());
        assertEquals(42, friends.affinity());
        assertTrue(ties.rivals(OWNER).stream().noneMatch(tie -> tie.a().startsWith("hire:")));
    }

    @Test
    void aFailedReadLeavesTheSeedsAndNeverThrows() {
        ports.readFails = true;
        ties.load(OWNER);
        assertTrue(ties.loaded(OWNER));
        assertEquals(30, ties.tie(OWNER, "fenna", "dorran").affinity());
    }

    @Test
    void rivalsListsEachRivalPairOnTheOwnersRosterOnce() {
        ports.owners.remove("ansel"); // released: no longer this owner's
        List<Tie> rivals = ties.rivals(OWNER);
        assertEquals(1, rivals.size());
        assertEquals("dorran", rivals.getFirst().a());
        assertEquals("fenna", rivals.getFirst().b());
    }
}
