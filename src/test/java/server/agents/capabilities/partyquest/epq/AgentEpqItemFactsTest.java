package server.agents.capabilities.partyquest.epq;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins (Step 2.1): the EPQ items and NPCs the companion lines name are what the game's own data calls them, and the
 * golem's marble is the fountain's item: Golem's Marble 4001164 is the Super-Charged Poison Golem's drop and reactor
 * 3008000's item, the Altaire Fragment 4001198 is east00's gift once the fountain fired, the post-clear dummy is
 * harmless and the Poison Flower revives itself.
 */
class AgentEpqItemFactsTest {

    @Test
    void theItemIdsNameWhatStringWzCallsThem() throws Exception {
        Element etc = child(xml("wz/String.wz/Etc.img.xml"), "imgdir", "Etc");
        Map<Integer, String> names = Map.of(
                AgentEpqDefinition.POISON, "Concentrated Poison",
                AgentEpqDefinition.PURIFIED_POISON, "Diluted Poison",
                AgentEpqDefinition.MAGIC_STONE, "Purple Stone of Magic",
                AgentEpqDefinition.GOLEMS_MARBLE, "Golem's Marble",
                AgentEpqDefinition.MONSTER_MARBLE, "Monster Marble",
                AgentEpqDefinition.ALTAIRE_FRAGMENT, "Altaire Fragment");
        for (Map.Entry<Integer, String> item : names.entrySet()) {
            assertEquals(item.getValue(), name(etc, item.getKey()), "String.wz Etc " + item.getKey());
        }
        assertEquals("Purification Marble",
                name(xml("wz/String.wz/Consume.img.xml"), AgentEpqDefinition.PURIFICATION_MARBLE));
        Element npcs = xml("wz/String.wz/Npc.img.xml");
        assertEquals("Ellin Forest Milepost", name(npcs, 2_133_002));
        assertEquals("Sprite", name(npcs, AgentEpqDefinition.STONE_NPC));
    }

    @Test
    void theFountainTakesTheGolemsMarble() throws Exception {
        Element event = child(child(child(xml("wz/Reactor.wz/" + AgentEpqDefinition.REWARD_REACTOR + ".img.xml"),
                "imgdir", "0"), "imgdir", "event"), "imgdir", "0");
        assertNotNull(event);
        assertEquals("100", value(event, "int", "type"));
        assertEquals(String.valueOf(AgentEpqDefinition.GOLEMS_MARBLE), value(event, "int", "0"));
    }

    @Test
    void eastZeroGivesTheFragmentOnceTheFountainFired() throws Exception {
        String script = Files.readString(Path.of("scripts/portal/party6_stage800.js"));
        assertTrue(script.contains("getReactorById(" + AgentEpqDefinition.REWARD_REACTOR + ")"), script);
        assertTrue(script.contains("spring.getState() > 0"), script);
        assertTrue(script.contains("pi.gainItem(" + AgentEpqDefinition.ALTAIRE_FRAGMENT + ", 1)"), script);
    }

    @Test
    void theSuperChargedGolemDropsItsMarbleSixTimesInTen() throws Exception {
        List<String> lines = Files.readAllLines(Path.of("src/main/resources/db/data/152-drop-data.sql"));
        assertEquals("(9300182, 4001164, 1, 1, 0, 600000),", lines.get(8968 - 1).trim());
    }

    @Test
    void theDummyIsHarmlessAndTheFlowerRevivesItself() throws Exception {
        Element dummy = child(xml("wz/Mob.wz/" + AgentEpqDefinition.POST_DEATH_DUMMY + ".img.xml"), "imgdir", "info");
        assertEquals("2090000000", value(dummy, "int", "maxHP"));
        assertEquals("0", value(dummy, "int", "PADamage"));
        assertEquals("0", value(dummy, "int", "bodyAttack"));
        Element flower = child(xml("wz/Mob.wz/" + AgentEpqDefinition.POISON_FLOWER + ".img.xml"), "imgdir", "info");
        assertEquals(String.valueOf(AgentEpqDefinition.POISON_FLOWER), value(child(flower, "imgdir", "revive"),
                "int", "0"));
    }

    @Test
    void aStackIsPickedBackUpOneSweepBeforeItExpires() {
        assertEquals(170_000L, AgentEpqDefinition.objectiveRecollectLatestMs(180_000L, 10_000L));
        assertEquals(AgentEpqDefinition.OBJECTIVE_RECOLLECT_LATEST_MS,
                AgentEpqDefinition.objectiveRecollectLatestMs(180_000L, 10_000L));
        assertEquals(0L, AgentEpqDefinition.objectiveRecollectLatestMs(5_000L, 10_000L));
    }

    private static String name(Element strings, int id) {
        Element entry = child(strings, "imgdir", String.valueOf(id));
        return entry == null ? null : value(entry, "string", "name");
    }

    private static Element xml(String path) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(Path.of(path).toFile())
                .getDocumentElement();
    }

    private static List<Element> children(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        if (parent == null) return out;
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && element.getTagName().equals(tag)) out.add(element);
        }
        return out;
    }

    private static Element child(Element parent, String tag, String name) {
        return children(parent, tag).stream().filter(element -> name.equals(element.getAttribute("name")))
                .findFirst().orElse(null);
    }

    private static String value(Element parent, String tag, String name) {
        Element element = child(parent, tag, name);
        return element == null ? null : element.getAttribute("value");
    }
}
