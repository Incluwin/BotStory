package server.living.roster;

import client.BuddyList;
import client.Character;
import client.Client;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.living.bond.BondService;
import server.living.bond.Friendship;
import server.living.config.Content.Persona;
import server.living.population.CitizenStore;
import server.living.population.CitizenStore.Acquaintance;
import server.living.population.Population;
import server.living.store.LivingStore.Bond;
import server.living.store.LivingStore.RosterState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.LITH;

/**
 * Companions ask you to be buddies (P9; decisions 2026-10-06): a Resident whose friendship with you reached "Friend"
 * (level 4), and a hired hand after 30 minutes in your party, send you the stock buddy request once while you are both
 * online, listing you on their own list first. Your own companions land in your "Crew" group when you accept; anyone
 * else in "Default Group". Nothing is asked with the kill switch off, of a Resident not in the world, or twice.
 */
class CompanionBuddiesTest {
    private static final int CASSE = 950;
    private static final int HIRE = 7001;

    private final LifecycleHarness game = new LifecycleHarness();
    private final BuddyList annList = new BuddyList(20);
    private final Client annClient = mock(Client.class);
    private final BondService bonds = mock(BondService.class);
    private final List<Bond> annBonds = new ArrayList<>();
    private final Map<Integer, Character> online = new HashMap<>();
    private final CitizenStore citizens = mock(CitizenStore.class);
    private final Map<Integer, Acquaintance> acquaintances = new HashMap<>();
    private Persona casse;
    private CompanionBuddies buddies;

    @BeforeEach
    void ann() {
        game.config.COMPANION_BUDDY_REQUESTS = true;
        when(game.ann.getBuddylist()).thenReturn(annList);
        when(game.ann.getClient()).thenReturn(annClient);
        when(annClient.getPlayer()).thenReturn(game.ann);
        when(annClient.getChannel()).thenReturn(1);
        casse = game.companion("casse", "Casse", CASSE, RosterState.BENCHED, LITH);
        game.roster();
        when(game.world.bonds()).thenReturn(bonds);
        Friendship friendship = new Friendship(game.world); // reads the config: not inside a stubbing
        when(game.world.friendship()).thenReturn(friendship);
        when(game.store.store.bondsOf(ANN)).thenAnswer(inv -> List.copyOf(annBonds));
        when(bonds.setFlag(anyInt(), anyString(), anyString())).thenAnswer(inv -> {
            int index = indexOf(inv.getArgument(1));
            Bond bond = annBonds.get(index);
            Set<String> flags = new LinkedHashSet<>(bond.flags());
            flags.add(inv.getArgument(2));
            annBonds.set(index, new Bond(bond.characterId(), bond.personaId(), bond.stage(), bond.points(), flags,
                    bond.contractState(), 0L, 0L, 0, 0L, 0, 0L, 0L));
            return annBonds.get(index);
        });
        when(citizens.acquaintance(anyInt(), eq(ANN))).thenAnswer(inv -> acquaintances.getOrDefault(
                (int) inv.getArgument(0), new Acquaintance(inv.getArgument(0), ANN, 0, 0, false, false)));
        org.mockito.Mockito.doAnswer(inv -> {
            Acquaintance saved = inv.getArgument(0);
            acquaintances.put(saved.characterId(), saved);
            return null;
        }).when(citizens).saveAcquaintance(any());
        Population population = mock(Population.class);
        when(population.store()).thenReturn(citizens);
        buddies = new CompanionBuddies(game.world, () -> population, online::get);
    }

    @AfterEach
    void close() {
        game.close();
    }

    private int indexOf(String personaId) {
        for (int i = 0; i < annBonds.size(); i++) {
            if (annBonds.get(i).personaId().equals(personaId)) {
                return i;
            }
        }
        throw new AssertionError(personaId);
    }

    private void friendship(String personaId, int points) {
        annBonds.add(new Bond(ANN, personaId, null, points, new LinkedHashSet<>(), null, 0L, 0L, 0, 0L, 0, 0L, 0L));
    }

    /** A loaded bot on channel 2 with its own buddy list. */
    private Character inTheWorld(int characterId, String name) {
        Character bot = game.bots.containsKey(characterId) ? game.bots.get(characterId) : game.bot(characterId, name);
        BuddyList list = new BuddyList(20);
        Client client = mock(Client.class);
        when(client.getChannel()).thenReturn(2);
        when(bot.getBuddylist()).thenReturn(list);
        when(bot.getClient()).thenReturn(client);
        online.put(characterId, bot);
        return bot;
    }

    @Test
    void aResidentAtFriendAsksOnceWhileBothAreOnline() {
        friendship("casse", 75); // level 4, Friend
        Character bot = inTheWorld(CASSE, "Casse");

        buddies.sweep(List.of(game.ann));
        buddies.sweep(List.of(game.ann));

        assertTrue(annList.contains(CASSE) && !annList.containsVisible(CASSE), "Ann has her request to answer");
        assertTrue(bot.getBuddylist().containsVisible(ANN), "Casse lists Ann");
        verify(bonds).setFlag(ANN, "casse", CompanionBuddies.ASKED_FLAG);
        verify(annClient, org.mockito.Mockito.times(1)).sendPacket(any());
    }

    @Test
    void belowFriendOrNotInTheWorldNobodyAsks() {
        friendship("casse", 74); // level 3
        inTheWorld(CASSE, "Casse");
        buddies.sweep(List.of(game.ann));
        assertFalse(annList.contains(CASSE));

        annBonds.clear();
        friendship("casse", 80);
        online.clear();
        buddies.sweep(List.of(game.ann));
        assertFalse(annList.contains(CASSE));
        verify(bonds, never()).setFlag(anyInt(), anyString(), anyString());

        inTheWorld(CASSE, "Casse");
        buddies.sweep(List.of(game.ann));
        assertTrue(annList.contains(CASSE), "it asks once it is in the world");
    }

    @Test
    void aHiredHandAsksAfterHalfAnHourInYourParty() {
        game.companion("hire:" + HIRE, "Pip", HIRE, RosterState.ACTIVE, LITH);
        Character pip = inTheWorld(HIRE, "Pip");
        game.outWithAnn(game.personas.get("hire:" + HIRE));
        acquaintances.put(HIRE, new Acquaintance(HIRE, ANN, 28, 40, false, false));

        buddies.sweep(List.of(game.ann));
        assertFalse(annList.contains(HIRE), "29 minutes together");
        assertEquals(29, acquaintances.get(HIRE).partyMinutes());

        buddies.sweep(List.of(game.ann));
        assertTrue(annList.contains(HIRE));
        assertTrue(acquaintances.get(HIRE).buddyAsked());
        assertTrue(pip.getBuddylist().containsVisible(ANN));

        buddies.sweep(List.of(game.ann));
        assertEquals(30, acquaintances.get(HIRE).partyMinutes(), "asked: no longer counted");
    }

    @Test
    void theKillSwitchAsksNothing() {
        game.config.COMPANION_BUDDY_REQUESTS = false;
        friendship("casse", 400);
        inTheWorld(CASSE, "Casse");

        buddies.sweep(List.of(game.ann));

        assertFalse(annList.contains(CASSE));
    }

    @Test
    void someoneAlreadyOnYourListIsNotAskedAgainAndIsMarked() {
        friendship("casse", 75);
        inTheWorld(CASSE, "Casse");
        annList.put(new client.BuddylistEntry("Casse", "Old friends", CASSE, 2, true));

        buddies.sweep(List.of(game.ann));

        verify(annClient, never()).sendPacket(any());
        verify(bonds).setFlag(ANN, "casse", CompanionBuddies.ASKED_FLAG);
    }

    @Test
    void yourOwnCompanionsLandInCrewAndEveryoneElseInDefaultGroup() {
        when(game.world.directory().ownerOfCharacter(CASSE)).thenReturn(ANN);
        when(game.world.directory().ownerOfCharacter(4242)).thenReturn(0);

        assertEquals("Crew", buddies.groupFor(game.ann, CASSE));
        assertEquals("Default Group", buddies.groupFor(game.ann, 4242));

        game.config.COMPANION_BUDDY_REQUESTS = false;
        assertEquals("Default Group", buddies.groupFor(game.ann, CASSE));
    }

    @Test
    void aHiredHandAtFriendshipLvFourAsksWithoutTheHalfHour() {
        game.config.HIRE_FRIENDSHIP_ENABLED = true; // docs/BOT_CONCEPT_PLAN.md C1.4: Lv 4 is a buddy
        game.companion("hire:" + HIRE, "Pip", HIRE, RosterState.BENCHED, LITH);
        Character pip = inTheWorld(HIRE, "Pip");
        when(bonds.peek(ANN, "hire:" + HIRE)).thenReturn(new Bond(ANN, "hire:" + HIRE, null, 75, new LinkedHashSet<>(), null,
                0L, 0L, 0, 0L, 0, 0L, 0L));

        buddies.sweep(List.of(game.ann));

        assertTrue(annList.contains(HIRE), "seeded at Lv 4 from its warmth: no 30 party minutes needed");
        assertTrue(acquaintances.get(HIRE).buddyAsked());
        assertTrue(pip.getBuddylist().containsVisible(ANN));
    }

    @Test
    void withHireFriendshipOffAHiredHandStillWaitsForItsHalfHour() {
        game.companion("hire:" + HIRE, "Pip", HIRE, RosterState.BENCHED, LITH);
        inTheWorld(HIRE, "Pip");
        when(bonds.peek(ANN, "hire:" + HIRE)).thenReturn(new Bond(ANN, "hire:" + HIRE, null, 400, new LinkedHashSet<>(), null,
                0L, 0L, 0, 0L, 0, 0L, 0L));

        buddies.sweep(List.of(game.ann));

        assertFalse(annList.contains(HIRE));
    }
}
