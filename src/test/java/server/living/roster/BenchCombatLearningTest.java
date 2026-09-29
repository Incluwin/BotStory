package server.living.roster;

import client.Character;
import config.LivingWorldConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.integration.AgentPresence;
import server.living.LivingWorld;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Decision 13 a (docs/BOT_MANAGEMENT_PLAN.md, "Bench combat learning"): the living world tells the combat learner which
 * Agent nobody can see: a companion on a live field slice with no human on its map. A human stepping onto the map ends
 * it at once, their leaving starts it again; town and stall slices, Agents on no slice, and BENCH_COMBAT_LEARN off never
 * learn.
 */
class BenchCombatLearningTest {
    private static final int ANT_TUNNEL = 105050000;

    private final LivingWorld world = mock(LivingWorld.class);
    private final LivingWorldConfig config = new LivingWorldConfig();
    private final BenchDutyDispatcher dispatcher = mock(BenchDutyDispatcher.class);
    private final Character mira = mock(Character.class);
    private final Character human = mock(Character.class);
    private final MapleMap camp = mock(MapleMap.class);
    private final List<Character> onTheMap = new ArrayList<>();
    private final BenchCombatLearning learning = new BenchCombatLearning(world);
    private MockedStatic<AgentPresence> presence;

    @BeforeEach
    void wire() {
        when(world.config()).thenReturn(config);
        when(world.benchDuty()).thenReturn(dispatcher);
        when(mira.getId()).thenReturn(950);
        when(mira.getMap()).thenReturn(camp);
        when(camp.getAllPlayers()).thenAnswer(inv -> List.copyOf(onTheMap));
        onTheMap.add(mira);
        presence = mockStatic(AgentPresence.class);
        presence.when(() -> AgentPresence.isAgent(any())).thenAnswer(inv -> inv.getArgument(0) == mira);
    }

    @AfterEach
    void close() {
        presence.close();
    }

    @Test
    void aFieldGrinderAloneOnItsMapLearnsUntilAHumanStepsOnAndAgainOnceTheyLeave() {
        onSlice(CompanionBudget.Kind.BENCH_LIVE);

        assertTrue(learning.learnUnseen(mira));
        onTheMap.add(human);
        assertFalse(learning.learnUnseen(mira), "a human on its map sees it: live play at once");
        onTheMap.remove(human);
        assertTrue(learning.learnUnseen(mira));
        assertEquals(config.BENCH_COMBAT_LEARN_EPSILON, learning.epsilon(), 1e-9);
    }

    @Test
    void townAndStallSlicesAndAgentsOnNoSliceNeverLearn() {
        assertFalse(learning.learnUnseen(mira), "no live slice: a companion out with its owner, a citizen");
        onSlice(CompanionBudget.Kind.BENCH_TOWN);
        assertFalse(learning.learnUnseen(mira));
        onSlice(CompanionBudget.Kind.COMMERCE);
        assertFalse(learning.learnUnseen(mira));
    }

    @Test
    void benchCombatLearnOffLearnsNothing() {
        onSlice(CompanionBudget.Kind.BENCH_LIVE);
        config.BENCH_COMBAT_LEARN = false;

        assertFalse(learning.learnUnseen(mira));
    }

    private void onSlice(CompanionBudget.Kind pool) {
        BenchDutyDispatcher.Form form = mock(BenchDutyDispatcher.Form.class);
        when(form.pool()).thenReturn(pool);
        RosterEntry row = new RosterEntry(10, "mira", RosterState.BENCHED, Assignment.TRAIN, 0, 0, ANT_TUNNEL, 0L, 0L, 0L,
                0L, 0L, 0L, 0, null);
        BenchDutyDispatcher.Slice slice = new BenchDutyDispatcher.Slice(row, 950, form, ANT_TUNNEL, 0L);
        when(dispatcher.sliceOf(950)).thenReturn(slice);
    }
}
