package server.knowledge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The knowledge side of the content lint over the real file and the seed rows: names next to ids, where an item really
 * comes from (Fairy Wing only from Happy's snowmen), mobs that never spawn, training fields players cannot share, PQ
 * lobbies, dead gear, and unknown ids as ERRORs only while the file is as new as the WZ that lists them.
 */
class KnowledgeLintTest {
    private static final String WHERE = "test.yaml quest q step s";

    private final KnowledgeLint lint = new KnowledgeLint(SeedOverlay.knowledge(), List.of(), true, true);

    @Test
    void labelsPutNamesNextToIds() {
        assertEquals("4003003 Fairy Wing", lint.label(EntityKind.ITEM, 4003003));
        assertEquals("1222014 (unnamed custom equip)", lint.label(EntityKind.ITEM, 1222014));
        assertEquals("9500317 Kid Snowman", lint.label(EntityKind.MOB, 9500317));
        assertEquals("209080000 Extra Frosty Snow Zone near Happyville", lint.label(EntityKind.MAP, 209080000));
        assertEquals("100000000 Henesys", lint.label(EntityKind.MAP, 100000000), "a town is not near itself");
        assertEquals("9220004 Happy", lint.label(EntityKind.NPC, 9220004));
        assertEquals("2104 Fanzy's Red Furball", lint.label(EntityKind.QUEST, 2104));
        assertEquals("9999999", lint.label(EntityKind.ITEM, 9999999));
    }

    @Test
    void fairyWingDropsOnlyFromTheHappyvilleSnowmen() {
        List<String> out = findings(found -> lint.obtainable(4003003, WHERE, found));

        assertEquals(1, out.size(), out.toString());
        String finding = out.get(0);
        assertTrue(finding.startsWith("WARN " + WHERE + ": item 4003003 Fairy Wing drops only from 9500317 Kid Snowman"), finding);
        assertTrue(finding.contains("which only a script spawns (NPC 9220004 Happy at 209080000"), finding);
        assertTrue(finding.endsWith("nobody can farm it in the open"), finding);
    }

    @Test
    void farmableBuyableAndGlobalItemsPass() {
        for (int item : new int[]{4000029, 2000000, 4310000}) {
            assertEquals(List.of(), findings(out -> lint.obtainable(item, WHERE, out)), "item " + item);
        }
    }

    @Test
    void questGatedAndSourcelessItemsWarn() {
        List<String> gated = findings(out -> lint.obtainable(4031273, WHERE, out));
        assertEquals(1, gated.size(), gated.toString());
        assertTrue(gated.get(0).contains("only drops while a Quest.wz quest is active: 2104 Fanzy's Red Furball (210100"), gated.get(0));

        List<String> none = findings(out -> lint.obtainable(4031000, WHERE, out));
        assertEquals(1, none.size(), none.toString());
        assertTrue(none.get(0).contains("no monster drops it and no NPC shop sells it in the Liquibase seed data"), none.get(0));
        assertTrue(none.get(0).contains("(quest rewards, NPC scripts and reactors are not checked)"), none.get(0));
    }

    @Test
    void spawnChecks() {
        assertEquals(List.of(), findings(out -> lint.spawns(100100, WHERE, out)));
        List<String> scripted = findings(out -> lint.spawns(9500317, WHERE, out));
        assertEquals(1, scripted.size(), scripted.toString());
        assertTrue(scripted.get(0).contains("mob 9500317 Kid Snowman only appears through a script (NPC 9220004 Happy"), scripted.get(0));
        List<String> never = findings(out -> lint.spawns(6230101, WHERE, out));
        assertEquals(1, never.size(), never.toString());
        assertTrue(never.get(0).contains("never spawns: no map places it and no script spawns it (DB-placed life is not checked offline)"),
                never.get(0));
    }

    @Test
    void trainingFieldChecks() {
        assertEquals(List.of(), findings(out -> lint.trainingField(100010000, "fields.yaml", out)));
        List<String> instanced = findings(out -> lint.trainingField(103000800, "fields.yaml", out));
        assertEquals(1, instanced.size(), instanced.toString());
        assertTrue(instanced.get(0).contains("sends players back to 103000890"), instanced.get(0));
        List<String> noWayIn = findings(out -> lint.trainingField(200060001, "fields.yaml", out));
        assertEquals(1, noWayIn.size(), noWayIn.toString());
        assertTrue(noWayIn.get(0).startsWith("WARN fields.yaml: no portal or travel leg leads to map 200060001"), noWayIn.get(0));
    }

    @Test
    void lobbyChecks() {
        List<String> town = findings(out -> lint.lobby("opq", 200000000, "regions.yaml orbis lobby opq", out));
        assertEquals(1, town.size(), town.toString());
        assertTrue(town.get(0).startsWith("WARN regions.yaml orbis lobby opq: the PQ starts at 200080101"), town.get(0));
        assertTrue(town.get(0).contains("(NPC 2013000 Wonky the Fairy), not at 200000000 Orbis"), town.get(0));
        assertEquals(List.of(), findings(out -> lint.lobby("opq", 200080101, WHERE, out)));
        assertEquals(List.of(), findings(out -> lint.lobby("lpq", 221024500, WHERE, out)));
        assertEquals(List.of(), findings(out -> lint.lobby("kpq", 103000000, WHERE, out)));
        assertEquals(List.of(), findings(out -> lint.lobby("nosuchpq", 100000000, WHERE, out)));
    }

    @Test
    void gearWarnsOnlyAboveEveryLevelAHolderReaches() {
        KnowledgeLint.Holder young = new KnowledgeLint.Holder("career a", 1, 9);
        String where = "loadouts.yaml loadout x";
        List<String> two = findings(out -> lint.gear(where, List.of(1002026), Map.of(),
                List.of(young, new KnowledgeLint.Holder("career b", 10, 19)), out));
        assertEquals(List.of("WARN " + where + ": equip 1002026 Brown Bamboo Hat needs level 25, above career a (levels 1-9)"
                + " and 1 more; it is carried, never worn"), two);
        List<String> one = findings(out -> lint.gear(where, List.of(1002026), Map.of(),
                List.of(young, new KnowledgeLint.Holder("career b", 10, 29)), out));
        assertEquals(1, one.size(), one.toString());
        assertFalse(one.get(0).contains("more"), "the 10-29 band grows into it: " + one.get(0));
        assertEquals(List.of(), findings(out -> lint.gear(where, List.of(1002026), Map.of(),
                List.of(new KnowledgeLint.Holder("persona p", 25, 30)), out)));

        KnowledgeLint.Holder page = new KnowledgeLint.Holder("career warrior-page", 10, 29);
        List<String> pool = findings(out -> lint.gear(where, List.of(), Map.of("weapon", List.of(1302008, 1402002)),
                List.of(page), out));
        assertEquals(1, pool.size(), pool.toString());
        assertTrue(pool.get(0).startsWith("WARN " + where + " outfit weapon: nothing in the pool is wearable below level 30"), pool.get(0));
        assertEquals(List.of(), findings(out -> lint.gear(where, List.of(), Map.of("weapon", List.of(1302008, 1402002, 0)),
                List.of(page), out)));
    }

    @Test
    void unknownIdsAreErrorsOnlyWhileTheirInputsAreFresh() {
        List<String> fresh = new ArrayList<>();
        assertFalse(lint.require(EntityKind.ITEM, 9999999, WHERE, fresh));
        assertTrue(lint.require(EntityKind.ITEM, 4003003, WHERE, fresh));
        assertEquals(List.of("ERROR " + WHERE + ": item 9999999 does not exist in the server WZ"), fresh);

        KnowledgeLint cap = new KnowledgeLint(SeedOverlay.knowledge(), List.of("wz/Character.wz/Cap"), true, true);
        List<String> stale = findings(out -> cap.require(EntityKind.ITEM, 9999999, WHERE, out));
        assertEquals(1, stale.size(), stale.toString());
        assertTrue(stale.get(0).startsWith("WARN " + WHERE + ": item 9999999 is not in the knowledge file, which is older than "
                + "wz/Character.wz/Cap"), stale.get(0));
        assertTrue(stale.get(0).endsWith("run tools\\knowledge-export.bat"), stale.get(0));
        assertTrue(findings(out -> cap.require(EntityKind.MOB, 7777777, WHERE, out)).get(0).startsWith("ERROR"),
                "an equip folder never downgrades mobs");

        KnowledgeLint scripts = new KnowledgeLint(SeedOverlay.knowledge(), List.of("scripts/npc"), true, true);
        assertTrue(findings(out -> scripts.require(EntityKind.ITEM, 9999999, WHERE, out)).get(0).startsWith("ERROR"));

        assertEquals(List.of("WARN " + WHERE + ": npc 7777777 is not a named or placed NPC in the server WZ"),
                findings(out -> lint.require(EntityKind.NPC, 7777777, WHERE, out)));
    }

    @Test
    void installOfflineReadsTheFileAndTheSeed(@TempDir Path empty) {
        try {
            assertEquals(List.of(), KnowledgeLint.installOffline(KnowledgeFixtures.DIR, Path.of(SeedData.DEFAULT_ROOT)));
            KnowledgeLint installed = KnowledgeLint.current();
            assertNotNull(installed);
            assertEquals("2070005 Steely Throwing-Knives", installed.label(EntityKind.ITEM, 2070005));
            assertEquals(1, findings(out -> installed.obtainable(4003003, WHERE, out)).size());

            KnowledgeLint.clearOffline();
            assertFalse(WorldKnowledge.current().ready(), "no snapshot is published in this test");
            List<String> unreadable = KnowledgeLint.installOffline(empty, Path.of(SeedData.DEFAULT_ROOT));
            assertEquals(1, unreadable.size(), unreadable.toString());
            assertTrue(unreadable.get(0).startsWith("cannot read ") && unreadable.get(0).endsWith("id and source checks skipped"),
                    unreadable.get(0));
            assertNull(KnowledgeLint.current(), "nothing installed");

            List<String> noSeed = KnowledgeLint.installOffline(KnowledgeFixtures.DIR, empty.resolve("db"));
            assertEquals(List.of("no Liquibase seed data at " + empty.resolve("db") + "; item source checks skipped"), noSeed);
            assertEquals(List.of(), findings(out -> KnowledgeLint.current().obtainable(4003003, WHERE, out)));
        } finally {
            KnowledgeLint.clearOffline();
        }
    }

    private static List<String> findings(java.util.function.Consumer<List<String>> check) {
        List<String> out = new ArrayList<>();
        check.accept(out);
        return out;
    }
}
