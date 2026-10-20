package scripting.npc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CWKPQ plan C3: Jack (9201113.js) takes the Mark of the Squad's five companion hooks (2083004.js) under the key "cwkpq",
 * the same choices in the same places (create, leader's list, registered, join) and the same picker loop, plus one hook
 * of his own: with companions on the list, the Start asks whether every sigil can be turned. Any change to one hook
 * block must reach every twin.
 */
class JackCwkpqHookScriptTest {
    @Test
    void jacksHookBlockIsTheMarksUnderTheKeyCwkpqPlusTheStartCheck() throws IOException {
        String mark = read("scripts/npc/2083004.js");
        String jack = read("scripts/npc/9201113.js");

        String marksHooks = between(mark, "function companionOffer()", "function action(").replace("\"horntail\"",
                "\"cwkpq\"");
        String startCheck = "// with companions on the list, nobody can turn some class's sigil: his refusal (null: start"
                + " as stock)\nfunction startRefusal() {\n"
                + "    return Java.type('server.living.LivingWorldHooks').cwkpqStartRefusal(cm);\n}\n\n";
        String clearTime = "// with companions on the list, how long he thinks the keep takes, over the leader's list"
                + " (empty: nothing to say)\nfunction clearTime() {\n"
                + "    var line = Java.type('server.living.LivingWorldHooks').cwkpqClearTime(cm);\n"
                + "    return line == null || line == \"\" ? \"\" : line + \"\\r\\n\\r\\n\";\n}\n\n";
        assertEquals(marksHooks, between(jack, "function companionOffer()", "function action(").replace(startCheck, "")
                .replace(clearTime, ""));
        assertEquals(between(mark, "} else if (status == 30) {", "\n    }\n}"),
                between(jack, "} else if (status == 30) {", "\n    }\n}"), "the same picker loop");
        assertEquals(5, count(jack, "LivingWorldHooks').boss"), "the five hooks");
        assertEquals(5, count(jack, "(cm, \"cwkpq\""));
    }

    @Test
    void jackOffersTheChoicesWhereTheMarkDoesAndChecksTheSigilsAtStart() throws IOException {
        String jack = read("scripts/npc/9201113.js");
        assertEquals(4, count(jack, "var choices = "), "create, leader's list, registered, join");
        assertEquals(1, count(jack, "var refusal = startRefusal();"));
        assertEquals(1, count(jack, "cm.sendSimple(clearTime() + list + choices);"),
                "his clear time over the leader's list, only when he offers companions");
        assertTrue(jack.contains("cm.sendSimple(list);"), "the stock leader's list is kept");
        int sizeCheck = jack.indexOf("You need at least \" + min + \" players registered in your expedition.");
        int refusal = jack.indexOf("var refusal = startRefusal();");
        int send = jack.indexOf("The expedition will begin and you will now be escorted");
        assertTrue(sizeCheck > 0 && sizeCheck < refusal && refusal < send, "after the stock size check, before Start");
        assertTrue(jack.contains("em.getInstance(\"CWKPQ\" + player.getClient().getChannel()).registerPlayer(player);"),
                "the stock late entry is kept");
    }

    private static String between(String text, String from, String to) {
        int start = text.indexOf(from);
        int end = text.indexOf(to, start + from.length());
        assertTrue(start >= 0 && end > start, from + " .. " + to);
        return text.substring(start, end);
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.US_ASCII).replace("\r\n", "\n");
    }

    private static int count(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) {
            count++;
        }
        return count;
    }
}
