package server.agents.capabilities.partyquest.lpq;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P6g LPQ section 10.0: every companion line of AgentLpqCompanionPlay (its String constants, and every static String
 * builder called with player names, stage numbers and both truth values) reads like a player: lower case except the
 * player names, no '#', '[', '<', '>', '->', '/', no 'a:b' or 'x/y' counters, no "stage N", no job names, no ids and
 * never "Yuris". Digit groups like 133 are allowed (players type the maze code so).
 */
class AgentLpqCompanionLinesTest {
    static final List<String> PLAYERS = List.of("Crewfriend", "Kes", "Pel", "Mira");
    /** Names as long as the game allows (12 characters), for the length rule. */
    private static final List<String> LONG_NAMES = List.of("Abcdefghijkl", "Mnopqrstuvwx", "Opqrstuvwxyz");
    /**
     * The longest map-chat line a companion says: HPQ's longest live line is 96, and a non-GM player who sends more than
     * 127 characters is disconnected as a packet editor (GeneralChatHandler), so no player ever reads a longer one.
     */
    private static final int LONGEST_CHAT_LINE = 100;
    private static final Pattern COUNTER = Pattern.compile("\\d\\s*[:/]\\s*\\d");
    private static final Pattern STAGE_NUMBER = Pattern.compile("(?i)\\bstage\\s*\\d");
    private static final Pattern ID = Pattern.compile("\\d{5,}");
    private static final Pattern JOB = Pattern.compile("(?i)\\b(warrior|magician|mage|bowman|archer|thief|pirate"
            + "|fighter|page|spearman|hunter|crossbowman|assassin|bandit|wizard|cleric|gunslinger|brawler)\\b");

    /** The lint problems of one line, or an empty list. */
    static List<String> problems(String line) {
        List<String> problems = new ArrayList<>();
        String withoutNames = line;
        for (String player : PLAYERS) withoutNames = withoutNames.replace(player, "");
        if (!withoutNames.equals(withoutNames.toLowerCase())) problems.add("upper case");
        for (String banned : List.of("#", "[", "<", ">", "->", "/", "Yuris", "yuris")) {
            if (line.contains(banned)) problems.add("contains " + banned);
        }
        if (COUNTER.matcher(line).find()) problems.add("a counter");
        if (STAGE_NUMBER.matcher(line).find()) problems.add("a stage number");
        if (ID.matcher(line).find()) problems.add("an id");
        if (JOB.matcher(line).find()) problems.add("a job name");
        return problems;
    }

    /** Every line AgentLpqCompanionPlay can produce for the sample arguments. */
    static List<String> allLines() throws ReflectiveOperationException {
        return allLines(List.of("Crewfriend", "Kes", "Pel"));
    }

    /** Every line AgentLpqCompanionPlay can produce with these player names (the first two as names, all in lists). */
    private static List<String> allLines(List<String> names) throws ReflectiveOperationException {
        List<String> lines = new ArrayList<>();
        for (Field field : AgentLpqCompanionPlay.class.getDeclaredFields()) {
            if (field.getType() == String.class && Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                lines.add((String) field.get(null));
            }
        }
        for (Method method : AgentLpqCompanionPlay.class.getDeclaredMethods()) {
            if (method.getReturnType() != String.class || !Modifier.isStatic(method.getModifiers())) continue;
            method.setAccessible(true);
            for (List<Object> args : argumentSets(method.getParameterTypes(), names)) {
                try {
                    Object line = method.invoke(null, args.toArray());
                    if (line != null) lines.add((String) line);
                } catch (InvocationTargetException rejected) {
                    // An argument the builder refuses (an int outside its range) makes no line.
                }
            }
        }
        return lines;
    }

    private static List<List<Object>> argumentSets(Class<?>[] types, List<String> names) {
        List<List<Object>> sets = new ArrayList<>();
        sets.add(new ArrayList<>());
        for (Class<?> type : types) {
            List<Object> values = new ArrayList<>();
            if (type == String.class) values.addAll(names.subList(0, 2));
            else if (type == int.class || type == Integer.class) {
                for (int value = 0; value <= 12; value++) values.add(value);
                values.addAll(List.of(501, 502, 503, 504, 505, 506, 922_010_502, 922_010_503));
            } else if (type == long.class) values.addAll(List.of(0L, 1L));
            else if (type == boolean.class) values.addAll(List.of(true, false));
            else if (type == List.class) {
                values.addAll(List.of(List.of(names.get(1)), List.of(names.get(1), names.get(2)), List.of()));
            }
            else if (type.isEnum()) values.addAll(List.of(type.getEnumConstants()));
            else return List.of();
            List<List<Object>> next = new ArrayList<>();
            for (List<Object> set : sets) {
                for (Object value : values) {
                    List<Object> extended = new ArrayList<>(set);
                    extended.add(value);
                    next.add(extended);
                }
            }
            sets = next;
        }
        return sets;
    }

    @Test
    void everyCompanionLinePassesTheLint() throws ReflectiveOperationException {
        List<String> lines = allLines();
        assertTrue(lines.size() >= 5, "found " + lines);
        List<String> bad = new ArrayList<>();
        for (String line : lines) {
            List<String> problems = problems(line);
            if (!problems.isEmpty()) bad.add(problems + ": " + line);
        }
        assertEquals(List.of(), bad);
    }

    /**
     * Every map-chat line fits what a player can send, with the longest names: a long line is said as two lines by
     * the same speaker (the parts a builder joins with a line break).
     */
    @Test
    void everyChatLineFitsWhatAPlayerCanSend() throws ReflectiveOperationException {
        List<String> lines = new ArrayList<>(allLines(LONG_NAMES));
        for (AgentLpqParting.Kind kind : AgentLpqParting.Kind.values()) {
            for (int speaker : List.of(1, 9)) {
                for (boolean pastTheClear : List.of(true, false)) {
                    String line = new AgentLpqParting(kind, 9, LONG_NAMES.getFirst()).line(speaker, pastTheClear);
                    if (line != null) lines.add(line);
                }
            }
        }
        List<String> tooLong = new ArrayList<>();
        for (String line : lines) {
            for (String part : line.split("\n")) {
                if (part.length() > LONGEST_CHAT_LINE) tooLong.add(part.length() + ": " + part);
            }
        }
        assertEquals(List.of(), tooLong);
    }

    @Test
    void theLintCatchesWhatItShould() {
        assertEquals(List.of("upper case", "a stage number"), problems("Stage 2 trap clear. Everyone enter now."));
        assertEquals(List.of("contains /", "a counter"), problems("passes 3/4"));
        assertEquals(List.of("upper case", "contains Yuris", "a job name"), problems("bringing it to Yuris as a hunter"));
        assertEquals(List.of(), problems("Crewfriend, it's 133 221 333 123 111 from the bottom."));
        assertEquals(List.of("an id"), problems("room 922010503"));
    }

    @Test
    void theStageLinesNameTheBalloons() {
        assertEquals("Crewfriend, talk to the red balloon so it knows we're here. we'll get the passes.",
                AgentLpqCompanionPlay.stageStartLine(1, "Crewfriend"));
        assertEquals("Crewfriend, talk to the yellow balloon, we'll get the passes.",
                AgentLpqCompanionPlay.stageStartLine(3, "Crewfriend"));
        assertEquals("violet balloon", AgentLpqCompanionPlay.balloonName(9));
        assertEquals("sky-blue balloon", AgentLpqCompanionPlay.balloonName(7));
    }
}
