package server.living.config;

import org.junit.jupiter.api.Test;
import server.living.config.Content.ItemStack;
import server.living.config.Content.Persona;
import server.living.config.Content.Quest;
import server.living.config.Content.Step;
import server.living.config.Content.StepType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Masteria cast (docs/ITCG_MASTERIA_PLAN.md section 6): its size per town, the five iTCG pets as Resident rewards
 * (RewardItems makes them real pets), a repeatable source of Ergoth's Jawbone, which no mob drops, and the Inner
 * Sanctum steps naming the CWKPQ event script, so any channel's instance counts.
 */
class MasteriaResidentsTest {
    private static final Set<String> TOWNS = Set.of("crimsonwood-keep", "valley-of-heroes", "haunted-house", "bigger-ben");
    private static final List<Integer> ITCG_PETS = List.of(5000034, 5000037, 5000039, 5000044, 5000045);
    private static final int ERGOTHS_JAWBONE = 4031904;

    @Test
    void eachTownHasItsResidentsAndRecruits() {
        LivingContent content = ContentLoader.load(Path.of("scripts/config/living-world"));
        Map<String, Integer> residents = new TreeMap<>();
        Map<String, Integer> recruits = new TreeMap<>();
        for (Persona persona : content.personas().values()) {
            if (TOWNS.contains(persona.homeTown())) {
                residents.merge(persona.homeTown(), 1, Integer::sum);
                recruits.merge(persona.homeTown(), persona.recruitable() ? 1 : 0, Integer::sum);
            }
        }
        assertEquals(Map.of("bigger-ben", 4, "crimsonwood-keep", 7, "haunted-house", 7, "valley-of-heroes", 4), residents);
        assertEquals(Map.of("bigger-ben", 1, "crimsonwood-keep", 3, "haunted-house", 2, "valley-of-heroes", 2), recruits);
    }

    @Test
    void theFiveItcgPetsAreMasteriaRewards() {
        LivingContent content = ContentLoader.load(Path.of("scripts/config/living-world"));
        Map<Integer, String> giverOf = new TreeMap<>();
        for (Quest quest : masteriaQuests(content)) {
            for (ItemStack stack : quest.rewards().items()) {
                if (ITCG_PETS.contains(stack.itemId())) {
                    giverOf.put(stack.itemId(), quest.id());
                    assertFalse(quest.repeatable(), quest.id() + " hands out a pet once");
                }
            }
        }
        assertEquals(Set.copyOf(ITCG_PETS), giverOf.keySet(), giverOf.toString());
    }

    @Test
    void ergothsJawboneHasARepeatableSource() {
        LivingContent content = ContentLoader.load(Path.of("scripts/config/living-world"));
        List<String> repeatable = masteriaQuests(content).stream()
                .filter(Quest::repeatable)
                .filter(quest -> quest.rewards().items().stream().anyMatch(stack -> stack.itemId() == ERGOTHS_JAWBONE))
                .map(Quest::id)
                .collect(Collectors.toList());
        assertEquals(List.of("ragna-the-sanctum-again"), repeatable);
    }

    @Test
    void sanctumStepsNameTheCwkpqScript() {
        LivingContent content = ContentLoader.load(Path.of("scripts/config/living-world"));
        int sanctum = 0;
        for (Quest quest : masteriaQuests(content)) {
            for (Step step : quest.steps()) {
                if (step.type() == StepType.PARTY_QUEST_CLEAR) {
                    assertEquals("CWKPQ", step.to(), quest.id());
                    sanctum++;
                }
            }
        }
        assertEquals(3, sanctum, "aldwin-into-the-sanctum, ragna-reverse-nibleheim, ragna-the-sanctum-again");
        assertTrue(Files.exists(Path.of("scripts/event/CWKPQ.js")), "the event script the steps name");
    }

    private static List<Quest> masteriaQuests(LivingContent content) {
        return content.quests().values().stream()
                .filter(quest -> {
                    Persona giver = content.persona(quest.persona());
                    return giver != null && TOWNS.contains(giver.homeTown());
                })
                .collect(Collectors.toList());
    }
}
