package scripting.npc;

import client.Character;
import client.Client;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import scripting.event.EventManager;
import server.expeditions.Expedition;
import server.expeditions.ExpeditionType;
import server.integration.AgentPresence;
import server.living.LivingWorld;
import server.living.roster.CompanionBossRuns;
import server.maps.MapleMap;

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
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Where Adobis (2030013.js) offers an owner "My party will register too." and "Call allies from my bench..." for Zakum
 * (P8 step 3): Mu Young's seven insertions (MuYoungHookScriptTest) with the key "zakum", and nothing else. Five
 * functions ask the living world (its class loaded inside them, lazily, never at the top level); the leader's list gets
 * the options as its fourth and fifth choices (status 2); a member already on his list, one who just joined it and the
 * owner who just created it get them as choices 1 and 3 of a window that ends with "That's all for now." (status 30).
 * The bench picker loops at status 40 until "That's all for now." (99) or an answer with no choice. His join block is
 * guarded by the expedition type as Mu Young's is (Zakum, so it always runs): it joins first and asks for the choices
 * only for one it listed, because the bench is offered only to one on his list. Without the seven blocks his script is
 * the stock one byte for byte (its SHA-256 at c6883f520, unchanged since). Run in Graal as NPCScriptManager runs it:
 * with the switch off (neither offer made), with no living world and for an Agent's headless sign-up (no choices) every
 * window is the stock script's; the in-progress and start branches stay stock with both offers on; and every step of
 * every talk answers with a window or ends it.
 */
class AdobisHookScriptTest {
    private static final Path SCRIPT = Path.of("scripts/npc/2030013.js");
    private static final String KEY = "zakum";
    private static final int EYE_OF_FIRE = 4001017;
    private static final String HOOKS = "Java.type('server.living.LivingWorldHooks').";
    /** 2030013.js before the hooks (c6883f520), LF line endings. */
    private static final String STOCK_SHA_256 = "5dcb5d7f25059607a92dfabdc817a782d87c600351297101946a3a2a9510a1a5";

    /** a: after start(), the five hook functions, the choices and the bench picker's window. */
    private static final String FUNCTIONS = ""
            + "\n"
            + "// companions: Adobis offers an owner \"My party will register too.\" "
            + "(living_world.COMPANION_BOSS_ENABLED_TYPES)\n"
            + "// and \"Call allies from my bench...\" (living_world.COMPANION_BOSS_ALLY_TYPES)\n"
            + "function companionOffer() {\n"
            + "    return " + HOOKS + "bossPartyOffered(cm, \"zakum\");\n"
            + "}\n"
            + "\n"
            + "function companionRegister() {\n"
            + "    return " + HOOKS + "bossPartyRegister(cm, \"zakum\");\n"
            + "}\n"
            + "\n"
            + "function allyOffer() {\n"
            + "    return " + HOOKS + "bossAlliesOffered(cm, \"zakum\");\n"
            + "}\n"
            + "\n"
            + "function allyPicker() {\n"
            + "    return " + HOOKS + "bossAllyPicker(cm, \"zakum\");\n"
            + "}\n"
            + "\n"
            + "function allyCall(selection) {\n"
            + "    return " + HOOKS + "bossAllyCall(cm, \"zakum\", selection);\n"
            + "}\n"
            + "\n"
            + "// his choices after a member's line (status 30); null when he offers neither\n"
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
            + "// his choices after the leader's list (status 2); null when he offers neither\n"
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
            + "                    var choices = leaderChoices();\n"
            + "                    if (choices != null) {\n"
            + "                        cm.sendSimple(list + choices);\n"
            + "                        status = 2;\n"
            + "                        return;\n"
            + "                    }\n";
    /** c: a member already on his list, before the stock "already registered" line. */
    private static final String ALREADY_LISTED = ""
            + "                    var choices = companionChoices();\n"
            + "                    if (choices != null) {\n"
            + "                        cm.sendSimple(\"You have already registered for the expedition. Please wait for "
            + "#r\" + expedition.getLeader().getName() + \"#k to begin it.\" + choices);\n"
            + "                        status = 30;\n"
            + "                        return;\n"
            + "                    }\n";
    /**
     * d: a member joining his list, before the stock join: the join first, then choices. Guarded by the expedition type
     * as Mu Young's Easy join is (always Zakum here), never by an offer: the bench is offered only to one on his list.
     */
    private static final String JOINING = ""
            + "                    if (exped == ExpeditionType.ZAKUM) {\n"
            + "                        var joined = expedition.addMember(player);\n"
            + "                        var choices = expedition.contains(player) ? companionChoices() : null;\n"
            + "                        if (choices != null) {\n"
            + "                            cm.sendSimple(joined + choices);\n"
            + "                            status = 30;\n"
            + "                        } else {\n"
            + "                            cm.sendOk(joined);\n"
            + "                            cm.dispose();\n"
            + "                        }\n"
            + "                        return;\n"
            + "                    }\n";
    /** e: the owner who just created the expedition, before the stock "has been created" line. */
    private static final String CREATED = ""
            + "                    var choices = companionChoices();\n"
            + "                    if (choices != null) {\n"
            + "                        cm.sendSimple(\"The #r\" + expedBoss + \" Expedition#k has been created.\\r\\n"
            + "\\r\\nTalk to me again to view the current team, or start the fight!\" + choices);\n"
            + "                        status = 30;\n"
            + "                        return;\n"
            + "                    }\n";
    /** f: the leader's fourth and fifth choices, first in the status 2 branch. */
    private static final String LEADERS_CHOICES = ""
            + "            if (selection == 4) {\n"
            + "                cm.sendOk(companionRegister());\n"
            + "                cm.dispose();\n"
            + "                return;\n"
            + "            }\n"
            + "            if (selection == 5) {\n"
            + "                allyWindow(allyPicker());\n"
            + "                return;\n"
            + "            }\n";
    /** g: the members' window's answer and the bench picker's loop, new branches after status 6. */
    private static final String MEMBERS_CHOICES_AND_PICKER = ""
            + "        } else if (status == 30) {\n"
            + "            if (selection == 3) {\n"
            + "                allyWindow(allyPicker());\n"
            + "                return;\n"
            + "            }\n"
            + "            if (selection == 1) {\n"
            + "                cm.sendOk(companionRegister());\n"
            + "            }\n"
            + "            cm.dispose();\n"
            + "        } else if (status == 40) {\n"
            + "            if (selection == 99) {\n"
            + "                cm.dispose();\n"
            + "                return;\n"
            + "            }\n"
            + "            allyWindow(allyCall(selection));\n";
    private static final List<String> BLOCKS = List.of(FUNCTIONS, LEADER_LIST, ALREADY_LISTED, JOINING, CREATED,
            LEADERS_CHOICES, MEMBERS_CHOICES_AND_PICKER);

    private static final String OPTION = "#L1#My party will register too.#l";
    private static final String ALLY_OPTION = "#L3#Call allies from my bench...#l";
    private static final String BOTH_CHOICES = "\r\n#b" + OPTION + "\r\n" + ALLY_OPTION
            + "\r\n#L2#That's all for now.#l";
    private static final String BENCH_CHOICES = "\r\n#b" + ALLY_OPTION + "\r\n#L2#That's all for now.#l";
    private static final String STOCK_LIST = "What would you like to do?#b\r\n\r\n#L1#View current Expedition members#l"
            + "\r\n#L2#Start the fight!#l\r\n#L3#Stop the expedition.#l";
    private static final String FOURTH = "\r\n#L4#My party will register too.#l";
    private static final String FIFTH = "\r\n#L5#Call allies from my bench...#l";
    private static final String JOINED = "You have registered for the expedition successfully!";
    private static final String ALREADY = "You have already registered for the expedition. Please wait for "
            + "#rBossfriend#k to begin it.";
    private static final String CREATED_LINE = "The #rZakum Expedition#k has been created.\r\n\r\nTalk to me again to "
            + "view the current team, or start the fight!";
    private static final String REPLY = CompanionBossRuns.SIGN_UP_REPLY;
    private static final String BENCH = "Who should I send word to? I close the list in about #b4 min#k, so they must "
            + "reach me before then.\r\nThere are #b5#k seats left for you.\r\n\r\n#L100#Orrin (Lv. 160 Paladin) - "
            + "about 3 min away#l\r\n#L101#Magna (Lv. 160 Paladin) - about 3 min away#l\r\n#L98#Everyone who can "
            + "make it.#l\r\n#L99#That's all for now.#l";
    private static final String SENT = "I have sent word to #bOrrin#k.\r\n\r\nWho should I send word to? I close the "
            + "list in about #b4 min#k, so they must reach me before then.\r\nThere are #b4#k seats left for you."
            + "\r\n\r\nOrrin is already on the way.\r\n#L100#Magna (Lv. 160 Paladin) - about 3 min away#l"
            + "\r\n#L99#That's all for now.#l";

    private final Character player = mock(Character.class);
    private final Client client = mock(Client.class);
    private final MapleMap map = mock(MapleMap.class);
    private final Expedition expedition = mock(Expedition.class);
    private final EventManager em = mock(EventManager.class);
    private final EventInstanceManager eim = mock(EventInstanceManager.class);
    private final LivingWorld world = mock(LivingWorld.class);
    private final CompanionBossRuns bossRuns = mock(CompanionBossRuns.class);
    private final List<String> windows = new ArrayList<>();
    private final AtomicBoolean listed = new AtomicBoolean();
    private final AtomicBoolean leading = new AtomicBoolean();
    private final AtomicBoolean inProgress = new AtomicBoolean();
    private final List<Map.Entry<Integer, String>> members = new ArrayList<>();
    private Expedition onTheChannel;
    private int level;
    private boolean hasEye;
    private int createResult;
    private int canJoin;

    /** One talk with him: its state (from {@link #fresh}) and the choices picked after his first window. */
    private record Talk(String name, Runnable state, int... selections) {
    }

    @BeforeAll
    static void muteGraal() {
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false");
    }

    /** Bossfriend at Adobis on channel 1; leading, joining and the fight's progress follow the fields. */
    @BeforeEach
    void bossfriendAtAdobis() {
        when(player.getLevel()).thenAnswer(inv -> level);
        when(player.getName()).thenReturn("Bossfriend");
        when(player.getClient()).thenReturn(client);
        when(player.getMap()).thenReturn(map);
        when(client.getChannel()).thenReturn(1);
        when(expedition.isLeader(player)).thenAnswer(inv -> leading.get());
        when(expedition.isRegistering()).thenAnswer(inv -> !inProgress.get());
        when(expedition.isInProgress()).thenAnswer(inv -> inProgress.get());
        when(expedition.getLeader()).thenReturn(player);
        when(expedition.contains(player)).thenAnswer(inv -> listed.get() || leading.get());
        when(expedition.addMember(player)).thenAnswer(inv -> {
            listed.set(true);
            return JOINED;
        });
        when(expedition.getMemberList()).thenAnswer(inv -> new ArrayList<>(members));
        when(em.getProperty("party")).thenReturn("6 or more");
        when(em.getInstance("Zakum1")).thenReturn(eim);
        when(em.startInstance(expedition)).thenReturn(true);
        when(eim.getIntProperty("canJoin")).thenAnswer(inv -> canJoin);
        fresh();
    }

    @AfterEach
    void close() {
        AgentPresence.install(null);
    }

    // ---- the insertions ------------------------------------------------------------------------

    @Test
    void sevenBlocksEachInItsPlace() throws IOException {
        String script = script();

        for (String block : BLOCKS) {
            int at = script.indexOf(block);
            assertTrue(at > 0, block);
            assertEquals(at, script.lastIndexOf(block), "once: " + block);
        }
        assertTrue(script.contains("function start() {\n    action(1, 0, 0);\n}\n" + FUNCTIONS
                + "\nfunction action(mode, type, selection) {\n"), "a: between start() and action()");
        within(script, LEADER_LIST, "} else if (expedition.isLeader(player)) {",
                "} else if (expedition.isRegistering()) {");
        assertTrue(script.contains("                } else {\n" + LEADER_LIST
                + "                    cm.sendSimple(list);\n                    status = 2;\n"),
                "b: before the stock leader's list");
        within(script, ALREADY_LISTED, "} else if (expedition.isRegistering()) {",
                "} else if (expedition.isInProgress()) {");
        assertTrue(script.contains("                if (expedition.contains(player)) { //If you're in it but it "
                + "hasn't started, be patient\n" + ALREADY_LISTED
                + "                    cm.sendOk(\"You have already registered"), "c: before the stock line");
        assertTrue(script.contains("                } else { //If you aren't in it, you're going to get added\n"
                + JOINING + "                    cm.sendOk(expedition.addMember(cm.getPlayer()));\n"),
                "d: before the stock join");
        assertTrue(script.contains("                if (res == 0) {\n" + CREATED
                + "                    cm.sendOk(\"The #r\""), "e: before the stock created line");
        assertTrue(script.contains("        } else if (status == 2) {\n" + LEADERS_CHOICES
                + "            if (selection == 1) {\n"), "f: first in the status 2 branch");
        assertTrue(script.endsWith("                status = 2;\n            }\n" + MEMBERS_CHOICES_AND_PICKER
                + "        }\n    }\n}\n"), "g: branches of their own after status 6, the last ones");
    }

    @Test
    void theLivingWorldIsLoadedOnlyInsideTheFiveFunctionsWithZakumsKey() throws IOException {
        String script = script();

        assertEquals(5, script.split("LivingWorldHooks", -1).length - 1, "five calls, no other living-world call");
        for (String hook : List.of("bossPartyOffered", "bossPartyRegister", "bossAlliesOffered", "bossAllyPicker",
                "bossAllyCall")) {
            assertEquals(1, script.split(hook + "\\(", -1).length - 1, hook);
        }
        assertEquals(5, script.split("\\(cm, \"zakum\"", -1).length - 1, "each call asks for Zakum");
        for (String line : script.split("\n")) {
            assertFalse(!line.startsWith(" ") && line.contains("Java.type('server.living"),
                    "no top-level load: " + line);
        }
        assertFalse(script.contains("balrog"), "Zakum's key, never another boss's");
        assertEquals(1, script.split("#L4#", -1).length - 1, "one fourth choice");
        assertEquals(1, script.split("#L5#", -1).length - 1, "one fifth choice");
        assertEquals(1, script.split("status == 30", -1).length - 1);
        assertEquals(1, script.split("status == 40", -1).length - 1);
        assertEquals(1, script.split("status = 40;", -1).length - 1, "only the bench picker's window loops");
        assertEquals(1, script.split("selection == 99", -1).length - 1);
    }

    @Test
    void withoutTheBlocksHisScriptIsTheStockOneByteForByte() throws IOException, NoSuchAlgorithmException {
        byte[] bytes = Files.readAllBytes(SCRIPT);
        for (byte b : bytes) {
            assertTrue(b >= 0 && b != '\r', "2030013.js stays US-ASCII and LF");
        }

        assertEquals(STOCK_SHA_256, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(stockScript().getBytes(StandardCharsets.US_ASCII))), "nothing else in his script changed");
    }

    // ---- run in Graal: stock windows -----------------------------------------------------------

    @Test
    void withTheSwitchOffEveryWindowIsTheStockOneByteForByte() throws Exception {
        when(world.bossRuns()).thenReturn(bossRuns); // the living world runs; its switches do not list zakum

        for (Talk talk : stockTalks()) {
            sameAsStock(talk);
        }

        verify(bossRuns, atLeastOnce()).offers(player, KEY);
        verify(bossRuns, atLeastOnce()).alliesOffered(player, KEY);
        verify(bossRuns, never()).offers(any(), argThat(key -> !KEY.equals(key)));
        verify(bossRuns, never()).alliesOffered(any(), argThat(key -> !KEY.equals(key)));
        verify(bossRuns, never()).registerFromNpc(any(), anyString());
        verify(bossRuns, never()).allyPicker(any(), anyString());
    }

    @Test
    void withNoLivingWorldEveryWindowIsTheStockOne() throws Exception {
        for (Talk talk : stockTalks()) {
            sameAsStock(talk);
        }
    }

    @Test
    void anAgentsHeadlessSignUpWithNoChoicesIsTheStockJoin() throws Exception {
        livingWorldOffers();
        benchOffered();
        AgentPresence.install(chr -> chr == player); // a companion signing itself up (runNpc with [])

        assertEquals(List.of("ok " + JOINED, "dispose"), talk(script()));
        assertTrue(listed.get());
        verify(bossRuns, never()).offers(any(), anyString());
        verify(bossRuns, never()).alliesOffered(any(), anyString());

        fresh();
        assertEquals(List.of("ok " + JOINED, "dispose"), talk(stockScript()), "the stock script's join");
    }

    @Test
    void theInProgressAndStartBranchesAreUntouched() throws Exception {
        livingWorldOffers();
        benchOffered();

        for (Talk talk : inProgressTalks()) {
            sameAsStock(talk);
        }
        verify(bossRuns, never()).offers(any(), anyString());
        verify(bossRuns, never()).alliesOffered(any(), anyString());
        verify(eim, times(2)).registerPlayer(player); // the gate open, once per script

        fresh();
        leading.set(true);
        sixMembers();
        List<String> stock = talk(stockScript(), 2, 0); // "Start the fight!", then the escort
        fresh();
        leading.set(true);
        sixMembers();
        List<String> his = talk(script(), 2, 0);

        assertEquals("simple " + STOCK_LIST, stock.get(0));
        assertEquals("simple " + STOCK_LIST + FOURTH + FIFTH, his.get(0));
        assertEquals(stock.subList(1, stock.size()), his.subList(1, his.size()), "the start is the stock one");
        verify(em, times(2)).startInstance(expedition);
        verify(bossRuns, never()).registerFromNpc(any(), anyString());
        verify(bossRuns, never()).allyPicker(any(), anyString());
    }

    // ---- run in Graal: the owner's choices -----------------------------------------------------

    @Test
    void theOwnerWhoCreatesItSeesTheOptionsAndPickingTheFirstRegisters() throws Exception {
        livingWorldOffers();
        benchOffered();
        onTheChannel = null;

        List<String> seen = talk(script(), 1, 1); // "Lets get this going!", then "My party will register too."

        assertEquals(List.of("simple " + CREATED_LINE + BOTH_CHOICES, "ok " + REPLY, "dispose"),
                seen.subList(1, seen.size()));
        verify(bossRuns).registerFromNpc(player, KEY);
    }

    @Test
    void theLeadersListOffersThemAsTheFourthAndFifthChoicesAndTheFourthRegisters() throws Exception {
        livingWorldOffers();
        benchOffered();
        leading.set(true);

        assertEquals(List.of("simple " + STOCK_LIST + FOURTH + FIFTH, "ok " + REPLY, "dispose"), talk(script(), 4));
        verify(bossRuns).registerFromNpc(player, KEY);

        reset(bossRuns);
        livingWorldOffers();
        assertEquals(List.of("simple " + STOCK_LIST + FOURTH), talk(script()), "the party alone");

        reset(bossRuns);
        benchOffered();
        assertEquals(List.of("simple " + STOCK_LIST + FIFTH), talk(script()), "the bench alone");
    }

    @Test
    void anOwnerJoiningOrAlreadyListedSeesTheOptions() throws Exception {
        livingWorldOffers();
        benchOffered();

        assertEquals(List.of("simple " + JOINED + BOTH_CHOICES, "ok " + REPLY, "dispose"), talk(script(), 1));
        assertEquals(List.of("simple " + ALREADY + BOTH_CHOICES, "dispose"), talk(script(), 2), "already listed, then "
                + "\"That's all for now.\"");
        verify(expedition).addMember(player);
        verify(bossRuns).registerFromNpc(player, KEY);

        doReturn("You have been banned from the expedition.").when(expedition).addMember(player);
        fresh();
        assertEquals(List.of("ok You have been banned from the expedition.", "dispose"), talk(script()),
                "a join that does not list them gets the plain answer");
    }

    @Test
    void anOwnerWhoseOnlyOfferIsTheBenchSeesItOnJoiningAndCreating() throws Exception {
        benchOffered(); // zakum on COMPANION_BOSS_ALLY_TYPES and no party companion can sign up

        assertEquals(List.of("simple " + JOINED + BENCH_CHOICES, "simple " + BENCH, "dispose"),
                talk(script(), 3, 99), "joining his list, then the bench picker and \"That's all for now.\"");
        verify(expedition).addMember(player);
        verify(bossRuns).allyPicker(player, KEY);

        fresh();
        onTheChannel = null;
        List<String> seen = talk(script(), 1, 2); // "Lets get this going!", then "That's all for now."
        assertEquals(List.of("simple " + CREATED_LINE + BENCH_CHOICES, "dispose"), seen.subList(1, seen.size()),
                "creating it");
        verify(bossRuns, never()).registerFromNpc(any(), anyString());
    }

    @Test
    void theFifthChoiceOpensTheBenchPickerWhichLoopsAtStatus40() throws Exception {
        livingWorldOffers();
        benchOffered();
        leading.set(true);

        List<String> seen = talk(script(), 5, 100, 98, 99); // Orrin, everyone, "That's all for now."

        assertEquals(List.of("simple " + BENCH, "simple " + SENT, "simple " + SENT, "dispose"),
                seen.subList(1, seen.size()));
        verify(bossRuns).allyPicker(player, KEY);
        verify(bossRuns).allyCall(player, KEY, 100);
        verify(bossRuns).allyCall(player, KEY, 98);
        verify(bossRuns, never()).allyCall(player, KEY, 99);

        fresh();
        listed.set(true);
        seen = talk(script(), 3, 99); // a member's window: "Call allies from my bench..."
        assertEquals(List.of("simple " + BENCH, "dispose"), seen.subList(1, seen.size()));
    }

    @Test
    void anAnswerWithNoChoiceEndsTheTalk() throws Exception {
        livingWorldOffers();
        benchOffered();
        when(bossRuns.allyPicker(player, KEY)).thenReturn(null); // the hook answers his no-candidate line

        List<String> seen = talk(script(), 3);
        assertEquals(List.of("ok " + CompanionBossRuns.NO_ALLY_CAN_COME, "dispose"), seen.subList(1, seen.size()));

        benchOffered();
        when(bossRuns.allyCall(player, KEY, 101)).thenReturn("I have sent word to #bMagna#k.");
        seen = talk(script(), 3, 101);
        assertEquals(List.of("simple " + BENCH, "ok I have sent word to #bMagna#k.", "dispose"),
                seen.subList(1, seen.size()));
    }

    @Test
    void everyStepOfEveryTalkAnswersWithAWindowOrEndsIt() throws Exception {
        // talk() checks each step; here each role meets each mix of offers and walks each of his new choices
        String script = script();
        List<int[]> memberTalks = List.of(new int[]{1}, new int[]{2}, new int[]{3, 100, 98, 99}, new int[]{3, 7},
                new int[]{3, 99});
        List<int[]> leaderTalks = List.of(new int[]{4}, new int[]{5, 100, 98, 99}, new int[]{5, 7}, new int[]{2},
                new int[]{1});
        for (boolean party : List.of(false, true)) {
            for (boolean bench : List.of(false, true)) {
                reset(bossRuns);
                when(world.bossRuns()).thenReturn(bossRuns);
                if (party) {
                    livingWorldOffers();
                }
                if (bench) {
                    benchOffered();
                }
                for (int[] choices : memberTalks) {
                    fresh();
                    talk(script, choices); // joining his list
                    talk(script, choices); // already on it
                    onTheChannel = null;
                    talk(script, then(1, choices)); // creating it
                }
                for (int[] choices : leaderTalks) {
                    fresh();
                    leading.set(true);
                    talk(script, choices); // his leader's list
                }
            }
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** Every way into his windows that the hooks must leave as they were, from the first one. */
    private List<Talk> stockTalks() {
        Runnable leader = () -> leading.set(true);
        Runnable crew = () -> {
            leading.set(true);
            sixMembers();
        };
        List<Talk> talks = new ArrayList<>(List.of(
                new Talk("joining his list", () -> { }),
                new Talk("already on it", () -> listed.set(true)),
                new Talk("creating it", () -> onTheChannel = null, 1),
                new Talk("creating it with no Eye", () -> {
                    onTheChannel = null;
                    hasEye = false;
                }, 1),
                new Talk("creating it over the quota", () -> {
                    onTheChannel = null;
                    createResult = 1;
                }, 1),
                new Talk("waiting a bit", () -> onTheChannel = null, 2),
                new Talk("below Lv50", () -> level = 30),
                new Talk("his leader's list", leader),
                new Talk("the only member", leader, 1),
                new Talk("viewing and banning", crew, 1, 2),
                new Talk("viewing and going back", crew, 1, 0),
                new Talk("starting with too few", leader, 2),
                new Talk("starting the fight", crew, 2, 0),
                new Talk("stopping the expedition", leader, 3)));
        talks.addAll(inProgressTalks());
        return talks;
    }

    /** His in-progress branch, which no hook touches. */
    private List<Talk> inProgressTalks() {
        return List.of(
                new Talk("on it, the gate open", () -> {
                    inProgress.set(true);
                    listed.set(true);
                }),
                new Talk("on it, the gate closed", () -> {
                    inProgress.set(true);
                    listed.set(true);
                    canJoin = 0;
                }),
                new Talk("not on it", () -> inProgress.set(true)),
                new Talk("leading it", () -> {
                    inProgress.set(true);
                    leading.set(true);
                }));
    }

    /** {@code talk} with the stock script and with his, each from the same state: the same windows. */
    private void sameAsStock(Talk talk) throws Exception {
        fresh();
        talk.state().run();
        List<String> stock = talk(stockScript(), talk.selections());
        fresh();
        talk.state().run();
        List<String> his = talk(script(), talk.selections());

        assertEquals(stock, his, talk.name());
    }

    /** Lv160 with an Eye of Fire, the channel's Zakum expedition takes sign-ups, and Bossfriend is not on it. */
    private void fresh() {
        listed.set(false);
        leading.set(false);
        inProgress.set(false);
        members.clear();
        members.add(Map.entry(1, "Bossfriend"));
        onTheChannel = expedition;
        level = 160;
        hasEye = true;
        createResult = 0;
        canJoin = 1;
    }

    /** Bossfriend and five more on the list: Zakum's minimum. */
    private void sixMembers() {
        members.clear();
        members.add(Map.entry(1, "Bossfriend"));
        for (String name : List.of("Isolde", "Thane", "Sorrel", "Ingrid", "Flint")) {
            members.add(Map.entry(members.size() + 1, name));
        }
    }

    /** The living world runs, and its boss runs offer the owner "My party will register too." */
    private void livingWorldOffers() {
        when(world.bossRuns()).thenReturn(bossRuns);
        when(bossRuns.offers(player, KEY)).thenReturn(true);
        when(bossRuns.registerFromNpc(player, KEY)).thenReturn(REPLY);
    }

    /**
     * The living world runs, and its boss runs offer the owner "Call allies from my bench..." once they are on his list
     * (as CompanionBossAllies.context: never before the join): his picker offers Orrin and Magna, and a pick of either
     * sends word and shows it again.
     */
    private void benchOffered() {
        when(world.bossRuns()).thenReturn(bossRuns);
        when(bossRuns.alliesOffered(player, KEY)).thenAnswer(inv -> expedition.contains(player));
        when(bossRuns.allyPicker(player, KEY)).thenReturn(BENCH);
        when(bossRuns.allyCall(any(), anyString(), anyInt())).thenReturn(SENT);
    }

    private static String script() throws IOException {
        return Files.readString(SCRIPT, StandardCharsets.US_ASCII);
    }

    /** His script with the seven blocks taken out (its SHA-256 is checked above). */
    private static String stockScript() throws IOException {
        String stock = script();
        for (String block : BLOCKS) {
            stock = stock.replace(block, "");
        }
        return stock;
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
     * As NPCScriptManager runs {@code source} for {@code player}: start, then one action(1, 0, selection) per window
     * until he disposes the talk. Each window is recorded as "simple text", "ok text" or "dispose", and every step must
     * answer with one (or end the talk). LivingWorld.get() answers the world when a test made it run. Returns this
     * talk's windows.
     */
    private List<String> talk(String source, int... selections) throws Exception {
        NPCConversationManager cm = mock(NPCConversationManager.class);
        AtomicBoolean disposed = new AtomicBoolean();
        when(cm.getPlayer()).thenReturn(player);
        when(cm.getExpedition(any(ExpeditionType.class))).thenAnswer(inv -> onTheChannel);
        when(cm.getEventManager("ZakumBattle")).thenReturn(em);
        when(cm.haveItem(EYE_OF_FIRE)).thenAnswer(inv -> hasEye);
        when(cm.createExpedition(ExpeditionType.ZAKUM)).thenAnswer(inv -> {
            if (createResult == 0) {
                onTheChannel = expedition;
                leading.set(true); // the one who creates it leads it, and is on its list
            }
            return createResult;
        });
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
        engine.eval(source);
        engine.put("cm", cm);
        Invocable script = (Invocable) engine;
        LivingWorld running = world.bossRuns() == null ? null : world;
        int first = windows.size();
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
        return new ArrayList<>(windows.subList(first, windows.size()));
    }
}
