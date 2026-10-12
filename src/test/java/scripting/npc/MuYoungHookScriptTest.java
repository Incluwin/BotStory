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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Where Mu Young (1061014.js) offers an owner "My party will register too." (P7 Part A step 9) and "Call allies from my
 * bench..." (Part B step 9): seven insertions and nothing else. Five functions ask the living world, with the key of
 * the difficulty picked (bossKey: "easy_balrog", or "balrog_normal" since P11)
 * (its class loaded inside them, lazily, never at the top level); the leader's list gets the options as its fourth and
 * fifth choices (status 2); a member already on her list, one who just joined it and the owner who just created it get
 * them as choices 1 and 3 of a window that ends with "That's all for now." (status 30): two choices with one offer,
 * three with both. The bench picker loops at status 40 until "That's all for now." (99) or an answer with no choice.
 * Without the seven blocks her script is the stock one byte for byte (its SHA-256 at c6883f520). Run in Graal as
 * NPCScriptManager runs it: with the ally switch off every window is Part A's byte for byte, an Agent's headless
 * sign-up ([1] or [2]) and every window with no living world are the stock ones, Normal Balrog asks with its own key
 * (P11), and every step of every talk answers with a window or ends it.
 */
class MuYoungHookScriptTest {
    private static final Path SCRIPT = Path.of("scripts/npc/1061014.js");
    private static final String HOOKS = "Java.type('server.living.LivingWorldHooks').";
    private static final String OFFERED = HOOKS + "bossPartyOffered(cm, bossKey())";
    private static final String REGISTER = HOOKS + "bossPartyRegister(cm, bossKey())";
    private static final String ALLIES_OFFERED = HOOKS + "bossAlliesOffered(cm, bossKey())";
    private static final String PICKER = HOOKS + "bossAllyPicker(cm, bossKey())";
    private static final String CALL = HOOKS + "bossAllyCall(cm, bossKey(), selection)";
    /** 1061014.js before the hooks (c6883f520), LF line endings. */
    private static final String STOCK_SHA_256 = "b3af92700a1a6fd18c991249a412d3cbd9a28635fd5d0e917552de8685b41935";

    /** a: after selectExpedition, the five hook functions, the choices and the bench picker's window. */
    private static final String FUNCTIONS = ""
            + "\n"
            + "// companions: Mu Young offers an owner \"My party will register too.\" "
            + "(living_world.COMPANION_BOSS_ENABLED_TYPES)\n"
            + "// and \"Call allies from my bench...\" (living_world.COMPANION_BOSS_ALLY_TYPES), for the difficulty they "
            + "chose\n"
            + "function bossKey() {\n"
            + "    return exped == ExpeditionType.BALROG_EASY ? \"easy_balrog\" : \"balrog_normal\";\n"
            + "}\n"
            + "\n"
            + "function companionOffer() {\n"
            + "    return " + OFFERED + ";\n"
            + "}\n"
            + "\n"
            + "function companionRegister() {\n"
            + "    return " + REGISTER + ";\n"
            + "}\n"
            + "\n"
            + "function allyOffer() {\n"
            + "    return " + ALLIES_OFFERED + ";\n"
            + "}\n"
            + "\n"
            + "function allyPicker() {\n"
            + "    return " + PICKER + ";\n"
            + "}\n"
            + "\n"
            + "function allyCall(selection) {\n"
            + "    return " + CALL + ";\n"
            + "}\n"
            + "\n"
            + "// her choices after a member's line (status 30); null when she offers neither\n"
            + "function companionChoices() {\n"
            + "    var party = companionOffer();\n"
            + "    var allies = allyOffer();\n"
            + "    if (!party && !allies) {\n"
            + "        return null;\n"
            + "    }\n"
            + "    return \"\\r\\n#b\" + (party ? \"#L1#My party will register too.#l\\r\\n\" : \"\") + (allies ? "
            + "\"#L3#Call allies from my bench...#l\\r\\n\" : \"\") + \"#L2#That's all for now.#l\";\n"
            + "}\n"
            + "\n"
            + "// her choices after the leader's list (status 2); null when she offers neither\n"
            + "function leaderChoices() {\n"
            + "    var party = companionOffer();\n"
            + "    var allies = allyOffer();\n"
            + "    if (!party && !allies) {\n"
            + "        return null;\n"
            + "    }\n"
            + "    return (party ? \"\\r\\n#L4#My party will register too.#l\" : \"\") + (allies ? "
            + "\"\\r\\n#L5#Call allies from my bench...#l\" : \"\");\n"
            + "}\n"
            + "\n"
            + "// the bench picker's answer: with choices it loops (status 40), any other answer ends the talk\n"
            + "function allyWindow(text) {\n"
            + "    if (text.indexOf(\"#L\") >= 0) {\n"
            + "        cm.sendSimple(text);\n"
            + "        status = 40;\n"
            + "    } else {\n"
            + "        cm.sendOk(text);\n"
            + "        cm.dispose();\n"
            + "    }\n"
            + "}\n";
    /** b: the leader's list, before the stock one. */
    private static final String LEADER_LIST = ""
            + "            var choices = leaderChoices();\n"
            + "            if (choices != null) {\n"
            + "                cm.sendSimple(list + choices);\n"
            + "                status = 2;\n"
            + "                return;\n"
            + "            }\n";
    /** c: a member already on her list, before the stock "already registered" line. */
    private static final String ALREADY_LISTED = ""
            + "            var choices = companionChoices();\n"
            + "            if (choices != null) {\n"
            + "                cm.sendSimple(\"You have already registered for the expedition. Please wait for #r\""
            + " + expedition.getLeader().getName() + \"#k to begin it.\" + choices);\n"
            + "                status = 30;\n"
            + "                return;\n"
            + "            }\n";
    /** d: a member joining her Easy or Normal list, before the stock join: the join first, then her choices. */
    private static final String JOINING = ""
            + "            if (exped == ExpeditionType.BALROG_EASY || exped == ExpeditionType.BALROG_NORMAL) {\n"
            + "                var joined = expedition.addMember(player);\n"
            + "                var choices = expedition.contains(player) ? companionChoices() : null;\n"
            + "                if (choices != null) {\n"
            + "                    cm.sendSimple(joined + choices);\n"
            + "                    status = 30;\n"
            + "                } else {\n"
            + "                    cm.sendOk(joined);\n"
            + "                    cm.dispose();\n"
            + "                }\n"
            + "                return;\n"
            + "            }\n";
    /** e: the owner who just created the expedition, before the stock "has been created" line. */
    private static final String CREATED = ""
            + "                var choices = companionChoices();\n"
            + "                if (choices != null) {\n"
            + "                    cm.sendSimple(\"The #r\" + expedDifficulty + \" \" + expedBoss + \" Expedition#k has "
            + "been created.\\r\\n\\r\\nTalk to me again to view the current team or start the fight!\" "
            + "+ choices);\n"
            + "                    status = 30;\n"
            + "                    return;\n"
            + "                }\n";
    /** f: the leader's fourth and fifth choices, first in the status 2 branch. */
    private static final String LEADERS_CHOICES = ""
            + "        if (selection == 4) {\n"
            + "            cm.sendOk(companionRegister());\n"
            + "            cm.dispose();\n"
            + "            return;\n"
            + "        }\n"
            + "        if (selection == 5) {\n"
            + "            allyWindow(allyPicker());\n"
            + "            return;\n"
            + "        }\n";
    /** g: the members' window's answer and the bench picker's loop, new branches before status 10. */
    private static final String MEMBERS_CHOICES_AND_PICKER = ""
            + "    } else if (status == 30) {\n"
            + "        if (selection == 3) {\n"
            + "            allyWindow(allyPicker());\n"
            + "            return;\n"
            + "        }\n"
            + "        if (selection == 1) {\n"
            + "            cm.sendOk(companionRegister());\n"
            + "        }\n"
            + "        cm.dispose();\n"
            + "    } else if (status == 40) {\n"
            + "        if (selection == 99) {\n"
            + "            cm.dispose();\n"
            + "            return;\n"
            + "        }\n"
            + "        allyWindow(allyCall(selection));\n";
    private static final List<String> BLOCKS = List.of(FUNCTIONS, LEADER_LIST, ALREADY_LISTED, JOINING, CREATED,
            LEADERS_CHOICES, MEMBERS_CHOICES_AND_PICKER);

    private static final String OPTION = "#L1#My party will register too.#l";
    private static final String ALLY_OPTION = "#L3#Call allies from my bench...#l";
    /** Part A's two-choice window, after her line. */
    private static final String PART_A_CHOICES = "\r\n#b" + OPTION + "\r\n#L2#That's all for now.#l";
    private static final String STOCK_LIST = "What would you like to do?#b\r\n\r\n#L1#View current Expedition members#l"
            + "\r\n#L2#Start the fight!#l\r\n#L3#Stop the expedition.#l";
    private static final String JOINED = "You have registered for the expedition successfully!";
    private static final String ALREADY = "You have already registered for the expedition. Please wait for "
            + "#rBossfriend#k to begin it.";
    private static final String CREATED_LINE = "The #rEasy Balrog Expedition#k has been created.\r\n\r\nTalk to me "
            + "again to view the current team or start the fight!";
    private static final String NORMAL_CREATED_LINE = "The #rNormal Balrog Expedition#k has been created.\r\n\r\nTalk "
            + "to me again to view the current team or start the fight!";
    private static final String REPLY = "Very well. Have them come and speak with me before time runs out.";
    private static final String BENCH = "Who should I send word to? I close the list in about #b4 min#k, so they must "
            + "reach me before then.\r\nThere are #b5#k seats left for you.\r\n\r\n#L100#Orrin (Lv. 70 White Knight) - "
            + "about 1 min away#l\r\n#L101#Magna (Lv. 70 White Knight) - about 1 min away#l\r\n#L98#Everyone who can "
            + "make it.#l\r\n#L99#That's all for now.#l";
    private static final String SENT = "I have sent word to #bOrrin#k.\r\n\r\nWho should I send word to? I close the "
            + "list in about #b4 min#k, so they must reach me before then.\r\nThere are #b4#k seats left for you."
            + "\r\n\r\nOrrin is already on the way.\r\n#L100#Magna (Lv. 70 White Knight) - about 1 min away#l"
            + "\r\n#L99#That's all for now.#l";

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
            return JOINED;
        });
    }

    @AfterEach
    void close() {
        AgentPresence.install(null);
    }

    // ---- the insertions ------------------------------------------------------------------------

    @Test
    void sevenBlocksEachInItsPlace() throws IOException {
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
        assertTrue(script.contains("    } else if (status == 2) {\n" + LEADERS_CHOICES
                + "        if (selection == 1) {\n"), "f: first in the status 2 branch");
        assertTrue(script.contains("        }\n" + MEMBERS_CHOICES_AND_PICKER + "    } else if (status == 10) {\n"),
                "g: branches of their own before status 10");
        within(script, MEMBERS_CHOICES_AND_PICKER, "} else if (status == 6) {", "} else if (status == 10) {");
    }

    @Test
    void herClassIsLoadedOnlyInsideTheFiveFunctions() throws IOException {
        String script = Files.readString(SCRIPT, StandardCharsets.US_ASCII);

        assertEquals(5, script.split("LivingWorldHooks", -1).length - 1, "five calls, no other living-world call");
        for (String hook : List.of("bossPartyOffered", "bossPartyRegister", "bossAlliesOffered", "bossAllyPicker",
                "bossAllyCall")) {
            assertEquals(1, script.split(hook + "\\(", -1).length - 1, hook);
        }
        for (String line : script.split("\n")) {
            assertFalse(!line.startsWith(" ") && line.contains("Java.type('server.living"), "no top-level load: " + line);
        }
        assertFalse(script.contains("\"zakum\""), "Balrog's keys, never another boss's");
        assertEquals(1, script.split("\"easy_balrog\"", -1).length - 1, "each key once, in bossKey()");
        assertEquals(1, script.split("\"balrog_normal\"", -1).length - 1, "each key once, in bossKey()");
        assertEquals(5, script.split("bossKey\\(\\)", -1).length - 1 - 1, "the five hooks pass bossKey()");
    }

    @Test
    void memberWindowsGoToStatus30TheLeadersChoicesAreOnlyInItsListAndThePickerLoopsAt40() throws IOException {
        String script = Files.readString(SCRIPT, StandardCharsets.US_ASCII);

        for (String memberWindow : List.of(ALREADY_LISTED, JOINING, CREATED)) {
            assertTrue(memberWindow.contains("status = 30;") && memberWindow.contains("companionChoices()"),
                    memberWindow);
        }
        assertEquals(1, script.split("#L4#", -1).length - 1, "one fourth choice");
        assertEquals(1, script.split("#L5#", -1).length - 1, "one fifth choice");
        assertTrue(LEADER_LIST.contains("leaderChoices()") && LEADER_LIST.contains("status = 2;"));
        for (String pick : List.of("selection == 4", "selection == 5")) {
            assertEquals(1, script.split(pick, -1).length - 1, pick);
            assertTrue(script.indexOf(pick) > script.indexOf("} else if (status == 2) {")
                    && script.indexOf(pick) < script.indexOf("} else if (status == 4) {"),
                    "answered in the status 2 branch: " + pick);
        }
        assertEquals(1, script.split("status == 30", -1).length - 1);
        assertEquals(1, script.split("status == 40", -1).length - 1);
        assertEquals(1, script.split("status = 40;", -1).length - 1, "only the bench picker's window loops");
        assertEquals(1, script.split("selection == 99", -1).length - 1);
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

    // ---- run in Graal: Part A ------------------------------------------------------------------

    @Test
    void anOwnerJoiningSeesTheOptionAndPickingItAnswersInHerWords() throws Exception {
        livingWorldOffers();

        run(1, 1); // Easy, then "My party will register too."

        assertEquals(4, windows.size(), windows.toString());
        assertEquals("simple " + JOINED + PART_A_CHOICES, windows.get(1));
        assertEquals(List.of("ok " + REPLY, "dispose"), windows.subList(2, 4));
        verify(bossRuns).registerFromNpc(player, "easy_balrog");
    }

    @Test
    void anAgentsHeadlessSignUpIsTheStockJoin() throws Exception {
        livingWorldOffers();
        benchOffered();
        AgentPresence.install(chr -> chr == player); // a companion signing itself up ([1])

        run(1);

        assertEquals(List.of("ok " + JOINED, "dispose"), windows.subList(1, windows.size()));
        assertTrue(listed.get());
        verify(bossRuns, never()).offers(any(), anyString());
        verify(bossRuns, never()).alliesOffered(any(), anyString());
    }

    @Test
    void theLeadersListOffersItAsTheFourthChoice() throws Exception {
        livingWorldOffers();
        when(expedition.isLeader(player)).thenReturn(true);

        run(1, 4);

        assertEquals("simple " + STOCK_LIST + "\r\n#L4#My party will register too.#l", windows.get(1));
        assertEquals(List.of("ok " + REPLY, "dispose"), windows.subList(2, windows.size()));
    }

    @Test
    void theOwnerWhoCreatesItSeesTheOptionAndThatsAllForNowEndsTheTalk() throws Exception {
        livingWorldOffers();
        onTheChannel = null;

        run(1, 1, 2); // Easy, "Lets get this going!", "That's all for now."

        assertEquals("simple " + CREATED_LINE + PART_A_CHOICES, windows.get(2));
        assertEquals(List.of("dispose"), windows.subList(3, windows.size()));
        verify(bossRuns, never()).registerFromNpc(any(), anyString());
    }

    @Test
    void withTheAllySwitchOffEveryPartAWindowIsAsBeforeByteForByte() throws Exception {
        livingWorldOffers(); // the bench is not offered: alliesOffered answers false

        run(1); // joining
        assertEquals(List.of("simple " + JOINED + PART_A_CHOICES), windows.subList(1, windows.size()));

        windows.clear();
        run(1); // already listed
        assertEquals(List.of("simple " + ALREADY + PART_A_CHOICES), windows.subList(1, windows.size()));

        windows.clear();
        onTheChannel = null;
        run(1, 1); // creating it
        assertEquals(List.of("simple " + CREATED_LINE + PART_A_CHOICES), windows.subList(2, windows.size()));

        windows.clear();
        when(expedition.isLeader(player)).thenReturn(true);
        run(1); // the leader's list
        assertEquals(List.of("simple " + STOCK_LIST + "\r\n#L4#My party will register too.#l"),
                windows.subList(1, windows.size()));
        verify(bossRuns, never()).allyPicker(any(), anyString());
    }

    @Test
    void withNoLivingWorldEveryWindowIsTheStockOne() throws Exception {
        when(expedition.isLeader(player)).thenReturn(true);
        run(1);
        assertEquals(List.of("simple " + STOCK_LIST), windows.subList(1, windows.size()));

        windows.clear();
        when(expedition.isLeader(player)).thenReturn(false);
        run(1);
        assertEquals(List.of("ok " + JOINED, "dispose"), windows.subList(1, windows.size()));

        windows.clear();
        run(1);
        assertEquals(List.of("ok " + ALREADY, "dispose"), windows.subList(1, windows.size()));

        windows.clear();
        onTheChannel = null;
        run(1, 1);
        assertEquals(List.of("ok " + CREATED_LINE, "dispose"), windows.subList(2, windows.size()));
    }

    @Test
    void normalBalrogAsksWithItsOwnKeyAndEasysOffersStayEasys() throws Exception {
        livingWorldOffers(); // Easy's offers only
        benchOffered();

        run(2); // Normal

        verify(bossRuns).offers(player, "balrog_normal");
        verify(bossRuns).alliesOffered(player, "balrog_normal");
        assertEquals(List.of("ok " + JOINED, "dispose"), windows.subList(1, windows.size()), "Normal is off: stock");
    }

    @Test
    void normalBalrogsOwnerJoiningSeesTheOptionAndPickingItRegistersForNormal() throws Exception {
        when(world.bossRuns()).thenReturn(bossRuns);
        when(bossRuns.offers(player, "balrog_normal")).thenReturn(true);
        when(bossRuns.registerFromNpc(player, "balrog_normal")).thenReturn(REPLY);

        run(2, 1); // Normal, then "My party will register too."

        assertEquals("simple " + JOINED + PART_A_CHOICES, windows.get(1));
        assertEquals(List.of("ok " + REPLY, "dispose"), windows.subList(2, 4));
        verify(bossRuns).registerFromNpc(player, "balrog_normal");
        verify(bossRuns, never()).registerFromNpc(player, "easy_balrog");

        windows.clear();
        onTheChannel = null;
        run(2, 1, 2); // Normal, "Lets get this going!", "That's all for now."
        assertEquals("simple " + NORMAL_CREATED_LINE + PART_A_CHOICES, windows.get(2));
    }

    @Test
    void anAgentsHeadlessNormalSignUpIsTheStockJoin() throws Exception {
        when(world.bossRuns()).thenReturn(bossRuns);
        when(bossRuns.offers(player, "balrog_normal")).thenReturn(true);
        AgentPresence.install(chr -> chr == player); // a companion signing itself up ([2])

        run(2);

        assertEquals(List.of("ok " + JOINED, "dispose"), windows.subList(1, windows.size()));
        assertTrue(listed.get());
        verify(bossRuns, never()).offers(any(), anyString());
    }

    // ---- run in Graal: the bench (Part B) ------------------------------------------------------

    @Test
    void bothOffersGiveAThreeChoiceWindow() throws Exception {
        livingWorldOffers();
        benchOffered();
        String three = "\r\n#b" + OPTION + "\r\n" + ALLY_OPTION + "\r\n#L2#That's all for now.#l";

        run(1); // joining
        assertEquals("simple " + JOINED + three, windows.get(1));

        windows.clear();
        run(1); // already listed
        assertEquals("simple " + ALREADY + three, windows.get(1));

        windows.clear();
        onTheChannel = null;
        run(1, 1); // creating it
        assertEquals("simple " + CREATED_LINE + three, windows.get(2));
    }

    @Test
    void theBenchAloneGivesATwoChoiceWindow() throws Exception {
        benchOffered(); // her party option is not offered (no party companion here)
        String two = "\r\n#b" + ALLY_OPTION + "\r\n#L2#That's all for now.#l";

        run(1);
        assertEquals("simple " + JOINED + two, windows.get(1));

        windows.clear();
        run(1, 2); // already listed, then "That's all for now."
        assertEquals(List.of("simple " + ALREADY + two, "dispose"), windows.subList(1, windows.size()));
        verify(bossRuns, never()).registerFromNpc(any(), anyString());
        verify(bossRuns, never()).allyPicker(any(), anyString());
    }

    @Test
    void theLeadersListGetsTheFifthChoiceAndItOpensThePicker() throws Exception {
        livingWorldOffers();
        benchOffered();
        when(expedition.isLeader(player)).thenReturn(true);

        run(1, 5);

        assertEquals("simple " + STOCK_LIST + "\r\n#L4#My party will register too.#l"
                + "\r\n#L5#Call allies from my bench...#l", windows.get(1));
        assertEquals("simple " + BENCH, windows.get(2));

        windows.clear();
        reset(bossRuns);
        benchOffered();
        run(1);
        assertEquals("simple " + STOCK_LIST + "\r\n#L5#Call allies from my bench...#l", windows.get(1),
                "the bench alone");
    }

    @Test
    void thePickerLoopsUntilThatsAllForNow() throws Exception {
        livingWorldOffers();
        benchOffered();

        run(1, 3, 100, 99); // join, "Call allies from my bench...", Orrin, "That's all for now."

        assertEquals(List.of("simple " + BENCH, "simple " + SENT, "dispose"), windows.subList(2, windows.size()));
        verify(bossRuns).allyCall(player, "easy_balrog", 100);
        verify(bossRuns, never()).allyCall(player, "easy_balrog", 99);

        windows.clear();
        run(1, 3, 98, 100, 99); // already listed: everyone, then one more pick, then done
        assertEquals(List.of("simple " + BENCH, "simple " + SENT, "simple " + SENT, "dispose"),
                windows.subList(2, windows.size()));
        verify(bossRuns).allyCall(player, "easy_balrog", 98);
    }

    @Test
    void anAnswerWithNoChoiceEndsTheTalk() throws Exception {
        livingWorldOffers();
        benchOffered();
        when(bossRuns.allyPicker(player, "easy_balrog")).thenReturn(null); // the hook answers her no-candidate line

        run(1, 3);
        assertEquals(List.of("ok " + CompanionBossRuns.NO_ALLY_CAN_COME, "dispose"),
                windows.subList(2, windows.size()));

        windows.clear();
        benchOffered();
        when(bossRuns.allyCall(player, "easy_balrog", 101)).thenReturn("I have sent word to #bMagna#k.");
        run(1, 3, 101);
        assertEquals(List.of("simple " + BENCH, "ok I have sent word to #bMagna#k.", "dispose"),
                windows.subList(2, windows.size()));
    }

    @Test
    void everyStepOfEveryTalkAnswersWithAWindowOrEndsIt() throws Exception {
        // run() checks each step; here each role meets each mix of offers and walks each of her new choices
        List<int[]> memberTalks = List.of(new int[]{1}, new int[]{2}, new int[]{3, 100, 98, 99}, new int[]{3, 7},
                new int[]{3, 99});
        List<int[]> leaderTalks = List.of(new int[]{4}, new int[]{5, 100, 98, 99}, new int[]{5, 7}, new int[]{2});
        for (boolean party : List.of(false, true)) {
            for (boolean bench : List.of(false, true)) {
                reset(bossRuns);
                if (party) {
                    livingWorldOffers();
                }
                if (bench) {
                    benchOffered();
                }
                for (int[] talk : memberTalks) {
                    when(expedition.isLeader(player)).thenReturn(false);
                    listed.set(false);
                    onTheChannel = expedition;
                    run(then(1, talk)); // joining her list
                    run(then(1, talk)); // already on it
                    onTheChannel = null;
                    run(then(1, then(1, talk))); // creating it
                }
                when(expedition.isLeader(player)).thenReturn(true);
                for (int[] talk : leaderTalks) {
                    run(then(1, talk)); // her leader's list
                }
            }
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** The living world runs, and its boss runs offer the owner "My party will register too." */
    private void livingWorldOffers() {
        when(world.bossRuns()).thenReturn(bossRuns);
        when(bossRuns.offers(player, "easy_balrog")).thenReturn(true);
        when(bossRuns.registerFromNpc(player, "easy_balrog")).thenReturn(REPLY);
    }

    /**
     * The living world runs, and its boss runs offer the owner "Call allies from my bench...": her picker offers Orrin
     * and Magna, and a pick of either sends word and shows it again.
     */
    private void benchOffered() {
        when(world.bossRuns()).thenReturn(bossRuns);
        when(bossRuns.alliesOffered(player, "easy_balrog")).thenReturn(true);
        when(bossRuns.allyPicker(player, "easy_balrog")).thenReturn(BENCH);
        when(bossRuns.allyCall(any(), anyString(), anyInt())).thenReturn(SENT);
    }

    /** {@code first}, then {@code rest}. */
    private static int[] then(int first, int[] rest) {
        int[] talk = new int[rest.length + 1];
        talk[0] = first;
        System.arraycopy(rest, 0, talk, 1, rest.length);
        return talk;
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
     * "simple text", "ok text" or "dispose", and every step must answer with one (or end the talk). LivingWorld.get()
     * answers the world when a test made it offer.
     */
    private void run(int... selections) throws Exception {
        NPCConversationManager cm = mock(NPCConversationManager.class);
        AtomicBoolean disposed = new AtomicBoolean();
        when(cm.getPlayer()).thenReturn(player);
        when(cm.getExpedition(any(ExpeditionType.class))).thenAnswer(inv -> onTheChannel);
        when(cm.createExpedition(any(ExpeditionType.class))).thenAnswer(inv -> {
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
            int before = windows.size();
            script.invokeFunction("start");
            assertTrue(windows.size() > before, "start answers with a window");
            for (int selection : selections) {
                if (disposed.get()) {
                    break;
                }
                before = windows.size();
                script.invokeFunction("action", 1, 0, selection);
                assertTrue(windows.size() > before, "choice " + selection + " answers with a window or ends the "
                        + "talk: " + windows);
            }
        }
    }
}
