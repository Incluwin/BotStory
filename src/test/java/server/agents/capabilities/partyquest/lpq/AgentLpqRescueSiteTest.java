package server.agents.capabilities.partyquest.lpq;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Every LPQ rescue the coordinator can apply logs an 'LPQ rescue:' line (P6d LPQ step 1, plan 1.6). A source scan
 * of AgentLpqCoordinator.java (comments stripped, string literals blanked, whitespace-tolerant) pins it:
 * <ol>
 *     <li>each raw world call a rescue makes (a pose teleport, a remote box hit, a kill with drops, an event
 *     re-registration, a map move, an inventory grant or removal, a drop vacuum) lives only inside its rescue
 *     primitive, and LPQ.runNpc only where an NPC is run after an approach (or remotely, in submit);</li>
 *     <li>each rescue primitive calls AgentLpqRescue.note;</li>
 *     <li>the (calling method, site label, kind) of every rescue is the reviewed inventory, each once;</li>
 *     <li>submit notes its remote NPC run, when the leader is not near the NPC, only once LPQ.runNpc has started
 *     it (a run that did not start is retried next tick, so a note before it would repeat every tick).</li>
 * </ol>
 */
class AgentLpqRescueSiteTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/server/agents/capabilities/partyquest/lpq/AgentLpqCoordinator.java");
    private static final Pattern METHOD = Pattern.compile(
            "(?m)^    (?:private |public |protected )?static (?!final)[^=;{}()]*?\\b(\\w+)\\s*\\(");

    /** Each rescue primitive and the kind its notes carry (grantItem takes its kind as an argument). */
    private static final Map<String, String> PRIMITIVES = new LinkedHashMap<>();

    static {
        PRIMITIVES.put("restoreEventMember", "EVENT_REREGISTER");
        PRIMITIVES.put("moveWithinEvent", "MAP_MOVE");
        PRIMITIVES.put("recoverStageTwoTrap", "TRAP_RETURN");
        PRIMITIVES.put("rescueTeleport", "TELEPORT");
        PRIMITIVES.put("remoteBoxHits", "REMOTE_REACTOR");
        PRIMITIVES.put("vacuumItemDrops", "VACUUM");
        PRIMITIVES.put("forcePassTransferToLeader", "FORCE_TRANSFER");
        PRIMITIVES.put("grantItem", "");
        PRIMITIVES.put("killWithDrops", "KILL_WITH_DROPS");
    }

    /** The wrappers a rescue call site names, and the kind each implies ("" = the kind is an argument). */
    private static final Map<String, String> WRAPPERS = new LinkedHashMap<>(PRIMITIVES);

    static {
        WRAPPERS.put("vacuumPassDrops", "VACUUM");
    }

    /** The REVIEWED inventory: 46 rows, (calling method, site label, kind). */
    private static final Set<String> INVENTORY = new TreeSet<>(List.of(
            "enter entering-not-in-event EVENT_REREGISTER",
            "catchUpLaggingMembers dead-agent-reentry EVENT_REREGISTER",
            "submit agent-leader-reentry EVENT_REREGISTER",
            "catchUpLaggingMembers catch-up-absolute MAP_MOVE",
            "catchUpLaggingMembers catch-up-traversal MAP_MOVE",
            "recoverPassHandoffAtNpc pass-handoff-stage-map MAP_MOVE",
            "movePartyToNextStageTogether next-stage-lagging MAP_MOVE",
            "movePartyToNextStageTogether next-stage-no-portal MAP_MOVE",
            "enterStagePortalWithRecovery stage-portal-traversal MAP_MOVE",
            "useStageTwoTrapExit trap-exit-stalled TRAP_RETURN",
            "catchUpLaggingMembers catch-up-trap TRAP_RETURN",
            "recoverBalloonRallyPositions balloon-rally TELEPORT",
            "applyStageFourRoomRecovery stage4-attack-assist TELEPORT",
            "recoverStageFiveRoomAtCeiling stage5-room-ceiling TELEPORT",
            "forceRoomExitThroughPortal room-exit-portal TELEPORT",
            "movePartyToNextStageTogether next-stage-portal TELEPORT",
            "approachStageSixPortal stage6-portal-approach TELEPORT",
            "occupyStageEightPlatformWithRecovery stage8-platform TELEPORT",
            "prepareStageNineBossFormation stage9-formation TELEPORT",
            "recoverBossKey boss-key-holder TELEPORT",
            "approachRewardNpc reward-npc-approach TELEPORT",
            "hitReactorFromCandidates reactor-approach TELEPORT",
            "applyStageFiveUnobservedBoxAssist stage5-unobserved-box TELEPORT",
            "applyStageFiveUnobservedExitAssist stage5-unobserved-exit TELEPORT",
            "approachWithRecovery approach TELEPORT",
            "recoverStageFiveRoomAtCeiling stage5-room-ceiling REMOTE_REACTOR",
            "regroupCouponsAtNpc coupon-regroup VACUUM",
            "allLoosePassesCollected loose-passes VACUUM",
            "forcePassTransferToLeader final-rally VACUUM",
            "stageSevenLeaderLootSweep stage7-leader-sweep VACUUM",
            "tickStageFiveRoomContinuation stage5-terminal-room VACUUM",
            "applyStageFourRoomRecovery stage4-hard-recovery VACUUM",
            "applyStageFourRoomRecovery stage4-pass-loot VACUUM",
            "recoverStageFiveRoomAtCeiling stage5-room-ceiling VACUUM",
            "recoverPassDrops missing-pass-drops VACUUM",
            "recoverPassDrops missing-pass-room-drops VACUUM",
            "recoverBossKey boss-key VACUUM",
            "regroupCouponsAtNpc final-rally FORCE_TRANSFER",
            "recoverMissingPasses missing-pass-award PASS_AWARD",
            "tickStageFiveRoomContinuation stage5-missing-room-pass PASS_AWARD",
            "restoreStageFiveEarnedPasses stage5-earned-passes PASS_RESTORE",
            "recoverBossKey boss-key-award KEY_AWARD",
            "applyStageFourRoomRecovery stage4-hard-recovery KILL_WITH_DROPS",
            "recoverMissingPasses missing-pass-mob-sweep KILL_WITH_DROPS",
            "submit remote-submit REMOTE_NPC",
            "markRoomAndEnter room-marker-potion MARKER_GRANT"));

    private static String code;     // comments blanked, string contents blanked: structure only
    private static String text;     // comments blanked, string literals intact: for arguments
    private static final List<Method> methods = new ArrayList<>();

    private record Method(String name, int bodyStart, int bodyEnd) {
    }

    @BeforeAll
    static void scan() throws Exception {
        String source = Files.readString(SOURCE).replace("\r\n", "\n");
        StringBuilder structure = new StringBuilder(source.length());
        StringBuilder literal = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                while (i < source.length() && source.charAt(i) != '\n') {
                    structure.append(' ');
                    literal.append(' ');
                    i++;
                }
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2) + 2;
                for (; i < end; i++) {
                    char blank = source.charAt(i) == '\n' ? '\n' : ' ';
                    structure.append(blank);
                    literal.append(blank);
                }
            } else if (c == '"' || c == '\'') {
                structure.append(c);
                literal.append(c);
                i++;
                while (source.charAt(i) != c) {
                    if (source.charAt(i) == '\\') {
                        structure.append("__");
                        literal.append(source, i, i + 2);
                        i += 2;
                        continue;
                    }
                    structure.append('_');
                    literal.append(source.charAt(i));
                    i++;
                }
                structure.append(c);
                literal.append(c);
                i++;
            } else {
                structure.append(c);
                literal.append(c);
                i++;
            }
        }
        code = structure.toString();
        text = literal.toString();
        Matcher header = METHOD.matcher(code);
        while (header.find()) {
            int open = code.indexOf('{', header.end());
            int semicolon = code.indexOf(';', header.end());
            if (open < 0 || (semicolon >= 0 && semicolon < open)) continue;
            int depth = 0;
            int end = open;
            for (; end < code.length(); end++) {
                if (code.charAt(end) == '{') depth++;
                else if (code.charAt(end) == '}' && --depth == 0) break;
            }
            methods.add(new Method(header.group(1), open, end));
        }
        assertTrue(methods.size() > 100, "the scan found the coordinator's methods: " + methods.size());
    }

    private static String enclosing(int offset) {
        for (Method method : methods) {
            if (offset > method.bodyStart() && offset < method.bodyEnd()) return method.name();
        }
        return null;
    }

    private static Method method(String name) {
        return methods.stream().filter(method -> method.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no method " + name));
    }

    /** Where each match of {@code regex} occurs, by enclosing method, outside declarations. */
    private static Map<String, Integer> callers(String regex) {
        Map<String, Integer> callers = new TreeMap<>();
        Matcher matcher = Pattern.compile(regex).matcher(code);
        while (matcher.find()) {
            String owner = enclosing(matcher.start());
            if (owner != null) callers.merge(owner, 1, Integer::sum);
        }
        return callers;
    }

    @Test
    void rawRescueCallsLiveOnlyInTheirPrimitives() {
        assertEquals(Map.of("rescueTeleport", 1), callers("ACTIONS\\s*\\.\\s*stagePosition\\s*\\("));
        assertEquals(Map.of("killWithDrops", 1), callers("\\bkillMonsterWithDrops\\s*\\("));
        assertEquals(Map.of("restoreEventMember", 1), callers("\\bregisterPlayer\\s*\\("));
        assertEquals(Map.of("restoreEventMember", 1, "moveWithinEvent", 1, "recoverStageTwoTrap", 1),
                callers("\\bchangeMapNear\\s*\\("));
        assertEquals(Map.of("grantItem", 1, "vacuumItemDrops", 1, "forcePassTransferToLeader", 1),
                callers("\\binventory\\s*\\(\\s*\\)\\s*\\.\\s*addItem\\s*\\("));
        assertEquals(Map.of("vacuumItemDrops", 1), callers("\\bremoveItemDrop\\s*\\("));
        assertEquals(Map.of("forcePassTransferToLeader", 1, "purgeResidualPasses", 1),
                callers("\\binventory\\s*\\(\\s*\\)\\s*\\.\\s*removeById\\s*\\("));
        assertEquals(Map.of("remoteBoxHits", 1, "hitReactorFromCandidates", 2),
                callers("ACTIONS\\s*\\.\\s*hitReactor\\s*\\("));
        assertEquals(Map.of("prepare", 1, "submit", 1, "platformPuzzle", 2, "bonus", 1, "claim", 2),
                callers("\\bLPQ\\s*\\.\\s*runNpc\\s*\\("));
    }

    @Test
    void everyRescuePrimitiveNotes() {
        for (String primitive : PRIMITIVES.keySet()) {
            Method method = method(primitive);
            String body = code.substring(method.bodyStart(), method.bodyEnd());
            assertTrue(Pattern.compile("\\bAgentLpqRescue\\s*\\.\\s*note\\s*\\(").matcher(body).find(),
                    primitive + " calls AgentLpqRescue.note");
        }
    }

    @Test
    void theRescueSitesAreTheReviewedInventoryEachOnce() {
        List<String> triples = new ArrayList<>();
        // A primitive that forwards its caller's site to another kind's primitive inherits its callers' labels.
        Map<String, List<String>> labelsByPrimitive = new HashMap<>();
        List<String[]> forwarded = new ArrayList<>();
        for (Map.Entry<String, String> wrapper : WRAPPERS.entrySet()) {
            Matcher matcher = Pattern.compile("\\b" + wrapper.getKey() + "\\s*\\(").matcher(code);
            while (matcher.find()) {
                String owner = enclosing(matcher.start());
                if (owner == null) continue; // the declaration
                List<String> args = arguments(matcher.end() - 1);
                boolean kindArgument = wrapper.getValue().isEmpty();
                String kind = kindArgument ? kindOf(args.get(1)) : wrapper.getValue();
                String site = args.get(kindArgument ? 2 : 1).trim();
                if (site.equals("site")) {
                    if (!PRIMITIVES.containsKey(owner) && !owner.equals("vacuumPassDrops")) {
                        fail(owner + " forwards a site it was not given: " + wrapper.getKey());
                    }
                    if (!kind.equals(WRAPPERS.get(owner))) forwarded.add(new String[]{owner, kind});
                    continue;
                }
                assertTrue(site.matches("\"[a-z0-9-]+\""), owner + " names a literal site for "
                        + wrapper.getKey() + ": " + site);
                String label = site.substring(1, site.length() - 1);
                triples.add(owner + " " + label + " " + kind);
                labelsByPrimitive.computeIfAbsent(wrapper.getKey(), ignored -> new ArrayList<>()).add(label);
            }
        }
        Matcher direct = Pattern.compile("\\bAgentLpqRescue\\s*\\.\\s*note\\s*\\(").matcher(code);
        while (direct.find()) {
            String owner = enclosing(direct.start());
            if (owner == null || PRIMITIVES.containsKey(owner)) continue;
            List<String> args = arguments(direct.end() - 1);
            String site = args.get(2).trim();
            assertTrue(site.matches("\"[a-z0-9-]+\""), owner + " notes a literal site: " + site);
            triples.add(owner + " " + site.substring(1, site.length() - 1) + " " + kindOf(args.get(1)));
        }
        for (String[] forward : forwarded) {
            for (String label : labelsByPrimitive.getOrDefault(forward[0], List.of())) {
                triples.add(forward[0] + " " + label + " " + forward[1]);
            }
        }

        assertEquals(triples.size(), new TreeSet<>(triples).size(), "no (method, site, kind) twice: " + triples);
        assertEquals(INVENTORY, new TreeSet<>(triples));
        assertEquals(46, triples.size());
    }

    @Test
    void submitNotesItsRemoteNpcRunOnlyOnceTheRunHasStarted() {
        Method submit = method("submit");
        String body = code.substring(submit.bodyStart(), submit.bodyEnd());
        // runNpc returns false when the NPC is not on the leader's map or its script does not start; nothing defers
        // the leader then, so submit runs again next tick. Only a run that started is a rescue applied.
        Matcher placed = Pattern.compile("if\\s*\\(\\s*LPQ\\s*\\.\\s*runNpc\\s*\\(\\s*leader\\s*,\\s*stage\\s*\\.\\s*npcId"
                + "\\s*\\(\\s*\\)\\s*,\\s*1\\s*\\)\\s*\\)\\s*\\{"
                + "\\s*if\\s*\\(\\s*!\\s*nearNpc\\s*\\)\\s*AgentLpqRescue\\s*\\.\\s*note\\s*\\("
                + "\\s*session\\s*,\\s*AgentLpqRescue\\s*\\.\\s*Kind\\s*\\.\\s*REMOTE_NPC\\s*,[^;]*;").matcher(body);
        assertTrue(placed.find(), "submit: if (LPQ.runNpc(leader, ...)) { if (!nearNpc) note REMOTE_NPC; ...");
        assertEquals(1, Pattern.compile("Kind\\s*\\.\\s*REMOTE_NPC").matcher(body).results().count(),
                "submit notes REMOTE_NPC once, inside the run's success branch");
    }

    /** The top-level arguments of the call whose '(' is at {@code open}, as written (string literals intact). */
    private static List<String> arguments(int open) {
        List<String> args = new ArrayList<>();
        int depth = 0;
        int from = open + 1;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(' || c == '{' || c == '[') depth++;
            else if (c == ')' || c == '}' || c == ']') {
                if (--depth == 0) {
                    args.add(text.substring(from, i));
                    return args;
                }
            } else if (c == ',' && depth == 1) {
                args.add(text.substring(from, i));
                from = i + 1;
            }
        }
        throw new AssertionError("unclosed call at " + open);
    }

    private static String kindOf(String argument) {
        Matcher kind = Pattern.compile("^\\s*AgentLpqRescue\\s*\\.\\s*Kind\\s*\\.\\s*([A-Z_]+)\\s*$").matcher(argument);
        assertTrue(kind.matches(), "a literal AgentLpqRescue.Kind: " + argument);
        return kind.group(1);
    }
}
