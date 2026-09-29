package server.living.roster.ui;

import client.BuddyList;
import client.Character;
import client.Client;
import client.Job;
import config.LivingWorldConfig;
import net.packet.Packet;
import net.server.world.Party;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.LivingWorld;
import server.living.config.LivingContent;
import server.living.population.Citizen;
import server.living.population.CitizenStore;
import server.living.population.FieldCatalog;
import server.living.population.Population;
import server.living.population.VoicePacks;
import server.living.resident.LivingTalk;
import server.living.resident.ResidentRuntime;
import server.living.roster.CompanionDirectory;
import server.living.roster.CompanionExecutor;
import server.living.roster.CompanionSlots;
import server.living.roster.CompanionViews;
import server.living.roster.DirectExecutor;
import server.living.roster.HiredHands;
import server.living.roster.RosterService;
import server.living.store.LivingStore;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;
import testutil.NpcTalks;
import testutil.NpcTalks.Talk;
import testutil.Packets;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The hire list, "Adventurers looking for a crew" (docs/BOT_MANAGEMENT_PLAN.md P4, decision 4 b, "Hire list"): the
 * Crew window offers it while there is room for a hire; it lists the citizens the owner has met who look for a crew for
 * them (still in the population, hired by nobody, partied up with nobody else, within the P2a invite level gap), buddies
 * first, then by time together, each "Name, LvN Job - where" ("- around" when not loaded); it pages with More... and has
 * Back to the Crew window; a row opens that citizen's hire terms; a row clicked after its citizen stopped looking draws
 * the list again; nobody on it is one line and Back; pending offers (the offers track's hook) top it as the offer.
 */
class HireListTest {
    private static final int ANN = 10;
    private static final int BOB = 20;
    private static final int PARTY_ID = 77;
    private static final int MIRA = 950;
    private static final int KIRA = 951;
    private static final int TOBI = 952;
    private static final int BRIN = 958;
    private static final int KERNING = 103000000;
    private static final byte MENU = 4;
    private static final byte ACCEPT_DECLINE = 0x0C;

    private final LivingWorld world = mock(LivingWorld.class);
    private final LivingContent content = mock(LivingContent.class);
    private final LivingStore store = mock(LivingStore.class);
    private final RosterService roster = mock(RosterService.class);
    private final CompanionSlots slots = mock(CompanionSlots.class);
    private final LivingWorldConfig config = new LivingWorldConfig();
    private final Population population = mock(Population.class);
    private final CitizenStore citizenStore = mock(CitizenStore.class);
    private final Character owner = mock(Character.class);
    private final Client client = mock(Client.class);
    private final BuddyList buddies = mock(BuddyList.class);
    private final Party annParty = mock(Party.class);
    private final List<RosterEntry> rows = new ArrayList<>();
    private final Map<Integer, Citizen> citizens = new HashMap<>();
    private final Map<Integer, Character> loaded = new HashMap<>();
    private final List<CitizenStore.Met> met = new ArrayList<>();
    private MockedStatic<Population> populations;
    private MockedStatic<AgentRuntimeRegistry> registry;
    private CompanionViews views;
    private CompanionMenus menus;

    @BeforeEach
    void wire() {
        when(owner.getId()).thenReturn(ANN);
        when(owner.getName()).thenReturn("Ann");
        when(owner.getLevel()).thenReturn(30);
        when(owner.getClient()).thenReturn(client);
        when(owner.getBuddylist()).thenReturn(buddies);
        when(annParty.getId()).thenReturn(PARTY_ID);
        when(client.getPlayer()).thenReturn(owner);
        when(client.canClickNPC()).thenReturn(true);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run(); // the owner's packet thread, at once
            return null;
        }).when(client).runOnIoThread(any());
        when(world.config()).thenReturn(config);
        when(world.content()).thenReturn(content);
        when(world.store()).thenReturn(store);
        when(world.roster()).thenReturn(roster);
        when(world.residents()).thenReturn(mock(ResidentRuntime.class));
        when(world.directory()).thenReturn(mock(CompanionDirectory.class));
        when(world.slots()).thenReturn(slots);
        CompanionExecutor ops = DirectExecutor.inline();
        when(world.executor()).thenReturn(ops);
        views = new CompanionViews(world, mapId -> "Ant Tunnel", itemId -> "Stolen Fence");
        when(world.views()).thenReturn(views);
        when(store.roster(ANN)).thenAnswer(inv -> List.copyOf(rows));
        when(roster.roster(owner)).thenReturn(rows);
        when(roster.describeAssignment(any())).thenReturn("train");
        HiredHands hands = new HiredHands(world);
        when(world.hiredHands()).thenReturn(hands);

        populations = mockStatic(Population.class);
        populations.when(Population::get).thenReturn(population);
        when(population.citizen(anyInt())).thenAnswer(inv -> citizens.get((int) inv.getArgument(0)));
        when(population.store()).thenReturn(citizenStore);
        when(citizenStore.metBy(ANN)).thenAnswer(inv -> List.copyOf(met));
        when(population.fields()).thenReturn(mock(FieldCatalog.class));
        VoicePacks voices = mock(VoicePacks.class);
        when(voices.render(any(), anyString(), anyLong())).thenAnswer(inv -> inv.getArgument(1));
        when(population.voices()).thenReturn(voices);
        registry = mockStatic(AgentRuntimeRegistry.class);
        registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(anyInt())).thenAnswer(inv -> {
            Character bot = loaded.get((int) inv.getArgument(0));
            return bot == null ? null : new AgentRuntimeEntry(bot, bot, null);
        });
        menus = new CompanionMenus(world, mapId -> "Ant Tunnel");
    }

    @AfterEach
    void close() {
        LivingTalk.forget(owner);
        registry.close();
        populations.close();
    }

    // ---- who is listed ---------------------------------------------------------------------------

    @Test
    void onlyCitizensLookingForACrewAreListedBuddiesFirstThenByTimeTogether() {
        loadedCitizen(MIRA, "Mira", 30, Job.HUNTER, "Kerning City", null, 0, 40);
        citizen(KIRA, "Kira", 28, Job.ASSASSIN.getId(), 1, 3); // offline, and a buddy of Ann's
        when(buddies.containsVisible(KIRA)).thenReturn(true);
        citizen(TOBI, "Tobi", 31, Job.CLERIC.getId(), 20, 30);
        citizen(953, "Vex", 36, Job.MAGICIAN.getId(), 50, 50); // six levels apart: past the invite level gap
        citizen(954, "Dorran", 30, Job.SPEARMAN.getId(), 50, 50);
        citizens.put(954, citizens.get(954).withFlag(Citizen.FLAG_HIRED)); // someone's hired hand
        Party elsewhere = mock(Party.class);
        when(elsewhere.getId()).thenReturn(88);
        loadedCitizen(955, "Olwen", 30, Job.BANDIT, "Henesys", elsewhere, 50, 50); // partied up with someone else
        citizen(956, "Jinx", 30, Job.BOWMAN.getId(), 50, 50);
        citizens.put(956, retired(citizens.get(956)));
        met.add(new CitizenStore.Met(957, 30, Job.FIGHTER.getId(), 50, 50)); // no citizen any more
        when(owner.getParty()).thenReturn(annParty);
        loadedCitizen(BRIN, "Brin", 29, Job.FIGHTER, "Henesys", annParty, 10, 10); // Ann's own guest
        guestSeat(BRIN, ANN);
        loadedCitizen(959, "Pax", 30, Job.PAGE, "Henesys", annParty, 50, 50); // Bob's guest in Ann's party
        guestSeat(959, BOB);

        menus.hireList(owner);

        Talk list = lastTalk();
        assertEquals(CompanionMenus.BOARD_NPC, list.npcId());
        assertEquals(CompanionMenus.NOTES_SPEAKER, list.speaker());
        assertTrue(list.text().startsWith("#eAdventurers looking for a crew#n\r\n#L"), list.text());
        assertEquals(List.of("Kira, Lv28 Assassin - around", "Tobi, Lv31 Cleric - around", "Mira, Lv30 Hunter - Kerning City",
                "Brin, Lv29 Fighter - Henesys", "Back"), list.labels());
    }

    @Test
    void theCrewWindowOffersTheListWhileThereIsRoomForAHireAndBackReturnsToIt() {
        citizen(TOBI, "Tobi", 31, Job.CLERIC.getId(), 20, 30);
        rows.add(new RosterEntry(ANN, "hire:800", RosterState.BENCHED, Assignment.TRAIN, 0, 0, KERNING, 0L, 0L, 0L, 0L, 0L, 0L,
                0, null));

        menus.crew(owner);
        pick("Adventurers looking for a crew...");
        assertEquals(List.of("Tobi, Lv31 Cleric - around", "Back"), lastTalk().labels());
        pick("Back");

        assertTrue(lastTalk().text().startsWith("#eParty "), lastTalk().text());

        LivingTalk.forget(owner);
        config.HIRE_MAX_PER_OWNER = 1; // Ann's one hire uses her hire cap
        views.invalidate(ANN);
        menus.crew(owner);
        assertFalse(lastTalk().labels().contains("Adventurers looking for a crew..."), lastTalk().labels().toString());

        LivingTalk.forget(owner);
        config.HIRE_MAX_PER_OWNER = 5;
        config.ROSTER_MAX = 1; // her roster is full
        menus.crew(owner);
        assertFalse(lastTalk().labels().contains("Adventurers looking for a crew..."), lastTalk().labels().toString());
    }

    // ---- paging and rows -------------------------------------------------------------------------

    @Test
    void aLongListPagesWithMoreAndKeepsBackOnEveryPage() {
        for (int index = 0; index < 12; index++) {
            citizen(1000 + index, "Citizen" + (char) ('A' + index), 30, Job.HUNTER.getId(), 0, 100 - index);
        }

        menus.hireList(owner);
        List<String> first = lastTalk().labels();
        assertEquals(12, first.size()); // ten rows, then "More..." and "Back"
        assertEquals("CitizenA, Lv30 Hunter - around", first.get(0));
        assertEquals("CitizenJ, Lv30 Hunter - around", first.get(9));
        assertEquals(List.of("More...", "Back"), first.subList(10, 12));

        pick("More...");

        assertEquals(List.of("CitizenK, Lv30 Hunter - around", "CitizenL, Lv30 Hunter - around", "Back to the top...", "Back"),
                lastTalk().labels());
    }

    @Test
    void aRowOpensThatCitizensHireTermsFromTheList() {
        loadedCitizen(MIRA, "Mira", 30, Job.HUNTER, "Kerning City", null, 0, 40);
        when(store.characterRow(MIRA)).thenReturn(new LivingStore.CharacterRow("Mira", 1, 2, 21001, 31002, 30,
                Job.HUNTER.getId(), KERNING));
        menus.hireList(owner);

        pick("Mira, Lv30 Hunter - Kerning City");

        Talk terms = lastTalk();
        assertEquals(ACCEPT_DECLINE, terms.msgType(), "the hire terms window");
        assertEquals(CompanionMenus.BOARD_NPC, terms.npcId(), "a hire has no rendered face");
        assertTrue(terms.text().startsWith("looking for work, actually. want a hand?"), terms.text());
        assertTrue(terms.text().contains("#eMira#n, Lv30 Hunter"), terms.text());
    }

    @Test
    void nobodyLookingIsOneLineAndBack() {
        citizen(953, "Vex", 40, Job.MAGICIAN.getId(), 50, 50); // met, but far out of range

        menus.hireList(owner);

        assertTrue(lastTalk().text().startsWith(CompanionCrewScreens.NOBODY_LOOKING + "\r\n#L"), lastTalk().text());
        assertEquals(List.of("Back"), lastTalk().labels());
        pick("Back");
        assertTrue(lastTalk().text().startsWith("#eParty "), lastTalk().text());
    }

    @Test
    void aRowClickedAfterItsCitizenStoppedLookingDrawsTheListAgain() {
        citizen(MIRA, "Mira", 30, Job.HUNTER.getId(), 0, 40);
        citizen(TOBI, "Tobi", 31, Job.CLERIC.getId(), 20, 30);
        menus.hireList(owner);

        citizens.put(MIRA, citizens.get(MIRA).withFlag(Citizen.FLAG_HIRED)); // Bob hired her meanwhile
        pick("Mira, Lv30 Hunter - around");

        Talk again = lastTalk();
        assertEquals(MENU, again.msgType(), "no terms window for her");
        assertTrue(again.text().startsWith(CompanionMenus.THINGS_CHANGED + "\r\n\r\n#eAdventurers looking for a crew#n"),
                again.text());
        assertEquals(List.of("Tobi, Lv31 Cleric - around", "Back"), again.labels());
    }

    // ---- the pending offers hook ---------------------------------------------------------------------

    @Test
    void pendingOffersTopTheListOnceAndOpenAsTheOffer() {
        citizen(TOBI, "Tobi", 31, Job.CLERIC.getId(), 20, 30);
        when(owner.getParty()).thenReturn(annParty);
        loadedCitizen(BRIN, "Brin", 29, Job.FIGHTER, "Henesys", annParty, 10, 10);
        guestSeat(BRIN, ANN);
        when(store.characterRow(BRIN)).thenReturn(new LivingStore.CharacterRow("Brin", 0, 1, 20000, 30000, 29,
                Job.FIGHTER.getId(), KERNING));
        CompanionCrewScreens crew = new CompanionCrewScreens(menus, who -> List.of(BRIN));

        crew.hireList(owner, null);

        assertEquals(List.of("Brin, Lv29 Fighter - Henesys (offered to stay)", "Tobi, Lv31 Cleric - around", "Back"),
                lastTalk().labels());
        pick("Brin, Lv29 Fighter - Henesys (offered to stay)");
        assertTrue(lastTalk().text().startsWith("this was fun. want me to stick around?"), lastTalk().text());
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    /** An offline citizen Ann has met ({@code partyMinutes} and {@code mapMinutes} together). */
    private void citizen(int characterId, String name, int level, int jobId, int partyMinutes, int mapMinutes) {
        citizens.put(characterId, new Citizen(characterId, name, "evening", 0, 0, "victoria", "hunter", "relaxed-v1",
                "default", 1.0d, 0, 1, new LinkedHashSet<>(), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, ""));
        met.add(new CitizenStore.Met(characterId, level, jobId, partyMinutes, mapMinutes));
    }

    /** A citizen Ann has met, loaded on the map named {@code where}, in {@code party} (or none). */
    private void loadedCitizen(int characterId, String name, int level, Job job, String where, Party party,
                               int partyMinutes, int mapMinutes) {
        citizen(characterId, name, 1, Job.BEGINNER.getId(), partyMinutes, mapMinutes); // its row is behind: it is live
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(characterId);
        when(bot.getName()).thenReturn(name);
        when(bot.getLevel()).thenReturn(level);
        when(bot.getJob()).thenReturn(job);
        MapleMap map = mock(MapleMap.class);
        when(map.getMapName()).thenReturn(where);
        when(bot.getMap()).thenReturn(map);
        when(bot.getParty()).thenReturn(party);
        loaded.put(characterId, bot);
    }

    private void guestSeat(int characterId, int hostId) {
        String key = CompanionSlots.characterKey(characterId);
        when(slots.reservation(key)).thenReturn(new CompanionSlots.Reservation(hostId, key, CompanionSlots.Kind.GUEST,
                Long.MAX_VALUE, 1L));
    }

    private static Citizen retired(Citizen citizen) {
        return new Citizen(citizen.characterId(), citizen.name(), citizen.cohort(), 0, 0, citizen.homeRegion(),
                citizen.career(), citizen.personality(), citizen.voice(), citizen.pace(), 0, 1, new LinkedHashSet<>(),
                Citizen.State.RETIRED, 0L, 0L, 0L, 0L, 0L, 0, "");
    }

    private void pick(String label) {
        int selection = lastTalk().selection(label);
        assertTrue(LivingTalk.respond(client, MENU, (byte) 1, Packets.buildInPacket(out -> out.writeInt(selection))));
    }

    private Talk lastTalk() {
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(client, atLeastOnce()).sendPacket(captor.capture());
        List<Talk> talks = NpcTalks.talks(captor.getAllValues());
        return talks.get(talks.size() - 1);
    }
}
