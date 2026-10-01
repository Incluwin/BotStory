package server.living.roster;

import client.BuffStat;
import client.Character;
import client.Job;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.StatEffect;
import server.agents.capabilities.inventory.AgentUseItemClassificationPolicy;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.CompanionRunRow;
import server.living.store.LivingStore.RosterState;
import tools.Pair;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.LITH;

/**
 * The lineup Lakelis proposes (P6, flow 3): the humans always, then the companions that fit, one seat per owner in
 * turn, the closest in level first and a new job branch before a second of one; four when four can go, else three.
 * The rest wait outside with their reason. A companion low on potions, or on mana potions when it fights with skills,
 * waits and is never sent in, a guest Agent or a companion that is not up blocks the start, and one on its way keeps
 * the lineup from starting until it is here. A companion's clear rate counts, and its record shows, once it has three
 * KPQ runs on record, and what it hits for in KPQ counts too. All of it from memory: the plan reads no store.
 */
class LineupPlanTest {
    private final CompanionPqFixture fx = new CompanionPqFixture();
    private final LifecycleHarness game = fx.game;

    @AfterEach
    void close() {
        fx.close();
    }

    @Test
    void sixMemberPartyProposesTheBestFourInRange() {
        fx.companion("a20", "Rook", 900, 20, Job.FIGHTER);
        fx.companion("b24", "Mira", 901, 24, Job.HUNTER);
        fx.companion("c26", "Pip", 902, 26, Job.FP_WIZARD);
        fx.companion("d29", "Tobi", 903, 29, Job.BANDIT);
        fx.companion("e32", "Hadrik", 904, 32, Job.PAGE);

        LineupPlan plan = fx.ready().plan(game.ann, "kpq");

        assertEquals(List.of("Ann", "Mira", "Pip", "Tobi"), names(plan.lineup()));
        assertEquals(List.of("Rook", "Hadrik"), names(plan.extras()));
        assertEquals(List.of(new LineupPlan.Waiting(900, "Rook", "Lv20, not ready for this yet"),
                new LineupPlan.Waiting(904, "Hadrik", "Lv32, too seasoned for this")), plan.waiting());
        assertTrue(plan.startable());
        assertFalse(plan.benchUseful(), "four go in");
    }

    @Test
    void jobSpreadBreaksLevelTies() {
        LineupPlan plan = LineupPlan.build("kpq", ANN, 25, List.of(human(Job.CLERIC.getId()),
                humanSeat(20, "Ben", Job.PAGE.getId()), humanSeat(21, "Cid", Job.BANDIT.getId()),
                ready(seat(901, "Aria", 26, Job.CLERIC.getId())), ready(seat(902, "Mira", 24, Job.HUNTER.getId()))), null);

        assertEquals(List.of("Ann", "Ben", "Cid", "Mira"), names(plan.lineup()),
                "one level off each: the party has a Cleric already, and no bowman");
        assertEquals(List.of("Aria"), names(plan.extras()));
    }

    @Test
    void bagsFullCompanionWaitsWithTheReason() {
        fx.companion("b24", "Mira", 901, 24, Job.HUNTER);
        fx.companion("c26", "Pip", 902, 26, Job.FP_WIZARD);
        Persona casse = fx.companion("casse", "Casse", 903, 25, Job.BANDIT);
        when(game.bot(casse).getInventory(InventoryType.USE).getNextFreeSlot()).thenReturn((short) -1);

        LineupPlan plan = fx.ready().plan(game.ann, "kpq");

        assertEquals(List.of("Ann", "Mira", "Pip"), names(plan.lineup()));
        assertEquals(List.of(new LineupPlan.Waiting(903, "Casse", "bags full")), plan.waiting());
        assertEquals(List.of("Casse"), names(plan.extras()));
        assertTrue(plan.startable());
    }

    @Test
    void aCompanionLowOnPotionsWaitsAndIsNeverSentIn() {
        fx.companion("b24", "Mira", 901, 24, Job.HUNTER);
        fx.companion("c26", "Pip", 902, 26, Job.FP_WIZARD);
        fx.companion("casse", "Casse", 903, 25, Job.BANDIT);
        fx.potions.put(903, CompanionPartyQuestService.KPQ_MIN_HP_POTIONS - 1); // live: 586 meso and no HP potions

        LineupPlan plan = fx.ready().plan(game.ann, "kpq");

        assertEquals(List.of("Ann", "Mira", "Pip"), names(plan.lineup()), "Casse fits best, but has nothing to drink");
        assertEquals(List.of(new LineupPlan.Waiting(903, "Casse", "low on potions")), plan.waiting());
        assertTrue(plan.startable());

        game.members.remove((Integer) 902); // Pip goes: Casse is the only one left to take the seat, and still waits
        LineupPlan short1 = fx.service.plan(game.ann, "kpq");

        assertEquals(List.of("Ann", "Mira"), names(short1.lineup()));
        assertEquals("Casse is low on potions.", short1.blocker());
        assertFalse(short1.startable());
    }

    @Test
    void aCompanionLowOnManaPotionsWaitsWhenItFightsWithSkills() {
        fx.companion("b24", "Mira", 901, 24, Job.HUNTER);
        fx.companion("c26", "Pip", 902, 26, Job.FP_WIZARD);
        fx.companion("rurik", "Rurik", 903, 25, Job.FIGHTER);
        fx.manaPotions.put(903, CompanionPartyQuestService.KPQ_MIN_MP_POTIONS - 1); // live: none sold inside the run

        LineupPlan plan = fx.ready().plan(game.ann, "kpq");

        assertEquals(List.of("Ann", "Mira", "Pip"), names(plan.lineup()), "Rurik fits best, but has no Power Strike left");
        assertEquals(List.of(new LineupPlan.Waiting(903, "Rurik", "low on mana potions")), plan.waiting());

        game.members.remove((Integer) 902); // Pip goes: Rurik is the only one left to take the seat, and still waits
        assertEquals("Rurik is low on mana potions.", fx.service.plan(game.ann, "kpq").blocker());
    }

    @Test
    void aBeginnerFightsWithoutSkillsAndGoesInWithoutManaPotions() {
        fx.companion("b24", "Mira", 901, 24, Job.HUNTER);
        fx.companion("nob", "Nob", 902, 25, Job.BEGINNER);
        fx.manaPotions.put(902, 0);

        LineupPlan plan = fx.ready().plan(game.ann, "kpq");

        assertEquals(List.of("Ann", "Nob", "Mira"), names(plan.lineup()));
        assertEquals(List.of(), plan.waiting());
    }

    @Test
    void travellerIsOnTheWayAndNotStartable() {
        fx.companion("b24", "Mira", 901, 24, Job.HUNTER);
        Persona rin = fx.companion("rin", "Rin", 902, 25, Job.BANDIT);
        game.store.seed(game.row(rin).withTravel(System.currentTimeMillis() + 40_000L, 1_000L)); // called at Lakelis

        LineupPlan plan = fx.ready().plan(game.ann, "kpq");

        assertEquals(List.of("Ann", "Rin", "Mira"), names(plan.lineup()));
        assertEquals(List.of("Rin"), names(plan.onTheWay()));
        long eta = plan.onTheWay().getFirst().etaMs();
        assertTrue(eta > 35_000L && eta <= 40_000L, "eta " + eta);
        assertNull(plan.blocker());
        assertFalse(plan.startable(), "Are they here yet?");
    }

    @Test
    void guestAgentBlocks() {
        fx.companion("b24", "Mira", 901, 24, Job.HUNTER);
        fx.companion("c26", "Pip", 902, 26, Job.FP_WIZARD);
        Character rin = game.bot(950, "Rin"); // a citizen that joined: it carries its own engagement
        game.standsBesideAnn(rin);
        game.members.add(950);

        LineupPlan plan = fx.ready().plan(game.ann, "kpq");

        assertEquals("Rin isn't part of your crew. Ask them to leave your party first.", plan.blocker());
        assertEquals(List.of("Ann", "Mira", "Pip"), names(plan.lineup()));
        assertEquals(List.of(), plan.extras(), "no hold for a character that is not a companion");
        assertFalse(plan.startable());
    }

    @Test
    void aCompanionThatIsNotUpBlocksAndIsNoExtra() {
        fx.companion("b24", "Mira", 901, 24, Job.HUNTER);
        fx.companion("c26", "Pip", 902, 26, Job.FP_WIZARD);
        game.companion("jinx", "Jinx", 903, RosterState.ACTIVE, LITH);
        game.members.add(903); // listed in the party, not loaded (logged off with a friend's session)
        game.offline.add(903);

        LineupPlan plan = fx.ready().plan(game.ann, "kpq");

        assertEquals("Jinx isn't up yet.", plan.blocker());
        assertEquals(List.of(), plan.extras(), "an unloaded companion cannot step out");
        assertFalse(plan.startable());
    }

    @Test
    void aCompanionWhoseRowIsNotOutWithAnnBlocksAndIsNoExtra() {
        fx.companion("b24", "Mira", 901, 24, Job.HUNTER);
        fx.companion("c26", "Pip", 902, 26, Job.FP_WIZARD);
        Persona casse = fx.companion("casse", "Casse", 903, 25, Job.BANDIT);
        game.store.seed(game.row(casse).with(RosterState.BENCHED, 1_000L)); // still in the party, walking out to camp

        LineupPlan plan = fx.ready().plan(game.ann, "kpq");

        assertEquals("Casse is busy with something. Give them a moment.", plan.blocker());
        assertEquals(List.of(), plan.extras(), "a hold refuses a companion that is not out with Ann");
        assertFalse(plan.startable());
    }

    @Test
    void aCompanionCommittedToAnotherRunBlocks() {
        fx.companion("b24", "Mira", 901, 24, Job.HUNTER);
        fx.companion("c26", "Pip", 902, 26, Job.FP_WIZARD);
        game.commitments.commit("pq:kpq:earlier", ANN, 902);

        LineupPlan plan = fx.ready().plan(game.ann, "kpq");

        assertEquals("Your crew is still catching their breath. Give them a moment.", plan.blocker());
        assertFalse(plan.startable());
    }

    @Test
    void threeSeatsWhenFourCannotBeMade() {
        LineupPlan plan = LineupPlan.build("kpq", ANN, 25, List.of(human(Job.CLERIC.getId()),
                ready(seat(901, "Mira", 24, Job.HUNTER.getId())), ready(seat(902, "Pip", 26, Job.FP_WIZARD.getId())),
                waits(seat(903, "Casse", 25, Job.BANDIT.getId()))), null);

        assertEquals(List.of("Ann", "Mira", "Pip"), names(plan.lineup()));
        assertTrue(plan.startable());
        assertTrue(plan.benchUseful(), "a fourth would help");
    }

    @Test
    void tooFewOffersTheBench() {
        LineupPlan plan = LineupPlan.build("kpq", ANN, 25, List.of(human(Job.CLERIC.getId()),
                ready(seat(901, "Mira", 24, Job.HUNTER.getId()))), null);

        assertEquals("I need at least three of you, all between level 21 and 30.", plan.blocker());
        assertTrue(plan.benchUseful());
        assertFalse(plan.startable());
    }

    @Test
    void twoOwnersEachGetASeatFirst() {
        LineupPlan plan = LineupPlan.build("kpq", ANN, 25, List.of(human(Job.CLERIC.getId()),
                humanSeat(20, "Ben", Job.PAGE.getId()),
                ready(seat(901, "Mira", 25, Job.HUNTER.getId())), ready(seat(902, "Nell", 25, Job.BANDIT.getId())),
                ready(ownedBy(20, seat(911, "Tobi", 22, Job.FP_WIZARD.getId())))), null);

        assertEquals(List.of("Ann", "Ben", "Mira", "Tobi"), names(plan.lineup()),
                "Ben's one seat before Ann's second, though Nell fits better");
        assertEquals(List.of("Nell"), names(plan.extras()));
    }

    @Test
    void pickedSetOverridesAndUnpickedBecomeExtras() {
        List<LineupPlan.Candidate> party = List.of(human(Job.CLERIC.getId()),
                ready(seat(901, "Mira", 24, Job.HUNTER.getId())), ready(seat(902, "Pip", 26, Job.FP_WIZARD.getId())),
                ready(seat(903, "Tobi", 29, Job.BANDIT.getId())), ready(seat(904, "Nell", 21, Job.PAGE.getId())));

        LineupPlan plan = LineupPlan.build("kpq", ANN, 25, party, Set.of(903, 904));

        assertEquals(List.of("Ann", "Nell", "Tobi"), names(plan.lineup()));
        assertEquals(List.of("Mira", "Pip"), names(plan.extras()));
        assertTrue(plan.startable());
    }

    @Test
    void clearRateCountsOnlyFromThreeRecords() {
        fx.companion("ash", "Ash", 901, 25, Job.HUNTER);
        fx.companion("bel", "Bel", 902, 25, Job.HUNTER);
        fx.companion("cid", "Cid", 903, 25, Job.HUNTER);
        fx.companion("dov", "Dov", 904, 25, Job.HUNTER);
        CompanionPartyQuestService service = fx.ready();
        record(903, "cid", 3, 3);
        record(904, "dov", 2, 2);
        service.records().load();

        LineupPlan plan = service.plan(game.ann, "kpq");

        assertEquals(List.of("Ann", "Cid", "Ash", "Bel"), names(plan.lineup()),
                "two runs, both cleared, are no record yet");
        assertEquals("cleared 3 of 3", plan.lineup().get(1).clearRecord());
        LineupPlan.Seat dov = plan.extras().getFirst();
        assertEquals(List.of(2, 2), List.of(dov.runs(), dov.clears()));
        assertNull(dov.clearRecord());
        assertNull(plan.lineup().getFirst().clearRecord(), "a human has none");
    }

    @Test
    void betterClearRateWinsALevelTie() {
        fx.companion("rook", "Rook", 901, 25, Job.FIGHTER);
        fx.companion("tobi", "Tobi", 902, 25, Job.BANDIT);
        fx.companion("ash", "Ash", 903, 24, Job.HUNTER);
        fx.companion("bel", "Bel", 904, 24, Job.HUNTER);
        CompanionPartyQuestService service = fx.ready();
        record(903, "ash", 3, 1);
        record(904, "bel", 3, 3);
        service.records().load();

        LineupPlan plan = service.plan(game.ann, "kpq");

        assertEquals(List.of("Ann", "Bel", "Rook", "Tobi"), names(plan.lineup()));
        assertEquals(List.of("Ash"), names(plan.extras()), "the same level and job as Bel, and one clear in three");
    }

    @Test
    void aCompanionThatCannotHitKpqsMonstersGivesWayToOnesThatCan() {
        fx.companion("rurik", "Rurik", 901, 25, Job.FIGHTER);
        fx.companion("brann", "Brann", 902, 27, Job.FIGHTER);
        fx.companion("b24", "Mira", 903, 24, Job.HUNTER);
        fx.companion("c26", "Pip", 904, 26, Job.FP_WIZARD);
        fx.dps.put(901, 0.4d); // live: Rurik hit King Slime 0 times in 95
        fx.dps.put(902, 30.0d);
        fx.dps.put(903, 30.0d);
        fx.dps.put(904, 30.0d);

        LineupPlan plan = fx.ready().plan(game.ann, "kpq");

        assertEquals(List.of("Ann", "Mira", "Brann", "Pip"), names(plan.lineup()),
                "Rurik is Ann's level, and still the one left out");
        assertEquals(List.of("Rurik"), names(plan.extras()));
    }

    @Test
    void planReadsNoStore() {
        fx.companion("b24", "Mira", 901, 24, Job.HUNTER);
        fx.companion("c26", "Pip", 902, 26, Job.FP_WIZARD);
        CompanionPartyQuestService service = fx.ready();
        service.plan(game.ann, "kpq"); // warms the views, as the Crew menus do
        clearInvocations(game.store.store);

        LineupPlan plan = service.plan(game.ann, "kpq");

        assertTrue(plan.startable());
        verifyNoInteractions(game.store.store);
    }

    @Test
    void potionsAreCountedByQuantityOfTheOnesThatRestoreHp() {
        Character bot = game.bot(900, "Casse");
        Inventory use = mock(Inventory.class);
        when(bot.getInventory(InventoryType.USE)).thenReturn(use);
        List<Item> tab = List.of(item(2000000, 12), item(2000001, 7), item(2000003, 30), item(2002000, 5));
        when(use.list()).thenReturn(tab);
        StatEffect red = effect(50, 0, false);
        StatEffect orange = effect(150, 0, false);
        StatEffect blue = effect(0, 100, false);
        StatEffect buff = effect(50, 0, true);
        try (MockedStatic<AgentUseItemClassificationPolicy> items =
                     mockStatic(AgentUseItemClassificationPolicy.class, CALLS_REAL_METHODS)) {
            items.when(() -> AgentUseItemClassificationPolicy.itemEffect(2000000)).thenReturn(red);
            items.when(() -> AgentUseItemClassificationPolicy.itemEffect(2000001)).thenReturn(orange);
            items.when(() -> AgentUseItemClassificationPolicy.itemEffect(2000003)).thenReturn(blue);
            items.when(() -> AgentUseItemClassificationPolicy.itemEffect(2002000)).thenReturn(buff);

            assertEquals(19, CompanionPartyQuestService.hpPotions(bot), "Red and Orange; no Blue, no buff");
        }
    }

    @Test
    void manaPotionsAreCountedByQuantityOfTheOnesThatRestoreMp() {
        Character bot = game.bot(900, "Rurik");
        Inventory use = mock(Inventory.class);
        when(bot.getInventory(InventoryType.USE)).thenReturn(use);
        List<Item> tab = List.of(item(2000000, 12), item(2000003, 30), item(2000004, 4), item(2002000, 5));
        when(use.list()).thenReturn(tab);
        StatEffect red = effect(50, 0, false);
        StatEffect blue = effect(0, 100, false);
        StatEffect elixir = effect(50, 50, false);
        StatEffect buff = effect(0, 50, true);
        try (MockedStatic<AgentUseItemClassificationPolicy> items =
                     mockStatic(AgentUseItemClassificationPolicy.class, CALLS_REAL_METHODS)) {
            items.when(() -> AgentUseItemClassificationPolicy.itemEffect(2000000)).thenReturn(red);
            items.when(() -> AgentUseItemClassificationPolicy.itemEffect(2000003)).thenReturn(blue);
            items.when(() -> AgentUseItemClassificationPolicy.itemEffect(2000004)).thenReturn(elixir);
            items.when(() -> AgentUseItemClassificationPolicy.itemEffect(2002000)).thenReturn(buff);

            assertEquals(34, CompanionPartyQuestService.mpPotions(bot), "Blue and the Elixirs; no Red, no buff");
        }
    }

    /** {@code runs} KPQ runs on the companion's record, the first {@code clears} of them cleared. */
    private void record(int companionId, String personaId, int runs, int clears) {
        for (int i = 0; i < runs; i++) {
            game.store.performance.add(new CompanionRunRow(ANN, companionId, personaId, "kpq",
                    i < clears ? "cleared" : "failed", 600, 0, 4));
        }
    }

    private static Item item(int itemId, int quantity) {
        Item item = mock(Item.class);
        when(item.getItemId()).thenReturn(itemId);
        when(item.getQuantity()).thenReturn((short) quantity);
        return item;
    }

    private static StatEffect effect(int hp, int mp, boolean buff) {
        StatEffect effect = mock(StatEffect.class);
        when(effect.getHp()).thenReturn((short) hp);
        when(effect.getMp()).thenReturn((short) mp);
        when(effect.getStatups()).thenReturn(buff ? List.of(new Pair<>(BuffStat.WATK, 5)) : List.of());
        return effect;
    }

    private static List<String> names(List<LineupPlan.Seat> seats) {
        return seats.stream().map(LineupPlan.Seat::name).toList();
    }

    private static LineupPlan.Candidate human(int jobId) {
        return new LineupPlan.Candidate(new LineupPlan.Seat(ANN, "Ann", 25, jobId, "", true, 0, null, 103000000, true,
                0L, 0, 0), LineupPlan.Fit.READY, null, false);
    }

    private static LineupPlan.Candidate humanSeat(int id, String name, int jobId) {
        return new LineupPlan.Candidate(new LineupPlan.Seat(id, name, 25, jobId, "", true, 0, null, 103000000, true,
                0L, 0, 0), LineupPlan.Fit.READY, null, false);
    }

    private static LineupPlan.Seat seat(int id, String name, int level, int jobId) {
        return new LineupPlan.Seat(id, name, level, jobId, "", false, ANN, name.toLowerCase(), 103000000, true, 0L, 0,
                0);
    }

    private static LineupPlan.Seat ownedBy(int ownerId, LineupPlan.Seat seat) {
        return new LineupPlan.Seat(seat.characterId(), seat.name(), seat.level(), seat.jobId(), "", false, ownerId,
                seat.personaId(), seat.mapId(), seat.here(), 0L, 0, 0);
    }

    private static LineupPlan.Candidate ready(LineupPlan.Seat seat) {
        return new LineupPlan.Candidate(seat, LineupPlan.Fit.READY, null, false);
    }

    private static LineupPlan.Candidate waits(LineupPlan.Seat seat) {
        return new LineupPlan.Candidate(seat, LineupPlan.Fit.WAITS, "bags full", false);
    }
}
