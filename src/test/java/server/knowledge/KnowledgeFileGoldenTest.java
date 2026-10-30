package server.knowledge;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden facts read from the committed server-knowledge.json (docs/SERVER_KNOWLEDGE_PLAN.md 4.3): custom names,
 * custom equips, Henesys's NPCs, the Happyville snowman script spawn and its revive chain, linked stub maps,
 * area bosses spawned by event scripts, counts against the WZ tree, travel.yaml and routes.
 */
class KnowledgeFileGoldenTest {
    private final WorldKnowledge knowledge = KnowledgeFixtures.with(KnowledgeOverlay.EMPTY);

    @Test
    void customItemNamesAndCategories() {
        assertEquals("Steely Throwing-Knives", knowledge.item(2070005).orElseThrow().name());
        assertEquals("use", knowledge.item(2070005).orElseThrow().category());
        assertEquals("Hyper Teleport Rock", knowledge.item(5041000).orElseThrow().name());
        assertEquals("cash", knowledge.item(5041000).orElseThrow().category());
        assertEquals("Fusion Anvil", knowledge.item(5900000).orElseThrow().name());
        assertEquals("cash", knowledge.item(5900000).orElseThrow().category());
        assertEquals("pet", knowledge.item(5000000).orElseThrow().category());
    }

    @Test
    void customEquipsAndRequirements() {
        ItemFact doom = knowledge.item(1152101).orElseThrow();
        assertEquals("Doom Shoulder", doom.name());
        assertEquals("shoulder", doom.category());
        ItemFact zhelm = knowledge.item(1002357).orElseThrow();
        assertEquals("Zakum Helmet", zhelm.name());
        assertEquals(50, zhelm.reqLevel());
        assertEquals(0, zhelm.reqStr(), "secondary stats are zeroed in the edited Character.wz");
        long unnamedEquips = knowledge.items().stream().filter(item -> item.unnamed() && item.equip()).count();
        assertEquals(136, unnamedEquips, "custom equips without a String.wz name stay visible to lint");
        assertTrue(knowledge.items().stream().anyMatch(item -> item.reqPop() > 0), "reqPOP (fame) is exported");
    }

    @Test
    void henesysListsItsNpcs() {
        MapFact henesys = knowledge.map(100000000).orElseThrow();
        assertEquals("Henesys", henesys.name());
        assertEquals("Victoria Road", henesys.street());
        assertEquals("victoria", henesys.region());
        assertTrue(henesys.town() && henesys.hub());
        for (int npc : new int[]{1012000, 1010100, 1012003, 9201022}) {
            assertTrue(henesys.npcs().contains(npc), "Henesys has NPC " + npc);
            assertTrue(knowledge.npcMaps(npc).contains(100000000));
        }
        assertEquals("Regular Cab", knowledge.npc(1012000).orElseThrow().name());
        assertEquals(List.of(100000201), knowledge.npcMaps(1012100), "Athena Pierce stands in the Bowman Instructional School");
        assertFalse(knowledge.map(100000201).orElseThrow().hub(), "an interior is a town map but not a hub");
    }

    @Test
    void happyvilleSnowmenAreScriptSpawnedAndInheritThroughRevive() {
        assertEquals("Happy", knowledge.npc(9220004).orElseThrow().name());
        List<ScriptSpawn> kid = knowledge.scriptSpawns(9500317);
        assertEquals(1, kid.size());
        assertEquals("npc/9220004.js", kid.get(0).script());
        assertEquals(9220004, kid.get(0).viaNpc());
        assertEquals(List.of(209080000), kid.get(0).maps());
        assertEquals(0, kid.get(0).revivedFrom());
        assertEquals(List.of(9500318), knowledge.mob(9500317).orElseThrow().revive());
        for (int revived : new int[]{9500318, 9500319}) {
            List<ScriptSpawn> inherited = knowledge.scriptSpawns(revived);
            assertEquals(1, inherited.size(), "mob " + revived);
            assertEquals(9500317, inherited.get(0).revivedFrom());
            assertEquals(9220004, inherited.get(0).viaNpc());
            assertEquals(List.of(209080000), knowledge.spawnMaps(revived));
        }
    }

    @Test
    void areaBossesSpawnedByEventScriptsHaveTheirMap() {
        assertEquals("Mano", knowledge.mob(2220000).orElseThrow().name());
        assertTrue(knowledge.mob(2220000).orElseThrow().boss());
        assertTrue(knowledge.spawnMaps(2220000).contains(104000400), "event/AreaBossMano.js getMap(104000400)");
        assertTrue(knowledge.spawnMaps(5220002).containsAll(List.of(100040105, 100040106)), "Faust: two event scripts");
    }

    @Test
    void linkedStubMapsTakeLifeFromTheirTarget() {
        MapFact stub = knowledge.map(100020101).orElseThrow();
        MapFact target = knowledge.map(100020100).orElseThrow();
        assertFalse(stub.mobs().isEmpty(), "100020101 is an info/link stub of 100020100");
        assertEquals(target.mobs(), stub.mobs());
        assertEquals("Henesys Pig Farm", stub.name());
    }

    @Test
    void countsMatchTheWzTree() throws IOException {
        assertEquals(5841, knowledge.maps().size(), "5,262 Cosmic maps and the Community Repack's 579");
        assertEquals(1629, knowledge.mobs().size(), "1,564 and the 65 the Repack maps brought");
        assertEquals(2807, knowledge.quests().size());
        try (Stream<Path> maps = Files.walk(Path.of("wz/Map.wz/Map"))) {
            assertEquals(maps.filter(p -> p.getParent().getFileName().toString().matches("Map\\d")).count(),
                    knowledge.maps().size());
        }
        try (Stream<Path> mobs = Files.list(Path.of("wz/Mob.wz"))) {
            assertEquals(mobs.filter(p -> p.toString().endsWith(".img.xml")).count(), knowledge.mobs().size());
        }
    }

    @Test
    void questFacts() {
        QuestFact quest = knowledge.quest(2050).orElseThrow();
        assertEquals("Sabitrama and the Diet Medicine", quest.name());
        assertEquals(1061005, quest.startNpc());
        assertEquals(25, quest.minLevel());
        assertEquals(List.of(4031020), quest.reqItems());
        assertEquals(3500, quest.rewardExp());
    }

    @Test
    void travelYamlLegsAllResolve() throws IOException {
        long authored = Files.readAllLines(KnowledgeFixtures.DIR.resolve("travel.yaml")).stream()
                .filter(line -> line.trim().startsWith("- {")).count();
        assertEquals(authored, knowledge.travel().size(), "every travel.yaml leg names a known map and NPC");
    }

    @Test
    void routesCrossContinentsByShip() {
        assertEquals(List.of(100000000, 101000000), knowledge.route(100000000, 101000000));
        List<Integer> toLudi = knowledge.route(100000000, 220000000);
        System.out.println("Henesys -> Ludibrium: " + toLudi);
        assertEquals(100000000, toLudi.get(0));
        assertEquals(220000000, toLudi.get(toLudi.size() - 1));
        assertTrue(toLudi.containsAll(List.of(101000300, 200000100, 200000121, 220000100)),
                "Ellinia station ship to Orbis, then the Ludibrium ship");
        List<Integer> toLeafre = knowledge.route(104000000, 240000000);
        System.out.println("Lith Harbor -> Leafre: " + toLeafre);
        assertTrue(toLeafre.containsAll(List.of(200000131, 240000100)));
        assertEquals(List.of(), knowledge.route(100000000, 999_000_000), "unknown map");
        assertEquals(List.of(100000000), knowledge.route(100000000, 100000000));
        List<Integer> toKft = knowledge.route(220000000, 222000000);
        System.out.println("Ludibrium -> Korean Folk Town: " + toKft);
        assertFalse(toKft.isEmpty(), "Helios Tower elevator");
        List<Integer> toElNath = knowledge.route(100000000, 211000000);
        System.out.println("Henesys -> El Nath: " + toElNath);
        assertTrue(toElNath.contains(200000000), "walk down from Orbis rather than chaining the crane and the dolphin");
        assertFalse(toElNath.contains(230000000));
        List<Integer> toEreve = knowledge.route(100000000, 130000000);
        System.out.println("Henesys -> Ereve: " + toEreve);
        assertTrue(toEreve.containsAll(List.of(101000400, 130000210)), "Kiriru's ferry, then the rankRoom script portal");
    }

    @Test
    void routesReachRienTheDojoHappyvilleAndBackAndLeaveMapleIsland() {
        for (int home : List.of(140000000, 130000000, 925020000, 209000000)) {
            assertFalse(knowledge.route(100000000, home).isEmpty(), () -> "Henesys -> " + home);
            assertFalse(knowledge.route(home, 100000000).isEmpty(), () -> home + " -> Henesys");
        }
        assertTrue(knowledge.route(140000000, 104000000).contains(140020300), "Rien's road ends at its dock");
        assertTrue(knowledge.route(925020000, 250000000).contains(250000100), "the Dojo's exit, back to Mu Lung Temple");
        assertFalse(knowledge.route(250000000, 100000000).contains(925020000), "the Dojo is no way to Victoria");
        assertTrue(knowledge.route(2000000, 100000000).contains(104000000), "Shanks sails Southperry -> Lith Harbor");
        assertEquals(List.of(), knowledge.route(104000000, 2000000), "nothing sails back to Maple Island");
        assertEquals("mu-lung", knowledge.map(925020000).orElseThrow().region());
        assertEquals("victoria", knowledge.map(910000000).orElseThrow().region());
    }

    @Test
    void scriptPortalsWithOneLiteralTargetAreExits() {
        assertTrue(knowledge.map(130000200).orElseThrow().exits().contains(130000100),
                "Crossroads of Ereve: portal/rankRoom.js, case 130000200 -> Knights Chamber");
        assertTrue(knowledge.map(130000200).orElseThrow().exits().contains(130010000),
                "portal/enterDisguise0.js warps only to Training Forest I");
    }

    @Test
    void trainingMapsStayInTheLevelWindow() {
        List<Integer> maps = knowledge.trainingMaps(35, "victoria", 5);
        System.out.println("level 35 victoria: " + maps.stream().map(id -> id + " " + knowledge.map(id).orElseThrow().name()
                + " (" + knowledge.map(id).orElseThrow().mobLevel() + ")").toList());
        assertEquals(5, maps.size());
        for (int id : maps) {
            MapFact map = knowledge.map(id).orElseThrow();
            assertEquals("victoria", map.region());
            assertTrue(map.mobLevel() >= 27 && map.mobLevel() <= 40, map.name() + " " + map.mobLevel());
            assertFalse(map.town());
        }
        assertTrue(knowledge.trainingMaps(35, "no-such-region", 5).isEmpty());
    }
}
