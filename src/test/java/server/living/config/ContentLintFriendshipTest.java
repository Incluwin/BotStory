package server.living.config;

import org.junit.jupiter.api.Test;
import server.living.config.Content.Persona;
import server.living.config.Content.Quest;
import server.living.config.Content.Step;
import server.living.config.Content.StepType;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Friendship quests: the {@code requires.friendship} gate and {@code rewards.gear} (armour for the player's class, the
 * Resident's own weapon to a recipe) load, and the lint's rules for them.
 */
class ContentLintFriendshipTest {
    @Test
    void shippedFriendshipQuestsCarryTheirGateAndGear() {
        LivingContent content = ContentLoader.load(Path.of("scripts/config/living-world"));

        Quest grip = content.quest("brannock-a-proper-grip");
        assertEquals(4, grip.requires().minFriendship());
        assertEquals(List.of(1082000, 1082019, 1082012, 1082029, 1082180), grip.rewards().gear().get(0).pool());
        assertFalse(grip.rewards().gear().get(0).owned(), "armour is made for the player's class");
        // the Resident's own weapon in three tiers, every scroll taking
        Content.GearReward lower = content.quest("brannock-the-old-mace").rewards().gear().get(0);
        Content.GearReward mid = content.quest("brannock-the-big-snail").rewards().gear().get(0);
        Content.GearReward last = content.quest("brannock-one-more-story").rewards().gear().get(0);
        assertEquals(List.of(1322002), lower.pool());
        assertEquals(List.of(new Content.ScrollRecipe(100, 7)), lower.scrolls());
        assertEquals(List.of(new Content.ScrollRecipe(60, 7)), mid.scrolls());
        assertEquals(List.of(new Content.ScrollRecipe(10, 2), new Content.ScrollRecipe(60, 5)), last.scrolls());
        assertEquals(5, content.quest("brannock-the-old-mace").requires().minFriendship());
        assertEquals(7, content.quest("brannock-the-big-snail").requires().minFriendship());
        assertEquals(10, content.quest("brannock-one-more-story").requires().minFriendship());
        assertEquals(List.of("brannock-crates", "brannock-mushroom-run", "brannock-a-proper-grip", "brannock-the-old-mace",
                "brannock-the-big-snail", "brannock-one-more-story"), content.persona("brannock").questLines());
        assertEquals(List.of(2020004, 2020005), content.persona("brannock").giftItemIds());

        Quest older = content.quest("brannock-crates");
        assertEquals(0, older.requires().minFriendship(), "no gate unless authored");
        assertTrue(older.rewards().gear().isEmpty());
        Map<String, Integer> weapons = new LinkedHashMap<>();
        for (Quest quest : content.quests().values()) {
            if (!quest.rewards().gear().isEmpty()) {
                assertTrue(quest.requires().minFriendship() >= 4, quest.id() + " is a friendship quest");
            }
            int owned = (int) quest.rewards().gear().stream().filter(Content.GearReward::owned).count();
            if (owned > 0) {
                weapons.merge(quest.persona(), owned, Integer::sum);
            }
        }
        // sixteen in Victoria, one per weapon type, and six regional ones (docs/REGIONAL_RESIDENTS_PLAN.md)
        assertEquals(22, weapons.size(), weapons.toString());
        assertTrue(weapons.values().stream().allMatch(tiers -> tiers == 3), "three weapon tiers each: " + weapons);
        assertEquals(List.of(1372014), content.quest("vesna-evil-tale").rewards().gear().get(0).pool());
        // an armourer: a glove for the player's class in three tiers, the final two 10% and the rest 60%
        Content.GearReward gloves = content.quest("hilde-the-guards-pattern").rewards().gear().get(0);
        assertFalse(gloves.owned());
        assertTrue(gloves.pool().size() >= 5, "a glove for every class");
        assertEquals(List.of(new Content.ScrollRecipe(10, 2), new Content.ScrollRecipe(60, Content.ScrollRecipe.REST)),
                gloves.scrolls());
        assertEquals(10, content.quest("hilde-the-guards-pattern").requires().minFriendship());
        assertFalse(ContentLint.hasErrors(content.warnings()), content.warnings().toString());
    }

    @Test
    void gearMustBeHandedOverAndLevelsStayInRange() {
        Map<String, Persona> personas = new LinkedHashMap<>();
        personas.put("olwen", persona("olwen", List.of("q-kill", "q-level", "q-ok", "q-recipe")));
        Map<String, Quest> quests = new LinkedHashMap<>();
        Step kill = step(StepType.KILL_MOBS, null);
        Step handIn = step(StepType.DELIVER, "olwen");
        quests.put("q-kill", quest("q-kill", 4, List.of(kill), List.of(1302000)));
        quests.put("q-level", quest("q-level", 11, List.of(handIn), List.of()));
        quests.put("q-ok", quest("q-ok", 7, List.of(kill, handIn), List.of(1302000)));
        quests.put("q-recipe", new Quest("q-recipe", "olwen", "q-recipe", Content.QuestLength.SHORT, false,
                new Content.Requirements(1, 200, Content.BondStage.NONE, List.of(), List.of(), List.of(), 5), List.of(handIn),
                new Content.Reward(0, 0, List.of(), 0, List.of(), 0, List.of(), List.of(new Content.GearReward(List.of(1302000), true,
                        List.of(new Content.ScrollRecipe(30, 2), new Content.ScrollRecipe(60, 0))))), null, "test"));
        LivingContent content = new LivingContent(personas, quests, Map.of(), Map.of(), Map.of(), Map.of(), List.of(),
                Map.of(), Map.of(), List.of());

        List<String> findings = ContentLint.check(content);

        assertTrue(findings.contains("ERROR test quest q-kill: a quest with gear must end in a TALK or a DELIVER to its giver"
                + " (the turn-in checks the player has room for it)"), findings.toString());
        assertTrue(findings.contains("ERROR test quest q-level: requires.friendship 11 is not a level 0-10"), findings.toString());
        assertTrue(findings.stream().noneMatch(finding -> finding.contains("q-ok")), findings.toString());
        assertTrue(findings.contains("ERROR test quest q-recipe: gear scrolls need a success of 10, 60 or 100 and a count of at"
                + " least 1, or none for the rest (got 30% x2)"), findings.toString());
        assertTrue(findings.contains("ERROR test quest q-recipe: gear scrolls need a success of 10, 60 or 100 and a count of at"
                + " least 1, or none for the rest (got 60% x0)"), findings.toString());
    }

    private static Step step(StepType type, String to) {
        return new Step("s-" + type.name().toLowerCase(), type, List.of(), 0, 1, type == StepType.KILL_MOBS ? List.of(100100) : List.of(),
                List.of(), to, null, null, 0, 0, 0, null, null, null, null);
    }

    private static Quest quest(String id, int friendship, List<Step> steps, List<Integer> pool) {
        return new Quest(id, "olwen", id, Content.QuestLength.SHORT, false,
                new Content.Requirements(1, 200, Content.BondStage.NONE, List.of(), List.of(), List.of(), friendship), steps,
                new Content.Reward(0, 0, List.of(), 0, List.of(), 0, List.of(),
                        pool.isEmpty() ? List.of() : List.of(new Content.GearReward(pool))), null, "test");
    }

    private static Persona persona(String id, List<String> questLines) {
        return new Persona(id, id, "", "BEGINNER", new Content.LevelBand(1, 1), Content.Strength.ROOKIE, null, "relaxed-v1",
                null, "lith-harbor", List.of(), false, null, questLines, null, List.of(), List.of(),
                Map.of("greet", List.of("hi")), -1, -1, -1, List.of(), 0, Content.Look.NONE, "test");
    }
}
