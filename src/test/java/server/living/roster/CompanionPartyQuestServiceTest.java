package server.living.roster;

import client.Character;
import client.Client;
import client.Job;
import config.AgentYamlConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import scripting.npc.NPCConversationManager;
import server.agents.capabilities.contracts.AgentResourceCategory;
import server.agents.capabilities.partyquest.pace.AgentPqMemberPower;
import server.agents.capabilities.partyquest.pace.AgentPqPaceCalibration;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfile;
import server.agents.capabilities.supplies.AgentSupplyProcurementState;
import server.agents.capabilities.dialogue.AgentDialogueCatalog;
import server.agents.capabilities.supplies.AgentSupplyReadiness;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.config.Content.Persona;

import java.awt.Point;
import java.util.HashMap;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;

/**
 * Lakelis and the companions' own restock (2026-10-02): she counts potions against the Agents' one readiness standard
 * (20 HP, and 25 mana potions for a skill user, from agent-engine.yaml), so the desk and the companion's own restock
 * never disagree. A companion gone to the store is on its way, as a bench call is, with the store as its errand, its
 * power weighed and the clear time shown, so Yes waits for it and "Are they here yet?" is offered; one whose restock
 * cannot afford its potions waits as "out of mesos" and says so once. A click nudges every short companion to look at
 * its potions at once. The live case: SilentSin with 14 HP / 21 MP potions and 103k mesos waited at Lakelis; now he
 * goes to restock, shows as on his way, and the next Yes takes him in.
 */
class CompanionPartyQuestServiceTest {
    private static final String HP_KEY = "server.agents.capabilities.supplies.AgentSupplyReadiness.PARTY_QUEST_HP_POTIONS";
    private static final String MP_KEY = "server.agents.capabilities.supplies.AgentSupplyReadiness.PARTY_QUEST_MP_POTIONS";
    private static final int KERNING_SHOP = 103_000_002;

    private final CompanionPqFixture fx = new CompanionPqFixture();
    private final LifecycleHarness game = fx.game;
    /** One runtime entry per loaded companion for the whole test (the harness hands out a new one per lookup). */
    private final Map<Integer, AgentRuntimeEntry> entries = new HashMap<>();
    private Persona sin;

    @BeforeEach
    void annAtLakelisWithMiraAndSilentSin() {
        fx.companion("mira", "Mira", 901, 24, Job.HUNTER);
        sin = fx.companion("silentsin", "SilentSin", 902, 26, Job.ASSASSIN);
        game.registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(anyInt())).thenAnswer(inv -> {
            int id = inv.getArgument(0);
            return game.loaded.contains(id)
                    ? entries.computeIfAbsent(id, key -> new AgentRuntimeEntry(game.bots.get(key), game.ann, null))
                    : null;
        });
    }

    @AfterEach
    void close() {
        fx.close();
    }

    // ---- the one standard ----------------------------------------------------------------

    @Test
    void sheCountsAgainstTheAgentsStandardTwentyHpAndTwentyFiveManaPotions() {
        fx.ready();
        fx.potions.put(902, 19);
        assertEquals(List.of(new LineupPlan.Waiting(902, "SilentSin", LakelisLines.LOW_ON_POTIONS)), waiting());
        fx.potions.put(902, 20);
        assertEquals(List.of(), waiting());

        fx.manaPotions.put(902, 24);
        assertEquals(List.of(new LineupPlan.Waiting(902, "SilentSin", LakelisLines.LOW_ON_MANA)), waiting());
        fx.manaPotions.put(902, 25);
        assertEquals(List.of(), waiting());
    }

    @Test
    void theDeskMovesWithTheStandardInAgentEngineYaml() {
        fx.ready();
        String hp = AgentYamlConfig.config.tuning.put(HP_KEY, "30");
        String mp = AgentYamlConfig.config.tuning.put(MP_KEY, "40");
        try {
            fx.potions.put(902, 29);
            assertEquals(List.of(new LineupPlan.Waiting(902, "SilentSin", LakelisLines.LOW_ON_POTIONS)), waiting());
            fx.potions.put(902, 30);
            fx.manaPotions.put(902, 39);
            assertEquals(List.of(new LineupPlan.Waiting(902, "SilentSin", LakelisLines.LOW_ON_MANA)), waiting());
            fx.manaPotions.put(902, 40);
            assertEquals(List.of(), waiting());
        } finally {
            AgentYamlConfig.config.tuning.put(HP_KEY, hp);
            AgentYamlConfig.config.tuning.put(MP_KEY, mp);
        }
    }

    /** Found 2026-10-02: Lakelis kept seating Garrick, who held no weapon he could fight with, so he never hit. */
    @Test
    void aCompanionWithNoWeaponItCanFightWithWaitsOutside() {
        fx.ready();
        fx.service.armedCheck(bot -> bot.getId() != 902);

        assertEquals(List.of(new LineupPlan.Waiting(902, "SilentSin", DeskLines.NO_WEAPON)), waiting());

        fx.service.armedCheck(bot -> true);
        assertEquals(List.of(), waiting(), "armed again: it goes in");
    }

    @Test
    void aBareWeaponSlotIsNoWeaponToFightWith() {
        Character bare = mock(Character.class);
        client.inventory.Inventory equipped = mock(client.inventory.Inventory.class);
        when(bare.getInventory(client.inventory.InventoryType.EQUIPPED)).thenReturn(equipped);

        assertFalse(CompanionPartyQuestService.armed(bare));
        assertTrue(CompanionPartyQuestService.armed(mock(Character.class)), "equipment it cannot read counts as armed");
    }

    // ---- ammo: the desk counts it, the companion's own restock buys it (user 2026-10-07) -----

    @Test
    void aRangedCompanionShortOfAmmoWaitsLowOnAmmoAndAsksItsPartyForSome() {
        fx.ammo.put(902, 0); // SilentSin, an assassin with no throwing stars (live: Kestrel walked into LPQ so)
        fx.ready();
        enableDesk();
        List<Runnable> ops = fx.holdCompanionOps();

        LineupPlan plan = fx.service.plan(game.ann, "kpq");
        assertEquals(List.of(new LineupPlan.Waiting(902, "SilentSin", DeskLines.LOW_ON_AMMO)), plan.waiting());
        assertEquals("SilentSin is low on ammo.", plan.blocker());
        assertTrue(offer());

        CompanionPqFixture.drain(ops);
        assertEquals(1, game.partyChat.size(), game.partyChat.toString());
        String said = game.partyChat.getFirst();
        assertTrue(said.startsWith("SilentSin: ") && AgentDialogueCatalog.combatAmmoLowReplies()
                .contains(said.substring("SilentSin: ".length())), said);

        fx.ammo.put(902, ammoStandard());
        assertEquals(List.of(), waiting());
    }

    @Test
    void aCompanionShortOfAmmoLooksAtItsAmmoOnTheClick() {
        fx.ammo.put(902, 0); // SilentSin: full potions, no throwing stars
        fx.ready();
        enableDesk();
        try (MockedStatic<AgentSupplyReadiness> readiness = mockStatic(AgentSupplyReadiness.class,
                Mockito.CALLS_REAL_METHODS)) {
            assertTrue(offer());
            readiness.verify(() -> AgentSupplyReadiness.checkSoon(entries.get(902)));
            readiness.verify(() -> AgentSupplyReadiness.checkSoon(entries.get(901)), never());
        }
    }

    @Test
    void aCompanionOnAnAmmoTripIsAtTheStore() {
        fx.ammo.put(902, 0);
        fx.ready();
        onTrip(902);

        LineupPlan plan = fx.service.plan(game.ann, "kpq");

        assertEquals(List.of(), plan.waiting());
        LineupPlan.Seat seat = plan.onTheWay().getFirst();
        assertEquals(902, seat.characterId());
        assertEquals(LakelisLines.AT_THE_STORE, seat.errand());
        assertFalse(plan.startable(), "Yes waits for him");
    }

    @Test
    void aCompanionThatCannotAffordAmmoWaitsLowOnAmmoOutOfMesos() {
        fx.ammo.put(902, 0);
        fx.ready();
        enableDesk();
        CompanionRestock restock = new CompanionRestock(game.world);
        when(game.world.restock()).thenReturn(restock);
        List<Runnable> ops = fx.holdCompanionOps();
        try (MockedStatic<AgentSupplyReadiness> readiness = mockStatic(AgentSupplyReadiness.class,
                Mockito.CALLS_REAL_METHODS)) {
            readiness.when(() -> AgentSupplyReadiness.status(any(), anyLong())).thenAnswer(inv ->
                    inv.getArgument(0) == entries.get(902)
                            ? new AgentSupplyReadiness.Status(AgentSupplyReadiness.StatusKind.CANNOT_AFFORD, 0L, 500)
                            : AgentSupplyReadiness.Status.NONE);

            LineupPlan plan = fx.service.plan(game.ann, "kpq");
            assertEquals(List.of(new LineupPlan.Waiting(902, "SilentSin", DeskLines.LOW_ON_AMMO_BROKE)),
                    plan.waiting());
            assertEquals("SilentSin is low on ammo and can't afford any more.", plan.blocker());

            assertTrue(offer());
            assertTrue(offer());
        }
        CompanionPqFixture.drain(ops);
        assertEquals(1, game.partyChat.size(), "once: " + game.partyChat);
        String said = game.partyChat.getFirst();
        String word = CompanionRestockLines.ammoWord(game.bot(sin));
        assertTrue(said.startsWith("SilentSin: ") && CompanionRestockLines.GENERIC.get(CompanionRestockLines.BROKE_AMMO)
                .stream().map(line -> CompanionRestockLines.withAmmo(line, word)).toList()
                .contains(said.substring("SilentSin: ".length())), said);

        List<Runnable> later = fx.holdCompanionOps();
        restock.refused(902, AgentSupplyReadiness.Activity.partyQuest("kpq"),
                AgentSupplyReadiness.Refusal.CANNOT_AFFORD, new AgentSupplyReadiness.Shortfall(0, 0, 500), 500);
        CompanionPqFixture.drain(later);
        assertEquals(1, game.partyChat.size(), "its own refusal says no second broke line: " + game.partyChat);
    }

    // ---- gone to the store -----------------------------------------------------------------

    @Test
    void aCompanionOnItsReadinessTripIsOnItsWayToTheStoreWithItsPowerAndTheClearTime() {
        fx.potions.put(902, 14); // live: SilentSin, 14 HP / 21 MP potions and 103k mesos
        fx.manaPotions.put(902, 21);
        fx.pace = new WarmPace();
        fx.ready();
        onTrip(902);

        LineupPlan plan = fx.service.plan(game.ann, "kpq");

        assertEquals(List.of(ANN, 901, 902), plan.lineup().stream().map(LineupPlan.Seat::characterId).toList());
        LineupPlan.Seat seat = plan.onTheWay().getFirst();
        assertEquals(902, seat.characterId());
        assertEquals(LakelisLines.AT_THE_STORE, seat.errand());
        assertEquals(50_000L, seat.etaMs(), "one hop: 25 s + 25 s");
        assertFalse(plan.startable(), "Yes waits for him");
        assertEquals(List.of(), plan.waiting());
        assertNotNull(plan.clearTime(), "his power is weighed: the clear time is shown");

        assertTrue(offer());
        assertEquals(LakelisLines.RAN_TO_THE_STORE, fx.topLine);

        entries.get(902).capabilityStates().require(AgentSupplyProcurementState.STATE_KEY).clear(); // he is back
        fx.potions.put(902, 40);
        fx.manaPotions.put(902, 50);
        LineupPlan back = fx.service.plan(game.ann, "kpq");
        assertTrue(back.startable(), "the next Yes takes him in");
        assertEquals(List.of(), back.onTheWay());
    }

    @Test
    void aCompanionAtTheShopMidTripIsOnItsWayNotNotInKerning() {
        fx.ready();
        Character bot = game.bot(sin);
        bot.changeMap(game.map(KERNING_SHOP), new Point(-297, -79));
        assertEquals(List.of(new LineupPlan.Waiting(902, "SilentSin", LakelisLines.NOT_IN_KERNING)), waiting());

        onTrip(902);
        LineupPlan plan = fx.service.plan(game.ann, "kpq");

        assertEquals(List.of(), plan.waiting());
        assertEquals(List.of(902), plan.onTheWay().stream().map(LineupPlan.Seat::characterId).toList());
        assertFalse(plan.onTheWay().getFirst().here());
    }

    @Test
    void aCompanionThatCannotAffordItsPotionsWaitsOutOfMesosAndSaysSoOnce() {
        Persona fenna = fx.companion("fenna", "Fenna", 903, 25, Job.HUNTER);
        game.members.remove((Integer) 902); // Ann, Mira and Fenna: Fenna's seat decides it
        fx.potions.put(903, 1); // live: 1 HP potion and 155 mesos
        fx.ready();
        enableDesk();
        List<Runnable> ops = fx.holdCompanionOps();
        try (MockedStatic<AgentSupplyReadiness> readiness = mockStatic(AgentSupplyReadiness.class,
                Mockito.CALLS_REAL_METHODS)) {
            readiness.when(() -> AgentSupplyReadiness.status(any(), anyLong())).thenAnswer(inv ->
                    inv.getArgument(0) == entries.get(903)
                            ? new AgentSupplyReadiness.Status(AgentSupplyReadiness.StatusKind.CANNOT_AFFORD, 0L, 795)
                            : AgentSupplyReadiness.Status.NONE);

            LineupPlan plan = fx.service.plan(game.ann, "kpq");
            assertEquals(List.of(new LineupPlan.Waiting(903, "Fenna", LakelisLines.LOW_ON_POTIONS_BROKE)),
                    plan.waiting());
            assertEquals("Fenna is low on potions and can't afford any more.", plan.blocker());

            assertTrue(offer());
            assertTrue(offer());
        }
        assertEquals(List.of(), game.partyChat, "said on companion-ops");
        CompanionPqFixture.drain(ops);
        assertEquals(1, game.partyChat.size(), "once: " + game.partyChat);
        String said = game.partyChat.getFirst();
        assertTrue(said.startsWith("Fenna: ") && CompanionRestockLines.GENERIC.get(CompanionRestockLines.BROKE)
                .stream().map(line -> CompanionRestockLines.filled(line, "potions")).toList()
                .contains(said.substring("Fenna: ".length())), said);
        assertNotNull(fenna);
    }

    @Test
    void aCompanionShortOnlyOfManaThatCannotAffordThemAsksForManaPotionsOrMesos() {
        fx.companion("fenna", "Fenna", 903, 25, Job.HUNTER);
        game.members.remove((Integer) 902);
        fx.potions.put(903, 40);
        fx.manaPotions.put(903, 3); // her HP potions are fine
        fx.ready();
        enableDesk();
        List<Runnable> ops = fx.holdCompanionOps();
        try (MockedStatic<AgentSupplyReadiness> readiness = mockStatic(AgentSupplyReadiness.class,
                Mockito.CALLS_REAL_METHODS)) {
            readiness.when(() -> AgentSupplyReadiness.status(any(), anyLong())).thenAnswer(inv ->
                    inv.getArgument(0) == entries.get(903)
                            ? new AgentSupplyReadiness.Status(AgentSupplyReadiness.StatusKind.CANNOT_AFFORD, 0L, 4_000)
                            : AgentSupplyReadiness.Status.NONE);
            assertEquals(List.of(new LineupPlan.Waiting(903, "Fenna", LakelisLines.LOW_ON_MANA_BROKE)),
                    fx.service.plan(game.ann, "kpq").waiting());
            assertTrue(offer());
        }
        CompanionPqFixture.drain(ops);
        assertEquals(1, game.partyChat.size(), game.partyChat.toString());
        String said = game.partyChat.getFirst().substring("Fenna: ".length());
        assertTrue(CompanionRestockLines.GENERIC.get(CompanionRestockLines.BROKE).stream()
                .map(line -> CompanionRestockLines.filled(line, "mana potions")).toList().contains(said),
                "the desk's \"low on mana potions, out of mesos\" in her words: " + said);
    }

    @Test
    void aClickWithAShortCompanionWhoseOwnRestockDecidesAsksNothingForIt() {
        fx.potions.put(902, 14); // SilentSin, 103k mesos: its own restock sets off on its next tick
        fx.ready();
        enableDesk();
        List<Runnable> ops = fx.holdCompanionOps();
        try (MockedStatic<AgentSupplyReadiness> readiness = mockStatic(AgentSupplyReadiness.class,
                Mockito.CALLS_REAL_METHODS)) {
            readiness.when(() -> AgentSupplyReadiness.restockPending(any(), anyLong()))
                    .thenAnswer(inv -> inv.getArgument(0) == entries.get(902));
            assertTrue(offer());
        }
        CompanionPqFixture.drain(ops);
        assertEquals(List.of(), game.partyChat, "no 'anyone have HP pots?' before its own 'brb, grabbing potions'");

        List<Runnable> later = fx.holdCompanionOps();
        assertTrue(offer()); // nothing pending for it any more (its trip could not start, say): it asks
        CompanionPqFixture.drain(later);
        assertEquals(1, game.partyChat.size(), game.partyChat.toString());
        assertTrue(game.partyChat.getFirst().startsWith("SilentSin: "), game.partyChat.toString());
    }

    @Test
    void theDeskSaysNoBrokeLineTheCompanionHasJustSaidItself() {
        fx.companion("fenna", "Fenna", 903, 25, Job.HUNTER);
        game.members.remove((Integer) 902);
        fx.potions.put(903, 1);
        fx.ready();
        enableDesk();
        CompanionRestock restock = new CompanionRestock(game.world);
        when(game.world.restock()).thenReturn(restock);
        assertTrue(restock.mayAsk(903, CompanionRestockLines.BROKE, game.config.COMPANION_RESTOCK_ASK_EVERY_MS),
                "its own refusal said it a tick ago");
        List<Runnable> ops = fx.holdCompanionOps();
        try (MockedStatic<AgentSupplyReadiness> readiness = mockStatic(AgentSupplyReadiness.class,
                Mockito.CALLS_REAL_METHODS)) {
            readiness.when(() -> AgentSupplyReadiness.status(any(), anyLong())).thenAnswer(inv ->
                    inv.getArgument(0) == entries.get(903)
                            ? new AgentSupplyReadiness.Status(AgentSupplyReadiness.StatusKind.CANNOT_AFFORD, 0L, 795)
                            : AgentSupplyReadiness.Status.NONE);
            assertTrue(offer());
            assertEquals(List.of(new LineupPlan.Waiting(903, "Fenna", LakelisLines.LOW_ON_POTIONS_BROKE)),
                    fx.service.plan(game.ann, "kpq").waiting());
        }
        CompanionPqFixture.drain(ops);
        assertEquals(List.of(), game.partyChat, "said once, by whoever said it first");
    }

    @Test
    void aClickNudgesEveryShortCompanionToLookAtItsPotionsAtOnce() {
        fx.potions.put(902, 14);
        fx.ready();
        enableDesk();
        try (MockedStatic<AgentSupplyReadiness> readiness = mockStatic(AgentSupplyReadiness.class,
                Mockito.CALLS_REAL_METHODS)) {
            assertTrue(offer());

            readiness.verify(() -> AgentSupplyReadiness.checkSoon(entries.get(902)));
            readiness.verify(() -> AgentSupplyReadiness.checkSoon(entries.get(901)), never());
        }
    }

    @Test
    void withTheKillSwitchOffATripChangesNothingAtTheDesk() {
        fx.potions.put(902, 14);
        fx.ready();
        enableDesk();
        game.config.COMPANION_RESTOCK_ENABLED = false;
        onTrip(902);

        LineupPlan plan = fx.service.plan(game.ann, "kpq");
        assertEquals(List.of(new LineupPlan.Waiting(902, "SilentSin", LakelisLines.LOW_ON_POTIONS)), plan.waiting());
        assertEquals(List.of(), plan.onTheWay());
        try (MockedStatic<AgentSupplyReadiness> readiness = mockStatic(AgentSupplyReadiness.class,
                Mockito.CALLS_REAL_METHODS)) {
            assertTrue(offer());
            readiness.verify(() -> AgentSupplyReadiness.checkSoon(any()), never());
        }
        assertNull(fx.topLine);
    }

    // ---- fixture ---------------------------------------------------------------------------

    private static int ammoStandard() {
        return config.AgentTuning.intValue(
                "server.agents.capabilities.supplies.AgentSupplyReadiness.PARTY_QUEST_AMMO");
    }

    private List<LineupPlan.Waiting> waiting() {
        return fx.service.plan(game.ann, "kpq").waiting();
    }

    /** The companion set off on a readiness trip to the Kerning shop (one hop). */
    private void onTrip(int characterId) {
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(characterId);
        entry.capabilityStates().require(AgentSupplyProcurementState.STATE_KEY).start(
                "readiness:HP_POTION:1000", "maintenance:resupply:readiness:HP_POTION:1000",
                AgentResourceCategory.HP_POTION, KERNING_SHOP, 1_051_002, LifecycleHarness.KERNING,
                AgentSupplyProcurementState.Phase.TRAVEL_TO_SUPPLIER);
    }

    /** Lakelis answers Ann's click with the lineup (her party's companions are hers). */
    private void enableDesk() {
        game.config.COMPANION_PQ_ENABLED_KEYS = "kpq";
        for (int id : List.of(901, 902, 903)) {
            when(game.world.directory().ownerOfCharacter(id)).thenReturn(ANN);
        }
        Client client = game.ann.getClient();
        when(client.onIoThread()).thenReturn(true);
        when(client.getCM()).thenReturn(mock(NPCConversationManager.class));
    }

    private boolean offer() {
        if (game.config.COMPANION_PQ_ENABLED_KEYS.isEmpty()) {
            enableDesk();
        }
        return fx.service.offerAtDesk(game.ann, "kpq");
    }

    /** A warm KPQ profile in which every member, its power read off its own hits, can clear. */
    private final class WarmPace implements CompanionPartyQuestService.PaceSource {
        @Override
        public AgentPqPaceProfile profile(String pqKey) {
            return PaceTestProfile.warm();
        }

        @Override
        public AgentPqPaceCalibration calibration(AgentPqPaceProfile profile, int leaderId) {
            return AgentPqPaceCalibration.prior(PaceTestProfile.warm());
        }

        @Override
        public AgentPqMemberPower power(Character chr, AgentPqPaceProfile profile, boolean human, boolean leader) {
            return new AgentPqMemberPower(chr.getId(), chr.getName(), human, leader, chr.getLevel(), 0,
                    Map.of(PaceTestProfile.LIGATOR, 500.0d, PaceTestProfile.KING_SLIME, 500.0d),
                    Map.of(PaceTestProfile.LIGATOR, 1.0d, PaceTestProfile.KING_SLIME, 1.0d), Map.of(),
                    Double.MAX_VALUE);
        }

        @Override
        public double lastClearS(String pqKey, int leaderId, Set<Integer> companionIds) {
            return 0.0d;
        }
    }
}
