package server.living.config;

import org.junit.jupiter.api.Test;
import server.living.config.Content.Persona;
import server.living.config.Content.Quest;
import server.living.config.Content.StepType;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentLoaderTest {
    private static final Path ROOT = Path.of("scripts/config/living-world");

    @Test
    void shippedContentLoadsWithoutErrors() {
        LivingContent content = ContentLoader.load(ROOT);
        List<String> errors = content.warnings().stream().filter(w -> w.startsWith("ERROR")).toList();
        assertTrue(errors.isEmpty(), "content errors: " + errors);
        assertTrue(content.personas().size() >= 8, content.census());
        assertTrue(content.quests().size() >= 12, content.census());
        assertFalse(content.phrases().isEmpty());
        for (Persona persona : content.personas().values()) {
            assertTrue(ContentLint.KNOWN_TOWNS.contains(persona.homeTown()), persona.id() + " town");
            assertFalse(persona.questLines().isEmpty(), persona.id() + " has at least one quest line");
        }
    }

    @Test
    void personaAndQuestFieldsRoundTrip() {
        LivingContent content = ContentLoader.load(ROOT);
        Persona hadrik = content.persona("hadrik");
        assertNotNull(hadrik);
        assertEquals("Hadrik", hadrik.name());
        assertTrue(hadrik.recruitable());
        assertEquals("hadrik-last-voyage", hadrik.contract());
        assertEquals(30, hadrik.level().min());
        assertEquals(3, hadrik.benchToleranceDays());
        assertEquals(2, hadrik.routine().size());
        assertFalse(hadrik.routine().get(1).materialize(), "night slot is unmaterialized");
        assertTrue(hadrik.routine().get(1).covers(3), "0-10 covers 3am");
        assertEquals(hadrik, content.personaByName("HADRIK"));

        Quest cutlass = content.quest("hadrik-old-cutlass");
        assertNotNull(cutlass);
        assertEquals(4, cutlass.steps().size());
        assertEquals(StepType.PAY, cutlass.step(2).type());
        assertEquals(500, cutlass.step(2).meso());
        assertEquals(4000016, cutlass.step(3).take().itemId());
        assertEquals("chapter-2", cutlass.onComplete().to());
        assertEquals(List.of("hadrik.cutlass.whetted"), cutlass.rewards().flags());

        Quest ghosts = content.quest("hadrik-ghosts-of-the-road");
        assertEquals(StepType.HUNT_WITH, ghosts.step(1).type());
        assertEquals("hadrik", ghosts.step(1).persona());

        Content.Contract voyage = content.contract("hadrik-last-voyage");
        assertEquals(30000L, voyage.price().meso());
        assertTrue(voyage.price().items().get(0).keep());
        assertEquals(12, voyage.terms().cooldownHoursAfterLeave());
        assertEquals(12000L, voyage.terms().rejoinMeso());

        Content.Arc arc = content.arc("hadrik-last-voyage");
        assertEquals(4, arc.chapters().size());
        assertEquals(1, arc.indexOf("chapter-2"));
        assertEquals(Boolean.FALSE, arc.chapters().get(3).recruitable());

        Content.Rumor rumor = content.rumor("lith-dock-confession");
        assertEquals(3, rumor.participants().size());
        assertEquals("hadrik.heard-confession", rumor.setsFlagOnListeners());
    }

    @Test
    void questLogIdsAreStableAndUnique() {
        LivingContent content = ContentLoader.load(ROOT);
        java.util.Map<String, Integer> ids = QuestLogExportMain.assignIds(content);
        assertEquals(content.quests().size(), ids.size());
        assertEquals(content.quests().size(), new java.util.HashSet<>(ids.values()).size(), "ids are unique");
        assertTrue(ids.values().stream().allMatch(id -> id >= ContentLint.QUEST_LOG_ID_MIN && id <= ContentLint.QUEST_LOG_ID_MAX));
        for (var entry : content.questLogIds().entrySet()) {
            if (content.quest(entry.getKey()) != null) {
                assertEquals(entry.getValue(), ids.get(entry.getKey()), "existing id kept for " + entry.getKey());
            }
        }
    }

    @Test
    void questLogEntriesAreResidentsQuestsWithABlueTitle() {
        LivingContent content = ContentLoader.load(ROOT);
        java.util.Map<String, Object> wren = QuestLogExportMain.entry(content, content.quest("wren-shelf-of-spores"), 30000);
        assertEquals(QuestLogExportMain.AREA_RESIDENTS, wren.get("area"));
        assertEquals("Ink from Spores", wren.get("name"), "the Quest Helper prints colour codes literally");
        assertEquals("Ink from Spores", wren.get("parent"), "the list label stays plain");
        assertTrue(((String) wren.get("inProgress")).startsWith("#bWren (Ellinia)#k: "), "the giver in blue in the detail text");
        assertEquals(10, wren.get("lvmin"));
        assertEquals(content.portraitNpcId("wren"), wren.get("npc"));
    }

    @Test
    void lintCatchesDanglingReferences() {
        LivingContent content = ContentLoader.load(ROOT);
        Persona broken = new Persona("ghost", "Ghost", "", "BEGINNER", new Content.LevelBand(1, 1), Content.Strength.ROOKIE,
                "no-such-loadout", "relaxed-v1", null, "atlantis", List.of(), true, null, List.of("nope"), "no-arc",
                List.of(), List.of(), java.util.Map.of(), -1, -1, -1, List.of(), 0, Content.Look.NONE, "test");
        LivingContent withBroken = new LivingContent(
                java.util.Map.of("ghost", broken), content.quests(), content.contracts(), content.arcs(), content.rumors(),
                content.loadouts(), content.phrases(), content.questLogIds(), content.portraitNpcIds(), List.of());
        List<String> findings = ContentLint.check(withBroken);
        assertTrue(ContentLint.hasErrors(findings));
        assertTrue(findings.stream().anyMatch(f -> f.contains("unknown home town")));
        assertTrue(findings.stream().anyMatch(f -> f.contains("recruitable personas need a contract")));
        assertTrue(findings.stream().anyMatch(f -> f.contains("loadout 'no-such-loadout' not found")));
        assertTrue(findings.stream().anyMatch(f -> f.contains("quest line 'nope' not found")));
    }

    @Test
    void nodeAccessorsReportPaths() {
        Node root = Node.root(java.util.Map.of("level", java.util.Map.of("min", "x")), "t.yaml");
        ContentException failure = org.junit.jupiter.api.Assertions.assertThrows(ContentException.class,
                () -> root.get("level").get("min").integer());
        assertTrue(failure.getMessage().startsWith("t.yaml.level.min"));
        assertEquals(7, root.get("missing").integer(7));
        assertTrue(root.get("level").get("nothing").isMissing());
    }

    @Test
    void lintCatchesOutfitItemsInTheWrongSlot() {
        LivingContent content = ContentLoader.load(ROOT);
        java.util.Map<String, Content.Loadout> loadouts = new java.util.LinkedHashMap<>(content.loadouts());
        loadouts.put("bad-outfit", new Content.Loadout("bad-outfit", List.of(), List.of(), java.util.Map.of(),
                java.util.Map.of("hat", List.of(0, 1002026, 1041000), "sleeves", List.of(1040002),
                        "weapon", List.of(1312004, 1702000))));
        LivingContent withBad = new LivingContent(content.personas(), content.quests(), content.contracts(), content.arcs(),
                content.rumors(), loadouts, content.phrases(), content.questLogIds(), content.portraitNpcIds(), List.of());
        List<String> findings = ContentLint.check(withBad);
        assertTrue(findings.stream().anyMatch(f -> f.contains("outfit hat item 1041000 is not a hat item")), findings.toString());
        assertTrue(findings.stream().anyMatch(f -> f.contains("unknown outfit slot 'sleeves'")), findings.toString());
        assertFalse(findings.stream().anyMatch(f -> f.contains("1002026")), findings.toString());
        assertTrue(findings.stream().anyMatch(f -> f.contains("outfit weapon item 1702000 is not a weapon item")), findings.toString());
        assertFalse(findings.stream().anyMatch(f -> f.contains("1312004")), findings.toString());
    }
}
