package server.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guide import (docs/GUIDE_KNOWLEDGE_PLAN.md section 7) against the committed knowledge file: what survives, why the
 * rest is dropped, the spots it counts, and the file the server loads back.
 */
class GuideImportTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int WILD_BOAR = 2230102;
    private static final int SHUMIS_LOST_COIN = 2055;
    private static final int MAGIC_ROCK = 4006000;
    private static Set<String> skills;

    @BeforeAll
    static void skills() throws IOException {
        skills = GuideImport.skillNames(Path.of("wz/String.wz/Skill.img.xml"));
    }

    private static JsonNode thread(String id, String... claims) throws IOException {
        return JSON.readTree("{\"source\":\"royals\",\"id\":\"" + id + "\",\"url\":\"https://example.test/" + id + "\","
                + "\"title\":\"t" + id + "\",\"starter\":\"a\",\"started\":\"2020-01-01\","
                + "\"chunks\":[{\"usable\":true,\"skip_reason\":\"\",\"claims\":[" + String.join(",", claims) + "]}]}");
    }

    private static String claim(String topic, String jobs, int min, int max, String entities, String say, boolean numbers) {
        return "{\"topic\":\"" + topic + "\",\"jobs\":[" + jobs + "],\"level_min\":" + min + ",\"level_max\":" + max
                + ",\"entities\":[" + entities + "],\"claim\":\"c " + say + "\",\"say\":\"" + say + "\","
                + "\"depends_on_numbers\":" + numbers + ",\"strength\":\"consensus\"}";
    }

    private static GuideImport.Result run(JsonNode... threads) {
        return GuideImport.run(SeedOverlay.knowledge(), skills, List.of(threads));
    }

    @Test
    void keepsWhatTheServerAgreesWith() throws IOException {
        GuideImport.Result r = run(thread("1",
                claim("training_spot", "\"warrior\", \"fighter-line\"", 20, 30, "{\"kind\":\"mob\",\"name\":\"Wild Boar\"}", "Wild boars til 30.", false),
                claim("skill_use", "\"assassin-line\"", 10, 30, "{\"kind\":\"skill\",\"name\":\"Lucky Seven\"}", "max lucky seven first", false),
                claim("build_ap", "\"thief\"", 0, 0, "", "keep dex at 25, rest luk", false)));
        assertEquals(3, r.claims().size(), r.dropped().toString());
        GuideImport.Claim boars = r.claims().get(0);
        assertEquals(List.of("MOB:" + WILD_BOAR), boars.refs());
        assertEquals(List.of("warrior"), boars.jobs(), "the branch covers its line");
        assertEquals("wild boars til 30", boars.say(), "lowercased, no final period");
        assertEquals(List.of("lucky seven"), r.claims().get(1).skills());
        assertEquals(1, r.sources().size());
    }

    @Test
    void dropsWhatTheServerCannotBackUp() throws IOException {
        GuideImport.Result r = run(thread("2",
                claim("training_spot", "", 20, 30, "{\"kind\":\"mob\",\"name\":\"Bluebottle Dragonfly King\"}", "kill them", false),
                claim("party_quest", "", 0, 0, "", "stage 2 gives 10k exp", true),
                claim("general_tip", "", 0, 0, "", "on royals you can vote for nx", false),
                claim("training_spot", "", 90, 100, "{\"kind\":\"mob\",\"name\":\"Wild Boar\"}", "boars at 90", false),
                claim("skill_use", "", 0, 0, "{\"kind\":\"skill\",\"name\":\"Phantom Blow\"}", "spam phantom blow", false),
                claim("training_spot", "", 20, 30, "{\"kind\":\"item\",\"name\":\"Red Potion\"}", "bring red pots", false)));
        assertTrue(r.claims().isEmpty(), r.claims().toString());
        Map<String, Integer> d = r.dropped();
        assertEquals(1, d.get("names something that does not resolve here"));
        assertEquals(1, d.get("rests on numbers"));
        assertEquals(1, d.get("names the guide's server, the forum or the real world"));
        assertEquals(1, d.get("training spot far from the claim's levels here"));
        assertEquals(1, d.get("names a skill this server does not have"));
        assertEquals(1, d.get("training spot names no map or monster"));
        assertEquals(1, r.unresolved().get("mob: bluebottle dragonfly king"));
        assertTrue(r.sources().isEmpty(), "a thread with nothing kept is not cited");
    }

    @Test
    void questsResolveByTheirFullName() throws IOException {
        GuideImport.Result r = run(thread("6",
                claim("quest", "", 20, 30, "{\"kind\":\"quest\",\"name\":\"Shumi's Lost Coin\"}", "shumi lost her coin in the subway", false),
                claim("quest", "", 0, 0, "{\"kind\":\"quest\",\"name\":\"Lost\"}", "do the lost quest", false),
                claim("quest", "", 0, 0, "{\"kind\":\"quest\",\"name\":\"Shumi's Lost\"}", "help shumi", false)));
        assertEquals(1, r.claims().size(), r.dropped().toString());
        assertEquals(List.of("QUEST:" + SHUMIS_LOST_COIN), r.claims().get(0).refs());
        assertEquals(2, r.dropped().get("names something that does not resolve here"), "one word or a part of a name never counts");
    }

    @Test
    void wzSpellingsDoNotDropAClaim() throws IOException {
        GuideImport.Result r = run(thread("7",
                claim("quest", "", 0, 0, "{\"kind\":\"item\",\"name\":\"Magic Rock\"}", "magic rocks for 2nd job skills", false),
                claim("general_tip", "", 0, 0, "{\"kind\":\"mob\",\"name\":\"Jr. Boogie\"}", "jr boogies curse you", false),
                claim("build_sp", "\"warrior\"", 0, 0, "{\"kind\":\"skill\",\"name\":\"Improving Max HP Increase\"}", "max improving maxhp early", false),
                claim("party_quest", "", 25, 0, "{\"kind\":\"region\",\"name\":\"Mu Lung Dojo\"}", "dojo is fun at 25", false),
                claim("quest", "", 0, 0, "{\"kind\":\"item\",\"name\":\"Summoning\"}", "get a summoning", false)));
        assertEquals(4, r.claims().size(), r.dropped().toString());
        assertEquals(List.of("ITEM:" + MAGIC_ROCK), r.claims().get(0).refs(), "WZ's \"The Magic Rock\"");
        assertEquals(List.of("improved maxhp increase"), r.claims().get(2).skills(), "kept as String.wz spells it");
        assertTrue(r.claims().get(3).refs().get(0).startsWith("PQ:"), "the dojo is a party quest here");
        assertEquals(1, r.dropped().get("names something that does not resolve here"), "a part of a name still never counts");
    }

    @Test
    void aClaimInTheGuidesOwnWordsIsDropped() throws IOException {
        String guide = "Honestly the best place to level from twenty to thirty is the wild boars outside Perion, bring pots.";
        GuideImport.Result r = GuideImport.run(SeedOverlay.knowledge(), skills, List.of(thread("8",
                claim("general_tip", "", 0, 0, "", "the best place to level from twenty to thirty is the wild boars", false),
                claim("general_tip", "", 0, 0, "", "boars by perion are good from 20 to 30", false))), t -> guide);
        assertEquals(1, r.claims().size(), r.dropped().toString());
        assertEquals("boars by perion are good from 20 to 30", r.claims().get(0).say());
        assertEquals(1, r.dropped().get("copies the guide's wording"));
    }

    @Test
    void sameAdviceTwiceIsKeptOnceAndSupportCountsThreads() throws IOException {
        String boars = claim("training_spot", "", 20, 29, "{\"kind\":\"mob\",\"name\":\"Wild Boar\"}", "wild boars til 30", false);
        GuideImport.Result r = run(thread("3", boars, boars), thread("4", boars));
        assertEquals(1, r.claims().size());
        assertEquals(2, r.dropped().get("duplicate"));
        // the claim is kept once, but each thread that gave it counts once
        assertEquals(2, r.sources().size());
        assertEquals(Set.of(0, 1), r.claims().get(0).sources());
    }

    @Test
    void skillNamesComeFromEveryStringEntry() {
        assertTrue(skills.contains("somersault kick"));
        assertTrue(skills.contains("lightning charge"));
        assertTrue(skills.contains("fire charge"), "Fire Charge: Sword, by its name before the colon");
        assertFalse(skills.contains("phantom blow"), "a Dual Blade skill");
    }

    @Test
    void sayLinesAreAsciiChatLines() {
        assertEquals("go to the boars - they're easy", GuideImport.say("“Go to the boars — they’re easy.”"));
        assertNull(GuideImport.say("   "));
        assertNull(GuideImport.say("x".repeat(GuideImport.SAY_MAX + 1)));
    }

    @Test
    void theWrittenFileLoadsBack(@TempDir Path dir) throws IOException {
        GuideImport.Result r = run(thread("5",
                claim("training_spot", "\"warrior\"", 20, 30, "{\"kind\":\"mob\",\"name\":\"Wild Boar\"}", "wild boars til 30", false),
                claim("party_quest", "", 21, 30, "{\"kind\":\"pq\",\"name\":\"kpq\"}", "kpq needs a party of 4", false)));
        assertEquals(2, r.claims().size(), r.dropped().toString());
        assertTrue(r.claims().get(1).refs().get(0).startsWith("PQ:"), "composites by name");
        Path file = dir.resolve(GuideImport.FILE_NAME);
        GuideImport.write(r, file);
        GuideKnowledge g = GuideKnowledge.load(file, SeedOverlay.knowledge());
        assertEquals(2, g.size());
        assertEquals(0, g.droppedAtLoad());
        int map = SeedOverlay.knowledge().spawnMaps(WILD_BOAR).get(0);
        assertEquals(1, g.spotsFor(110, 25).get(map), "a fighter at 25: the warrior spot");
        assertEquals(1, g.spotsFor(110, 30).get(map), "the band of its top level");
        assertNull(g.spotsFor(110, 40).get(map), "past the claim's levels");
        assertEquals(1, g.spotsFor(100, 25).get(map), "an undecided warrior too");
        assertNull(g.spotsFor(410, 25).get(map), "not a sin's spot");
        assertNull(g.spotsFor(-1, 25).get(map), "unknown class: any-class spots only");
        long boar = SeedOverlay.knowledge().names().entity(EntityKind.MOB, WILD_BOAR).orElseThrow().key();
        assertEquals("wild boars til 30", g.about(boar, 111, 27, Set.of()).get(0).say());
        assertTrue(g.about(boar, 111, 45, Set.of()).isEmpty(), "outside the claim's levels");
        assertTrue(g.about(boar, 211, 27, Set.of()).isEmpty(), "another class");
    }

    @Test
    void classKeys() {
        assertTrue(GuideKnowledge.matches("assassin-line", 412));
        assertTrue(GuideKnowledge.matches("assassin-line", 400));
        assertFalse(GuideKnowledge.matches("assassin-line", 420));
        assertTrue(GuideKnowledge.matches("thief", 422));
        assertTrue(GuideKnowledge.matches("night-walker", 1411));
        assertFalse(GuideKnowledge.matches("thief", 1411));
        assertTrue(GuideKnowledge.matches("aran", 2112));
        assertTrue(GuideKnowledge.matches("beginner", 1000));
        assertTrue(GuideKnowledge.JOB_KEYS.stream().allMatch(key -> GuideKnowledge.matches(key, jobOf(key))));
    }

    private static int jobOf(String key) {
        return switch (key) {
            case "beginner" -> 0;
            case "dawn-warrior" -> 1110;
            case "blaze-wizard" -> 1210;
            case "wind-archer" -> 1310;
            case "night-walker" -> 1410;
            case "thunder-breaker" -> 1510;
            case "aran" -> 2110;
            default -> key.startsWith("warrior") || key.startsWith("fighter") || key.startsWith("page") || key.startsWith("spear") ? 100
                    : key.startsWith("magician") || key.startsWith("fp") || key.startsWith("il") || key.startsWith("cleric") ? 200
                    : key.startsWith("bowman") || key.startsWith("hunter") || key.startsWith("crossbow") ? 300
                    : key.startsWith("thief") || key.startsWith("assassin") || key.startsWith("bandit") ? 400 : 500;
        };
    }
}
