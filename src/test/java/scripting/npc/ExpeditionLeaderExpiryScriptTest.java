package scripting.npc;

import client.Character;
import client.Client;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import scripting.AbstractScriptManager;
import scripting.event.EventManager;
import server.expeditions.Expedition;
import server.expeditions.ExpeditionType;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The expedition NPCs re-read the expedition on every click, and its registration clock can run out while the leader
 * has a page open. 2026-10-20 live (Zakum): the clock ended 2 s before the leader's OK on "The expedition will
 * begin", and Adobis called em.startInstance(null) - a Graal TypeError (two overloads fit null) instead of a line.
 */
class ExpeditionLeaderExpiryScriptTest {
    private static final String ENDED = "This expedition has already ended. Talk to me again to form a new one.";

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    private record Leader(ScriptEngine engine, NPCConversationManager cm, EventManager em) {
        void click(int selection) throws Exception {
            ((Invocable) engine).invokeFunction("action", 1, 0, selection);
        }
    }

    @BeforeAll
    static void muteGraal() {
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false");
    }

    /** The leader of a full, registering {@code type} expedition with the NPC's menu open (status 2). */
    private static Leader leaderAtMenu(String script, ExpeditionType type, String event) throws Exception {
        ScriptEngine engine = new Loader().load("npc/" + script);
        NPCConversationManager cm = mock(NPCConversationManager.class);
        Character leader = mock(Character.class);
        Client client = mock(Client.class);
        when(client.getChannel()).thenReturn(1);
        when(leader.getClient()).thenReturn(client);
        when(leader.getName()).thenReturn("Leader");
        when(leader.getLevel()).thenReturn(type.getMinLevel());
        Expedition expedition = mock(Expedition.class);
        when(expedition.isLeader(leader)).thenReturn(true);
        when(expedition.isRegistering()).thenReturn(true);
        when(expedition.getLeader()).thenReturn(leader);
        List<Entry<Integer, String>> members = new ArrayList<>();
        for (int i = 0; i < type.getMinSize(); i++) {
            members.add(new SimpleEntry<>(i + 1, "Member" + i));
        }
        when(expedition.getMemberList()).thenReturn(members);
        EventManager em = mock(EventManager.class);
        when(em.getProperty(anyString())).thenReturn("");
        when(cm.getPlayer()).thenReturn(leader);
        when(cm.getExpedition(type)).thenReturn(expedition);
        when(cm.getEventManager(event)).thenReturn(em);
        engine.put("cm", cm);

        Leader opened = new Leader(engine, cm, em);
        opened.click(0);
        return opened;
    }

    @ParameterizedTest
    @CsvSource({"2030013.js, ZAKUM, ZakumBattle", "2083004.js, HORNTAIL, HorntailBattle",
            "2141001.js, PINKBEAN, PinkBeanBattle", "9120201.js, SHOWA, ShowaBattle", "9270047.js, SCARGA, ScargaBattle"})
    void theClockRunningOutOnTheStartPageEndsTheTalkInsteadOfStartingNothing(String script, ExpeditionType type,
                                                                              String event) throws Exception {
        Leader leader = leaderAtMenu(script, type, event);
        leader.click(2);
        when(leader.cm().getExpedition(type)).thenReturn(null);

        leader.click(0);

        verify(leader.cm()).sendOk(ENDED);
        verify(leader.cm()).dispose();
        verify(leader.em(), never()).startInstance(any(Expedition.class));
    }

    @ParameterizedTest
    @CsvSource({"2030013.js, ZAKUM, ZakumBattle", "2083004.js, HORNTAIL, HorntailBattle",
            "2141001.js, PINKBEAN, PinkBeanBattle", "9120201.js, SHOWA, ShowaBattle", "9270047.js, SCARGA, ScargaBattle"})
    void theClockRunningOutOnTheMenuEndsTheTalk(String script, ExpeditionType type, String event) throws Exception {
        for (int choice = 1; choice <= 3; choice++) {
            Leader leader = leaderAtMenu(script, type, event);
            when(leader.cm().getExpedition(type)).thenReturn(null);

            leader.click(choice);

            verify(leader.cm()).sendOk(ENDED);
            verify(leader.cm()).dispose();
        }
    }
}
