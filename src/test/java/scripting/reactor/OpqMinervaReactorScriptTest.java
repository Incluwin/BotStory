package scripting.reactor;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import scripting.AbstractScriptManager;
import scripting.event.EventInstanceManager;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P6h OB3 (user-approved content change): the Root of Life reactor in the Tower of Goddess (2006001.js) frees Minerva,
 * clears the event, and then gives every player the 3,500 EXP, the clear effect and the 5-minute bonus timer, whose
 * scheduledTimeout sends the team to 920011300. Stock act() used {@code eim} without defining it, so the script
 * stopped at that line with a ReferenceError: no EXP, no effect, no bonus timer. The fix is one line, the first in
 * act(): {@code var eim = rm.getEventInstance();}. Without it the script is the stock one byte for byte (its SHA-256
 * at da212d0b9, US-ASCII, LF, no final newline).
 */
class OpqMinervaReactorScriptTest {
    private static final Path SCRIPT = Path.of("scripts/reactor/2006001.js");
    private static final String DEFINITION = "    var eim = rm.getEventInstance();\n";
    /** 2006001.js before the fix (da212d0b9). */
    private static final String STOCK_SHA_256 = "8b0e05d46a987dd51c0ac6cc6dd639a4803121557f162813767c08784eb29965";

    /** Loads a script the way the reactor script manager does (Graal.js with host access). */
    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    @BeforeAll
    static void muteGraal() {
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false");
    }

    @Test
    void eimIsDefinedAsTheEventInstanceBeforeItsFirstUse() throws IOException {
        String script = Files.readString(SCRIPT, StandardCharsets.US_ASCII);

        int act = script.indexOf("function act() {\n");
        int definition = script.indexOf(DEFINITION);
        int firstUse = script.indexOf("eim.");
        assertTrue(act >= 0, "act() is still there");
        assertEquals(act + "function act() {\n".length(), definition, "the first line inside act()");
        assertEquals(definition, script.lastIndexOf(DEFINITION), "defined once");
        assertTrue(firstUse > definition, "defined before its first use");
    }

    @Test
    void withoutTheDefinitionTheScriptIsTheStockOneByteForByte() throws IOException, NoSuchAlgorithmException {
        byte[] bytes = Files.readAllBytes(SCRIPT);
        for (byte b : bytes) {
            assertTrue(b >= 0 && b != '\r', "2006001.js stays US-ASCII and LF");
        }
        String stock = new String(bytes, StandardCharsets.US_ASCII).replace(DEFINITION, "");

        assertEquals(STOCK_SHA_256, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(stock.getBytes(StandardCharsets.US_ASCII))), "nothing else in the script changed");
    }

    @Test
    void actFreesMinervaClearsTheEventAndGivesTheExpTheEffectAndTheBonusTimer() throws Exception {
        ScriptEngine engine = new Loader().load("reactor/2006001.js");
        assertNotNull(engine, "the script evaluates");
        ReactorActionManager rm = mock(ReactorActionManager.class);
        EventInstanceManager eim = mock(EventInstanceManager.class);
        when(rm.getEventInstance()).thenReturn(eim);
        engine.put("rm", rm);

        ((Invocable) engine).invokeFunction("act");

        var order = inOrder(rm, eim);
        order.verify(rm).spawnNpc(2013002);
        order.verify(eim).clearPQ();
        order.verify(eim).setProperty("statusStg8", "1");
        order.verify(eim).giveEventPlayersExp(3500);
        order.verify(eim).showClearEffect(true);
        order.verify(eim).startEventTimer(5L * 60_000L);
    }
}
