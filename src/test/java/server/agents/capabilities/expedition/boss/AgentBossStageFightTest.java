package server.agents.capabilities.expedition.boss;

import client.Character;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.runtime.AgentRuntimeEntry;
import server.life.Monster;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P18: Horntail's fight runs over three maps. On a pre-head map the members fight its head; once the map holds no live
 * monster but never-targets, they walk to next00 and take it (the stock seal lets them through once the head fell), at
 * most every STAGE_PORTAL_EVERY_MS. On the altar every member follows the focus order (2026-10-14): the legs, the
 * tail, then the wings and hands from the two low ledges, then the heads from the three high ones. The altar is no
 * stage map, so with nothing up there (before the reactor) members hold. Read from the shipped boss rules.
 */
class AgentBossStageFightTest {
    private static final int FIRST_STAGE = 240_060_000;
    private static final int SECOND_STAGE = 240_060_100;
    private static final int ALTAR = 240_060_200;
    private static final int LEFT_HEAD = 8_810_000;
    private static final int RIGHT_HEAD = 8_810_001;
    private static final int HEAD_A = 8_810_002;
    private static final int HEAD_B = 8_810_003;
    private static final int HEAD_C = 8_810_004;
    private static final int LEFT_HAND = 8_810_005;
    private static final int RIGHT_HAND = 8_810_006;
    private static final int WINGS = 8_810_007;
    private static final int WYVERN = 8_810_020;
    private static final int LEGS = 8_810_008;
    private static final int TAIL = 8_810_009;
    private static final int HORNTAIL_HP = 8_810_018;
    private static final int INTRO = 8_810_026;
    private static final Point NEXT00 = new Point(832, -166);

    private final PrimitiveCapabilityGateway gateway = mock(PrimitiveCapabilityGateway.class);
    private final Map<Integer, MapleMap> maps = new HashMap<>();
    private final Map<MapleMap, List<Monster>> monsters = new HashMap<>();
    private final Map<Integer, AgentRuntimeEntry> entries = new HashMap<>();
    private final Set<Integer> melee = new HashSet<>();
    private final List<Integer> portalTries = new ArrayList<>();
    private final Set<Integer> guardDown = new HashSet<>();
    /** Members whose attacks reach no part from anywhere (Ports.reaches); every other member reaches all. */
    private final Set<Integer> outOfReach = new HashSet<>();
    private final EventInstanceManager event = mock(EventInstanceManager.class);
    private AgentBossPriorityFight fight;

    @BeforeEach
    void setUp() {
        fight = new AgentBossPriorityFight("horntail", new AgentBossPriorityFight.Ports() {
            @Override
            public PrimitiveCapabilityGateway gateway() {
                return gateway;
            }

            @Override
            public AgentRuntimeEntry entry(int characterId) {
                return entries.get(characterId);
            }

            @Override
            public List<Monster> monsters(MapleMap on) {
                return monsters.getOrDefault(on, List.of());
            }

            @Override
            public boolean hittable(Monster monster) {
                return true;
            }

            @Override
            public boolean melee(Character member) {
                return melee.contains(member.getId());
            }

            @Override
            public Point portalPosition(Character member, String name) {
                return "next00".equals(name) && member.getMapId() != ALTAR ? NEXT00 : null;
            }

            @Override
            public boolean enterPortal(Character member, String name) {
                portalTries.add(member.getId());
                return true;
            }

            @Override
            public boolean castSurvivalBuff(Character member) {
                return guardDown.remove(member.getId());
            }

            @Override
            public boolean reaches(Character member, Monster part, Point stand) {
                return !outOfReach.contains(member.getId());
            }
        });
    }

    @Test
    void theShippedRulesNameTheTwoPreHeadMapsTheirPortalTheMeleeOrderAndTheLeafreWarp() {
        AgentBossRules.Boss rules = AgentBossRules.of("horntail");
        assertEquals(List.of(FIRST_STAGE, SECOND_STAGE), rules.stageMaps());
        assertEquals("next00", AgentBossRules.stagePortal("horntail"));
        assertEquals(240_000_000, AgentBossRules.reentryWarpFrom("horntail"));
        assertEquals(LEGS, rules.meleePriority().get(2), "melee: the pre-heads, then the legs");
        assertTrue(rules.neverTarget().containsAll(List.of(HORNTAIL_HP, 8_810_024, 8_810_025, INTRO)));
        assertTrue(AgentBossRunCatalog.HORNTAIL.fightMap(FIRST_STAGE));
        assertTrue(AgentBossRunCatalog.HORNTAIL.fightMap(ALTAR));
        assertFalse(AgentBossRunCatalog.HORNTAIL.fightMap(240_050_400), "the recruit map is no fight map");
        assertFalse(AgentBossRunCatalog.SCARGA.fightMap(FIRST_STAGE), "other bosses fight on their battle map only");
        assertNull(AgentBossRules.stagePortal("scarga"));
    }

    @Test
    void onAPreHeadMapMembersFightItsHeadFromTheTopPlatformSpreadOverIt() {
        // 2026-10-14: the user saw members hit the heads from the floor when the top platform put them in reach
        mobs(FIRST_STAGE, LEFT_HEAD);
        groundAtAnchors();
        AgentRuntimeEntry first = entry(10);
        AgentRuntimeEntry second = entry(11);

        fight.tick(List.of(member(10, FIRST_STAGE, new Point(500, 260)), member(11, FIRST_STAGE, new Point(600, 260))),
                event, 0L);

        verify(gateway).grindFromAnchor(first, new Point(820, -160), Set.of(LEFT_HEAD), Set.of());
        verify(gateway).grindFromAnchor(second, new Point(900, -160), Set.of(LEFT_HEAD), Set.of());
        assertTrue(portalTries.isEmpty());
    }

    @Test
    void theRightHeadFromItsTopPlatformToo() {
        mobs(SECOND_STAGE, RIGHT_HEAD);
        groundAtAnchors();
        AgentRuntimeEntry thane = entry(10);

        fight.tick(List.of(member(10, SECOND_STAGE, new Point(0, 260))), event, 0L);

        verify(gateway).grindFromAnchor(thane, new Point(-370, -160), Set.of(RIGHT_HEAD), Set.of());
    }

    @Test
    void aPreHeadPlatformWithNoGroundFallsBackToGrindingTheHead() {
        mobs(FIRST_STAGE, LEFT_HEAD);
        AgentRuntimeEntry thane = entry(10);

        fight.tick(List.of(member(10, FIRST_STAGE, new Point(500, 260))), event, 0L);

        verify(gateway).grind(thane, Set.of(LEFT_HEAD), Set.of());
    }

    @Test
    void onceTheHeadFellAMemberWalksToNext00AndTakesItAtMostEveryInterval() {
        mobs(FIRST_STAGE, INTRO);
        AgentRuntimeEntry thane = entry(10);
        Character member = member(10, FIRST_STAGE, new Point(200, 120));

        fight.tick(List.of(member), event, 0L);
        verify(gateway).navigate(thane, NEXT00, true);
        assertTrue(portalTries.isEmpty(), "too far to take it");

        when(member.getPosition()).thenReturn(new Point(NEXT00));
        long every = AgentBossPriorityFight.stagePortalEveryMs();
        fight.tick(List.of(member), event, 1_000L);
        fight.tick(List.of(member), event, 1_000L + every - 1);
        assertEquals(List.of(10), portalTries, "one try per interval");
        fight.tick(List.of(member), event, 1_000L + every);
        assertEquals(List.of(10, 10), portalTries);
        verify(gateway, never()).grind(any(AgentRuntimeEntry.class), anySet(), anySet());
    }

    @Test
    void theSecondPreHeadMapIsAStageToo() {
        mobs(SECOND_STAGE);
        AgentRuntimeEntry thane = entry(10);

        fight.tick(List.of(member(10, SECOND_STAGE, new Point(NEXT00))), event, 0L);

        verify(gateway).stop(thane);
        assertEquals(List.of(10), portalTries);
    }

    @Test
    void besideTheStagePortalAMemberWithoutMagicGuardCastsItBeforeTakingThePortal() {
        mobs(SECOND_STAGE);
        entry(10);
        guardDown.add(10);
        Character ilse = member(10, SECOND_STAGE, new Point(NEXT00));

        fight.tick(List.of(ilse), event, 0L);
        assertTrue(portalTries.isEmpty(), "the guard first");

        fight.tick(List.of(ilse), event, 600L);
        assertEquals(List.of(10), portalTries);
    }

    @Test
    void onTheAltarEveryMemberTakesTheLegsFirst() {
        mobs(ALTAR, HEAD_A, HEAD_B, LEGS, TAIL, HORNTAIL_HP);
        AgentRuntimeEntry ranged = entry(10);
        AgentRuntimeEntry warrior = entry(11);
        melee.add(11);

        fight.tick(List.of(member(10, ALTAR, new Point(0, 260)), member(11, ALTAR, new Point(0, 260))), event, 0L);

        verify(gateway).grind(ranged, Set.of(LEGS), Set.of());
        verify(gateway).grind(warrior, Set.of(LEGS), Set.of());
    }

    @Test
    void withTheLegsDownEveryMemberTakesTheTailAndAnAddIsTheFallback() {
        mobs(ALTAR, HEAD_A, TAIL, WINGS, WYVERN);
        AgentRuntimeEntry ranged = entry(10);

        fight.tick(List.of(member(10, ALTAR, new Point(-300, 260))), event, 0L);

        verify(gateway).grind(ranged, Set.of(TAIL), Set.of(WYVERN));
    }

    @Test
    void thenTheWingsAndHandsFromTheTwoLowLedgesSpreadOverThem() {
        mobs(ALTAR, HEAD_A, WINGS, LEFT_HAND, RIGHT_HAND, WYVERN);
        groundAtAnchors();
        AgentRuntimeEntry first = entry(10);
        AgentRuntimeEntry second = entry(11);
        melee.add(11);

        fight.tick(List.of(member(10, ALTAR, new Point(-300, 260)), member(11, ALTAR, new Point(-300, 260))),
                event, 0L);

        verify(gateway).grindFromAnchor(first, new Point(-235, 80), Set.of(LEFT_HAND, WINGS),
                Set.of(RIGHT_HAND, WYVERN));
        verify(gateway).grindFromAnchor(second, new Point(412, 80), Set.of(RIGHT_HAND, WINGS),
                Set.of(LEFT_HAND, WYVERN));
    }

    @Test
    void lastTheHeadsFromTheHighLedgesOnlyThoseThatReachALiveHead() {
        mobs(ALTAR, HEAD_B, HEAD_C);
        groundAtAnchors();
        AgentRuntimeEntry first = entry(10);
        AgentRuntimeEntry second = entry(11);
        AgentRuntimeEntry third = entry(12);

        fight.tick(List.of(member(10, ALTAR, new Point(-300, 260)), member(11, ALTAR, new Point(-300, 260)),
                member(12, ALTAR, new Point(-300, 260))), event, 0L);

        verify(gateway).grindFromAnchor(first, new Point(-55, -280), Set.of(HEAD_B), Set.of(HEAD_C));
        verify(gateway).grindFromAnchor(second, new Point(325, -160), Set.of(HEAD_C), Set.of(HEAD_B));
        verify(gateway).grindFromAnchor(third, new Point(-55, -280), Set.of(HEAD_B), Set.of(HEAD_C));
    }

    @Test
    void aStepWhoseLedgeHasNoGroundFallsBackToGrindingItsParts() {
        mobs(ALTAR, HEAD_A);
        AgentRuntimeEntry thane = entry(10);

        fight.tick(List.of(member(10, ALTAR, new Point(-300, 260))), event, 0L);

        verify(gateway).grind(thane, Set.of(HEAD_A), Set.of());
        verify(gateway, never()).grindFromAnchor(any(), any(), anySet(), anySet());
    }

    @Test
    void theShippedFocusOrderIsThePreHeadsThenLegsTailWingsAndHandsThenHeads() {
        List<AgentBossRules.Focus> focus = AgentBossRules.of("horntail").focus();

        assertEquals(List.of(List.of(LEFT_HEAD), List.of(RIGHT_HEAD), List.of(LEGS), List.of(TAIL),
                List.of(WINGS, LEFT_HAND, RIGHT_HAND), List.of(HEAD_A, HEAD_B, HEAD_C)),
                focus.stream().map(AgentBossRules.Focus::mobIds).toList());
        assertEquals(2, focus.get(0).anchors().size(), "the left head's top platform");
        assertEquals(2, focus.get(1).anchors().size(), "the right head's top platform");
        assertEquals("left", focus.get(2).side());
        assertEquals("left", focus.get(3).side());
        assertTrue(focus.get(2).anchors().isEmpty() && focus.get(3).anchors().isEmpty(), "the floor parts need none");
        assertEquals(2, focus.get(4).anchors().size());
        assertEquals(3, focus.get(5).anchors().size());
    }

    /** The gateway grounds a point on its own spot, as on a ledge's foothold. */

    /**
     * 2026-10-29 (P18 open item 18): once its head fell, a member on that head's ledge keeps shooting a live head from
     * there while it reaches one (a bow or claw from (-235,-160) reaches the middle head); a member that does not
     * reach from there goes to a ledge that names a live head, as before.
     */
    @Test
    void aMemberWhoseHeadFellKeepsItsLedgeWhileItStillReachesALiveHead() {
        mobs(ALTAR, HEAD_A, HEAD_B, HEAD_C);
        groundAtAnchors();
        AgentRuntimeEntry shooter = entry(10);
        AgentRuntimeEntry swinger = entry(13);
        List<Character> crew = List.of(member(10, ALTAR, new Point(-300, 260)), member(11, ALTAR, new Point(-300, 260)),
                member(12, ALTAR, new Point(-300, 260)), member(13, ALTAR, new Point(-300, 260)));
        fight.tick(crew, event, 0L);
        verify(gateway).grindFromAnchor(shooter, new Point(-235, -160), Set.of(HEAD_A), Set.of(HEAD_B, HEAD_C));
        verify(gateway).grindFromAnchor(swinger, new Point(-235, -160), Set.of(HEAD_A), Set.of(HEAD_B, HEAD_C));

        mobs(ALTAR, HEAD_B, HEAD_C);
        outOfReach.add(13);
        fight.tick(crew, event, 1L);

        verify(gateway).grindFromAnchor(shooter, new Point(-235, -160), Set.of(HEAD_B, HEAD_C), Set.of());
        verify(gateway).grindFromAnchor(swinger, new Point(325, -160), Set.of(HEAD_C), Set.of(HEAD_B));
    }

    private void groundAtAnchors() {
        when(gateway.groundPoint(any(), any())).thenAnswer(call -> new Point((Point) call.getArgument(1)));
    }

    @Test
    void onTheAltarBeforeTheReactorMembersHoldAndTakeNoPortal() {
        mobs(ALTAR);
        AgentRuntimeEntry thane = entry(10);

        fight.tick(List.of(member(10, ALTAR, new Point(0, 260))), event, 0L);

        verify(gateway).stop(thane);
        verify(gateway, never()).navigate(eq(thane), any(), anyBoolean());
        assertTrue(portalTries.isEmpty());
    }

    @Test
    void afterTheClearAMemberOnAPreHeadMapHolds() {
        mobs(FIRST_STAGE);
        when(event.isEventCleared()).thenReturn(true);
        AgentRuntimeEntry thane = entry(10);

        fight.tick(List.of(member(10, FIRST_STAGE, new Point(NEXT00))), event, 0L);

        verify(gateway).stop(thane);
        assertTrue(portalTries.isEmpty());
    }

    private void mobs(int mapId, int... ids) {
        List<Monster> live = new ArrayList<>();
        for (int id : ids) {
            Monster monster = mock(Monster.class);
            when(monster.getId()).thenReturn(id);
            when(monster.isAlive()).thenReturn(true);
            live.add(monster);
        }
        monsters.put(map(mapId), live);
    }

    private MapleMap map(int mapId) {
        return maps.computeIfAbsent(mapId, id -> {
            MapleMap map = mock(MapleMap.class);
            when(map.getId()).thenReturn(id);
            return map;
        });
    }

    private Character member(int id, int mapId, Point at) {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(id);
        when(character.getMap()).thenReturn(map(mapId));
        when(character.getMapId()).thenReturn(mapId);
        when(character.getPosition()).thenReturn(at);
        return character;
    }

    private AgentRuntimeEntry entry(int id) {
        AgentRuntimeEntry entry = mock(AgentRuntimeEntry.class);
        entries.put(id, entry);
        return entry;
    }
}
