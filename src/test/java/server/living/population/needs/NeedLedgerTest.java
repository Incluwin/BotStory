package server.living.population.needs;

import client.Character;
import client.Job;
import config.PopulationConfig;
import net.server.PlayerStorage;
import net.server.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.living.population.ChatterEngine;
import server.living.population.Citizen;
import server.living.population.CitizenSession;
import server.living.population.CitizenSessions;
import server.living.population.FieldCatalog;
import server.living.population.Market;
import server.living.population.PersonMemories;
import server.living.population.PersonMemory;
import server.living.population.Population;
import server.living.population.PopulationContent;
import server.living.population.PresenceScheduler;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The needs board's ledger (docs/BOT_CONCEPT_PLAN.md F1): at most two item requests a citizen (a forming party apart),
 * requests that follow what it carries, a player's holds (three, an hour each), deadlines and the repost pause; a
 * hand-over in person moves the item into the citizen's inventory and its mesos to the player and warms it (FULFILLED);
 * a board hand-in for a citizen who is away waits in escrow, which its next load takes, and its thanks are whispered
 * once both are online; a gear want (D4) holds its pick back until the deadline; and the market buys toward a want.
 */
class NeedLedgerTest {
    private static final int CITIZEN = 5001;
    private static final int OTHER_CITIZEN = 5002;
    private static final int ANN = 10;
    private static final int BO = 11;
    private static final int WHITE = 2000002;
    private static final int MANA = 2000006;
    private static final int CLAW_ATT = 2044701;
    private static final int GARNIER = 1472030;
    private static final int HENESYS = 100000000;
    private static final long HOUR = 3_600_000L;
    private static final long NOW = 1_800_000_000_000L;

    private final PopulationConfig config = new PopulationConfig();
    private final Population population = mock(Population.class);
    private final PresenceScheduler presence = mock(PresenceScheduler.class);
    private final PersonMemories memories = mock(PersonMemories.class);
    private final ChatterEngine chatter = mock(ChatterEngine.class);
    private final Market market = mock(Market.class);
    private final FieldCatalog fields = mock(FieldCatalog.class);
    private final PopulationContent content = mock(PopulationContent.class);
    private final MemoryStore store = new MemoryStore();
    private final FakeHands hands = new FakeHands();
    private final Character citizenChr = mock(Character.class);
    private final Character ann = mock(Character.class);
    private final Character bo = mock(Character.class);
    private MockedStatic<AgentRuntimeIdentityRuntime> identity;
    private CitizenSession session;
    private NeedLedger ledger;

    @BeforeEach
    void setUp() {
        config.NEEDS_ENABLED = true;
        config.NEEDS_SCROLL_CHANCE = 0.0d;
        when(population.config()).thenReturn(config);
        when(population.presence()).thenReturn(presence);
        when(population.memories()).thenReturn(memories);
        when(population.chatter()).thenReturn(chatter);
        when(population.market()).thenReturn(market);
        when(population.fields()).thenReturn(fields);
        when(population.content()).thenReturn(content);
        when(population.citizen(CITIZEN)).thenReturn(citizen(CITIZEN, "Kim"));
        when(population.citizen(OTHER_CITIZEN)).thenReturn(citizen(OTHER_CITIZEN, "Lu"));
        when(citizenChr.getId()).thenReturn(CITIZEN);
        when(citizenChr.getMapId()).thenReturn(HENESYS);
        when(citizenChr.getMeso()).thenReturn(2_000_000);
        when(ann.getId()).thenReturn(ANN);
        when(ann.getName()).thenReturn("Ann");
        when(ann.getJob()).thenReturn(Job.BEGINNER);
        when(bo.getJob()).thenReturn(Job.BEGINNER);
        when(bo.getId()).thenReturn(BO);
        when(bo.getName()).thenReturn("Bo");
        session = CitizenSessions.loaded(citizen(CITIZEN, "Kim"), 0L, 10 * HOUR);
        session.entry = mock(AgentRuntimeEntry.class);
        session.mapId = HENESYS;
        identity = mockStatic(AgentRuntimeIdentityRuntime.class);
        identity.when(() -> AgentRuntimeIdentityRuntime.bot(any())).thenReturn(citizenChr);
        ledger = new NeedLedger(population, store, hands, new FakeItems());
        ledger.start(NOW);
    }

    @AfterEach
    void close() {
        identity.close();
    }

    private static Citizen citizen(int id, String name) {
        return new Citizen(id, name, "evening", 0, 0, "victoria", "hunter", "relaxed-v1", "default", 1.0d, 0, 1,
                new LinkedHashSet<>(), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
    }

    private Need post(NeedKind kind, int itemId, int quantity, long offer, long nowMs) {
        return ledger.post(CITIZEN, session, HENESYS, new NeedDerivation.Proposal(kind, itemId, quantity, offer, "easy_balrog"),
                nowMs + 24 * HOUR, nowMs);
    }

    private void online() {
        when(presence.session(CITIZEN)).thenReturn(session);
    }

    @Test
    void aCitizenAsksForAtMostTwoThingsAndItsPartyIsApart() {
        assertNotNull(post(NeedKind.SUPPLY, WHITE, 100, 345, NOW));
        assertNotNull(post(NeedKind.SUPPLY, MANA, 50, 700, NOW));
        assertNull(post(NeedKind.SCROLL, CLAW_ATT, 1, 2_500_000, NOW), "NEEDS_PER_CITIZEN is 2");

        ledger.partyShort(session, "Balrog", 105100100, 2, "cleric", NOW);
        assertEquals(3, ledger.of(CITIZEN).size(), "a forming party's request is apart from the two");
        Need party = ledger.of(CITIZEN).stream().filter(need -> need.kind() == NeedKind.PARTY).findFirst().orElseThrow();
        assertEquals("1 more cleric", ledger.what(party.withQuantity(1, 0L)));
        assertEquals("cleric", ledger.forWords(party));

        ledger.partyOver(CITIZEN, NOW + 60_000L);
        assertTrue(ledger.of(CITIZEN).stream().noneMatch(need -> need.kind() == NeedKind.PARTY), "it went in");
        ledger.partyShort(session, "Balrog", 105100100, 3, "", NOW + 2 * HOUR);
        assertTrue(ledger.of(CITIZEN).stream().anyMatch(need -> need.kind() == NeedKind.PARTY),
                "its next party asks at once: no repost pause for a party");
    }

    @Test
    void supplyRequestsFollowWhatItCarries() {
        Need white = post(NeedKind.SUPPLY, WHITE, 100, 345, NOW);
        ledger.reconcile(session, HENESYS, List.of(white),
                List.of(new NeedDerivation.Proposal(NeedKind.SUPPLY, WHITE, 60, 345, "easy_balrog"),
                        new NeedDerivation.Proposal(NeedKind.SUPPLY, MANA, 20, 700, "easy_balrog")), NOW + HOUR);
        assertEquals(60, ledger.need(white.id()).quantity(), "it bought 40 itself meanwhile");
        assertEquals(2, ledger.open().size(), "and now lacks Mana Elixirs too");

        Need mana = ledger.open().stream().filter(need -> need.itemId() == MANA).findFirst().orElseThrow();
        ledger.reconcile(session, HENESYS, List.of(ledger.need(white.id()), mana), List.of(), NOW + 2 * HOUR);
        assertTrue(ledger.open().isEmpty(), "it carries enough: bought elsewhere");
        assertEquals(Need.State.BOUGHT, store.rows.get(white.id()).state());
    }

    @Test
    void aPlayerHoldsThreeRequestsForAnHourEach() {
        Need first = post(NeedKind.SUPPLY, WHITE, 100, 345, NOW);
        Need second = post(NeedKind.SUPPLY, MANA, 50, 700, NOW);
        when(population.citizen(OTHER_CITIZEN)).thenReturn(citizen(OTHER_CITIZEN, "Lu"));
        Need third = ledger.post(OTHER_CITIZEN, null, HENESYS,
                new NeedDerivation.Proposal(NeedKind.SCROLL, CLAW_ATT, 1, 2_500_000L, "1472030"), NOW + HOUR, NOW);
        Need fourth = ledger.post(OTHER_CITIZEN, null, HENESYS,
                new NeedDerivation.Proposal(NeedKind.SUPPLY, WHITE, 10, 345, "easy_balrog"), NOW + HOUR, NOW);

        assertEquals(NeedLedger.Claim.OK, ledger.claim(ANN, first.id(), NOW));
        assertEquals(NeedLedger.Claim.HELD, ledger.claim(BO, first.id(), NOW), "Ann holds it");
        assertEquals(NeedLedger.Claim.OK, ledger.claim(ANN, second.id(), NOW));
        assertEquals(NeedLedger.Claim.OK, ledger.claim(ANN, third.id(), NOW));
        assertEquals(NeedLedger.Claim.LIMIT, ledger.claim(ANN, fourth.id(), NOW), "three at once");
        assertEquals(3, ledger.claimedBy(ANN, NOW).size());

        long later = NOW + 61 * 60_000L;
        assertEquals(0, ledger.need(first.id()).holder(later), "an hour later the hold ran out");
        assertEquals(NeedLedger.Claim.OK, ledger.claim(BO, first.id(), later));
        assertTrue(ledger.drop(BO, first.id(), later));
        assertEquals(0, ledger.need(first.id()).holder(later));
    }

    @Test
    void requestsEndAtTheirDeadlineAndAreNotAskedAgainRightAway() {
        Need white = post(NeedKind.SUPPLY, WHITE, 100, 345, NOW);
        Need gear = ledger.post(CITIZEN, session, HENESYS, new NeedDerivation.Proposal(NeedKind.GEAR, 1472030, 1, 900_000L,
                "weapon"), NOW + 48 * HOUR, NOW);
        ledger.expire(NOW + 25 * HOUR);
        assertEquals(Need.State.EXPIRED, store.rows.get(white.id()).state());
        assertTrue(ledger.need(gear.id()).isOpen(), "a gear want lasts its 48 hours");
        ledger.expire(NOW + 49 * HOUR);
        assertEquals(Need.State.BOUGHT, store.rows.get(gear.id()).state(), "past it: handed over as before");

        assertNull(post(NeedKind.SUPPLY, WHITE, 100, 345, NOW + 26 * HOUR), "NEEDS_REPOST_HOURS 12");
        assertNotNull(post(NeedKind.SUPPLY, WHITE, 100, 345, NOW + 38 * HOUR));
    }

    @Test
    void aHiredCitizensRequestsEnd() {
        Need white = post(NeedKind.SUPPLY, WHITE, 100, 345, NOW);
        Citizen hired = citizen(CITIZEN, "Kim").withFlag(Citizen.FLAG_HIRED);
        when(population.citizen(CITIZEN)).thenReturn(hired);
        ledger.expire(NOW + 60_000L);
        assertEquals(Need.State.GONE, store.rows.get(white.id()).state());
    }

    @Test
    void inPersonTheItemGoesIntoItsInventoryItPaysAndItWarms() {
        online();
        Need white = post(NeedKind.SUPPLY, WHITE, 100, 345, NOW);
        hands.carried.put(ANN, 120);

        assertNull(ledger.handOver(ann, white.id(), 40, false, NOW));
        assertEquals(40, hands.inventory.get(CITIZEN), "into the citizen's own inventory, not destroyed");
        assertEquals(40 * 345L, hands.paid.get(ANN), "paid from its purse");
        assertEquals(2_000_000L - 40 * 345L, hands.purse.get(CITIZEN));
        assertEquals(60, ledger.need(white.id()).remaining());
        verify(memories).record(CITIZEN, ANN, PersonMemory.Kind.FULFILLED, HENESYS, "white potion", 40);
        assertEquals(List.of(WHITE), hands.used, "it readies its potions");

        assertNull(ledger.handOver(ann, white.id(), 80, false, NOW + 60_000L));
        assertEquals(100, hands.inventory.get(CITIZEN), "only what it still lacked");
        assertNull(ledger.need(white.id()), "met");
        assertEquals(Need.State.MET, store.rows.get(white.id()).state());
    }

    @Test
    void aHandOverIsRefusedWhenItCannotPayOrSomeoneElseHoldsIt() {
        online();
        Need white = post(NeedKind.SUPPLY, WHITE, 100, 345, NOW);
        hands.carried.put(ANN, 100);
        hands.carried.put(BO, 100);
        hands.purse.put(CITIZEN, 1_000L);
        assertEquals("Kim can't pay that right now.", ledger.handOver(ann, white.id(), 100, false, NOW));

        hands.purse.put(CITIZEN, 2_000_000L);
        ledger.claim(BO, white.id(), NOW);
        assertEquals("Someone else is bringing that.", ledger.handOver(ann, white.id(), 10, false, NOW));
        hands.carried.put(BO, 0);
        assertEquals("You don't have 10 of those.", ledger.handOver(bo, white.id(), 10, false, NOW));
        assertNull(hands.inventory.get(CITIZEN), "nothing moved");
        when(presence.session(CITIZEN)).thenReturn(null);
        assertEquals("Kim is not here.", ledger.handOver(bo, white.id(), 10, false, NOW), "in person needs the citizen");
    }

    @Test
    void aBoardHandInForAnAbsentCitizenWaitsInEscrowItsLoadTakesItAndItThanksLater() {
        Need claw = ledger.post(CITIZEN, null, HENESYS, new NeedDerivation.Proposal(NeedKind.SCROLL, CLAW_ATT, 1,
                2_500_000L, "1472030"), NOW + 24 * HOUR, NOW);
        hands.carried.put(ANN, 1);
        hands.purse.put(CITIZEN, 5_000_000L);

        assertNull(ledger.handOver(ann, claw.id(), 1, true, NOW), "the citizen is away: at a board");
        assertEquals(Need.State.ESCROW, ledger.need(claw.id()).state());
        assertEquals(1, hands.escrow.get(CITIZEN), "waiting for it, not in its inventory yet");
        assertTrue(ledger.escrowHolders().contains(CITIZEN));
        assertEquals(2_500_000L, hands.paid.get(ANN), "the player is paid at once");
        verify(memories).record(CITIZEN, ANN, PersonMemory.Kind.FULFILLED_BOARD, HENESYS, "claw att 60%", 1);

        when(fields.lobbiesFor(any(), anyInt())).thenReturn(List.of());
        ledger.onLoad(session, citizenChr, NOW + 2 * HOUR);
        assertEquals(1, hands.inventory.get(CITIZEN), "its load took it");
        assertFalse(ledger.escrowHolders().contains(CITIZEN));
        assertEquals(Need.State.MET, ledger.need(claw.id()).state(), "met, still owing thanks");
        assertEquals(List.of(CLAW_ATT), hands.used, "it scrolls its claw");

        online();
        when(presence.sessions()).thenReturn(List.of(session));
        World world = mock(World.class);
        PlayerStorage players = mock(PlayerStorage.class);
        when(population.gameWorld()).thenReturn(world);
        when(world.getPlayerStorage()).thenReturn(players);
        when(players.getCharacterById(ANN)).thenReturn(ann);
        when(chatter.compose(any())).thenReturn("thx for leaving the claw att 60% at the board Ann!!");
        when(citizenChr.getMap()).thenReturn(null);
        ledger.tick();
        verify(chatter).whisper(eq(session), eq(ann), anyString());
        assertNull(ledger.need(claw.id()), "thanked: done");
        assertTrue(store.rows.get(claw.id()).thanked());
    }

    @Test
    void aGearWantHoldsItsPickBackUntilItsDeadline() {
        int pick = 0;
        for (int candidate = 1_472_000; candidate < 1_472_200 && pick == 0; candidate++) {
            pick = NeedDerivation.wanted(CITIZEN, candidate, config.NEEDS_GEAR_WANT_SHARE) ? candidate : 0;
        }
        int unwanted = 0;
        for (int candidate = 1_472_000; candidate < 1_472_200 && unwanted == 0; candidate++) {
            unwanted = NeedDerivation.wanted(CITIZEN, candidate, config.NEEDS_GEAR_WANT_SHARE) ? 0 : candidate;
        }
        when(market.price(anyInt())).thenReturn(900_000L);

        assertFalse(ledger.gearWant(citizenChr, "weapon", unwanted, NOW), "70% are handed over as before");
        assertTrue(ledger.gearWant(citizenChr, "weapon", pick, NOW), "a want instead");
        Need want = ledger.open().get(0);
        assertEquals(NeedKind.GEAR, want.kind());
        assertEquals(pick, want.itemId());
        assertEquals(900_000L, want.offer(), "the index price");
        assertEquals(NOW + 48 * HOUR, want.deadlineMs(), "decision D4: 48 hours");
        assertTrue(ledger.gearWant(citizenChr, "weapon", pick, NOW + HOUR), "still wanted at the next load");

        assertFalse(ledger.gearWant(citizenChr, "weapon", pick, NOW + 49 * HOUR), "bought it elsewhere");
        assertEquals(Need.State.BOUGHT, store.rows.get(want.id()).state());
        assertFalse(ledger.gearWant(citizenChr, "weapon", pick, NOW + 50 * HOUR), "never asked twice");
    }

    @Test
    void theMarketBuysTowardAnOpenWantUpToItsOffer() {
        Need claw = ledger.post(CITIZEN, session, HENESYS, new NeedDerivation.Proposal(NeedKind.SCROLL, CLAW_ATT, 1,
                2_500_000L, "1472030"), NOW + 24 * HOUR, NOW);
        post(NeedKind.SUPPLY, WHITE, 100, 345, NOW);
        assertEquals(2_500_000L, ledger.marketOffer(CITIZEN, CLAW_ATT, Map.of(), NOW));
        assertEquals(0L, ledger.marketOffer(CITIZEN, WHITE, Map.of(), NOW), "potions come from the store, not a player's shop");
        assertTrue(ledger.wantsFromMarket(CITIZEN));
        Need garnier = ledger.post(OTHER_CITIZEN, null, HENESYS, new NeedDerivation.Proposal(NeedKind.GEAR, GARNIER, 1,
                900_000L, "weapon"), NOW + 48 * HOUR, NOW);
        assertEquals(900_000L, ledger.marketOffer(OTHER_CITIZEN, GARNIER, Map.of("weaponAttack", 25), NOW));
        assertEquals(0L, ledger.marketOffer(OTHER_CITIZEN, GARNIER, Map.of("weaponAttack", 1), NOW), "below base: not it");
        assertTrue(ledger.need(garnier.id()).isOpen());

        ledger.claim(ANN, claw.id(), NOW);
        assertEquals(0L, ledger.marketOffer(CITIZEN, CLAW_ATT, Map.of(), NOW), "Ann is bringing it");
        ledger.drop(ANN, claw.id(), NOW);

        ledger.boughtAtMarket(session, citizenChr, CLAW_ATT, BO, NOW);
        assertNull(ledger.need(claw.id()));
        assertEquals(Need.State.MET, store.rows.get(claw.id()).state());
        assertEquals(BO, store.rows.get(claw.id()).fulfilledBy(), "the shop's owner");
        assertEquals(List.of(CLAW_ATT), hands.used);
        verify(memories, never()).record(anyInt(), anyInt(), eq(PersonMemory.Kind.FULFILLED), anyInt(), anyString(), anyInt());
    }

    @Test
    void offTheLedgerDoesNothing() {
        config.NEEDS_ENABLED = false;
        assertFalse(ledger.gearWant(citizenChr, "weapon", 1472030, NOW));
        assertNull(ledger.posting(CITIZEN));
        assertEquals(0L, ledger.marketOffer(CITIZEN, CLAW_ATT, Map.of(), NOW));
        ledger.partyShort(session, "Balrog", 105100100, 2, "", NOW);
        assertTrue(ledger.of(CITIZEN).isEmpty());
    }

    /** living_need in memory. */
    private static final class MemoryStore extends NeedStore {
        final Map<Integer, Need> rows = new HashMap<>();
        private int next = 1;

        @Override
        public List<Need> live(long nowMs) {
            return new ArrayList<>();
        }

        @Override
        public List<Need> endedSince(long sinceMs) {
            return new ArrayList<>();
        }

        @Override
        public Set<Integer> gearAsked(int citizenId) {
            Set<Integer> out = new HashSet<>();
            for (Need need : rows.values()) {
                if (need.citizenId() == citizenId && need.kind() == NeedKind.GEAR) {
                    out.add(need.itemId());
                }
            }
            return out;
        }

        @Override
        public int insert(Need need) {
            int id = next++;
            rows.put(id, need.withId(id));
            return id;
        }

        @Override
        public void update(Need need) {
            rows.put(need.id(), need);
        }

        @Override
        public void update(Connection con, Need need) {
            rows.put(need.id(), need);
        }

        @Override
        public Set<Integer> escrowHolders() {
            return new HashSet<>();
        }
    }

    /** The few items the tests name; Garnier's base is 25 weapon attack. */
    private static final class FakeItems implements NeedLedger.Items {
        @Override
        public String name(int itemId) {
            return switch (itemId) {
                case WHITE -> "White Potion";
                case MANA -> "Mana Elixir";
                case CLAW_ATT -> "Scroll for Claw for ATT";
                case GARNIER -> "Garnier";
                default -> null;
            };
        }

        @Override
        public int successRate(int scrollId) {
            return scrollId == CLAW_ATT ? 60 : 0;
        }

        @Override
        public boolean canApplyScroll(int scrollId, int equipId) {
            return scrollId == CLAW_ATT && equipId == GARNIER;
        }

        @Override
        public boolean tradeable(int itemId) {
            return true;
        }

        @Override
        public boolean baseOrBetter(int itemId, Map<String, Object> stats) {
            return itemId != GARNIER || NeedQuality.baseOrBetter(stats, Map.of("weaponAttack", 25));
        }
    }

    /** Inventories and purses by character id. */
    private final class FakeHands implements NeedLedger.Hands {
        final Map<Integer, Integer> carried = new HashMap<>();
        final Map<Integer, Integer> inventory = new HashMap<>();
        final Map<Integer, Integer> escrow = new HashMap<>();
        final Map<Integer, Long> purse = new HashMap<>(Map.of(CITIZEN, 2_000_000L));
        final Map<Integer, Long> paid = new HashMap<>();
        final List<Integer> used = new ArrayList<>();

        @Override
        public int carried(Character player, Need need) {
            return carried.getOrDefault(player.getId(), 0);
        }

        @Override
        public long purse(int citizenId, Character citizen) {
            return purse.getOrDefault(citizenId, -1L);
        }

        @Override
        public String transfer(Character player, Character citizen, Need need, Need after, int count, long pay) {
            carried.merge(player.getId(), -count, Integer::sum);
            (citizen == null ? escrow : inventory).merge(need.citizenId(), count, Integer::sum);
            purse.merge(need.citizenId(), -pay, Long::sum);
            paid.merge(player.getId(), pay, Long::sum);
            store.update(after);
            return null;
        }

        @Override
        public List<Integer> takeEscrow(Character citizen, List<Need> escrowed) {
            Integer waiting = escrow.remove(citizen.getId());
            inventory.merge(citizen.getId(), waiting == null ? 0 : waiting, Integer::sum);
            for (Need need : escrowed) {
                store.update(need.closed(Need.State.MET, NOW));
            }
            return List.of(waiting == null ? 0 : waiting);
        }

        @Override
        public Boolean use(Character citizen, Need need) {
            used.add(need.itemId());
            return need.kind() == NeedKind.SCROLL ? Boolean.TRUE : null;
        }
    }
}
