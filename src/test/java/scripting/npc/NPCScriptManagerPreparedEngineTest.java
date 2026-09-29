package scripting.npc;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class NPCScriptManagerPreparedEngineTest {
    private static final int LAKELIS = 9_020_000;

    @BeforeAll
    static void muteGraal() {
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false");
    }

    /** The subset of the conversation manager the KPQ entry script touches up to its opening menu. */
    public static final class KpqEntryCm {
        public final List<String> calls = new ArrayList<>();

        public int getMapId() {
            calls.add("getMapId");
            return 103_000_000;
        }

        public KpqEvent getEventManager(String name) {
            calls.add("getEventManager:" + name);
            return new KpqEvent();
        }

        public boolean isUsingOldPqNpcStyle() {
            return false;
        }

        public Player getPlayer() {
            return new Player();
        }

        public void sendSimple(String menu) {
            calls.add("sendSimple");
        }
    }

    public static final class KpqEvent {
        public String getProperty(String key) {
            return "party";
        }
    }

    public static final class Player {
        public boolean isRecvPartySearchInviteEnabled() {
            return false;
        }

        public boolean isGM() {
            return false;
        }
    }

    @Test
    void kpqEntryScriptEvaluatedOffThreadRunsOnTheCallerThread() throws Exception {
        NPCScriptManager scripts = NPCScriptManager.getInstance();
        ScriptEngine prepared = CompletableFuture
                .supplyAsync(() -> scripts.prepareScriptEngine(NPCScriptManager.npcScriptPath(LAKELIS)))
                .get(60, TimeUnit.SECONDS);
        assertNotNull(prepared);
        assertNull(prepared.get("state"), "only the top level ran; start() was never invoked");

        KpqEntryCm cm = new KpqEntryCm();
        prepared.put("cm", cm);
        ((Invocable) prepared).invokeFunction("start");

        assertEquals(List.of("getMapId", "getEventManager:KerningPQ", "sendSimple"), cm.calls);
        assertEquals(0, ((Number) prepared.get("state")).intValue());
    }
}
