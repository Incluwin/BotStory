package server.living.quest;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.integration.AgentPresence;
import server.living.LivingWorld;
import server.living.LivingWorldHooks;
import server.living.config.ContentLoader;
import server.living.config.LivingContent;
import server.living.roster.BenchDutyDispatcher;
import server.living.store.LivingStore;
import server.living.store.LivingStore.QuestState;
import server.living.store.LivingStore.QuestStatus;

import java.nio.file.Path;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A collect step counts what the player picks up off the floor, not only what a script or a reward hands over. Live
 * check 2026-11-08: Crewfriend looted 160 Coke Bottle Lids on Bartolo's "The Lid Wall" and the step stayed at 0/15,
 * because only InventoryManipulator.addById fired the quest recount (LivingWorldHooks.itemGained) and a pickup
 * (addFromDrop, then LivingWorldHooks.looted) never did.
 */
class CollectStepPickupTest {
    private static final LivingContent CONTENT = ContentLoader.load(Path.of("scripts/config/living-world"));
    private static final int ME = 865;
    private static final int LIDS = 4000221;

    private LivingWorld world;
    private LivingStore store;
    private QuestLedger ledger;
    private Character chr;

    @BeforeEach
    void setUp() {
        world = mock(LivingWorld.class);
        store = mock(LivingStore.class);
        ledger = mock(QuestLedger.class);
        chr = mock(Character.class);
        when(world.content()).thenReturn(CONTENT);
        when(world.store()).thenReturn(store);
        when(world.markers()).thenReturn(mock(QuestMarkers.class));
        when(world.benchDuty()).thenReturn(mock(BenchDutyDispatcher.class));
        when(world.quests()).thenReturn(ledger);
        when(chr.getId()).thenReturn(ME);
    }

    @AfterEach
    void tearDown() {
        AgentPresence.install(null);
    }

    private void pickUp(int itemId, int quantity, int meso) {
        try (MockedStatic<LivingWorld> living = mockStatic(LivingWorld.class)) {
            living.when(LivingWorld::get).thenReturn(world);
            LivingWorldHooks.looted(chr, itemId, quantity, meso, true);
        }
    }

    @Test
    void aPlayersPickupRecountsItsCollectSteps() {
        pickUp(LIDS, 4, 0);

        verify(ledger).onItemGained(chr, LIDS);
        verify(world.markers()).forget(chr);
    }

    @Test
    void anAgentsPickupLeavesTheQuestLedgerAlone() {
        AgentPresence.install(c -> c == chr);

        pickUp(LIDS, 4, 0);

        verify(ledger, never()).onItemGained(any(), anyInt());
    }

    @Test
    void mesoOffTheFloorLeavesTheQuestLedgerAlone() {
        pickUp(0, 0, 500);

        verify(ledger, never()).onItemGained(any(), anyInt());
    }

    /** End to end through the real ledger, on a collect step with its own label (no item-name lookup, no database). */
    @Test
    void axesPickedUpDuringTheStepShowOnItsCount() {
        String quest = "gunnar-the-walking-stumps"; // steps: talk, stumps (kills), axes (collect 10, labelled), return
        int axe = 4000215;
        Inventory etc = mock(Inventory.class);
        when(chr.getInventory(InventoryType.ETC)).thenReturn(etc);
        when(etc.countById(axe)).thenReturn(4);
        when(store.questStates(ME, QuestStatus.ACTIVE)).thenReturn(List.of(
                new QuestState(ME, quest, 2, 0, QuestStatus.ACTIVE, 1_000L, 0L, 0)));
        when(world.quests()).thenReturn(new QuestLedger(world));

        pickUp(axe, 4, 0);

        verify(store).saveQuestState(argThat(state -> state.questId().equals(quest) && state.stepIndex() == 2
                && state.progress() == 4 && state.status() == QuestStatus.ACTIVE));
        verify(chr).dropMessage(eq(5), argThat(line -> line.contains("Gunnar's old felling axe 4/10")));
    }
}
