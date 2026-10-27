package server.living.roster;

import client.Character;
import config.LivingWorldConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.supplies.AgentPotionThresholds;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.LivingWorld;
import server.living.habits.OwnerHabitBook;
import server.living.store.LivingStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The learned potion point on the potion seam (docs/BOT_CONCEPT_PLAN.md F2.4): a companion whose owner never set its
 * percentages drinks at what the habit book learned (the HP floor already applied there), never inside boss content,
 * and one whose owner set them keeps to those.
 */
class OwnerHabitPotionTest {
    private static final int OWNER = 10;
    private static final int BOT = 902;

    private final LivingWorld world = mock(LivingWorld.class);
    private final LivingStore store = mock(LivingStore.class);
    private final OwnerHabitBook habits = mock(OwnerHabitBook.class);
    private final Character bot = mock(Character.class);
    private MockedStatic<AgentRuntimeRegistry> registry;
    private CompanionSettingsApplier applier;

    @BeforeEach
    void wire() {
        when(world.config()).thenReturn(new LivingWorldConfig());
        when(world.store()).thenReturn(store);
        when(world.ownerHabits()).thenReturn(habits);
        when(store.saveSettings(anyInt(), anyString(), anyString())).thenReturn(true);
        when(habits.potionThresholds(eq(OWNER), anyFloat(), anyFloat())).thenReturn(new float[]{0.45f, 0.5f});
        when(bot.getId()).thenReturn(BOT);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(bot, null, null);
        registry = mockStatic(AgentRuntimeRegistry.class);
        registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(BOT)).thenReturn(entry);
        applier = new CompanionSettingsApplier(world, mock(RosterService.class));
        applier.bind(bot, OWNER, "brin"); // a live bench slice: it drinks by its settings
    }

    @AfterEach
    void close() {
        registry.close();
    }

    @Test
    void unsetItDrinksAtItsOwnersLearnedPointOutsideBossContent() {
        AgentPotionThresholds.Thresholds learned = applier.thresholds(BOT);
        assertEquals(0.45f, learned.hp(), 1e-6);
        assertEquals(0.5f, learned.mp(), 1e-6);

        when(bot.getEventInstance()).thenReturn(mock(EventInstanceManager.class)); // a party quest, a boss run
        assertNull(applier.thresholds(BOT), "boss content: as configured");
    }

    @Test
    void percentagesItsOwnerSetAreKept() {
        assertTrue(applier.change(OWNER, "brin", own -> own.withPotions(70, 40)));
        AgentPotionThresholds.Thresholds set = applier.thresholds(BOT);
        assertEquals(0.70f, set.hp(), 1e-6);
        assertEquals(0.40f, set.mp(), 1e-6);
    }

    @Test
    void nothingLearnedNoAnswer() {
        when(habits.potionThresholds(eq(OWNER), anyFloat(), anyFloat())).thenReturn(null);
        assertNull(applier.thresholds(BOT));
    }
}
