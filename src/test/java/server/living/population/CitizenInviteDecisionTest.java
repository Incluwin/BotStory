package server.living.population;

import client.BuddyList;
import client.Character;
import net.server.world.Party;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.living.population.CitizenInviteDecision.Answer;
import server.living.population.CitizenInviteDecision.Familiarity;
import server.living.population.CitizenInviteDecision.Profile;
import server.living.population.CitizenInviteDecision.Temperament;
import server.living.population.CitizenStore.Acquaintance;
import server.living.population.PopulationContent.Voice;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A citizen's answer to an invite with personality (docs/BOT_MANAGEMENT_PLAN.md P4, "Bot invite decisions"): its
 * temperament comes from what the population keeps (its voice's reserve, its sociability, its flags), it knows a player
 * by their living_acquaintance minutes, a buddy list or a guild; a reserved citizen turns strangers down and goes with
 * someone it has partied with, an outgoing one goes with anyone, an easygoing one with familiar faces and some
 * strangers; one out with its own circle is busy; the stock statuses stay 22 and 23; and the luck in it is a fixed roll
 * per (citizen, player), so asking again never flips the answer.
 */
class CitizenInviteDecisionTest {
    private static final int ANN = 10;
    private static final int MIRA = 950;

    private final Population population = mock(Population.class);
    private final PresenceScheduler presence = mock(PresenceScheduler.class);
    private final VoicePacks voices = mock(VoicePacks.class);
    private final SocialFabric social = mock(SocialFabric.class);
    private final CitizenStore store = mock(CitizenStore.class);
    private final Character ann = character(ANN, 30);
    private final Character mira = character(MIRA, 30);
    private MockedStatic<Population> populations;

    @BeforeEach
    void wire() {
        populations = mockStatic(Population.class);
        populations.when(Population::get).thenReturn(population);
        when(population.presence()).thenReturn(presence);
        when(population.voices()).thenReturn(voices);
        when(population.social()).thenReturn(social);
        when(population.store()).thenReturn(store);
        when(store.acquaintance(anyInt(), anyInt())).thenAnswer(inv -> new Acquaintance(inv.getArgument(0), inv.getArgument(1),
                0, 0, false, false));
    }

    @AfterEach
    void close() {
        populations.close();
    }

    // ---- temperament ----------------------------------------------------------------------------

    @Test
    void temperamentComesFromTheVoiceTheSociabilityAndTheFlags() {
        assertEquals(Temperament.RESERVED, CitizenInviteDecision.temperament(citizen(Set.of()), 0.3d, 55), "quiet-practical");
        assertEquals(Temperament.RESERVED, CitizenInviteDecision.temperament(citizen(Set.of()), 0.4d, 55), "terse-grinder");
        assertEquals(Temperament.RESERVED, CitizenInviteDecision.temperament(citizen(Set.of()), 0.15d, 35), "efficient-v1");
        assertEquals(Temperament.EASYGOING, CitizenInviteDecision.temperament(citizen(Set.of()), 0.15d, 55), "casual-typos");
        assertEquals(Temperament.EASYGOING, CitizenInviteDecision.temperament(citizen(Set.of()), 0.1d, 50), "explorer-v1");
        assertEquals(Temperament.OUTGOING, CitizenInviteDecision.temperament(citizen(Set.of()), 0.1d, 55), "playful-caps");
        assertEquals(Temperament.OUTGOING, CitizenInviteDecision.temperament(citizen(Set.of(Citizen.FLAG_RECRUITER)), 0.4d, 35),
                "a recruiter seeks people out, however it types");
        assertEquals(Temperament.OUTGOING, CitizenInviteDecision.temperament(citizen(Set.of(Citizen.FLAG_FAMER)), 0.3d, 55));
    }

    // ---- the answer -----------------------------------------------------------------------------

    @Test
    void aReservedCitizenTurnsDownStrangersButGoesWithSomeoneItHasPartiedWith() {
        Profile reserved = new Profile(MIRA, Temperament.RESERVED, 35);

        assertEquals(Answer.DECLINED, CitizenInviteDecision.welcome(reserved, ANN, () -> Familiarity.NONE));
        assertEquals(Answer.DECLINED, CitizenInviteDecision.welcome(reserved, ANN, () -> new Familiarity(0, 60, false, false)),
                "a face it has only seen around is still a stranger to it");
        assertEquals(Answer.ACCEPT, CitizenInviteDecision.welcome(reserved, ANN, () -> new Familiarity(4, 0, false, false)));
        assertEquals(Answer.ACCEPT, CitizenInviteDecision.welcome(reserved, ANN, () -> new Familiarity(0, 0, true, false)), "a buddy");
        assertEquals(Answer.ACCEPT, CitizenInviteDecision.welcome(reserved, ANN, () -> new Familiarity(0, 0, false, true)), "a guildmate");
        assertEquals(23, Answer.DECLINED.status(), "the stock '... have denied request to the party.'");
    }

    @Test
    void anOutgoingCitizenGoesWithAnyoneWithoutLookingThemUp() {
        Profile outgoing = new Profile(MIRA, Temperament.OUTGOING, 55);
        AtomicInteger reads = new AtomicInteger();

        for (int inviter = 1; inviter <= 50; inviter++) {
            assertEquals(Answer.ACCEPT, CitizenInviteDecision.welcome(outgoing, inviter, () -> {
                reads.incrementAndGet();
                return Familiarity.NONE;
            }));
        }
        assertEquals(0, reads.get(), "no acquaintance row is read for an answer that does not need it");
    }

    @Test
    void anEasygoingCitizenGoesWithFamiliarFacesAndSomeStrangersTheSameWayEveryTime() {
        Profile easygoing = new Profile(MIRA, Temperament.EASYGOING, 55);
        int accepted = 0;
        for (int inviter = 1; inviter <= 400; inviter++) {
            Answer first = CitizenInviteDecision.welcome(easygoing, inviter, () -> Familiarity.NONE);
            for (int retry = 0; retry < 5; retry++) {
                assertEquals(first, CitizenInviteDecision.welcome(easygoing, inviter, () -> Familiarity.NONE),
                        "asking again gives the same answer");
            }
            assertEquals(Answer.ACCEPT, CitizenInviteDecision.welcome(easygoing, inviter,
                    () -> new Familiarity(0, CitizenInviteDecision.SEEN_MINUTES, false, false)), "a familiar face");
            accepted += first == Answer.ACCEPT ? 1 : 0;
        }
        // 50 + sociability / 2 = 77 in 100 strangers
        assertTrue(accepted > 400 * 60 / 100 && accepted < 400 * 92 / 100, "accepted " + accepted + " of 400 strangers");
    }

    @Test
    void theRollIsFixedPerCitizenAndPlayer() {
        assertEquals(CitizenInviteDecision.roll(MIRA, ANN, 7L), CitizenInviteDecision.roll(MIRA, ANN, 7L));
        int differs = 0;
        for (int player = 1; player <= 50; player++) {
            int roll = CitizenInviteDecision.roll(MIRA, player, 7L);
            assertTrue(roll >= 0 && roll < 100);
            differs += roll != CitizenInviteDecision.roll(MIRA, player, 8L) ? 1 : 0;
        }
        assertTrue(differs > 30, "the invite and the offer are different coins");
    }

    // ---- from the live characters ----------------------------------------------------------------

    @Test
    void withoutAPersonalityOnFileTheLevelGapAloneDecides() {
        assertEquals(Answer.ACCEPT, CitizenInviteDecision.decide(ann, mira));
        verify(store, never()).acquaintance(anyInt(), anyInt());
    }

    @Test
    void aReservedCitizenWarmsUpToAPlayerItHasPartiedWith() {
        reserved();

        assertEquals(Answer.DECLINED, CitizenInviteDecision.decide(ann, mira));
        assertEquals(Answer.DECLINED, CitizenInviteDecision.decide(ann, mira), "no flip-flop on a retry");

        when(store.acquaintance(MIRA, ANN)).thenReturn(new Acquaintance(MIRA, ANN, 12, 30, false, false));
        assertEquals(Answer.ACCEPT, CitizenInviteDecision.decide(ann, mira));
    }

    @Test
    void aBuddyOrAGuildmateIsNoStrangerToAReservedCitizen() {
        reserved();
        BuddyList buddies = mock(BuddyList.class);
        when(mira.getBuddylist()).thenReturn(buddies);
        when(buddies.containsVisible(ANN)).thenReturn(true);
        assertEquals(Answer.ACCEPT, CitizenInviteDecision.decide(ann, mira));

        when(buddies.containsVisible(ANN)).thenReturn(false);
        when(population.citizen(MIRA)).thenReturn(citizen(Set.of()).withGuild(7));
        when(ann.getGuildId()).thenReturn(7);
        assertEquals(Answer.ACCEPT, CitizenInviteDecision.decide(ann, mira));
    }

    @Test
    void theLevelGapAndBusinessComeBeforeTemperament() {
        outgoing();
        when(mira.getLevel()).thenReturn(30 + 6);
        assertEquals(Answer.TOO_FAR, CitizenInviteDecision.decide(ann, mira));

        when(mira.getLevel()).thenReturn(30);
        CitizenSession session = mock(CitizenSession.class);
        when(presence.session(MIRA)).thenReturn(session);
        session.stallOpen = true;
        assertEquals(Answer.BUSY, CitizenInviteDecision.decide(ann, mira));
        verify(store, never()).acquaintance(anyInt(), anyInt());
    }

    @Test
    void aCitizenOutWithItsOwnCircleIsBusy() {
        outgoing();
        CitizenSession session = mock(CitizenSession.class);
        when(presence.session(MIRA)).thenReturn(session);
        session.partyId = 44; // the party its circle formed (SocialFabric.partyUp)
        Party circle = mock(Party.class);
        when(circle.getId()).thenReturn(44);
        when(mira.getParty()).thenReturn(circle);

        assertTrue(CitizenInviteDecision.withItsCircle(mira));
        assertEquals(Answer.BUSY, CitizenInviteDecision.decide(ann, mira));
        assertEquals(22, Answer.BUSY.status(), "the stock '... is taking care of another invitation.'");

        Party another = mock(Party.class);
        when(another.getId()).thenReturn(45);
        when(mira.getParty()).thenReturn(another);
        assertFalse(CitizenInviteDecision.withItsCircle(mira), "someone else's party is not its circle's");
    }

    // ---- staying on ------------------------------------------------------------------------------

    @Test
    void whetherAGuestOffersToStayIsItsOwnFixedChoiceWarmerForBuddies() {
        int reserved = 0;
        int easygoing = 0;
        int outgoing = 0;
        int reservedBuddy = 0;
        for (int owner = 1; owner <= 500; owner++) {
            boolean stays = CitizenInviteDecision.staysOn(new Profile(MIRA, Temperament.EASYGOING, 55), owner, Familiarity.NONE);
            assertEquals(stays, CitizenInviteDecision.staysOn(new Profile(MIRA, Temperament.EASYGOING, 55), owner,
                    Familiarity.NONE), "the same answer every time");
            easygoing += stays ? 1 : 0;
            reserved += CitizenInviteDecision.staysOn(new Profile(MIRA, Temperament.RESERVED, 35), owner, Familiarity.NONE) ? 1 : 0;
            outgoing += CitizenInviteDecision.staysOn(new Profile(MIRA, Temperament.OUTGOING, 55), owner, Familiarity.NONE) ? 1 : 0;
            reservedBuddy += CitizenInviteDecision.staysOn(new Profile(MIRA, Temperament.RESERVED, 35), owner,
                    new Familiarity(10, 10, true, false)) ? 1 : 0;
        }
        assertTrue(reserved < easygoing && easygoing < outgoing, reserved + " < " + easygoing + " < " + outgoing);
        assertTrue(reservedBuddy > reserved, "a buddy is asked more often");
        assertTrue(outgoing < 500, "even the warmest does not always stay");
    }

    @Test
    void timeTogetherWarmsAGuestUpToStayingOn() {
        Profile reserved = new Profile(MIRA, Temperament.RESERVED, 10); // 950 with Ann: roll 27

        assertFalse(CitizenInviteDecision.staysOn(reserved, ANN, Familiarity.NONE), "10 is under 27: no, this stint");
        assertFalse(CitizenInviteDecision.staysOn(reserved, ANN, new Familiarity(9, 0, false, false)), "whole tens only");
        assertTrue(CitizenInviteDecision.staysOn(reserved, ANN, new Familiarity(20, 0, false, false)),
                "20 party minutes on: 10 + 20 is over 27, so a no can become a yes");
    }

    @Test
    void theHireListAsksTheSameTemperamentAsTheInvite() {
        reserved();

        assertFalse(CitizenInviteDecision.welcomes(population.citizen(MIRA), ann, 0, 30, false),
                "seen around, never partied with: a reserved citizen takes no stranger's job");
        assertTrue(CitizenInviteDecision.welcomes(population.citizen(MIRA), ann, 5, 0, false), "partied with");
        assertTrue(CitizenInviteDecision.welcomes(population.citizen(MIRA), ann, 0, 0, true), "a buddy");
        assertFalse(CitizenInviteDecision.welcomes(null, ann, 5, 5, true));
    }

    @Test
    void staysOnReadsTheLiveCitizen() {
        outgoing();
        // 950 with Ann: roll 27, under an outgoing 55 + 35
        assertTrue(CitizenInviteDecision.staysOn(ann, mira, population.citizen(MIRA)));
        assertFalse(CitizenInviteDecision.staysOn(ann, mira, null));
    }

    // ---- helpers ---------------------------------------------------------------------------------

    // ---- what it remembers of them (docs/CITIZEN_PERSON_MEMORY_PLAN.md, decision D1) -------------------

    @Test
    void aGrudgeTurnsTheInviteDownUnlessItIsOutgoingOrTheyAreABuddyOrAGuildmate() {
        Profile reserved = new Profile(MIRA, Temperament.RESERVED, 35);
        Profile easygoing = new Profile(MIRA, Temperament.EASYGOING, 55);
        Profile outgoing = new Profile(MIRA, Temperament.OUTGOING, 55);
        Familiarity grudge = new Familiarity(40, 120, false, false, PersonMemory.COLD);

        assertEquals(Answer.DECLINED, CitizenInviteDecision.welcome(reserved, ANN, () -> grudge),
                "they partied, then the player let it down");
        assertEquals(Answer.DECLINED, CitizenInviteDecision.welcome(easygoing, ANN, () -> grudge));
        assertEquals(Answer.ACCEPT, CitizenInviteDecision.welcome(outgoing, ANN, () -> grudge));
        assertEquals(Answer.ACCEPT, CitizenInviteDecision.welcome(reserved, ANN, () -> new Familiarity(0, 0, true, false, -80)),
                "a buddy");
        assertEquals(Answer.ACCEPT, CitizenInviteDecision.welcome(easygoing, ANN, () -> new Familiarity(0, 0, false, true, -80)),
                "a guildmate");
        assertEquals(Answer.ACCEPT, CitizenInviteDecision.welcome(reserved, ANN,
                () -> new Familiarity(40, 120, false, false, PersonMemory.COLD + 1)), "cool is no grudge");
    }

    @Test
    void someoneItRemembersWarmlyIsNoStrangerToAReservedCitizen() {
        Profile reserved = new Profile(MIRA, Temperament.RESERVED, 35);

        assertEquals(Answer.DECLINED, CitizenInviteDecision.welcome(reserved, ANN,
                () -> new Familiarity(0, 0, false, false, PersonMemory.WARM - 1)));
        assertEquals(Answer.ACCEPT, CitizenInviteDecision.welcome(reserved, ANN,
                () -> new Familiarity(0, 0, false, false, PersonMemory.WARM)), "they talked and traded, never partied");
    }

    @Test
    void theInviteReadsHowWarmItFeelsFromItsMemory() {
        reserved();
        PersonMemories memories = mock(PersonMemories.class);
        when(population.memories()).thenReturn(memories);
        when(memories.warmth(MIRA, ANN)).thenReturn(PersonMemory.WARM);
        assertEquals(Answer.ACCEPT, CitizenInviteDecision.decide(ann, mira));

        when(memories.warmth(MIRA, ANN)).thenReturn(-58);
        when(store.acquaintance(MIRA, ANN)).thenReturn(new Acquaintance(MIRA, ANN, 12, 30, false, false));
        assertEquals(Answer.DECLINED, CitizenInviteDecision.decide(ann, mira), "a grudge outweighs the party minutes");
    }

    private void reserved() {
        when(population.citizen(MIRA)).thenReturn(citizen(Set.of()));
        when(voices.voiceOf(any())).thenReturn(voice(0.3d));
        when(social.sociability(any())).thenReturn(55);
    }

    private void outgoing() {
        when(population.citizen(MIRA)).thenReturn(citizen(Set.of()));
        when(voices.voiceOf(any())).thenReturn(voice(0.1d));
        when(social.sociability(any())).thenReturn(55);
    }

    private static Voice voice(double ignoreRate) {
        return new Voice("test", "friendly-casual-v1", 100, 0.8d, 0.0d, 0.0d, Map.of(), List.of(), 0.0d, List.of(), 0.0d, 50,
                ignoreRate);
    }

    private static Citizen citizen(Set<String> flags) {
        return new Citizen(MIRA, "Mira", "evening", 0, 0, "victoria", "hunter", "relaxed-v1", "test", 1.0d, 0, 1,
                new LinkedHashSet<>(flags), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
    }

    private static Character character(int id, int level) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getLevel()).thenReturn(level);
        return chr;
    }
}
