package server.living.roster;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.living.crew.CrewBanter;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.MapFactory;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static server.living.roster.BenchHarness.ANN;
import static server.living.roster.BenchHarness.BEN;
import static server.living.roster.BenchHarness.FIELD;
import static server.living.roster.BenchHarness.TOWN;

/**
 * The crew camp (docs/WORLD_SYSTEMS_PLAN.md N7.1-N7.2) over the real bench dispatcher, leases and budget: the entry
 * gates; a fresh private copy per owner whose logouts save the town they came from; the free bench seated there before
 * its owner walks in, with theirs; one out on a live duty away and said where; everyone back out and every camp
 * companion unloaded on leaving, on a disconnect and at a server stop, one that will not unload retried until it does;
 * the CAMP pool (16 server-wide, its own lease, never the bench pools); the bench looked at again each tick; banter in
 * map chat; nothing at all while switched off.
 */
class CrewCampTest {
    private static final int CAMP = 220000306;

    private BenchHarness game;
    private FakePorts ports;
    private FakeHands hands;
    private CrewCamp camp;
    private Character ann;

    @BeforeEach
    void setUp() {
        game = new BenchHarness();
        game.config.CREW_CAMP_ENABLED = true;
        when(game.roster.roster(any(Character.class))).thenAnswer(inv ->
                game.store.store.roster(((Character) inv.getArgument(0)).getId()));
        game.statics(MapFactory.class).when(() -> MapFactory.loadPlaceName(FIELD)).thenReturn("Ant Tunnel I");
        ports = new FakePorts();
        hands = new FakeHands();
        camp = new CrewCamp(game.world, ports, new BenchCampForm(game.world, hands, new Random(7)), new Random(7));
        when(game.world.crewCamp()).thenReturn(camp);
        ann = owner(ANN, "Ann");
    }

    @AfterEach
    void close() {
        game.close();
    }

    private Character owner(int id, String name) {
        Character owner = mock(Character.class);
        when(owner.getId()).thenReturn(id);
        when(owner.getName()).thenReturn(name);
        return owner;
    }

    /** {@code count} resting companions of {@code ownerId}'s (no live form for REST here: free for camp). */
    private void bench(int ownerId, int firstId, int count) {
        for (int i = 0; i < count; i++) {
            int id = firstId + i;
            game.companion(ownerId, "c" + id, "C" + id, id, Assignment.REST);
        }
    }

    // ---- gates ------------------------------------------------------------------------------------

    @Test
    void theGatesKeepTheCampOutOfRunsBossesTheMarketTheColiseumAndDeath() {
        assertNull(CrewCamp.refusal(new CrewCamp.Place(FIELD, true, false, false, false)), "a field is fine");
        assertNull(CrewCamp.refusal(new CrewCamp.Place(TOWN, true, false, false, false)), "a town too");
        assertEquals(CrewCamp.DEAD, CrewCamp.refusal(new CrewCamp.Place(FIELD, false, false, false, false)));
        assertEquals(CrewCamp.BUSY, CrewCamp.refusal(new CrewCamp.Place(103000800, true, true, false, false)),
                "a party quest stage, a boss run, an event, a duel: all event instances");
        assertEquals(CrewCamp.BUSY, CrewCamp.refusal(new CrewCamp.Place(FIELD, true, false, true, false)), "a run holds them");
        assertEquals(CrewCamp.BOSS, CrewCamp.refusal(new CrewCamp.Place(FIELD, true, false, false, true)), "a field boss");
        assertEquals(CrewCamp.MARKET, CrewCamp.refusal(new CrewCamp.Place(910000000, true, false, false, false)));
        assertEquals(CrewCamp.MARKET, CrewCamp.refusal(new CrewCamp.Place(910000014, true, false, false, false)));
        assertEquals(CrewCamp.COLISEUM, CrewCamp.refusal(new CrewCamp.Place(980010101, true, false, false, false)));
    }

    @Test
    void aRefusedEntryOpensNothingAndSaysWhy() {
        bench(ANN, 901, 2);
        ports.place = new CrewCamp.Place(FIELD, true, true, false, false);

        camp.enter(ann);

        assertEquals(List.of(CrewCamp.BUSY), ports.told);
        assertTrue(ports.opened.isEmpty());
        assertTrue(hands.loaded.isEmpty());
        assertFalse(camp.inCamp(ANN));
    }

    @Test
    void switchedOffNothingOpensAndAnOpenCampClosesAtTheNextTick() {
        bench(ANN, 901, 2);
        ports.on = false;
        camp.enter(ann);
        assertEquals(List.of(CrewCamp.CLOSED), ports.told);
        assertTrue(ports.opened.isEmpty());

        ports.on = true;
        camp.enter(ann);
        assertEquals(2, camp.liveCount());
        ports.on = false;
        camp.tick();
        assertFalse(camp.inCamp(ANN));
        assertEquals(0, camp.liveCount());
        assertTrue(hands.loaded.isEmpty());
    }

    // ---- entry, who is there, and the way out ----------------------------------------------------------

    @Test
    void theFreeBenchIsSeatedBeforeItsOwnerWalksInWithTheirOwnAndOneOnALiveDutyIsAwayAndSaidWhere() {
        bench(ANN, 901, 2);
        game.companion(ANN, "fenna", "Fenna", 905, Assignment.TRAIN);
        game.duty.tick(); // Fenna's training slice goes live at the field
        assertTrue(game.duty.live("fenna"));
        Character mira = owner(950, "Mira");
        ports.party.add(mira);

        camp.enter(ann);

        assertEquals(List.of(CAMP + " exit " + TOWN), ports.opened, "a fresh copy whose logouts save the town");
        assertEquals(List.of("load C901", "load C902", "enter 10", "enter 950"), ports.order,
                "the bench is there before its owner comes in");
        assertEquals(Set.of(901, 902), hands.loaded.keySet());
        assertEquals(new CompanionLeases.Lease(CompanionLeases.Kind.CAMP, ANN), game.leases.holder(901));
        assertEquals(List.of(new BenchCampForm.Absent("fenna", "Fenna", "out to train at Ant Tunnel I")), camp.away(ANN));
        assertEquals(List.of("Your crew's camp. C901 and C902 are here; Fenna is out to train at Ant Tunnel I."), ports.told);
        assertTrue(camp.inCamp(ANN));
        assertEquals(0, game.duty.liveCount(CompanionBudget.Kind.BENCH_TOWN), "never charged to the bench pools");
    }

    @Test
    void leavingPutsEveryoneBackAndUnloadsEveryCampCompanion() {
        bench(ANN, 901, 3);
        Character mira = owner(950, "Mira");
        ports.party.add(mira);
        camp.enter(ann);

        camp.leave(ann);

        assertEquals(List.of(Set.of(950)), ports.ground.closed, "back to their spots, the companion following again");
        assertTrue(hands.loaded.isEmpty(), "" + hands.loaded);
        assertNull(game.leases.holder(901));
        assertEquals(0, camp.liveCount());
        assertFalse(camp.inCamp(ANN));
        assertFalse(camp.anyOpen(), "the timer hands over nothing again");
    }

    @Test
    void aDisconnectOrAReturnScrollTakesTheCampDownTheSameWay() {
        bench(ANN, 901, 2);
        Character mira = owner(950, "Mira");
        ports.party.add(mira);
        camp.enter(ann);

        ports.onLeft.accept(mira); // a companion of hers warped out on its own: only forgotten
        assertTrue(camp.inCamp(ANN));
        ports.onLeft.accept(ann); // the copy heard her disconnect

        assertFalse(camp.inCamp(ANN));
        assertEquals(List.of(Set.of()), ports.ground.closed, "nobody left to follow");
        assertTrue(hands.loaded.isEmpty(), "no orphan Agents");
        assertEquals(0, camp.liveCount());
    }

    @Test
    void aServerStopUnloadsEveryCampAndARestartHasNone() {
        bench(ANN, 901, 2);
        bench(BEN, 911, 2);
        camp.enter(ann);
        camp.enter(owner(BEN, "Ben"));
        assertEquals(4, camp.liveCount());

        camp.endAll();

        assertTrue(hands.loaded.isEmpty());
        assertEquals(0, camp.liveCount());
        assertFalse(camp.inCamp(ANN) || camp.inCamp(BEN));
        CrewCamp restarted = new CrewCamp(game.world, ports, new BenchCampForm(game.world, hands, new Random(1)), new Random(1));
        assertFalse(restarted.anyOpen(), "nothing of a camp is kept: the owner logs in where the copy's exit saved them");
    }

    @Test
    void oneThatWillNotUnloadKeepsItsLeaseAndIsUnloadedAgainNextTick() {
        bench(ANN, 901, 2);
        camp.enter(ann);
        hands.unloadFails = true;

        camp.leave(ann);

        assertEquals(2, camp.liveCount(), "still loaded: still counted");
        assertEquals(new CompanionLeases.Lease(CompanionLeases.Kind.CAMP, ANN), game.leases.holder(901));
        assertTrue(camp.anyOpen());
        hands.unloadFails = false;
        camp.tick();
        assertEquals(0, camp.liveCount());
        assertNull(game.leases.holder(901));
        assertTrue(hands.loaded.isEmpty());
    }

    // ---- the pool ---------------------------------------------------------------------------------

    @Test
    void sixteenCampCompanionsServerWideAndTheRestRestAtTheirOwnCamp() {
        bench(ANN, 901, 10);
        bench(BEN, 921, 10);

        camp.enter(ann);
        camp.enter(owner(BEN, "Ben"));

        assertEquals(16, camp.liveCount());
        assertEquals(10, camp.seated(ANN).size());
        assertEquals(6, camp.seated(BEN).size());
        assertEquals(4, camp.away(BEN).size());
        assertTrue(camp.away(BEN).stream().allMatch(absent -> BenchCampForm.POOL_FULL.equals(absent.where())));
        assertFalse(game.world.budget().admit(CompanionBudget.Kind.CAMP, BEN, game.now).allowed(), "the pool is full");
        assertTrue(game.world.budget().admit(CompanionBudget.Kind.BENCH_TOWN, BEN, game.now).allowed(),
                "and the bench pools are untouched by it");

        camp.leave(ann);
        camp.tick();
        assertEquals(10, camp.seated(BEN).size(), "Ann gone, the rest of Ben's bench comes in at the next look");
        assertTrue(camp.away(BEN).isEmpty());
    }

    @Test
    void theCampPoolIsItsOwnAndIsNotSpacedLikeABenchLoad() {
        game.config.COMPANION_SPAWN_INTERVAL_MS = 60_000;
        game.config.CREW_CAMP_MAX = 3;
        CompanionBudget.Usage full = new CompanionBudget.Usage(10, 0, 8, 2, 0, 4, 2, 1, 1, 3);
        assertFalse(CompanionBudget.decide(CompanionBudget.Kind.CAMP, full, game.config).allowed());
        CompanionBudget.Usage room = new CompanionBudget.Usage(10, 0, 8, 2, 0, 4, 2, 1, 1, 2);
        assertTrue(CompanionBudget.decide(CompanionBudget.Kind.CAMP, room, game.config).allowed(),
                "full bench pools do not close the camp");
        bench(ANN, 901, 3);
        camp.enter(ann);
        assertEquals(3, camp.liveCount(), "three loads in one go: a camp is a burst, not one load per minute");
    }

    // ---- the tick ---------------------------------------------------------------------------------

    @Test
    void eachTickSeatsOneBenchedMeanwhileAndUnloadsOneCalledOrSentOut() {
        bench(ANN, 901, 2);
        camp.enter(ann);
        RosterEntry c901 = game.store.rows.get(FakeLivingStore.key(ANN, "c901"));
        game.store.seed(new RosterEntry(ANN, "c901", RosterState.ACTIVE, c901.assignment(), 0, 0, c901.campMapId(), 0L, 0L,
                0L, 1_000L, 1_000L, 1_000L, 0, null)); // called meanwhile
        game.companion(ANN, "c903", "C903", 903, Assignment.REST); // benched meanwhile

        camp.tick();

        assertEquals(Set.of(902, 903), hands.loaded.keySet());
        assertTrue(camp.release("c902"), "a call from the Crew window unloads its camp copy first");
        assertEquals(Set.of(903), hands.loaded.keySet());
        assertNull(game.leases.holder(902), "free for the COMPANION lease");
    }

    @Test
    void twoCampCompanionsTradeAnExchangeInMapChatAndTheAnswerComesAfterItsDelay() {
        bench(ANN, 901, 2);
        camp.enter(ann);
        when(ann.getMap()).thenReturn(ports.ground.map);
        ports.exchange = new CrewBanter.CampExchange(new CrewBanter.Speaker(901, "c901", "C901", 10, null),
                "how long have we been sitting here", new CrewBanter.Speaker(902, "c902", "C902", 10, null), "too long", 3_000L);

        ports.now += CrewCamp.FIRST_BANTER_MS - 1;
        camp.tick();
        assertTrue(ports.said.isEmpty(), "not in the first moments");
        ports.now += 1;
        camp.tick();
        assertEquals(List.of("C901: how long have we been sitting here"), ports.said);
        ports.runLater();
        assertEquals(List.of("C901: how long have we been sitting here", "C902: too long"), ports.said);
        assertEquals(List.of(3_000L), ports.delays);
        camp.tick();
        assertEquals(2, ports.said.size(), "the next one is minutes away");
    }

    @Test
    void theNoticeAndTheCampCompanionsAnswerOnlyTheirOwnersClicksInside() {
        bench(ANN, 901, 1);
        camp.enter(ann);
        Character ben = owner(BEN, "Ben");
        when(ann.getMap()).thenReturn(ports.ground.map);
        Character c901 = game.bots.get(901);

        assertTrue(camp.isNotice(ann, FakePorts.NOTICE));
        assertFalse(camp.isNotice(ann, FakePorts.NOTICE + 1));
        assertFalse(camp.isNotice(ben, FakePorts.NOTICE));
        assertEquals("c901", camp.campCompanion(ann, c901));
        assertNull(camp.campCompanion(ben, c901));
    }

    @Test
    void theCopysExitIsTheTownTheOwnerCameFrom() {
        CrewCamp.LivePorts live = new CrewCamp.LivePorts(game.world);
        MapleMap field = mock(MapleMap.class);
        when(field.getReturnMapId()).thenReturn(TOWN);
        when(ann.getMap()).thenReturn(field);
        when(ann.getMapId()).thenReturn(FIELD);
        assertEquals(TOWN, live.exitMapId(ann));
        MapleMap town = mock(MapleMap.class);
        when(town.isTown()).thenReturn(true);
        when(ann.getMap()).thenReturn(town);
        when(ann.getMapId()).thenReturn(TOWN);
        assertEquals(TOWN, live.exitMapId(ann), "a town is its own exit");
    }

    // ---- fakes ------------------------------------------------------------------------------------

    private final class FakeHands implements BenchCampForm.Hands {
        final BenchCampForm.LiveHands live = new BenchCampForm.LiveHands(game.world);
        final Map<Integer, Character> loaded = new LinkedHashMap<>();
        boolean unloadFails;
        final List<String> idle = new ArrayList<>();

        @Override
        public boolean take(int characterId, int ownerId) {
            return live.take(characterId, ownerId); // the real CAMP lease and budget
        }

        @Override
        public void release(int characterId) {
            live.release(characterId);
        }

        @Override
        public Character load(int characterId, Character owner, MapleMap map, Point at) {
            Character bot = game.bots.get(characterId);
            loaded.put(characterId, bot);
            game.botMaps.put(characterId, map);
            ports.order.add("load " + bot.getName());
            return bot;
        }

        @Override
        public boolean unload(int characterId, Character bot) {
            if (unloadFails) {
                return false;
            }
            loaded.remove(characterId);
            return true;
        }

        @Override
        public Point spot(MapleMap map, Random random) {
            return new Point(random.nextInt(800) - 400, 145);
        }

        @Override
        public void walk(Character bot, Point to) {
            idle.add("walk " + bot.getName());
        }

        @Override
        public void sit(Character bot) {
            idle.add("sit " + bot.getName());
        }

        @Override
        public void stand(Character bot) {
            idle.add("stand " + bot.getName());
        }
    }

    private static final class FakeGround implements CrewCamp.Ground {
        final MapleMap map = mock(MapleMap.class);
        final List<String> order;
        final List<Set<Integer>> closed = new ArrayList<>();

        FakeGround(List<String> order) {
            this.order = order;
            when(map.getId()).thenReturn(CAMP);
        }

        @Override
        public MapleMap map() {
            return map;
        }

        @Override
        public Point landing() {
            return new Point(0, 145);
        }

        @Override
        public void enter(Character chr, Point at) {
            order.add("enter " + chr.getId());
        }

        @Override
        public void close(Collection<Integer> followers) {
            closed.add(Set.copyOf(followers));
        }
    }

    private final class FakePorts implements CrewCamp.Ports {
        static final int NOTICE = 4242;
        boolean on = true;
        long now = 1_000_000L;
        CrewCamp.Place place = new CrewCamp.Place(FIELD, true, false, false, false);
        final List<String> told = new ArrayList<>();
        final List<String> opened = new ArrayList<>();
        final List<String> order = new ArrayList<>();
        final List<Character> party = new ArrayList<>();
        final List<String> said = new ArrayList<>();
        final List<Long> delays = new ArrayList<>();
        final List<Runnable> later = new ArrayList<>();
        Consumer<Character> onLeft;
        FakeGround ground;
        CrewBanter.CampExchange exchange;

        void runLater() {
            List<Runnable> due = new ArrayList<>(later);
            later.clear();
            due.forEach(Runnable::run);
        }

        @Override
        public boolean on() {
            return on;
        }

        @Override
        public int campMapId() {
            return CAMP;
        }

        @Override
        public long now() {
            return now;
        }

        @Override
        public CrewCamp.Place place(Character owner) {
            return place;
        }

        @Override
        public int exitMapId(Character owner) {
            return TOWN;
        }

        @Override
        public CrewCamp.Ground open(Character owner, int mapId, int exitMapId, Consumer<Character> onLeft) {
            opened.add(mapId + " exit " + exitMapId);
            this.onLeft = onLeft;
            ground = new FakeGround(order);
            return ground;
        }

        @Override
        public List<Character> partyBeside(Character owner) {
            return owner.getId() == ANN ? party : List.of();
        }

        @Override
        public int putUpNotice(MapleMap map, Point near) {
            return NOTICE;
        }

        @Override
        public void tell(Character owner, String line) {
            told.add(line);
        }

        @Override
        public void submit(String what, Runnable work) {
            work.run();
        }

        @Override
        public void later(String what, long delayMs, Runnable work) {
            delays.add(delayMs);
            later.add(work);
        }

        @Override
        public void say(Character bot, String line) {
            said.add(bot.getName() + ": " + line);
        }

        @Override
        public CrewBanter.CampExchange exchange(Character owner, List<Character> bots) {
            return exchange;
        }
    }
}
