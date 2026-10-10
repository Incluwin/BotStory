package server.agents.capabilities.supplies;

import client.Character;
import client.Disease;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.contracts.AgentResourceCategory;
import server.agents.capabilities.partyquest.AgentPartyQuestRuntime;
import server.agents.capabilities.supplies.AgentPotionRuntime;
import server.agents.integration.AgentRelationshipRuntime;
import server.agents.runtime.AgentModeService;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.integration.InventoryGateway;
import server.StatEffect;
import server.life.MobSkill;
import tools.Pair;

import java.awt.Point;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AgentPotionServiceTest {
    @Test
    void consumesAMatchingDiseaseCureOnlyAfterTheRolledDelay() {
        Character bot = mock(Character.class);
        Inventory useInventory = mock(Inventory.class);
        InventoryGateway inventory = mock(InventoryGateway.class);
        Item holyWater = mock(Item.class);
        StatEffect effect = mock(StatEffect.class);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(bot, null, null);
        when(bot.getAllDiseases()).thenReturn(Map.of(
                Disease.SEAL, new Pair<>(30_000L, mock(MobSkill.class))));
        when(bot.getInventory(InventoryType.USE)).thenReturn(useInventory);
        when(useInventory.list()).thenReturn(List.of(holyWater));
        when(holyWater.getItemId()).thenReturn(2_050_003);
        when(holyWater.getPosition()).thenReturn((short) 4);
        when(holyWater.getQuantity()).thenReturn((short) 1);
        when(inventory.getItemEffect(2_050_003)).thenReturn(effect);
        when(effect.curesDisease(Disease.SEAL)).thenReturn(true);
        when(inventory.consumeUseItem(bot, (short) 4, 2_050_003)).thenReturn(true);

        AgentPotionService.tickDiseaseCure(entry, bot, inventory, 1_000L, () -> 1_500L);
        AgentPotionService.tickDiseaseCure(entry, bot, inventory, 2_499L, () -> 1_500L);

        verify(inventory, never()).consumeUseItem(bot, (short) 4, 2_050_003);
        AgentPotionService.tickDiseaseCure(entry, bot, inventory, 2_500L, () -> 1_500L);
        verify(inventory).consumeUseItem(bot, (short) 4, 2_050_003);
        assertEquals(0L, AgentPotionStateRuntime.diseaseCureDueAtMs(entry));
    }

    @Test
    @SuppressWarnings("unchecked")
    void ownerPotionShareSchedulesThroughAgentPotionRuntime() {
        Character owner = mock(Character.class);
        Character requestingBot = mock(Character.class);
        Character donorBot = mock(Character.class);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(requestingBot, owner, null);
        AgentRuntimeEntry donorEntry = new AgentRuntimeEntry(donorBot, owner, null);

        when(owner.getId()).thenReturn(88);
        when(owner.getMapId()).thenReturn(100000000);
        when(owner.getTrade()).thenReturn(null);
        when(donorBot.getMapId()).thenReturn(100000000);

        Map<Integer, List<AgentRuntimeEntry>> bots = AgentRuntimeRegistry.entriesByLeaderId();
        AgentRuntimeRegistry.registerEntry(owner.getId(), entry);
        AgentRuntimeRegistry.registerEntry(owner.getId(), donorEntry);

        try (MockedStatic<AgentPotionService> potions = mockStatic(AgentPotionService.class, CALLS_REAL_METHODS);
             MockedStatic<AgentPotionRuntime> scheduler = mockStatic(AgentPotionRuntime.class)) {
            potions.when(() -> AgentPotionService.countPotions(donorBot)).thenReturn(new int[]{400, 0});
            scheduler.when(() -> AgentPotionRuntime.randomDelayMs(900, 1400)).thenReturn(77L);

            assertEquals(AgentPotionService.OwnerPotShareResult.OFFERED,
                    AgentPotionService.offerPotShareToOwner(entry, true));

            scheduler.verify(() -> AgentPotionRuntime.randomDelayMs(900, 1400));
            scheduler.verify(() -> AgentPotionRuntime.afterDelay(eq(donorEntry), eq(77L), any(Runnable.class)));
        } finally {
            AgentRuntimeRegistry.unregisterLeader(owner.getId());
        }
    }

    /**
     * Review of the EPQ golem rest follow-up (2026-10-09): a pinned dry rester fights from its rest point through the
     * anchored grind, which is grind mode, so the generic out-of-potions stop (fewer than POT_STOP HP potions, under
     * 40 % HP) stopped it, glared and said "low on pots!! walking to you" every potion check while it never walked.
     * While a party quest's own rest holds the member (AgentPartyQuestRuntime.ownsPotionRest), the stop stays quiet.
     */
    @Test
    void theGrindStopForPotionsStaysQuietWhileAPartyQuestOwnsTheRest() {
        GrindStop check = grindStopCheck(742_311, true);
        assertFalse(check.said(), "no 'walking to you' line");
        assertFalse(check.glared());
        assertTrue(check.stillGrinding(), "the rest's anchored fight goes on");
    }

    @Test
    void theGrindStopForPotionsIsTodaysWithoutAPartyQuestRest() {
        GrindStop check = grindStopCheck(742_312, false);
        assertTrue(check.said());
        assertTrue(check.glared());
        assertFalse(check.stillGrinding());
    }

    private record GrindStop(boolean said, boolean glared, boolean stillGrinding) { }

    /** One potion check of a grinding Agent with 3 HP potions at 30 % HP whose trip is due. */
    private static GrindStop grindStopCheck(int id, boolean questOwnsRest) {
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(id);
        when(bot.getHp()).thenReturn(300);
        when(bot.getMaxHp()).thenReturn(1_000);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(bot, null, null);
        AgentRelationshipRuntime.setCohortId(entry, id);
        AgentModeService.startFarmHere(entry, new Point(-540, 150), ignored -> { });
        try (MockedStatic<AgentPotionService> potions = mockStatic(AgentPotionService.class, CALLS_REAL_METHODS);
             MockedStatic<AgentPotionRuntime> speech = mockStatic(AgentPotionRuntime.class);
             MockedStatic<AgentCombatAmmoCheckRuntime> ammo = mockStatic(AgentCombatAmmoCheckRuntime.class);
             MockedStatic<AgentAmmoService> ammoShare = mockStatic(AgentAmmoService.class);
             MockedStatic<AgentPotionReserve> reserve = mockStatic(AgentPotionReserve.class);
             MockedStatic<AgentPartyQuestRuntime> partyQuests = mockStatic(AgentPartyQuestRuntime.class)) {
            potions.when(() -> AgentPotionService.setupAutopotForBot(bot)).then(call -> null);
            potions.when(() -> AgentPotionService.countPotions(bot)).thenReturn(new int[]{3, 200});
            reserve.when(() -> AgentPotionReserve.tripNeeded(eq(bot), eq(AgentResourceCategory.HP_POTION),
                    anyBoolean())).thenReturn(true);
            partyQuests.when(() -> AgentPartyQuestRuntime.ownsPotionRest(id)).thenReturn(questOwnsRest);
            boolean[] said = {false};
            speech.when(() -> AgentPotionRuntime.sayMapNow(eq(bot), any())).then(call -> {
                said[0] = true;
                return null;
            });

            AgentPotionService.tickPotionCheck(entry, bot, mock(InventoryGateway.class));

            boolean glared = mockingDetails(bot).getInvocations().stream()
                    .anyMatch(call -> call.getMethod().getName().equals("changeFaceExpression"));
            return new GrindStop(said[0], glared, AgentModeStateRuntime.grinding(entry));
        } finally {
            AgentPotionService.clearLeaderRuntimeState(id);
        }
    }

    /**
     * A caller that spaces its own asks by its tick's clock (the EPQ dry rester, every DRY_ASK_INTERVAL_MS = the 30 s
     * cohort cooldown) has the cooldown set from that clock: an ask at its end goes out, one a moment before is held.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aShareAskedOnTheCallersClockMeetsTheCooldownsEndExactly() {
        Character owner = mock(Character.class);
        Character requester = mock(Character.class);
        Character donor = mock(Character.class);
        when(owner.getId()).thenReturn(742_313);
        when(requester.getMapId()).thenReturn(930_000_600);
        when(donor.getMapId()).thenReturn(930_000_600);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(requester, owner, null);
        AgentRuntimeEntry donorEntry = new AgentRuntimeEntry(donor, owner, null);
        AgentRuntimeRegistry.registerEntry(owner.getId(), entry);
        AgentRuntimeRegistry.registerEntry(owner.getId(), donorEntry);
        AgentPotionService.clearLeaderRuntimeState(owner.getId());
        long t = 1_000_000L;
        try (MockedStatic<AgentPotionService> potions = mockStatic(AgentPotionService.class, CALLS_REAL_METHODS);
             MockedStatic<AgentPotionRuntime> scheduler = mockStatic(AgentPotionRuntime.class)) {
            potions.when(() -> AgentPotionService.countPotions(donor)).thenReturn(new int[]{400, 0});

            assertTrue(AgentPotionService.requestPotShareAt(entry, requester, true, t));
            assertFalse(AgentPotionService.requestPotShareAt(entry, requester, true, t + 29_999),
                    "the cohort cooldown holds");
            assertTrue(AgentPotionService.requestPotShareAt(entry, requester, true, t + 30_000),
                    "the cooldown ends on the caller's clock");

            scheduler.verify(() -> AgentPotionRuntime.afterDelay(eq(donorEntry), anyLong(), any(Runnable.class)),
                    times(2));
        } finally {
            AgentPotionService.clearLeaderRuntimeState(owner.getId());
            AgentRuntimeRegistry.unregisterLeader(owner.getId());
        }
    }
}
