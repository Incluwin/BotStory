package server.agents.capabilities.partyquest.rjpq;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import server.agents.capabilities.partyquest.AgentPartyQuestDefinition;

import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.Point;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Each side's table matches its own stock scripts and Map.wz files, not the other side's. */
class AgentRjpqSideTest {
    private static String script(String path) throws IOException {
        return Files.readString(Path.of("scripts", path));
    }

    private static int scriptInt(String script, String name) {
        Matcher matcher = Pattern.compile("(?m)^var " + name + " = (\\d+);").matcher(script);
        assertTrue(matcher.find(), name);
        return Integer.parseInt(matcher.group(1));
    }

    /** Every child imgdir of {@code section} in a Map.wz file, as name -> value maps. */
    private static List<Map<String, String>> wz(int mapId, String section) throws Exception {
        Element root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(Path.of("wz", "Map.wz", "Map", "Map9", mapId + ".img.xml").toFile()).getDocumentElement();
        List<Map<String, String>> entries = new ArrayList<>();
        for (Node part = root.getFirstChild(); part != null; part = part.getNextSibling()) {
            if (!(part instanceof Element section0) || !section.equals(section0.getAttribute("name"))) {
                continue;
            }
            NodeList children = section0.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i) instanceof Element entry) {
                    Map<String, String> values = new HashMap<>();
                    NodeList fields = entry.getChildNodes();
                    for (int j = 0; j < fields.getLength(); j++) {
                        if (fields.item(j) instanceof Element field) {
                            values.put(field.getAttribute("name"), field.getAttribute("value"));
                        }
                    }
                    values.put("@index", entry.getAttribute("name"));
                    entries.add(values);
                }
            }
        }
        return entries;
    }

    @ParameterizedTest
    @EnumSource(AgentRjpqSide.class)
    void theDefinitionIsTheSidesOwnEventScript(AgentRjpqSide side) throws IOException {
        String event = script("event/" + side.eventManagerName() + ".js");
        AgentPartyQuestDefinition definition = side.definition();

        assertEquals(side.key(), definition.questKey());
        assertEquals(side.eventManagerName(), definition.eventManagerName());
        assertEquals(scriptInt(event, "recruitMap"), definition.recruitMapId());
        assertEquals(scriptInt(event, "entryMap"), definition.entryMapId());
        assertEquals(scriptInt(event, "clearMap"), definition.clearMapId());
        assertEquals(scriptInt(event, "exitMap"), definition.exitMapId());
        assertEquals(definition.recruitMapId(), definition.recoveryMapId());
        assertTrue(event.contains("var minPlayers = 4, maxPlayers = 4;"));
        assertTrue(event.contains("var minLevel = 71, maxLevel = 85;"));
        assertEquals(4, definition.minimumPartySize());
        assertEquals(4, definition.maximumPartySize());
        assertEquals(71, definition.minimumLevel());
        assertEquals(85, definition.maximumLevel());
        assertEquals(scriptInt(event, "eventTime") * 60_000L, definition.eventTimerMs());
        assertEquals(scriptInt(event, "minMapId"), side.map(AgentRjpqDefinition.BOOKS_MAP));
        assertEquals(scriptInt(event, "maxMapId"), side.map(AgentRjpqDefinition.REWARD_MAP));
        assertTrue(event.contains("mob.getId() == " + side.bossMobIds().stream().sorted().toList().get(0)
                + " || mob.getId() == " + side.bossMobIds().stream().sorted().toList().get(1)), "the side's bosses");
        assertTrue(event.contains("eim.spawnNpc(" + side.claimNpcId() + ", new java.awt.Point(-370, -150), map);"));
        assertTrue(event.contains("eim.spawnNpc(" + side.officeYuleteNpcId() + ", new Point(252, 243)"));
        assertTrue(event.contains("eim.spawnNpc(" + side.tracesYuleteNpcId() + ", new Point(200, 100)"));
        assertTrue(event.contains("getReactorByName(\"" + side.reactorName(AgentRjpqDefinition.OFFICE_EXIT) + "\")"));
    }

    @ParameterizedTest
    @EnumSource(AgentRjpqSide.class)
    void theSidesNpcScriptsNameItsMapsItemsAndEvent(AgentRjpqSide side) throws IOException {
        String entry = script("npc/" + side.entryNpcId() + ".js");
        assertTrue(entry.contains("cm.getMapId() != " + side.recruitMapId()));
        assertTrue(entry.contains("cm.getEventManager(\"" + side.eventManagerName() + "\")"));

        String claim = script("npc/" + side.claimNpcId() + ".js");
        assertTrue(claim.contains("cm.haveItem(" + side.letterItemId() + ", 1)"), "the letter it takes first");
        assertTrue(claim.contains("cm.gainItem(" + side.marbleItemId() + ", 1)"), "the marble it gives");
        assertTrue(claim.contains("cm.haveItem(" + AgentRjpqDefinition.ALCADNO_FILES_ITEM_ID + ", 1) && cm.haveItem("
                + AgentRjpqDefinition.ZENUMIST_FILES_ITEM_ID + ", 1)"));

        String book = script("npc/" + side.bookNpcId() + ".js");
        assertTrue(book.contains("getNpcObjectId() % 26"), "books are told apart by their object id");
        assertTrue(book.contains("var letter = " + side.letterItemId() + ";"), "the book gives the side's own letter");
    }

    @ParameterizedTest
    @EnumSource(AgentRjpqSide.class)
    void theSidesMapsHoldItsBooksDoorsEscortAndRowPortals(AgentRjpqSide side) throws Exception {
        long hiddenBooks = wz(side.map(AgentRjpqDefinition.BOOKS_MAP), "life").stream()
                .filter(life -> "n".equals(life.get("type")) && String.valueOf(side.bookNpcId()).equals(life.get("id")))
                .filter(life -> "1".equals(life.get("hide")))
                .count();
        assertEquals(26, hiddenBooks);

        List<String> labReactors = wz(side.map(AgentRjpqDefinition.SPECIAL_LAB_MAP), "reactor").stream()
                .map(reactor -> reactor.get("name")).toList();
        assertTrue(labReactors.contains(side.reactorName(AgentRjpqDefinition.LAB_ONE_DOOR)));
        assertTrue(labReactors.contains(side.reactorName(AgentRjpqDefinition.LAB_TWO_DOOR)));
        assertTrue(labReactors.contains(side.reactorName(AgentRjpqDefinition.OFFICE_DOOR)));
        List<String> beakers = wz(side.map(AgentRjpqDefinition.BEAKER_MAP), "reactor").stream()
                .filter(reactor -> String.valueOf(AgentRjpqDefinition.BEAKER_REACTOR_ID).equals(reactor.get("id")))
                .map(reactor -> reactor.get("name")).toList();
        assertEquals(AgentRjpqDefinition.BEAKER_NAMES, beakers);

        Map<String, String> escort = wz(side.map(AgentRjpqDefinition.CENTER_LAB_MAP), "life").stream()
                .filter(life -> String.valueOf(side.escortMobId()).equals(life.get("id")))
                .findFirst().orElseThrow();
        assertEquals(side.escortLedge(),
                new Point(Integer.parseInt(escort.get("x")), Integer.parseInt(escort.get("y"))));

        Map<String, Integer> rowPortals = new HashMap<>();
        for (Map<String, String> portal : wz(side.map(AgentRjpqDefinition.FIRST_LAB_UNIT_MAP), "portal")) {
            rowPortals.put(portal.get("pn"), Integer.parseInt(portal.get("@index")));
        }
        for (int row = 0; row < AgentRjpqDefinition.LAB_UNIT_ROWS; row++) {
            for (int column = 0; column < AgentRjpqDefinition.LAB_UNIT_COLUMNS; column++) {
                assertEquals(rowPortals.get(AgentRjpqDefinition.labUnitPortalName(row, column)),
                        AgentRjpqDefinition.labUnitPortalId(row, column));
            }
        }
    }

    @Test
    void sidesAreFoundByKeyRecruitMapAndEventMap() {
        assertSame(AgentRjpqSide.ZENUMIST, AgentRjpqSide.fromKey(" RJPQ "));
        assertSame(AgentRjpqSide.ALCADNO, AgentRjpqSide.fromKey("rjpqj"));
        assertNull(AgentRjpqSide.fromKey("zzpq"));
        assertSame(AgentRjpqSide.ZENUMIST, AgentRjpqSide.fromRecruitMap(261_000_011));
        assertSame(AgentRjpqSide.ALCADNO, AgentRjpqSide.fromRecruitMap(261_000_021));
        assertNull(AgentRjpqSide.fromRecruitMap(261_000_000));
        assertSame(AgentRjpqSide.ZENUMIST, AgentRjpqSide.fromEventMap(926_100_302));
        assertSame(AgentRjpqSide.ALCADNO, AgentRjpqSide.fromEventMap(926_110_302));
        assertNull(AgentRjpqSide.fromEventMap(926_100_305));
        assertEquals(926_100_302, AgentRjpqSide.ALCADNO.zenumistMap(926_110_302));
        assertEquals(-1, AgentRjpqSide.ALCADNO.zenumistMap(926_100_302));
        assertEquals(2, AgentRjpqDefinition.labUnitRoom(926_100_302));
    }
}
