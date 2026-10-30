package server.living.roster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.living.store.LivingStore.Assignment;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static server.living.roster.BenchHarness.ANN;
import static server.living.roster.BenchHarness.FIELD;

/**
 * A TRAIN slice keeps all it picks up, so its Equip tab fills (live 2026-10-10..29: Casse, HappyWolf, IceCookie, Yara,
 * Pell, Rurik, Halvard, Anvith and Faro723 on 96/96 Equip took no pickup and no gift). User decision 2026-10-10: a full
 * (or nearly full) Equip tab ends the TRAIN slice, and its next slice is a shop slice that sells its junk; the slice
 * after that is at its camp again. A bag of gear it keeps, a farming slice, or the switch off sends it nowhere.
 */
class BenchJunkSaleSliceTest {
    private static final int BRIN = 902;
    /** The nearest potion shop from its camp stands in a town. */
    private static final int SHOP = BenchHarness.TOWN;

    private final BenchHarness game = new BenchHarness();
    private final List<String> asked = new ArrayList<>();
    private final List<String> counted = new ArrayList<>();
    private int freeSlots = 40;
    private int junk = 12;
    private boolean sells = true;

    @BeforeEach
    void wire() {
        game.duty.freeEquipSlots = agent -> freeSlots;
        game.duty.junkCount = (entry, agent) -> {
            counted.add(agent.getName() + " on " + agent.getMap().getId());
            return junk;
        };
        game.duty.sellShopFrom = camp -> camp == FIELD ? SHOP : 0;
        game.duty.askToSell = (entry, agent) -> {
            asked.add(agent.getName() + " at " + agent.getMap().getId());
            return sells;
        };
    }

    @AfterEach
    void close() {
        game.close();
    }

    @Test
    void aNearlyFullEquipTabEndsTheTrainSliceAndTheNextSliceSellsAtTheShopThenItIsBackAtItsCamp() {
        game.companion(ANN, "brin", "Brin", BRIN, Assignment.TRAIN);
        game.duty.tick();
        assertEquals(FIELD, game.duty.slice("brin").mapId());

        freeSlots = game.config.BENCH_SELL_FREE_EQUIP_SLOTS; // four free slots left, twelve pieces of junk
        game.now += 60_000L;
        game.duty.tick();

        assertTrue(game.store.events("bench.live-end").contains(ANN + " brin bench.live-end " + BenchDutyDispatcher.BAG_FULL),
                () -> "ended: " + game.store.events("bench.live-end"));
        assertTrue(game.duty.live("brin"), "its next slice starts at once");
        assertEquals(SHOP, game.duty.slice("brin").mapId(), "...at the nearest potion shop");
        assertTrue(game.duty.slice("brin").selling(), "its card says it is selling junk there");
        assertEquals(List.of("Brin at " + SHOP), asked, "loaded at the shop, it is asked to sell there");
        assertTrue(game.store.events("bench.live").contains(ANN + " brin bench.live junk sale live at map " + SHOP),
                () -> "began: " + game.store.events("bench.live"));

        game.now += 60_000L; // its sale is over: no visit under way any more
        game.duty.tick();

        assertTrue(game.store.events("bench.live-end").contains(ANN + " brin bench.live-end " + BenchDutyDispatcher.JUNK_SOLD),
                () -> "ended: " + game.store.events("bench.live-end"));
        assertEquals(FIELD, game.duty.slice("brin").mapId(), "back at its camp");
        assertFalse(game.duty.slice("brin").selling());
        assertEquals(List.of("load Brin at " + FIELD + " ch" + game.config.CHANNEL, "unload Brin",
                "load Brin at " + SHOP + " ch" + game.config.CHANNEL, "unload Brin",
                "load Brin at " + FIELD + " ch" + game.config.CHANNEL),
                game.calls.stream().filter(call -> call.startsWith("load") || call.startsWith("unload")).toList());
    }

    @Test
    void aBagFullOfGearItKeepsSendsItNowhereAndARoomyBagIsNotEvenCounted() {
        game.companion(ANN, "brin", "Brin", BRIN, Assignment.TRAIN);
        game.duty.tick();

        game.now += 60_000L;
        game.duty.tick();
        assertEquals(List.of(), counted, "plenty of room: its junk is not counted");

        freeSlots = 0;
        junk = 0; // upgrades, good rolls, its build's gear: nothing it would sell
        game.now += 60_000L;
        game.duty.tick();

        assertEquals(List.of("Brin on " + FIELD), counted, "counted on its own mailbox");
        assertEquals(FIELD, game.duty.slice("brin").mapId(), "it hunts on");
        assertTrue(game.store.events("bench.live-end").isEmpty());
    }

    @Test
    void aFarmingSliceHaulsItsLootAndNeverEndsToSell() {
        game.companion(ANN, "brin", "Brin", BRIN, Assignment.FARM_MESO);
        game.duty.tick();
        freeSlots = 0;

        game.now += 60_000L;
        game.duty.tick();

        assertEquals(List.of(), counted);
        assertEquals(FIELD, game.duty.slice("brin").mapId());
        assertTrue(game.store.events("bench.live-end").isEmpty());
    }

    @Test
    void theKillSwitchKeepsTheTrainSliceAsBefore() {
        game.config.COMPANION_SELL_JUNK_EVERY_VISIT = false;
        game.companion(ANN, "brin", "Brin", BRIN, Assignment.TRAIN);
        game.duty.tick();
        freeSlots = 0;

        game.now += 60_000L;
        game.duty.tick();

        assertEquals(List.of(), counted);
        assertEquals(FIELD, game.duty.slice("brin").mapId());
        assertTrue(game.store.events("bench.live-end").isEmpty());
    }

    @Test
    void aSaleSliceWhoseAgentFindsNothingToSellEndsAtItsNextLookAndNoSecondOneComesWithinASlice() {
        game.companion(ANN, "brin", "Brin", BRIN, Assignment.TRAIN);
        game.duty.tick();
        freeSlots = 0;
        sells = false; // the junk went meanwhile (a gift to its owner, say)
        game.now += 60_000L;
        game.duty.tick();
        assertTrue(game.duty.slice("brin").selling());

        game.now += 60_000L;
        game.duty.tick();

        assertTrue(game.store.events("bench.live-end").contains(ANN + " brin bench.live-end "
                + BenchDutyDispatcher.NOTHING_TO_SELL), () -> "ended: " + game.store.events("bench.live-end"));
        assertEquals(FIELD, game.duty.slice("brin").mapId());
        asked.clear();
        game.now += 60_000L;
        game.duty.tick();
        assertEquals(FIELD, game.duty.slice("brin").mapId(), "one shop slice per BENCH_LIVE_SLICE_MS");
        assertEquals(List.of(), asked);
    }
}
