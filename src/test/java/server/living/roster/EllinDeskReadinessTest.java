package server.living.roster;

import client.Character;
import client.Client;
import client.Job;
import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import scripting.npc.NPCConversationManager;
import server.agents.capabilities.supplies.AgentSupplyReadiness;
import server.agents.capabilities.supplies.AgentSupplyReadiness.Activity;
import server.agents.capabilities.supplies.AgentSupplyReadiness.Standard;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;

/**
 * Ellin's desk judges readiness by her quest's own standard (P6g EPQ step 6.3): a companion in her lineup waits "low
 * on potions" (or "low on mana potions") below the epq standard (AgentSupplyReadiness.standard(partyQuest("epq")),
 * PARTY_QUEST_STANDARDS), and a click at her nudges every companion short of it to look at its potions, as the restock
 * duty in her lobby fills it (CompanionRestock reads PqEntrance.keyAt). Tory's desk, whose quest has no standard of its
 * own, still reads the default. The counts are read from the standard, so this holds whatever the potion numbers are.
 */
class EllinDeskReadinessTest {
    private final CompanionPqFixture fx = new CompanionPqFixture();
    private final LifecycleHarness game = fx.game;
    /** One runtime entry per loaded companion for the whole test (the harness hands out a new one per lookup). */
    private final Map<Integer, AgentRuntimeEntry> entries = new HashMap<>();
    private final Standard epq = AgentSupplyReadiness.standard(Activity.partyQuest("epq"));
    private final Standard defaults = AgentSupplyReadiness.standard(Activity.PARTY_QUEST);

    @BeforeEach
    void annAtEllinWithThreeCompanions() {
        CompanionPqFixture.stand(game.ann, 48, Job.CLERIC);
        roomy(game.ann);
        fx.atEntrance("epq");
        roomy(game.bot(fx.companion("mira", "Mira", 900, 48, Job.HUNTER)));
        roomy(game.bot(fx.companion("tobi", "Tobi", 901, 49, Job.PAGE)));
        roomy(game.bot(fx.companion("pip", "Pip", 902, 47, Job.FP_WIZARD)));
        for (int id : List.of(900, 901, 902)) {
            fx.potions.put(id, epq.hp());
            fx.manaPotions.put(id, epq.mp());
            when(game.world.directory().ownerOfCharacter(id)).thenReturn(ANN);
        }
        game.registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(anyInt())).thenAnswer(inv -> {
            int id = inv.getArgument(0);
            return game.loaded.contains(id)
                    ? entries.computeIfAbsent(id, key -> new AgentRuntimeEntry(game.bots.get(key), game.ann, null))
                    : null;
        });
        assertTrue(epq.hp() > defaults.hp() && epq.mp() > defaults.mp(),
                "the forest asks more than the default (PARTY_QUEST_STANDARDS): " + epq + " vs " + defaults);
    }

    @AfterEach
    void close() {
        fx.close();
    }

    @Test
    void aCompanionOnePotionShortOfHerStandardWaitsLowOnPotions() {
        fx.potions.put(901, epq.hp() - 1); // still well above the default standard

        LineupPlan plan = fx.ready().plan(game.ann, "epq");

        assertEquals(List.of(new LineupPlan.Waiting(901, "Tobi", "low on potions")), plan.waiting());
    }

    @Test
    void aCompanionOneManaPotionShortOfHerStandardWaitsLowOnManaPotions() {
        fx.manaPotions.put(902, epq.mp() - 1);

        LineupPlan plan = fx.ready().plan(game.ann, "epq");

        assertEquals(List.of(new LineupPlan.Waiting(902, "Pip", "low on mana potions")), plan.waiting());
    }

    @Test
    void withExactlyHerStandardEveryoneGoesIn() {
        LineupPlan plan = fx.ready().plan(game.ann, "epq");

        assertEquals(List.of(), plan.waiting());
        assertEquals(List.of(ANN, 900, 901, 902), List.copyOf(plan.memberIds()));
    }

    @Test
    void atToryTheSameCompanionIsReadyWithTheDefaultsCounts() { // pin: Tory's quest has no standard of its own
        fx.atEntrance("hpq");
        fx.potions.put(901, defaults.hp());
        fx.manaPotions.put(901, defaults.mp());

        LineupPlan plan = fx.ready().plan(game.ann, "hpq");

        assertTrue(plan.waiting().stream().noneMatch(waiting -> waiting.characterId() == 901), plan.toString());
    }

    @Test
    void aClickAtEllinNudgesACompanionShortOfHerStandardToLookAtItsPotions() {
        fx.potions.put(901, epq.hp() - 1);
        fx.ready();
        clickable("kpq,hpq,epq");
        try (MockedStatic<AgentSupplyReadiness> readiness = mockStatic(AgentSupplyReadiness.class,
                Mockito.CALLS_REAL_METHODS)) {
            assertTrue(fx.service.offerAtDesk(game.ann, "epq"));

            readiness.verify(() -> AgentSupplyReadiness.checkSoon(entries.get(901)));
            readiness.verify(() -> AgentSupplyReadiness.checkSoon(entries.get(900)), never());
        }
    }

    @Test
    void aClickAtToryNudgesNobodyAboveTheDefault() { // pin
        fx.atEntrance("hpq");
        fx.potions.put(901, defaults.hp());
        fx.ready();
        clickable("kpq,hpq");
        try (MockedStatic<AgentSupplyReadiness> readiness = mockStatic(AgentSupplyReadiness.class,
                Mockito.CALLS_REAL_METHODS)) {
            assertTrue(fx.service.offerAtDesk(game.ann, "hpq"));

            readiness.verify(() -> AgentSupplyReadiness.checkSoon(any()), never());
        }
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** The kill switch listing {@code keys}, and Ann's click answerable on her I/O thread. */
    private void clickable(String keys) {
        game.config.COMPANION_PQ_ENABLED_KEYS = keys;
        Client client = game.ann.getClient();
        when(client.onIoThread()).thenReturn(true);
        when(client.getCM()).thenReturn(mock(NPCConversationManager.class));
    }

    /** Room for EPQ's items: four Etc slots and one Use slot free (AgentEpqAdmissionService.memberProblem). */
    private static void roomy(Character character) {
        when(character.getInventory(InventoryType.ETC).getNumFreeSlot()).thenReturn((short) 20);
        when(character.getInventory(InventoryType.USE).getNumFreeSlot()).thenReturn((short) 20);
    }
}
