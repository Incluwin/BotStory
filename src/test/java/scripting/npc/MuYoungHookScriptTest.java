package scripting.npc;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.event.EventManager;
import server.expeditions.Expedition;
import server.expeditions.ExpeditionType;
import server.integration.AgentPresence;
import server.living.LivingWorld;
import server.living.roster.CompanionBossRuns;

import javax.script.Bindings;
import javax.script.Invocable;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Where Mu Young (1061014.js) offers an owner "My party will register too." (P7 Part A step 9): seven insertions in her
 * Easy branch and nothing else. Two functions ask the living world (its class loaded inside them, lazily, never at the
 * top level); the leader's list gets the option as its fourth choice (status 2); a member already on her list, one who
 * just joined it and the owner who just created it get it as their first choice of a two-choice window (status 30).
 * Without the seven blocks her script is the stock one byte for byte (its SHA-256 at c6883f520). Run in Graal as
 * NPCScriptManager runs it: an owner sees the option and picking it answers in her words, an Agent's headless sign-up
 * ([1]) and every window with no living world are the stock ones, and Normal Balrog never asks.
 */
class MuYoungHookScriptTest {
    private static final Path SCRIPT = Path.of("scripts/npc/1061014.js");
    private static final String OFFERED = "Java.type('server.living.LivingWorldHooks').bossPartyOffered(cm, \"easy_balrog\")";
    private static final String REGISTER = "Java.type('server.living.LivingWorldHooks').bossPartyRegister(cm, \"easy_balrog\")";
    /** 1061014.js before the hooks (c6883f520), LF line endings. */
    private static final String STOCK_SHA_256 = "b3af92700a1a6fd18c991249a412d3cbd9a28635fd5d0e917552de8685b41935";

    /** a: after selectExpedition, the two functions and the two-choice window's choices. */
    private static final String FUNCTIONS = ""
            + "\n"
            + "// companions: Mu Young offers an owner \"My party will register too.\" "
            + "(living_world.COMPANION_BOSS_ENABLED_TYPES)\n"
            + "var companionOptions = \"\\r\\n#b#L1#My party will register too.#l\\r\\n#L2#That's all for now.#l\";\n"
            + "\n"
            + "function companionOffer() {\n"
            + "    return exped == ExpeditionType.BALROG_EASY && " + OFFERED + ";\n"
            + "}\n"
            + "\n"
            + "function companionRegister() {\n"
            + "    return " + REGISTER + ";\n"
            + "}\n";
    /** b: the leader's list, before the stock one. */
    private static final String LEADER_LIST = ""
            + "            if (companionOffer()) {\n"
            + "                cm.sendSimple(list + \"\\r\\n#L4#My party will register too.#l\");\n"
            + "                status = 2;\n"
            + "                return;\n"
            + "            }\n";
    /** c: a member already on her list, before the stock "already registered" line. */
    private static final String ALREADY_LISTED = ""
            + "            if (companionOffer()) {\n"
            + "                cm.sendSimple(\"You have already registered for the expedition. Please wait for #r\""
            + " + expedition.getLeader().getName() + \"#k to begin it.\" + companionOptions);\n"
            + "                status = 30;\n"
            + "                return;\n"
            + "            }\n";
    /** d: a member joining her list, before the stock join. */
    private static final String JOINING = ""
            + "            if (companionOffer()) {\n"
            + "                var joined = expedition.addMember(player);\n"
            + "                if (expedition.contains(player)) {\n"
            + "                    cm.sendSimple(joined + companionOptions);\n"
            + "                    status = 30;\n"
            + "                } else {\n"
            + "                    cm.sendOk(joined);\n"
            + "                    cm.dispose();\n"
            + "                }\n"
            + "                return;\n"
            + "            }\n";
    /** e: the owner who just created the expedition, before the stock "has been created" line. */
    private static final String CREATED = ""
            + "                if (companionOffer()) {\n"
            + "                    cm.sendSimple(\"The #r\" + expedDifficulty + \" \" + expedBoss + \" Expedition#k has "
            + "been created.\\r\\n\\r\\nTalk to me again to view the current team or start the fight!\" "
            + "+ companionOptions);\n"
            + "                    status = 30;\n"
            + "                    return;\n"
            + "                }\n";
    /** f: the leader's fourth choice, first in the status 2 branch. */
    private static final String FOURTH_CHOICE = ""
            + "        if (selection == 4) {\n"
            + "            cm.sendOk(companionRegister());\n"
            + "            cm.dispose();\n"
            + "            return;\n"
            + "        }\n";
    /** g: the two-choice window's answer, a new branch before status 10. */
    private static final String TWO_CHOICES = ""
            + "    } else if (status == 30) {\n"
            + "        if (selection == 1) {\n"
            + "            cm.sendOk(companionRegister());\n"
            + "        }\n"
            + "        cm.dispose();\n";
    private static final List<String> BLOCKS = List.of(FUNCTIONS, LEADER_LIST, ALREADY_LISTED, JOINING, CREATED,
            FOURTH_CHOICE, TWO_CHOICES);

    private static final String OPTION = "#L1#My party will register too.#l";
    private static final String REPLY = "Very well. Have them come and speak with me before time runs out.";

    private final Character player = mock(Character.class);
    private final Expedition expedition = mock(Expedition.class);
    private final LivingWorld world = mock(LivingWorld.class);
    private final CompanionBossRuns bossRuns = mock(CompanionBossRuns.class);
    private final List<String> windows = new ArrayList<>();
    private final AtomicBoolean listed = new AtomicBoolean();
    private Expedition onTheChannel = expedition;

    @BeforeAll
    static void muteGraal() {
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false");
    }

    /** Bossfriend (Lv70) and the channel's Easy expedition, which takes sign-ups; joining it lists them. */
    @BeforeEach
    void bossfriendAtMuYoung() {
        when(player.getLevel()).thenReturn(70);
        when(player.getName()).thenReturn("Bossfriend");
        when(expedition.isRegistering()).thenReturn(true);
        when(expedition.getLeader()).thenReturn(player);
        when(expedition.contains(player)).thenAnswer(inv -> listed.get());
        when(expedition.addMember(player)).thenAnswer(inv -> {
            listed.set(true);
            return "You have registered for the expedition successfully!";
        });
    }

    @AfterEach
    void close() {
        AgentPresence.install(null);
    }

    // ---- the insertions ------------------------------------------------------------------------

    @Test
    void sevenBlocksInHerEasyBranchEachInItsPlace() throws IOException {
        String script = Files.readString(SCRIPT, StandardCharsets.US_ASCII);

        for (String block : BLOCKS) {
            int at = script.indexOf(block);
            assertTrue(at > 0, block);
            assertEquals(at, script.lastIndexOf(block), "once: " + block);
        }
        assertTrue(script.contains("    expedition = cm.getExpedition(exped);\n}\n" + FUNCTIONS
                + "\nfunction openExpedition() {\n"), "a: right after selectExpedition");
        within(script, LEADER_LIST, "} else if (expedition.isLeader(player)) {", "} else if (expedition.isRegistering()) {");
        assertTrue(script.contains("        } else {\n" + LEADER_LIST + "            cm.sendSimple(list);\n"
                + "            status = 2;\n"), "b: before the stock leader's list");
        assertTrue(script.contains("        if (expedition.contains(player)) {\n" + ALREADY_LISTED
                + "            cm.sendOk(\"You have already registered"), "c: before the stock line");
        within(script, ALREADY_LISTED, "} else if (expedition.isRegistering()) {", "} else if (expedition.isInProgress()) {");
        assertTrue(script.contains("        } else {\n" + JOINING + "            cm.sendOk(expedition.addMember(player));\n"),
                "d: before the stock join");
        assertTrue(script.contains("            if (result == 0) {\n" + CREATED + "                cm.sendOk(\"The #r\""),
                "e: before the stock created line");
        assertTrue(script.contains("    } else if (status == 2) {\n" + FOURTH_CHOICE + "        if (selection == 1) {\n"),
                "f: first in the status 2 branch");
        assertTrue(script.contains("        }\n" + TWO_CHOICES + "    } else if (status == 10) {\n"),
                "g: a branch of its own before status 10");
        within(script, TWO_CHOICES, "} else if (status == 6) {", "} else if (status == 10) {");
    }

    @Test
    void herClassIsLoadedOnlyInsideTheTwoFunctions() throws IOException {
        String script = Files.readString(SCRIPT, StandardCharsets.US_ASCII);

        assertEquals(2, script.split("LivingWorldHooks", -1).length - 1, "two calls, no other living-world call");
        assertEquals(1, script.split("bossPartyOffered\\(", -1).length - 1);
        assertEquals(1, script.split("bossPartyRegister\\(", -1).length - 1);
        for (String line : script.split("\n")) {
            assertFalse(!line.startsWith(" ") && line.contains("Java.type('server.living"), "no top-level load: " + line);
        }
        assertFalse(script.contains("\"zakum\"") || script.contains("bossPartyOffered(cm, \"balrog"),
                "Easy Balrog's key, never another boss's");
    }

    @Test
    void memberWindowsGoToStatus30AndTheFourthChoiceIsOnlyInTheLeadersList() throws IOException {
        String script = Files.readString(SCRIPT, StandardCharsets.US_ASCII);

        for (String memberWindow : List.of(ALREADY_LISTED, JOINING, CREATED)) {
            assertTrue(memberWindow.contains("status = 30;") && memberWindow.contains("companionOptions"),
                    memberWindow);
        }
        assertEquals(1, script.split("#L4#", -1).length - 1, "one fourth choice");
        assertTrue(LEADER_LIST.contains("#L4#") && LEADER_LIST.contains("status = 2;"));
        assertEquals(1, script.split("selection == 4", -1).length - 1);
        assertTrue(script.indexOf("selection == 4") > script.indexOf("} else if (status == 2) {")
                && script.indexOf("selection == 4") < script.indexOf("} else if (status == 4) {"),
                "answered in the status 2 branch");
        assertEquals(1, script.split("status == 30", -1).length - 1);
    }

    @Test
    void withoutTheBlocksHerScriptIsTheStockOneByteForByte() throws IOException, NoSuchAlgorithmException {
        byte[] bytes = Files.readAllBytes(SCRIPT);
        for (byte b : bytes) {
            assertTrue(b >= 0 && b != '\r', "1061014.js stays US-ASCII and LF");
        }
        String stock = new String(bytes, StandardCharsets.US_ASCII);
        for (String block : BLOCKS) {
            stock = stock.replace(block, "");
        }

        assertEquals(STOCK_SHA_256, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(stock.getBytes(StandardCharsets.US_ASCII))), "nothing else in her script changed");
    }

    // ---- run in Graal --------------------------------------------------------------------------

    @Test
    void anOwnerJoiningSeesTheOptionAndPickingItAnswersInHerWords() throws Exception {
        livingWorldOffers();

        run(1, 1); // Easy, then "My party will register too."

        assertEquals(4, windows.size(), windows.toString());
        assertTrue(windows.get(1).startsWith("simple You have registered for the expedition successfully!"),
                windows.get(1));
        assertTrue(windows.get(1).endsWith(OPTION + "\r\n#L2#That's all for now.#l"), windows.get(1));
        assertEquals(List.of("ok " + REPLY, "dispose"), windows.subList(2, 4));
        verify(bossRuns).registerFromNpc(player, "easy_balrog");
    }

    @Test
    void anAgentsHeadlessSignUpIsTheStockJoin() throws Exception {
        livingWorldOffers();
        AgentPresence.install(chr -> chr == player); // a companion signing itself up ([1])

        run(1);

        assertEquals(List.of("ok You have registered for the expedition successfully!", "dispose"),
                windows.subList(1, windows.size()));
        assertTrue(listed.get());
        verify(bossRuns, never()).offers(any(), anyString());
    }

    @Test
    void theLeadersListOffersItAsTheFourthChoice() throws Exception {
        livingWorldOffers();
        when(expedition.isLeader(player)).thenReturn(true);

        run(1, 4);

        assertTrue(windows.get(1).startsWith("simple What would you like to do?"), windows.get(1));
        assertTrue(windows.get(1).endsWith("#L3#Stop the expedition.#l\r\n#L4#My party will register too.#l"),
                windows.get(1));
        assertEquals(List.of("ok " + REPLY, "dispose"), windows.subList(2, windows.size()));
    }

    @Test
    void theOwnerWhoCreatesItSeesTheOptionAndThatsAllForNowEndsTheTalk() throws Exception {
        livingWorldOffers();
        onTheChannel = null;

        run(1, 1, 2); // Easy, "Lets get this going!", "That's all for now."

        assertTrue(windows.get(2).startsWith("simple The #rEasy Balrog Expedition#k has been created."), windows.get(2));
        assertTrue(windows.get(2).endsWith(OPTION + "\r\n#L2#That's all for now.#l"), windows.get(2));
        assertEquals(List.of("dispose"), windows.subList(3, windows.size()));
        verify(bossRuns, never()).registerFromNpc(any(), anyString());
    }

    @Test
    void withNoLivingWorldEveryWindowIsTheStockOne() throws Exception {
        when(expedition.isLeader(player)).thenReturn(true);
        run(1, 0);
        assertEquals("simple What would you like to do?#b\r\n\r\n#L1#View current Expedition members#l\r\n"
                + "#L2#Start the fight!#l\r\n#L3#Stop the expedition.#l", windows.get(1));

        windows.clear();
        when(expedition.isLeader(player)).thenReturn(false);
        run(1);
        assertEquals(List.of("ok You have registered for the expedition successfully!", "dispose"),
                windows.subList(1, windows.size()));

        windows.clear();
        run(1);
        assertEquals(List.of("ok You have already registered for the expedition. Please wait for #rBossfriend#k to "
                + "begin it.", "dispose"), windows.subList(1, windows.size()));
    }

    @Test
    void normalBalrogNeverAsks() throws Exception {
        livingWorldOffers();

        run(2); // Normal

        verify(bossRuns, never()).offers(any(), anyString());
        assertEquals(List.of("ok You have registered for the expedition successfully!", "dispose"),
                windows.subList(1, windows.size()));
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** The living world runs, and its boss runs offer the owner "My party will register too." */
    private void livingWorldOffers() {
        when(world.bossRuns()).thenReturn(bossRuns);
        when(bossRuns.offers(player, "easy_balrog")).thenReturn(true);
        when(bossRuns.registerFromNpc(player, "easy_balrog")).thenReturn(REPLY);
    }

    private static void within(String script, String block, String from, String to) {
        int at = script.indexOf(block);
        int start = script.indexOf(from);
        int end = script.indexOf(to, start);
        assertTrue(start > 0 && at > start && at < end, "between '" + from + "' and '" + to + "': " + block);
    }

    /**
     * As NPCScriptManager runs her for {@code player} (Lv70, on a channel whose Easy expedition takes sign-ups): start,
     * then one action(1, 0, selection) per window until she disposes the talk. Each window is recorded as
     * "simple text", "ok text" or "dispose". LivingWorld.get() answers the world when a test made it offer.
     */
    private void run(int... selections) throws Exception {
        NPCConversationManager cm = mock(NPCConversationManager.class);
        AtomicBoolean disposed = new AtomicBoolean();
        when(cm.getPlayer()).thenReturn(player);
        when(cm.getExpedition(any(ExpeditionType.class))).thenAnswer(inv -> onTheChannel);
        when(cm.createExpedition(ExpeditionType.BALROG_EASY)).thenAnswer(inv -> {
            onTheChannel = expedition;
            return 0;
        });
        EventManager em = mock(EventManager.class);
        when(em.getProperty("party")).thenReturn("3 or more");
        when(cm.getEventManager(anyString())).thenReturn(em);
        doAnswer(inv -> windows.add("simple " + inv.getArgument(0))).when(cm).sendSimple(anyString());
        doAnswer(inv -> windows.add("ok " + inv.getArgument(0))).when(cm).sendOk(anyString());
        doAnswer(inv -> {
            disposed.set(true);
            return windows.add("dispose");
        }).when(cm).dispose();

        ScriptEngine engine = new ScriptEngineManager().getEngineByName("graal.js");
        Bindings bindings = engine.getBindings(ScriptContext.ENGINE_SCOPE);
        bindings.put("polyglot.js.allowHostAccess", true);
        bindings.put("polyglot.js.allowHostClassLookup", true);
        engine.eval(Files.readString(SCRIPT, StandardCharsets.US_ASCII));
        engine.put("cm", cm);
        Invocable script = (Invocable) engine;
        LivingWorld running = world.bossRuns() == null ? null : world;
        try (MockedStatic<LivingWorld> living = mockStatic(LivingWorld.class)) {
            living.when(LivingWorld::get).thenReturn(running);
            script.invokeFunction("start");
            for (int selection : selections) {
                if (disposed.get()) {
                    break;
                }
                script.invokeFunction("action", 1, 0, selection);
            }
        }
    }
}
