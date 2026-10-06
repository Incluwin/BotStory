package server.agents.capabilities.partyquest.epq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import server.agents.capabilities.supplies.AgentAutopotPolicy;
import server.agents.capabilities.supplies.AgentPotionService;
import server.agents.runtime.AgentModeService;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.life.Monster;

import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.Point;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EPQ base fix 7: a golem-fight member out of potions backs off out of the golem's reach (LPQ base fix 6's rule). With
 * fewer than POT_STOP HP potions and HP under REST_HP_FRACTION it walks to the usable floor end out of the golem's
 * reach and stands still there until RESUME_HP_FRACTION or POT_STOP potions; out of reach it fights a golem add that
 * reaches it, never the golem. Everyone else keeps today's grind.
 */
class AgentEpqBossRestTest {
    private static final int POT_STOP = 10;

    private final EpqRunFixture run = new EpqRunFixture();
    private final Character ben = run.human(1, "Ben", AgentEpqDefinition.BOSS_MAP);
    private final Character mira = run.companion(2, "Mira", AgentEpqDefinition.BOSS_MAP);
    private final Character tobi = run.companion(3, "Tobi", AgentEpqDefinition.BOSS_MAP);
    private final Character una = run.companion(4, "Una", AgentEpqDefinition.BOSS_MAP);

    @AfterEach
    void tearDown() {
        run.close();
    }

    private static boolean resting(boolean wasResting, int potions, int hp, int maxHp) {
        return AgentEpqRecoveryPolicy.resting(wasResting, potions, POT_STOP, hp, maxHp,
                AgentEpqRecoveryPolicy.REST_HP_FRACTION, AgentEpqRecoveryPolicy.RESUME_HP_FRACTION);
    }

    @Test
    void theRestRule() {
        assertEquals(0.4, AgentEpqRecoveryPolicy.REST_HP_FRACTION, 1e-9);
        assertEquals(0.7, AgentEpqRecoveryPolicy.RESUME_HP_FRACTION, 1e-9);
        assertTrue(resting(false, 9, 39, 100), "9 potions, 39 %");
        assertFalse(resting(false, 9, 41, 100), "9 potions, 41 %");
        assertFalse(resting(false, 9, 40, 100), "at the threshold it still fights");
        assertFalse(resting(false, POT_STOP, 10, 100), "10 potions: the autopot still covers it");
        assertFalse(resting(false, -1, 10, 100), "an unknown count never rests");
        assertTrue(resting(false, 0, 50, 4_400), "50 HP and no potions: Halvard's run 1");
        assertTrue(resting(true, 9, 69, 100), "a rester holds until 70 %");
        assertFalse(resting(true, 9, 70, 100));
        assertFalse(resting(true, POT_STOP, 10, 100), "or until it holds POT_STOP potions again");
        assertFalse(resting(true, -1, 10, 100));
    }

    // ---- WZ pins ----

    private static Element xml(String path) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(Path.of(path).toFile())
                .getDocumentElement();
    }

    private static List<Element> children(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && element.getTagName().equals(tag)) out.add(element);
        }
        return out;
    }

    private static Element child(Element parent, String tag, String name) {
        return children(parent, tag).stream().filter(element -> name.equals(element.getAttribute("name")))
                .findFirst().orElse(null);
    }

    @Test
    void theReachCoversEveryOrdinaryGolemAttackAndTheSeal() throws Exception {
        int widest = 0;
        int attacks = 0;
        for (int golem : List.of(9_300_180, 9_300_181, 9_300_182)) {
            Element mob = xml("wz/Mob.wz/" + golem + ".img.xml");
            for (Element attack : children(mob, "imgdir")) {
                if (!attack.getAttribute("name").startsWith("attack")) continue;
                Element info = child(attack, "imgdir", "info");
                Element type = child(info, "int", "type");
                if (type == null || !"0".equals(type.getAttribute("value"))) continue;
                Element range = child(info, "imgdir", "range");
                for (String corner : List.of("lt", "rb")) {
                    widest = Math.max(widest, Math.abs(Integer.parseInt(
                            child(range, "vector", corner).getAttribute("x"))));
                }
                attacks++;
            }
        }
        assertEquals(5, attacks, "attack1 of each golem and attack2 of 9300181/9300182");
        assertEquals(500, widest, "9300182 attack1");
        assertTrue(AgentEpqRecoveryPolicy.REACH_PX >= widest);

        Element seal = child(child(child(xml("wz/Skill.wz/MobSkill.img.xml"), "imgdir", "120"), "imgdir", "level"),
                "imgdir", "3");
        int sealHalfWidth = Math.max(Math.abs(Integer.parseInt(child(seal, "vector", "lt").getAttribute("x"))),
                Math.abs(Integer.parseInt(child(seal, "vector", "rb").getAttribute("x"))));
        assertEquals(450, sealHalfWidth);
        assertTrue(AgentEpqRecoveryPolicy.REACH_PX > sealHalfWidth);

        int largestMargin = 150; // the yaml's allowed range for FLOOR_EDGE_MARGIN_PX
        AgentEpqDefinition.BossFloor floor = AgentEpqDefinition.BOSS_FLOOR;
        assertTrue(2 * AgentEpqRecoveryPolicy.REACH_PX < (floor.rightX() - floor.leftX()) - 2 * largestMargin,
                "one floor end is always out of reach");
    }

    @Test
    void theBossFloorIsTheWalkableFloorOfTheGolemsMap() throws Exception {
        Element footholds = child(xml("wz/Map.wz/Map/Map9/930000600.img.xml"), "imgdir", "foothold");
        int left = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE;
        Set<Integer> heights = new java.util.HashSet<>();
        for (Element layer : children(footholds, "imgdir")) {
            for (Element group : children(layer, "imgdir")) {
                for (Element foothold : children(group, "imgdir")) {
                    int id = Integer.parseInt(foothold.getAttribute("name"));
                    if (id < 1 || id > 18) continue; // 19-24: the Ellin ledge, no rope up
                    int x1 = Integer.parseInt(child(foothold, "int", "x1").getAttribute("value"));
                    int x2 = Integer.parseInt(child(foothold, "int", "x2").getAttribute("value"));
                    left = Math.min(left, Math.min(x1, x2));
                    right = Math.max(right, Math.max(x1, x2));
                    heights.add(Integer.parseInt(child(foothold, "int", "y1").getAttribute("value")));
                    heights.add(Integer.parseInt(child(foothold, "int", "y2").getAttribute("value")));
                }
            }
        }
        assertEquals(new AgentEpqDefinition.BossFloor(left, right, 150), AgentEpqDefinition.BOSS_FLOOR);
        assertEquals(Set.of(150), heights);
    }

    // ---- the rest point ----

    @Test
    void theRestPointIsAlwaysOutOfTheGolemsReachOnTheFloor() {
        AgentEpqDefinition.BossFloor floor = AgentEpqDefinition.BOSS_FLOOR;
        int margin = AgentEpqRecoveryPolicy.FLOOR_EDGE_MARGIN_PX;
        int reach = AgentEpqRecoveryPolicy.REACH_PX;
        for (int golemX = floor.leftX(); golemX <= floor.rightX(); golemX += 5) {
            Point golem = new Point(golemX, floor.y());
            for (int offset : new int[]{-300, -1, 1, 300}) {
                Point member = new Point(golemX + offset, floor.y());
                Point rest = AgentEpqRecoveryPolicy.restPoint(golem, member, null, floor, margin, reach);
                assertTrue(Math.abs(rest.x - golemX) > reach, "golem " + golemX + " member " + member.x);
                assertTrue(rest.x >= floor.leftX() + margin && rest.x <= floor.rightX() - margin);
                assertEquals(floor.y(), rest.y);
            }
        }
        Point stored = new Point(-480, 150);
        assertSame(stored, AgentEpqRecoveryPolicy.restPoint(new Point(100, 150), new Point(90, 150), stored, floor,
                margin, reach), "a stored point out of reach is kept");
        assertEquals(new Point(1_020, 150), AgentEpqRecoveryPolicy.restPoint(new Point(-100, 150),
                new Point(-90, 150), stored, floor, margin, reach), "a stored point the golem walked onto is left");
        assertEquals(new Point(-480, 150), AgentEpqRecoveryPolicy.restPoint(new Point(392, 150),
                new Point(300, 150), null, floor, margin, reach), "its own side when that end is out of reach");
    }

    @Test
    void addThreatIsWithinTheThreatBoxBothWays() {
        Point member = new Point(1_020, 150);
        assertTrue(AgentEpqRecoveryPolicy.addThreat(member, List.of(new Point(1_120, 50)), 100));
        assertFalse(AgentEpqRecoveryPolicy.addThreat(member, List.of(new Point(1_121, 150)), 100));
        assertFalse(AgentEpqRecoveryPolicy.addThreat(member, List.of(new Point(1_020, 251)), 100));
        assertFalse(AgentEpqRecoveryPolicy.addThreat(member, List.of(), 100));
    }

    // ---- wiring ----

    private AgentEpqSession boss() {
        when(run.actions.liveMonsterCount(any(), eq(AgentEpqDefinition.BOSS_MOBS))).thenReturn(1);
        return run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, true, AgentEpqSession.Phase.BOSS,
                ben, mira, tobi, una);
    }

    private Monster golemAt(int x) {
        Monster golem = run.monster(9_300_180);
        when(golem.getPosition()).thenReturn(new Point(x, 150));
        return golem;
    }

    private void vitals(Character member, int x, int hp, int potions) {
        when(member.getPosition()).thenReturn(new Point(x, 150));
        when(member.getHp()).thenReturn(hp);
        when(member.getCurrentMaxHp()).thenReturn(4_400);
        when(run.actions.hpPotionCount(member)).thenReturn(potions);
    }

    @Test
    void aMemberOutOfPotionsBesideTheGolemWalksToItsRestPointAndStandsThere() {
        AgentEpqSession session = boss();
        golemAt(392);
        vitals(tobi, 400, 1_320, 9);
        AgentRuntimeEntry entry = run.entry(tobi);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickMember(session, entry, tobi, 2_000L);
            verify(run.actions).navigate(entry, new Point(1_020, 150), true);
            verify(run.actions, never()).grind(eq(entry), any());

            vitals(tobi, 1_020, 1_330, 9);
            AgentEpqCoordinator.tickMember(session, entry, tobi, 3_000L);
            verify(run.actions).stop(entry);

            vitals(tobi, 1_020, 3_080, 9); // 70 %: back into the fight
            AgentEpqCoordinator.tickMember(session, entry, tobi, 4_000L);
            verify(run.actions).grind(entry, AgentEpqDefinition.BOSS_COMBAT_TARGETS);

            String id = session.sessionId();
            assertEquals(List.of(
                    "EPQ boss rest: session=" + id + " member=Tobi state=START hp=1320/4400 hpPots=9 "
                            + "restPoint=(1020,150) golem=(392,150)",
                    "EPQ boss rest: session=" + id + " member=Tobi state=END hp=3080/4400 hpPots=9 "
                            + "restPoint=(1020,150) golem=(392,150)"), lines.startingWith("EPQ boss rest:"));
        }
    }

    @Test
    void aGolemWalkingOntoTheRestPointSendsTheResterAcrossAndSaysSo() {
        AgentEpqSession session = boss();
        Monster golem = golemAt(392);
        vitals(tobi, 1_020, 1_000, 3);
        AgentRuntimeEntry entry = run.entry(tobi);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickMember(session, entry, tobi, 2_000L);
            when(golem.getPosition()).thenReturn(new Point(700, 150));
            AgentEpqCoordinator.tickMember(session, entry, tobi, 3_000L);

            verify(run.actions).navigate(entry, new Point(-480, 150), true);
            assertEquals(1, lines.startingWith("EPQ boss rest: session=" + session.sessionId()
                    + " member=Tobi state=MOVE").size(), lines.startingWith("EPQ boss rest:")::toString);
        }
    }

    @Test
    void aResterOutOfReachFightsAnAddOnItButNeverTheGolem() {
        AgentEpqSession session = boss();
        golemAt(392);
        Monster add = run.monster(9_300_177);
        when(add.getPosition()).thenReturn(new Point(1_060, 150));
        vitals(tobi, 1_020, 1_000, 3);
        AgentRuntimeEntry entry = run.entry(tobi);

        AgentEpqCoordinator.tickMember(session, entry, tobi, 2_000L);

        verify(run.actions).grindFromAnchor(entry, new Point(1_020, 150), AgentEpqDefinition.BOSS_ADDS, Set.of());
        verify(run.actions, never()).grind(eq(entry), any());
        verify(run.actions, never()).stop(entry);
    }

    @Test
    void anAirborneResterAtItsPointKeepsNavigating() {
        AgentEpqSession session = boss();
        golemAt(392);
        vitals(tobi, 1_020, 1_000, 3);
        when(run.actions.grounded(tobi)).thenReturn(false); // knocked into the air at its point
        AgentRuntimeEntry entry = run.entry(tobi);

        AgentEpqCoordinator.tickMember(session, entry, tobi, 2_000L);

        verify(run.actions).navigate(entry, new Point(1_020, 150), true);
        verify(run.actions, never()).stop(entry);
    }

    /** Inside the arrival tolerance but still within the golem's reach, a rester fights no add: only out of reach. */
    @Test
    void aResterStillWithinTheGolemsReachFightsNoAdd() {
        AgentEpqSession session = boss();
        golemAt(519); // its point, the right end (1020), is 501 px away
        Monster add = run.monster(9_300_177);
        when(add.getPosition()).thenReturn(new Point(1_040, 150));
        vitals(tobi, 1_001, 1_000, 3); // 19 px from its point, 482 px from the golem
        AgentRuntimeEntry entry = run.entry(tobi);

        AgentEpqCoordinator.tickMember(session, entry, tobi, 2_000L);

        verify(run.actions, never()).grindFromAnchor(any(), any(), any(), any());
        verify(run.actions).stop(entry);
    }

    @Test
    void aMemberWithPotionsOrHealthKeepsTodaysGrind() {
        AgentEpqSession session = boss();
        golemAt(392);
        vitals(tobi, 400, 1_320, 40); // low, but the autopot still has potions
        vitals(una, 400, 4_000, 3); // few potions, but healthy

        AgentEpqCoordinator.tickMember(session, run.entry(tobi), tobi, 2_000L);
        AgentEpqCoordinator.tickMember(session, run.entry(una), una, 2_000L);

        verify(run.actions).grind(run.entry(tobi), AgentEpqDefinition.BOSS_COMBAT_TARGETS);
        verify(run.actions).grind(run.entry(una), AgentEpqDefinition.BOSS_COMBAT_TARGETS);
        verify(run.actions, never()).navigate(any(), any(), anyBoolean());
    }

    /**
     * The golem chain spawns by Mob.wz revive after each die animation, so for a moment between 9300180 and 9300181
     * no golem lives. A rester at 50 % with 5 potions keeps resting through that gap (it may top up there) and does not
     * walk back into melee when 9300181 appears: it rejoins only at RESUME_HP_FRACTION or with POT_STOP potions.
     */
    @Test
    void aRestLastsThroughTheGolemsRevive() {
        AgentEpqSession session = boss();
        Monster first = golemAt(392);
        vitals(tobi, 400, 1_320, 5); // 30 %, 5 potions: rests
        AgentRuntimeEntry entry = run.entry(tobi);
        try (MockedStatic<AgentPotionService> potions = mockStatic(AgentPotionService.class);
             EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            potions.when(() -> AgentPotionService.selectAutopotForCurrentDeficit(any(), eq(true)))
                    .thenReturn(new AgentAutopotPolicy.AutopotItemChoice(2_000_002, (short) 1, null));
            AgentEpqCoordinator.tickMember(session, entry, tobi, 2_000L);
            vitals(tobi, 1_020, 2_200, 5); // 50 %: still resting
            AgentEpqCoordinator.tickMember(session, entry, tobi, 3_000L);

            when(first.isAlive()).thenReturn(false); // 9300180 dies; 9300181 follows its die animation
            when(run.actions.liveMonsterCount(any(), eq(AgentEpqDefinition.BOSS_MOBS))).thenReturn(0);
            AgentEpqCoordinator.tickMember(session, entry, tobi, 4_000L);
            assertTrue(session.member(tobi.getId()).bossResting(), "the rest lasts through the revive");

            Monster second = run.monster(9_300_181);
            when(second.getPosition()).thenReturn(new Point(392, 150));
            when(run.actions.liveMonsterCount(any(), eq(AgentEpqDefinition.BOSS_MOBS))).thenReturn(1);
            AgentEpqCoordinator.tickMember(session, entry, tobi, 5_000L);

            verify(run.actions, never()).grind(eq(entry), any());
            List<String> rest = lines.startingWith("EPQ boss rest:");
            assertEquals(1, rest.size(), rest::toString);
            assertTrue(rest.getFirst().contains(" state=START "), rest::toString);
        }
        assertTrue(session.member(tobi.getId()).bossResting());
    }

    @Test
    void aRestEndsWithTheClear() {
        AgentEpqSession session = boss();
        Monster golem = golemAt(392);
        vitals(tobi, 1_020, 1_000, 0);
        AgentRuntimeEntry entry = run.entry(tobi);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickMember(session, entry, tobi, 2_000L);
            when(golem.isAlive()).thenReturn(false); // 9300182's death clears the event
            when(run.actions.liveMonsterCount(any(), eq(AgentEpqDefinition.BOSS_MOBS))).thenReturn(0);
            when(run.event.isEventCleared()).thenReturn(true);
            AgentEpqCoordinator.tickMember(session, entry, tobi, 3_000L);

            assertEquals(1, lines.startingWith("EPQ boss rest: session=" + session.sessionId()
                    + " member=Tobi state=END").size(), lines.startingWith("EPQ boss rest:")::toString);
        }
        assertFalse(session.member(tobi.getId()).bossResting());
        assertEquals(null, session.member(tobi.getId()).bossRestPoint());
    }

    @Test
    void aRestEndsWhenTheMemberLeavesTheGolemsMap() {
        AgentEpqSession session = boss();
        golemAt(392);
        vitals(tobi, 1_020, 1_000, 0);
        AgentRuntimeEntry entry = run.entry(tobi);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickMember(session, entry, tobi, 2_000L);
            run.onMap(tobi, AgentEpqDefinition.REWARD_MAP); // a GM warp; still in the event
            AgentEpqCoordinator.tickMember(session, entry, tobi, 3_000L);

            assertEquals(1, lines.startingWith("EPQ boss rest: session=" + session.sessionId()
                    + " member=Tobi state=END").size(), lines.startingWith("EPQ boss rest:")::toString);
        }
        assertFalse(session.member(tobi.getId()).bossResting());
    }

    /**
     * Today's fact, recorded: the anchored add fight puts the Agent in grind mode, the generic out-of-potions stop's
     * condition (AgentPotionService.tickPotionCheck), which can send a resting companion toward its owner while an add
     * is on it; the next coordinator pass re-issues the rest order (LPQ base fix 6 accepted the same).
     */
    @Test
    void theAnchoredAddFightIsGrindMode() throws Exception {
        String gateway = Files.readString(Path.of(
                "src/main/java/server/agents/integration/cosmic/CosmicPrimitiveCapabilityGateway.java"))
                .replace("\r\n", "\n");
        int at = gateway.indexOf("public void grindFromAnchor(");
        assertTrue(at > 0);
        assertTrue(gateway.substring(at, gateway.indexOf("\n    }\n", at)).contains("AgentModeService.startFarmHere("));

        AgentRuntimeEntry entry = new AgentRuntimeEntry(mock(Character.class), mock(Character.class), null);
        AgentModeService.startFarmHere(entry, new Point(1_020, 150), ignored -> { });
        assertTrue(AgentModeStateRuntime.grinding(entry));
    }

    @Test
    void theRestDecisionComesBeforeTheGolemGrind() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/server/agents/capabilities/partyquest/epq/AgentEpqCoordinator.java"))
                .replace("\r\n", "\n");
        int boss = source.indexOf("    private static void boss(");
        int branch = source.indexOf("if (actions().liveMonsterCount(agent, AgentEpqDefinition.BOSS_MOBS) > 0) {", boss);
        int rest = source.indexOf("bossRest(", branch);
        int grind = source.indexOf("actions().grind(entry, AgentEpqDefinition.BOSS_COMBAT_TARGETS);", branch);
        assertTrue(boss > 0 && branch > boss && rest > branch && grind > rest, "rest " + rest + " grind " + grind);
        int branchEnd = source.indexOf("\n        }\n", branch);
        assertTrue(grind < branchEnd, "both inside the golem branch");
    }
}
