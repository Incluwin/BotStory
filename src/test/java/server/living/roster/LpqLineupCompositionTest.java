package server.living.roster;

import client.Character;
import client.Job;
import constants.skills.FPWizard;
import constants.skills.Magician;
import constants.skills.Rogue;
import constants.skills.Warrior;
import org.junit.jupiter.api.Test;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.config.Content.Persona;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The Red Sign's lineup before Yes (P6g LPQ C5.4): an LPQ lineup whose companions miss one of the tower's roles
 * (Teleport with magic, Dark Sight, a bow, claw or gun for stage 7, a physical attack) is kept out by the sign's notice
 * for the first one missing, so the leader can change it before Yes instead of hearing a refusal after it. Admission
 * counts the companions only, so a human with the role does not cover it. A covering lineup, a lineup already kept out
 * by something else, and every KPQ and HPQ lineup are returned as they are.
 */
class LpqLineupCompositionTest {
    private static final int BEN = 10;
    private static final String DARK_SIGHT = "Notice: the tower needs a member who can move unseen with Dark Sight.";
    private static final String TELEPORT = "Notice: the tower needs a member who can teleport and cast magic.";

    private final Character ben = character(BEN, "Ben", Job.BANDIT, Rogue.DARK_SIGHT, Rogue.LUCKY_SEVEN);
    private final Map<Integer, Character> characters = new HashMap<>(Map.of(BEN, ben,
            901, character(901, "Pip", Job.FP_WIZARD, FPWizard.TELEPORT, Magician.ENERGY_BOLT),
            902, character(902, "Tobi", Job.SPEARMAN, Warrior.POWER_STRIKE),
            903, character(903, "Mira", Job.HUNTER, constants.skills.Archer.ARROW_BLOW),
            904, character(904, "Rin", Job.ASSASSIN, Rogue.DARK_SIGHT, Rogue.LUCKY_SEVEN),
            905, character(905, "Hadrik", Job.FIGHTER, Warrior.POWER_STRIKE),
            906, character(906, "Garrick", Job.FIGHTER, Warrior.POWER_STRIKE)));
    private final List<Integer> lookedUp = new ArrayList<>();
    private final Function<Integer, Character> byId = id -> {
        lookedUp.add(id);
        return characters.get(id);
    };

    @Test
    void anLpqLineupWithoutDarkSightIsKeptOutByTheSignsNoticeBeforeYes() {
        LineupPlan plan = plan("lpq", null, 901, 902, 903, 905);
        assertTrue(plan.startable(), "as the lineup is drawn today");

        LineupPlan composed = LineupComposition.apply(plan, ben, PqEntrance.of("lpq"), byId);

        assertEquals(DARK_SIGHT, composed.blocker());
        assertFalse(composed.startable(), "no Yes");
        assertEquals(plan.lineup(), composed.lineup(), "the same lineup, kept out");
        assertEquals(plan.extras(), composed.extras());
        assertEquals(List.of(901, 902, 903, 905), lookedUp, "the companions, never the humans");
    }

    @Test
    void aHumanWithTheRoleDoesNotCoverItAsAdmissionCountsTheCompanionsOnly() {
        LineupPlan composed = LineupComposition.apply(plan("lpq", null, 901, 902, 903, 905), ben,
                PqEntrance.of("lpq"), byId);

        assertEquals(DARK_SIGHT, composed.blocker(), "Ben has Dark Sight, but admission counts the companions");
    }

    @Test
    void theFirstRoleAdmissionListsIsNamed() {
        LineupPlan composed = LineupComposition.apply(plan("lpq", null, 902, 903, 905, 906), ben,
                PqEntrance.of("lpq"), byId);

        assertEquals(TELEPORT, composed.blocker(), "Teleport and Dark Sight missing: Teleport first");
    }

    @Test
    void aCoveringLineupIsReturnedAsItIs() {
        LineupPlan plan = plan("lpq", null, 901, 902, 903, 904);

        assertSame(plan, LineupComposition.apply(plan, ben, PqEntrance.of("lpq"), byId));
        assertTrue(plan.startable());
    }

    @Test
    void anExistingBlockerIsKept() {
        LineupPlan plan = plan("lpq", "Notice: parties of five or six only.", 901, 902, 903, 905);

        assertSame(plan, LineupComposition.apply(plan, ben, PqEntrance.of("lpq"), byId));
        assertEquals(List.of(), lookedUp, "nothing weighed");
    }

    @Test
    void kpqAndHpqLineupsAreReturnedAsTheSameObject() {
        LineupPlan kpq = plan("kpq", null, 902, 903);
        LineupPlan hpq = plan("hpq", null, 902, 903, 905);

        assertSame(kpq, LineupComposition.apply(kpq, ben, PqEntrance.of("kpq"), byId));
        assertSame(hpq, LineupComposition.apply(hpq, ben, PqEntrance.of("hpq"), byId));
        assertEquals(List.of(), lookedUp, "no companion looked up");
    }

    @Test
    void aBlockerCopyKeepsEverythingElse() {
        LineupPlan plan = plan("lpq", null, 901, 902, 903, 905);
        LineupPlan blocked = plan.withBlocker("X");

        assertEquals("X", blocked.blocker());
        assertEquals(new LineupPlan(plan.pqKey(), plan.leaderId(), plan.lineup(), plan.extras(), plan.onTheWay(),
                plan.waiting(), "X", plan.benchUseful(), plan.picked(), plan.clearTime()), blocked);
        assertNull(blocked.withBlocker(null).blocker());
    }

    // ---- through the service, at the sign ------------------------------------------------------

    @Test
    void theSignsLineupWithoutDarkSightHasNoYesUntilACompanionWithItIsIn() {
        try (CompanionPqFixture fx = new CompanionPqFixture()) {
            LifecycleHarness game = fx.game;
            CompanionPqFixture.stand(game.ann, 40, Job.BANDIT);
            fx.atEntrance("lpq");
            Persona pip = fx.companion("pip", "Pip", 900, 39, Job.FP_WIZARD);
            Persona tobi = fx.companion("tobi", "Tobi", 901, 41, Job.SPEARMAN);
            Persona mira = fx.companion("mira", "Mira", 902, 38, Job.HUNTER);
            Persona hadrik = fx.companion("hadrik", "Hadrik", 903, 42, Job.FIGHTER);
            Persona rin = fx.companion("rin", "Rin", 904, 36, Job.HUNTER);
            skills(game.bot(pip), FPWizard.TELEPORT, Magician.ENERGY_BOLT);
            skills(game.bot(tobi), Warrior.POWER_STRIKE);
            skills(game.bot(mira), constants.skills.Archer.ARROW_BLOW);
            skills(game.bot(hadrik), Warrior.POWER_STRIKE);
            skills(game.bot(rin), constants.skills.Archer.ARROW_BLOW);
            skills(game.ann, Rogue.DARK_SIGHT, Rogue.LUCKY_SEVEN); // Ann's own Dark Sight does not count
            Map<Integer, AgentRuntimeEntry> entries = new HashMap<>();
            game.registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(anyInt())).thenAnswer(inv -> {
                int id = inv.getArgument(0);
                return game.loaded.contains(id)
                        ? entries.computeIfAbsent(id, key -> new AgentRuntimeEntry(game.bots.get(key), game.ann, null))
                        : null;
            });
            fx.ready();

            LineupPlan plan = fx.service.plan(game.ann, "lpq");

            assertEquals(6, plan.lineup().size(), plan.toString());
            assertEquals(DARK_SIGHT, plan.blocker());
            assertFalse(plan.startable());

            when(game.bot(rin).getJob()).thenReturn(Job.ASSASSIN);
            skills(game.bot(rin), Rogue.DARK_SIGHT, Rogue.LUCKY_SEVEN);

            LineupPlan covered = fx.service.plan(game.ann, "lpq");
            assertNull(covered.blocker(), covered.toString());
            assertTrue(covered.startable());
        }
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** Ben (the leader) and the companions {@code companionIds}, in the party quest {@code pqKey}. */
    private LineupPlan plan(String pqKey, String blocker, int... companionIds) {
        List<LineupPlan.Seat> lineup = new ArrayList<>();
        lineup.add(new LineupPlan.Seat(BEN, "Ben", 40, 400, "Bandit", true, 0, null, 221_024_500, true, 0L, 0, 0));
        for (int id : companionIds) {
            String name = characters.get(id).getName();
            lineup.add(new LineupPlan.Seat(id, name, 40, 0, "", false, BEN, name.toLowerCase(), 221_024_500, true, 0L,
                    0, 0));
        }
        return new LineupPlan(pqKey, BEN, List.copyOf(lineup), List.of(), List.of(), List.of(), blocker, false, null);
    }

    private static Character character(int id, String name, Job job, int... skillIds) {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(id);
        when(character.getName()).thenReturn(name);
        when(character.getJob()).thenReturn(job);
        skills(character, skillIds);
        return character;
    }

    private static void skills(Character character, int... skillIds) {
        for (int skillId : skillIds) {
            when(character.getSkillLevel(skillId)).thenReturn(1);
        }
    }
}
