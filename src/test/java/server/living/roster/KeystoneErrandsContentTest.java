package server.living.roster;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static server.living.roster.KeystoneErrands.*;

/**
 * The keystone errand's content constants are the server's own data: the quests' requirements (Quest.wz Check), where
 * Lukan and Jack stand and what the two hunting grounds spawn (Map.wz life), and Firebrand's revive (Mob.wz). A WZ edit
 * that moves any of them fails here.
 */
class KeystoneErrandsContentTest {
    private static final Pattern LIFE_BLOCK = Pattern.compile("(?s)<imgdir name=\"life\">(.*?)\\n  </imgdir>");
    private static final Pattern LIFE_ENTRY = Pattern.compile("(?s)<imgdir name=\"\\d+\">(.*?)</imgdir>");
    private static final Pattern FIELD = Pattern.compile("name=\"(\\w+)\" value=\"([^\"]*)\"");

    @Test
    void theBrewingStormAsksTenStormbreakerBadgesForLukan() throws IOException {
        String quest = quest(BREWING_STORM_QUEST_ID);
        assertTrue(quest.contains("<int name=\"npc\" value=\"" + LUKAN_NPC_ID + "\"/>"));
        assertEquals(Map.of(STORMBREAKER_BADGE_ITEM_ID, BADGES_WANTED), pairs(phase(quest, 1), "item"));
    }

    @Test
    void stormingTheCastleWantsStemmingTheTideInProgressAndSeventyFiveOfEachMark() throws IOException {
        String quest = quest(STORMING_THE_CASTLE_QUEST_ID);
        assertEquals(Map.of(STEMMING_THE_TIDE_QUEST_ID, 1, BREWING_STORM_QUEST_ID, 2), statePairs(phase(quest, 0)));
        Map<Integer, Integer> marks = new TreeMap<>();
        for (int mob : CASTLE_MARKS) {
            marks.put(mob, KILLS_WANTED);
        }
        assertEquals(marks, new TreeMap<>(pairs(phase(quest, 1), "mob")));
        assertTrue(quest(STEMMING_THE_TIDE_QUEST_ID).contains("<int name=\"npc\" value=\"" + JACK_NPC_ID + "\"/>"));
    }

    @Test
    void lukanAndJackStandWhereTheErrandWalksUpToThem() throws IOException {
        assertTrue(life(PHANTOM_ROAD_MAP_ID, "n").contains(LUKAN_NPC_ID));
        assertTrue(life(CAVERN_OF_PAIN_MAP_ID, "n").contains(JACK_NPC_ID));
    }

    @Test
    void theHuntingGroundsSpawnNothingButTheirMarks() throws IOException {
        assertEquals(Set.of(STORMBREAKER_MOB_ID), new TreeSet<>(life(STORMHALL_MAP_ID, "m")));
        assertEquals(Set.of(WINDRAIDER_MOB_ID, FIREBRAND_MOB_ID, NIGHTSHADOW_MOB_ID),
                new TreeSet<>(life(CAVERN_OF_FORTITUDE_MAP_ID, "m")));
    }

    @Test
    void theFirebrandThatSpawnsRevivesIntoTheOneTheQuestCounts() throws IOException {
        String mob = Files.readString(Path.of("wz", "Mob.wz", FIREBRAND_MOB_ID + ".img.xml"));
        Matcher revive = Pattern.compile("(?s)<imgdir name=\"revive\">(.*?)</imgdir>").matcher(mob);
        assertTrue(revive.find());
        assertTrue(revive.group(1).contains("value=\"" + RISEN_FIREBRAND_MOB_ID + "\""));
    }

    private static String quest(int id) throws IOException {
        String check = Files.readString(Path.of("wz", "Quest.wz", "Check.img.xml"));
        int start = check.indexOf("\n  <imgdir name=\"" + id + "\">");
        int end = check.indexOf("\n  </imgdir>", start);
        assertTrue(start >= 0 && end > start, "quest " + id + " in Check.img.xml");
        return check.substring(start, end);
    }

    /** One phase of a quest's Check entry (0 start, 1 end), up to the next phase. */
    private static String phase(String quest, int phase) {
        int start = quest.indexOf("\n    <imgdir name=\"" + phase + "\">");
        int end = quest.indexOf("\n    </imgdir>", start);
        return quest.substring(start, end);
    }

    private static Map<Integer, Integer> pairs(String phase, String list) {
        Map<Integer, Integer> pairs = new TreeMap<>();
        int start = phase.indexOf("<imgdir name=\"" + list + "\">");
        Matcher entry = Pattern.compile("(?s)<int name=\"id\" value=\"(\\d+)\"/>\\s*<int name=\"count\" value=\"(\\d+)\"/>")
                .matcher(phase.substring(start));
        while (entry.find()) {
            pairs.put(Integer.parseInt(entry.group(1)), Integer.parseInt(entry.group(2)));
        }
        return pairs;
    }

    private static Map<Integer, Integer> statePairs(String phase) {
        Map<Integer, Integer> pairs = new TreeMap<>();
        Matcher entry = Pattern.compile("(?s)<int name=\"id\" value=\"(\\d+)\"/>\\s*<int name=\"state\" value=\"(\\d+)\"/>")
                .matcher(phase);
        while (entry.find()) {
            pairs.put(Integer.parseInt(entry.group(1)), Integer.parseInt(entry.group(2)));
        }
        return pairs;
    }

    /** The ids of the map's life entries of {@code type} ("n" NPCs, "m" mobs). */
    private static List<Integer> life(int mapId, String type) throws IOException {
        Path file = Path.of("wz", "Map.wz", "Map", "Map" + mapId / 100_000_000, String.format("%09d.img.xml", mapId));
        Matcher block = LIFE_BLOCK.matcher(Files.readString(file));
        List<Integer> ids = new ArrayList<>();
        if (!block.find()) {
            return ids;
        }
        Matcher entry = LIFE_ENTRY.matcher(block.group(1));
        while (entry.find()) {
            Map<String, String> fields = new TreeMap<>();
            Matcher field = FIELD.matcher(entry.group(1));
            while (field.find()) {
                fields.put(field.group(1), field.group(2));
            }
            if (type.equals(fields.get("type"))) {
                ids.add(Integer.parseInt(fields.get("id")));
            }
        }
        return ids;
    }
}
