package server.agents.capabilities.partyquest.epq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EPQ base fix 7 and the golem rest fix (live 2026-10-08, sessions ad7e9a29 and e65d82a8): a golem-fight member backs
 * off before it goes down. It rests once its HP falls under REST_HP_FRACTION, with or without potions, or while it
 * holds fewer than POT_STOP HP potions at any HP; it backs off on its own side of the golem (never across it, never
 * toward it), holds its spot when it already stands out of the golem's reach, drinks an HP potion every
 * TOP_UP_INTERVAL_MS while under RESUME_HP_FRACTION, and rejoins only at RESUME_HP_FRACTION with POT_STOP potions. Out
 * of reach it fights a golem add that reaches it, never the golem. Everyone else keeps today's grind.
 */
class AgentEpqBossRestTest {
    private static final int POT_STOP = 10;
    private static final int WHITE_POTION = 2_000_002;

    private final EpqRunFixture run = new EpqRunFixture();
    private final Character ben = run.human(1, "Ben", AgentEpqDefinition.BOSS_MAP);
    private final Character mira = run.companion(2, "Mira", AgentEpqDefinition.BOSS_MAP);
    private final Character tobi = run.companion(3, "Tobi", AgentEpqDefinition.BOSS_MAP);
    private final Character una = run.companion(4, "Una", AgentEpqDefinition.BOSS_MAP);
    private final MockedStatic<AgentPotionService> potions = mockStatic(AgentPotionService.class);

    @BeforeEach
    void whitePotions() {
        potions.when(() -> AgentPotionService.selectAutopotForCurrentDeficit(any(), eq(true)))
                .thenReturn(new AgentAutopotPolicy.AutopotItemChoice(WHITE_POTION, (short) 1, null));
        when(run.actions.useItem(any(), eq(WHITE_POTION))).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        potions.close();
        run.close();
    }

    private static boolean resting(boolean wasResting, int potions, int hp, int maxHp) {
        return AgentEpqRecoveryPolicy.resting(wasResting, potions, POT_STOP, hp, maxHp,
                AgentEpqRecoveryPolicy.REST_HP_FRACTION, AgentEpqRecoveryPolicy.RESUME_HP_FRACTION);
    }

    @Test
    void theRestRule() {
        assertEquals(0.5, AgentEpqRecoveryPolicy.REST_HP_FRACTION, 1e-9);
        assertEquals(0.8, AgentEpqRecoveryPolicy.RESUME_HP_FRACTION, 1e-9);
        // It starts under REST_HP_FRACTION with or without potions, before the autopot drains the stock.
        assertTrue(resting(false, 200, 49, 100), "200 potions at 49 %: it backs off to drink up");
        assertFalse(resting(false, 200, 50, 100), "at the threshold it still fights");
        assertTrue(resting(false, 350, 2_900, 5_904), "Halvard at 49 % with a full stock");
        // Or while it holds fewer than POT_STOP HP potions, at any HP.
        assertTrue(resting(false, 9, 100, 100), "9 potions at full HP: out of potions, it hangs back");
        assertTrue(resting(false, 0, 501, 2_928), "Kestrel's 05:46 start, now long before 17 %");
        assertFalse(resting(false, POT_STOP, 51, 100), "POT_STOP potions and HP: it fights");
        // It ends only at RESUME_HP_FRACTION with POT_STOP potions.
        assertTrue(resting(true, 158, 1_286, 5_904), "Halvard 05:44: 158 shared potions at 22 % keep it resting");
        assertTrue(resting(true, 110, 867, 5_904), "Halvard 06:46: 110 shared potions at 15 % keep it resting");
        assertTrue(resting(true, 200, 79, 100));
        assertFalse(resting(true, 200, 80, 100), "safe HP and potions: back into the fight");
        assertFalse(resting(true, POT_STOP, 80, 100));
        assertTrue(resting(true, 9, 100, 100), "full HP without potions still hangs back");
        // An unknown count or maximum never rests.
        assertFalse(resting(false, -1, 10, 100));
        assertFalse(resting(true, -1, 10, 100));
        assertFalse(resting(false, 0, 0, 0));
        assertFalse(resting(true, 0, 0, 0));
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

    private static Point restPoint(int golemX, int memberX, Point stored) {
        return restPoint(golemX, new Point(memberX, AgentEpqDefinition.BOSS_FLOOR.y()), stored);
    }

    private static Point restPoint(int golemX, Point member, Point stored) {
        return AgentEpqRecoveryPolicy.restPoint(new Point(golemX, AgentEpqDefinition.BOSS_FLOOR.y()), member, stored,
                AgentEpqDefinition.BOSS_FLOOR, AgentEpqRecoveryPolicy.FLOOR_EDGE_MARGIN_PX,
                AgentEpqRecoveryPolicy.REACH_PX);
    }

    /**
     * Over every golem position and member offset: the rest point lies on the floor on the member's own side of the
     * golem (the walk never passes it), never nearer the golem than the member stands, and out of reach whenever the
     * member's side has room out of reach.
     */
    @Test
    void theRestPointNeverCrossesTheGolemNorStepsTowardIt() {
        AgentEpqDefinition.BossFloor floor = AgentEpqDefinition.BOSS_FLOOR;
        int margin = AgentEpqRecoveryPolicy.FLOOR_EDGE_MARGIN_PX;
        int reach = AgentEpqRecoveryPolicy.REACH_PX;
        int outOfReach = 0;
        for (int golemX = floor.leftX(); golemX <= floor.rightX(); golemX += 5) {
            for (int memberX : new int[]{floor.leftX(), golemX - 600, golemX - 300, golemX - 1, golemX + 1,
                    golemX + 300, golemX + 600, floor.rightX()}) {
                if (memberX < floor.leftX() || memberX > floor.rightX() || memberX == golemX) continue;
                Point rest = restPoint(golemX, memberX, null);
                String where = "golem " + golemX + " member " + memberX + " rest " + rest.x;
                assertEquals(floor.y(), rest.y, where);
                assertTrue(rest.x >= floor.leftX() && rest.x <= floor.rightX(), where);
                assertEquals(memberX < golemX, rest.x < golemX, "its own side: " + where);
                assertTrue(Math.abs(rest.x - golemX) >= Math.abs(memberX - golemX), "never toward it: " + where);
                int ownEnd = memberX < golemX ? floor.leftX() + margin : floor.rightX() - margin;
                if (Math.abs(ownEnd - golemX) > reach || Math.abs(memberX - golemX) > reach) {
                    assertTrue(Math.abs(rest.x - golemX) > reach, "out of reach: " + where);
                    outOfReach++;
                }
            }
        }
        assertTrue(outOfReach > 1_000, "most cases have room out of reach: " + outOfReach);
    }

    @Test
    void theRestPointCases() {
        assertEquals(new Point(-540, 150), restPoint(-230, -540, null),
                "Halvard 05:44, pinned at the wall: he holds there instead of crossing to x 1020");
        assertEquals(new Point(-540, 150), restPoint(-502, -540, null), "Kestrel 05:46, 38 px from the golem");
        assertEquals(new Point(-480, 150), restPoint(-230, -300, null), "its side's end even when that end is in reach");
        assertEquals(new Point(1_020, 150), restPoint(392, 400, null), "beside the golem: the end of its own side");
        assertEquals(new Point(-480, 150), restPoint(392, 300, null), "the left end, out of reach");
        assertEquals(new Point(-100, 150), restPoint(500, -100, null), "already out of reach: it holds its spot");
        assertEquals(new Point(-100, 150), restPoint(500, new Point(-100, 60), null), "airborne: the floor below it");

        Point stored = new Point(-480, 150);
        assertSame(stored, restPoint(100, -470, stored), "a stored point out of reach on its side is kept");
        assertEquals(new Point(1_020, 150), restPoint(100, 200, stored),
                "a stored point across the golem is left for the end of its own side");
        assertEquals(new Point(1_020, 150), restPoint(-100, -90, stored),
                "the golem walked past it: the end of its new side, away from the golem");
        Point pinned = new Point(-540, 150);
        assertSame(pinned, restPoint(-400, -500, pinned), "a pinned rester keeps the farther point it already has");
        assertEquals(new Point(-480, 150), restPoint(-100, -300, new Point(-380, 150)),
                "a stored point the golem walked onto: the end of its side");
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
        vitals(member, x, hp, 4_400, potions);
    }

    private void vitals(Character member, int x, int hp, int maxHp, int potions) {
        when(member.getPosition()).thenReturn(new Point(x, 150));
        when(member.getHp()).thenReturn(hp);
        when(member.getCurrentMaxHp()).thenReturn(maxHp);
        when(run.actions.hpPotionCount(member)).thenReturn(potions);
    }

    private static String rest(AgentEpqSession session, String state, String why, String hp, int potions, int drinks,
                               String restPoint, String golem) {
        return "EPQ boss rest: session=" + session.sessionId() + " member=Tobi state=" + state + " why=" + why
                + " hp=" + hp + " hpPots=" + potions + " drinks=" + drinks + " restPoint=" + restPoint
                + " golem=" + golem;
    }

    /** Under REST_HP_FRACTION with a full stock it backs off on its side, drinks on the way and rejoins at 80 %. */
    @Test
    void aMemberUnderTheRestHpBacksOffDrinksUpAndRejoinsAtTheResumeHp() {
        AgentEpqSession session = boss();
        golemAt(392);
        vitals(tobi, 400, 2_000, 200); // 45 %, 200 potions
        AgentRuntimeEntry entry = run.entry(tobi);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickMember(session, entry, tobi, 2_000L);
            verify(run.actions).navigate(entry, new Point(1_020, 150), true);
            verify(run.actions).useItem(tobi, WHITE_POTION);
            verify(run.actions, never()).grind(eq(entry), any());

            vitals(tobi, 700, 3_519, 199); // still under 80 %
            AgentEpqCoordinator.tickMember(session, entry, tobi, 2_700L);
            verify(run.actions, times(2)).useItem(tobi, WHITE_POTION);
            verify(run.actions, never()).grind(eq(entry), any());

            vitals(tobi, 760, 3_520, 198); // 80 %: back into the fight
            AgentEpqCoordinator.tickMember(session, entry, tobi, 3_400L);
            verify(run.actions).grind(entry, AgentEpqDefinition.BOSS_COMBAT_TARGETS);
            verify(run.actions, times(2)).useItem(tobi, WHITE_POTION);

            assertEquals(List.of(
                    rest(session, "START", "hp", "2000/4400", 200, 0, "(1020,150)", "(392,150)"),
                    rest(session, "END", "safe", "3520/4400", 198, 2, "(1020,150)", "(392,150)")),
                    lines.startingWith("EPQ boss rest:"));
        }
    }

    /** One drink every TOP_UP_INTERVAL_MS while it rests under RESUME_HP_FRACTION, not one every pass. */
    @Test
    void aResterDrinksOnePotionEveryTopUpInterval() {
        AgentEpqSession session = boss();
        golemAt(392);
        vitals(tobi, 1_020, 1_000, 3); // out of potions at its point
        AgentRuntimeEntry entry = run.entry(tobi);

        AgentEpqCoordinator.tickMember(session, entry, tobi, 2_000L);
        AgentEpqCoordinator.tickMember(session, entry, tobi, 2_000L + AgentEpqRecoveryPolicy.TOP_UP_INTERVAL_MS - 1);
        verify(run.actions, times(1)).useItem(tobi, WHITE_POTION);
        AgentEpqCoordinator.tickMember(session, entry, tobi, 2_000L + AgentEpqRecoveryPolicy.TOP_UP_INTERVAL_MS);
        verify(run.actions, times(2)).useItem(tobi, WHITE_POTION);

        vitals(tobi, 1_020, 3_600, 1); // over 80 % with one potion left: it keeps it and still rests
        AgentEpqCoordinator.tickMember(session, entry, tobi, 2_000L + 3 * AgentEpqRecoveryPolicy.TOP_UP_INTERVAL_MS);
        verify(run.actions, times(2)).useItem(tobi, WHITE_POTION);
        verify(run.actions, never()).grind(eq(entry), any());
        assertTrue(session.member(tobi.getId()).bossResting());
    }

    /**
     * Halvard 05:44 and 06:46: the stack a party member shares (158, 110) comes 2-3 s after he asks, at 15-22 % HP.
     * He keeps resting and drinks up to RESUME_HP_FRACTION before he rejoins.
     */
    @Test
    void sharedPotionsAtLowHpDoNotEndTheRest() {
        AgentEpqSession session = boss();
        golemAt(-230);
        vitals(tobi, 700, 2_070, 5_904, 0);
        AgentRuntimeEntry entry = run.entry(tobi);
        AgentEpqCoordinator.tickMember(session, entry, tobi, 2_000L);
        verify(run.actions, never()).useItem(any(), anyInt());

        vitals(tobi, 1_020, 1_286, 5_904, 158); // the share lands
        AgentEpqCoordinator.tickMember(session, entry, tobi, 8_000L);

        assertTrue(session.member(tobi.getId()).bossResting());
        verify(run.actions).useItem(tobi, WHITE_POTION);
        verify(run.actions, never()).grind(eq(entry), any());
    }

    /** Halvard 05:44 and Kestrel 05:46 went down at x -540 walking toward x 1020 across the golem. */
    @Test
    void aMemberPinnedAtTheWallHoldsThereAndNeverCrossesTheGolem() {
        AgentEpqSession session = boss();
        golemAt(-230);
        vitals(tobi, -540, 2_070, 5_904, 0);
        AgentRuntimeEntry entry = run.entry(tobi);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickMember(session, entry, tobi, 2_000L);

            verify(run.actions, never()).navigate(eq(entry), any(), anyBoolean());
            verify(run.actions).stop(entry);
            verify(run.actions, never()).grind(eq(entry), any());
            assertEquals(List.of(rest(session, "START", "pots", "2070/5904", 0, 0, "(-540,150)", "(-230,150)")),
                    lines.startingWith("EPQ boss rest:"));
        }
    }

    @Test
    void aMemberAlreadyOutOfReachHoldsItsSpot() {
        AgentEpqSession session = boss();
        golemAt(500);
        vitals(tobi, -100, 1_000, 200);
        AgentRuntimeEntry entry = run.entry(tobi);

        AgentEpqCoordinator.tickMember(session, entry, tobi, 2_000L);

        verify(run.actions, never()).navigate(eq(entry), any(), anyBoolean());
        verify(run.actions).stop(entry);
        verify(run.actions).useItem(tobi, WHITE_POTION);
        assertEquals(new Point(-100, 150), session.member(tobi.getId()).bossRestPoint());
    }

    @Test
    void aGolemWalkingOntoTheRestPointNeverSendsTheResterAcross() {
        AgentEpqSession session = boss();
        Monster golem = golemAt(392);
        vitals(tobi, 1_020, 1_000, 3);
        AgentRuntimeEntry entry = run.entry(tobi);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickMember(session, entry, tobi, 2_000L);
            when(golem.getPosition()).thenReturn(new Point(700, 150));
            AgentEpqCoordinator.tickMember(session, entry, tobi, 3_000L);

            verify(run.actions, never()).navigate(eq(entry), eq(new Point(-480, 150)), anyBoolean());
            verify(run.actions, times(2)).stop(entry);
            assertEquals(List.of(), lines.startingWith("EPQ boss rest: session=" + session.sessionId()
                    + " member=Tobi state=MOVE"));
        }
    }

    /** The golem walks past a rester at its point: the rester turns for the end of its new side, away from it. */
    @Test
    void aGolemWalkingPastTheResterSendsItAwayAndSaysSo() {
        AgentEpqSession session = boss();
        Monster golem = golemAt(0);
        vitals(tobi, -480, 1_000, 3);
        AgentRuntimeEntry entry = run.entry(tobi);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickMember(session, entry, tobi, 2_000L);
            when(golem.getPosition()).thenReturn(new Point(-530, 150));
            AgentEpqCoordinator.tickMember(session, entry, tobi, 3_000L);

            verify(run.actions).navigate(entry, new Point(1_020, 150), true);
            assertEquals(1, lines.startingWith("EPQ boss rest: session=" + session.sessionId()
                    + " member=Tobi state=MOVE").size(), lines.startingWith("EPQ boss rest:")::toString);
        }
    }

    /** A companion run's rester says once a run that it backs off, and once that it is out of potions. */
    @Test
    void aCompanionSaysOnceThatItBacksOffAndOnceThatItIsOutOfPotions() {
        AgentEpqSession session = boss();
        session.setMemberCoordinationChatEnabled(true);
        golemAt(392);
        AgentRuntimeEntry entry = run.entry(tobi);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqDialogue.class)) {
            vitals(tobi, 400, 2_000, 200);
            AgentEpqCoordinator.tickMember(session, entry, tobi, 2_000L); // START: hp
            vitals(tobi, 1_020, 4_000, 200);
            AgentEpqCoordinator.tickMember(session, entry, tobi, 3_000L); // END
            vitals(tobi, 400, 2_000, 200);
            AgentEpqCoordinator.tickMember(session, entry, tobi, 4_000L); // START: hp, said already
            vitals(tobi, 1_020, 4_000, 200);
            AgentEpqCoordinator.tickMember(session, entry, tobi, 5_000L); // END
            vitals(tobi, 400, 4_000, 3);
            AgentEpqCoordinator.tickMember(session, entry, tobi, 6_000L); // START: pots

            String id = session.sessionId();
            assertEquals(List.of(
                    "EPQ line: session=" + id + " key=boss-rest-3 speaker=Tobi text="
                            + AgentEpqCompanionPlay.BACKING_OFF,
                    "EPQ line: session=" + id + " key=boss-rest-dry-3 speaker=Tobi text="
                            + AgentEpqCompanionPlay.OUT_OF_POTS),
                    lines.startingWith("EPQ line:").stream().filter(line -> line.contains(" key=boss-rest")).toList());
        }
    }

    @Test
    void aStockRunsResterSaysNothing() {
        when(run.actions.liveMonsterCount(any(), eq(AgentEpqDefinition.BOSS_MOBS))).thenReturn(1);
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, false,
                AgentEpqSession.Phase.BOSS, ben, mira, tobi, una);
        session.setMemberCoordinationChatEnabled(true);
        golemAt(392);
        vitals(tobi, 400, 2_000, 3);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqDialogue.class)) {
            AgentEpqCoordinator.tickMember(session, run.entry(tobi), tobi, 2_000L);

            assertTrue(session.member(tobi.getId()).bossResting());
            assertEquals(List.of(), lines.startingWith("EPQ line:"));
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
    void aMemberWithPotionsAndHalfItsHpKeepsTodaysGrind() {
        AgentEpqSession session = boss();
        golemAt(392);
        vitals(tobi, 400, 2_200, 40); // 50 %: the autopot still keeps up
        vitals(una, 400, 4_000, POT_STOP); // POT_STOP potions and healthy

        AgentEpqCoordinator.tickMember(session, run.entry(tobi), tobi, 2_000L);
        AgentEpqCoordinator.tickMember(session, run.entry(una), una, 2_000L);

        verify(run.actions).grind(run.entry(tobi), AgentEpqDefinition.BOSS_COMBAT_TARGETS);
        verify(run.actions).grind(run.entry(una), AgentEpqDefinition.BOSS_COMBAT_TARGETS);
        verify(run.actions, never()).navigate(any(), any(), anyBoolean());
    }

    /**
     * The golem chain spawns by Mob.wz revive after each die animation, so for a moment between 9300180 and 9300181
     * no golem lives. A rester at 50 % with 5 potions keeps resting through that gap (it may top up there) and does not
     * walk back into melee when 9300181 appears: it rejoins only at RESUME_HP_FRACTION with POT_STOP potions.
     */
    @Test
    void aRestLastsThroughTheGolemsRevive() {
        AgentEpqSession session = boss();
        Monster first = golemAt(392);
        vitals(tobi, 400, 1_320, 5); // 30 %, 5 potions: rests
        AgentRuntimeEntry entry = run.entry(tobi);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
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
