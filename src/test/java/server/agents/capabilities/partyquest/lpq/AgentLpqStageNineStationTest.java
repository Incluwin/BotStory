package server.agents.capabilities.partyquest.lpq;

import org.junit.jupiter.api.Test;
import provider.Data;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import server.agents.capabilities.combat.AgentProjectileHitbox;
import server.agents.capabilities.partyquest.lpq.AgentLpqStageNineBossPolicy.Band;
import server.agents.capabilities.partyquest.lpq.AgentLpqStageNineBossPolicy.Floor;
import server.agents.capabilities.partyquest.lpq.AgentLpqStageNineBossPolicy.Group;
import server.agents.capabilities.partyquest.lpq.AgentLpqStageNineBossPolicy.Side;
import server.agents.capabilities.partyquest.lpq.AgentLpqStageNineBossPolicy.Targets;

import java.awt.Point;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LPQ base fix 5: stage-9 shooters fight Alishar from outside his reach. Pins the band against Alishar's WZ reach and
 * the projectile range, the floor against the map's footholds, the side and station choice (both with hysteresis),
 * the target tiers, and the coordinator's wiring.
 */
class AgentLpqStageNineStationTest {
    private static final Band BAND = AgentLpqStageNineBossPolicy.band();
    private static final Floor FLOOR = AgentLpqCoordinator.stageNineFloor();
    private static final Set<Integer> ALISHAR = Set.of(AgentLpqDefinition.ALISHAR);

    private static Point boss(int x) {
        return new Point(x, 184);
    }

    @Test
    void theBandClearsAlisharsReachAndStaysInProjectileRange() {
        Data alishar = DataProviderFactory.getDataProvider(WZFiles.MOB).getData("9300012.img");
        int attackReach = Math.abs(DataTool.getPoint("attack1/info/range/lt", alishar).x);
        assertEquals(320, attackReach);
        List<String> debuffs = new ArrayList<>();
        for (Data skill : alishar.getChildByPath("info/skill").getChildren()) {
            debuffs.add(DataTool.getInt("skill", skill, 0) + "/" + DataTool.getInt("level", skill, 0));
        }
        assertTrue(debuffs.containsAll(List.of("120/1", "121/1")), "Seal and Darkness at level 1: " + debuffs);
        Data mobSkill = DataProviderFactory.getDataProvider(WZFiles.SKILL).getData("MobSkill.img");
        int debuffHalfWidth = 0;
        for (String id : List.of("120", "121")) {
            Data level = mobSkill.getChildByPath(id + "/level/1");
            debuffHalfWidth = Math.max(debuffHalfWidth, Math.max(Math.abs(DataTool.getPoint("lt", level).x),
                    Math.abs(DataTool.getPoint("rb", level).x)));
        }
        assertEquals(300, debuffHalfWidth);

        assertEquals(Math.max(attackReach, debuffHalfWidth), AgentLpqStageNineBossPolicy.reachPx(), "his reach");
        assertTrue(BAND.minPx() > AgentLpqStageNineBossPolicy.reachPx(), "a station is out of his reach");
        assertTrue(FLOOR.usableRightX() - FLOOR.usableLeftX() > 2 * AgentLpqStageNineBossPolicy.reachPx(),
                "one floor end is always out of his reach");
        assertTrue(BAND.minPx() < BAND.maxPx());
        assertTrue(BAND.maxPx() <= AgentProjectileHitbox.CLIENT_PROJECTILE_BASE_RANGE, "and inside a shot's range");
    }

    @Test
    void theFloorIsTheBossMapsMainFoothold() {
        Data map = DataProviderFactory.getDataProvider(WZFiles.MAP).getData("Map/Map9/922010900.img");
        int left = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE;
        Set<Integer> heights = new java.util.HashSet<>();
        for (Data layer : map.getChildByPath("foothold").getChildren()) {
            for (Data group : layer.getChildren()) {
                for (Data foothold : group.getChildren()) {
                    int id = Integer.parseInt(foothold.getName());
                    if (id < 95 || id > 100) continue;
                    left = Math.min(left, Math.min(DataTool.getInt("x1", foothold), DataTool.getInt("x2", foothold)));
                    right = Math.max(right, Math.max(DataTool.getInt("x1", foothold), DataTool.getInt("x2", foothold)));
                    heights.add(DataTool.getInt("y1", foothold));
                    heights.add(DataTool.getInt("y2", foothold));
                }
            }
        }
        assertEquals(-310, left);
        assertEquals(1_210, right);
        assertEquals(Set.of(184), heights);
        assertEquals(new Floor(left, right, 184, AgentLpqStageNineBossPolicy.floorEdgeMarginPx()), FLOOR);
    }

    @Test
    void everyBossPositionGivesEveryShooterAStationThatFitsInsideTheBand() {
        for (int x = FLOOR.leftX(); x <= FLOOR.rightX(); x += 5) {
            Point boss = boss(x);
            for (Group group : Group.values()) {
                for (Side home : Side.values()) {
                    Side preferred = AgentLpqStageNineBossPolicy.preferredSide(group, home);
                    for (Side current : Arrays.asList(null, Side.LEFT, Side.RIGHT)) {
                        Side chosen = AgentLpqStageNineBossPolicy.chosenSide(current, preferred, boss, BAND, FLOOR);
                        assertTrue(AgentLpqStageNineBossPolicy.fits(chosen, boss, 0, BAND, FLOOR),
                                () -> "boss " + boss.x + " " + group + " home " + home + " current " + current);
                        Point station = AgentLpqStageNineBossPolicy.nextStation(boss, null, chosen, BAND, FLOOR);
                        int dx = Math.abs(station.x - boss.x);
                        assertTrue(dx >= BAND.minPx() && dx <= BAND.maxPx(), () -> "boss " + boss.x + " " + station);
                        assertTrue(station.x >= FLOOR.usableLeftX() && station.x <= FLOOR.usableRightX(),
                                () -> "boss " + boss.x + " " + station);
                        assertEquals(184, station.y);
                        assertEquals(chosen, AgentLpqStageNineBossPolicy.sideOf(station, boss));
                    }
                }
            }
        }
    }

    @Test
    void theStepZeroPositions() {
        Side bPreferred = AgentLpqStageNineBossPolicy.preferredSide(AgentLpqStageNineBossPolicy.group(1), Side.LEFT);
        Side aPreferred = AgentLpqStageNineBossPolicy.preferredSide(AgentLpqStageNineBossPolicy.group(0), Side.LEFT);

        Side a = AgentLpqStageNineBossPolicy.chosenSide(null, aPreferred, boss(858), BAND, FLOOR);
        assertEquals(new Point(491, 184), AgentLpqStageNineBossPolicy.nextStation(boss(858), null, a, BAND, FLOOR));
        Side b = AgentLpqStageNineBossPolicy.chosenSide(null, bPreferred, boss(858), BAND, FLOOR);
        assertEquals(Side.LEFT, b, "the right station (1225) is past the right margin");
        assertEquals(new Point(491, 184), AgentLpqStageNineBossPolicy.nextStation(boss(858), null, b, BAND, FLOOR));

        Side back = AgentLpqStageNineBossPolicy.chosenSide(Side.LEFT, bPreferred, boss(700), BAND, FLOOR);
        assertEquals(Side.RIGHT, back, "1067 fits by 83 >= 45: group B goes back to its side");
        assertEquals(new Point(1_067, 184), AgentLpqStageNineBossPolicy.nextStation(boss(700), null, back, BAND, FLOOR));

        Side nearLeft = AgentLpqStageNineBossPolicy.chosenSide(null, aPreferred, boss(100), BAND, FLOOR);
        assertEquals(Side.RIGHT, nearLeft, "-267 is past the left margin");
        assertEquals(new Point(467, 184), AgentLpqStageNineBossPolicy.nextStation(boss(100), null, nearLeft, BAND,
                FLOOR));
    }

    @Test
    void aStationIsKeptWhileItStaysInsideTheBand() {
        Point stored = new Point(140, 184);
        assertEquals(stored, AgentLpqStageNineBossPolicy.nextStation(boss(500), stored, Side.LEFT, BAND, FLOOR),
                "360 px away: kept");
        assertEquals(new Point(113, 184),
                AgentLpqStageNineBossPolicy.nextStation(boss(480), stored, Side.LEFT, BAND, FLOOR), "340 px: too near");
        assertEquals(new Point(168, 184),
                AgentLpqStageNineBossPolicy.nextStation(boss(535), stored, Side.LEFT, BAND, FLOOR), "395 px: too far");
        assertEquals(new Point(867, 184),
                AgentLpqStageNineBossPolicy.nextStation(boss(500), stored, Side.RIGHT, BAND, FLOOR), "other side");
    }

    @Test
    void alisharSteppingAroundAThresholdChangesASideAtMostOnce() {
        // The right station fits while Alishar stands at x <= 783 (783 + 367 = 1150).
        int[] steps = {763, 803, 763, 803, 770, 796, 763, 803, 783, 784, 763};
        for (Side start : Side.values()) {
            Side current = start;
            int changes = 0;
            for (int x : steps) {
                Side next = AgentLpqStageNineBossPolicy.chosenSide(current, Side.RIGHT, boss(x), BAND, FLOOR);
                assertTrue(AgentLpqStageNineBossPolicy.fits(next, boss(x), 0, BAND, FLOOR), "boss " + x);
                if (next != current) changes++;
                current = next;
            }
            assertTrue(changes <= 1, "started " + start + ", changed " + changes + " times");
        }
    }

    @Test
    void targetsThreatsAndShooters() {
        assertEquals(new Targets(ALISHAR, AgentLpqDefinition.ALISHAR_SUMMONS),
                AgentLpqStageNineBossPolicy.targets(true, false));
        assertEquals(new Targets(AgentLpqDefinition.ALISHAR_SUMMONS, ALISHAR),
                AgentLpqStageNineBossPolicy.targets(true, true));
        assertEquals(new Targets(AgentLpqDefinition.ALISHAR_SUMMONS, ALISHAR),
                AgentLpqStageNineBossPolicy.targets(false, false));
        for (boolean shooter : new boolean[]{true, false}) {
            for (boolean threat : new boolean[]{true, false}) {
                Targets targets = AgentLpqStageNineBossPolicy.targets(shooter, threat);
                Set<Integer> all = new java.util.HashSet<>(targets.preferred());
                all.addAll(targets.fallback());
                assertTrue(all.containsAll(AgentLpqDefinition.ALISHAR_SUMMONS) && all.contains(AgentLpqDefinition.ALISHAR));
            }
        }

        Point agent = new Point(491, 184);
        assertTrue(AgentLpqStageNineBossPolicy.addThreat(agent, List.of(new Point(591, 84)), 100));
        assertFalse(AgentLpqStageNineBossPolicy.addThreat(agent, List.of(new Point(592, 184)), 100));
        assertFalse(AgentLpqStageNineBossPolicy.addThreat(agent, List.of(new Point(491, 285)), 100));
        assertFalse(AgentLpqStageNineBossPolicy.addThreat(agent, List.of(), 100));

        assertFalse(AgentLpqStageNineBossPolicy.shooter(false, false), "a dagger rogue with Lucky Seven is melee");
        assertTrue(AgentLpqStageNineBossPolicy.shooter(true, false), "a magician");
        assertTrue(AgentLpqStageNineBossPolicy.shooter(false, true), "a bow, crossbow, claw or gun");
    }

    @Test
    void shootersAlternateGroups() {
        assertEquals(List.of(Group.A, Group.B, Group.A, Group.B),
                List.of(AgentLpqStageNineBossPolicy.group(0), AgentLpqStageNineBossPolicy.group(1),
                        AgentLpqStageNineBossPolicy.group(2), AgentLpqStageNineBossPolicy.group(3)));
        assertEquals(Side.LEFT, AgentLpqStageNineBossPolicy.preferredSide(Group.A, Side.LEFT));
        assertEquals(Side.RIGHT, AgentLpqStageNineBossPolicy.preferredSide(Group.B, Side.LEFT));
    }

    // ---- wiring (source scan) ----

    private static String source() throws Exception {
        return Files.readString(Path.of(
                "src/main/java/server/agents/capabilities/partyquest/lpq/AgentLpqCoordinator.java"))
                .replace("\r\n", "\n");
    }

    /** The text of the block opened by the first '{' at or after {@code from}, through its matching '}'. */
    static String block(String text, int from) {
        int open = text.indexOf('{', from);
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            if (text.charAt(i) == '{') depth++;
            else if (text.charAt(i) == '}' && --depth == 0) return text.substring(open, i + 1);
        }
        throw new AssertionError("unbalanced block at " + from);
    }

    static String method(String text, String header) {
        int at = text.indexOf(header);
        assertTrue(at >= 0, "no " + header);
        return block(text, at);
    }

    static final List<String> RESCUE_CALLS = List.of("stagePosition(", "registerPlayer(", "changeMapNear(",
            "killMonsterWithDrops(", "hitReactor(", "addItem(", "removeById(", "removeItemDrop(", "grantItem(",
            "rescueTeleport(", "vacuum", "moveWithinEvent(", "restoreEventMember(", "killWithDrops(",
            "remoteBoxHits(", "forcePassTransferToLeader(");

    @Test
    void theAlisharBranchFightsFromStations() throws Exception {
        String source = source();
        String boss = method(source, "    private static void boss(");
        String branch = block(boss, boss.indexOf("if (alisharAlive) {"));
        int hp = branch.indexOf("if (agent.getHp() <= 0) continue;");
        assertTrue(hp > 0, "a held corpse gets no order");
        assertTrue(branch.indexOf("ACTIONS.") > hp, "nothing is ordered before the HP check");
        assertTrue(branch.contains("fightAlishar("), branch);
        assertFalse(branch.contains("ACTIONS.grind("), "the shared boss grind is gone");

        // fightAlishar applies AgentLpqStageNineBossPolicy.order as is: AgentLpqStageNineOrderTest pins both.
        String fight = method(source, "    private static void fightAlishar(");

        String shooter = method(source, "    private static boolean stageNineShooter(");
        assertTrue(shooter.replaceAll("\\s+", "").contains("AgentLpqStageNineBossPolicy.shooter("
                + "AgentLpqRosterRequirementPolicy.magicAttack(agent),ACTIONS.rangedWeapon(agent))"));
        assertFalse(shooter.contains("rangedAttack("), "a Lucky Seven dagger rogue is not a shooter");

        for (String body : List.of(branch, fight, shooter, method(source, "    private static List<Integer> "
                + "stageNineShooterIds("))) {
            for (String rescue : RESCUE_CALLS) assertFalse(body.contains(rescue), "no rescue: " + rescue);
        }

        String policy = Files.readString(Path.of(
                "src/main/java/server/agents/capabilities/partyquest/lpq/AgentLpqStageNineBossPolicy.java"));
        assertFalse(policy.lines().anyMatch(line -> line.startsWith("import server.agents.capabilities.")),
                "the policy imports no capability package");
    }
}
