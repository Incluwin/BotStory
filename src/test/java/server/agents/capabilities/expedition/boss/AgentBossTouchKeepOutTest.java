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
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-10-14: a boss-run member keeps out of a deadly touch area on its floor. On Horntail's altar (origin 71,260) the
 * Legs touch along x -186..327 and the Tails along x 191..497 (the lower halves of their body boxes, as touch damage
 * reads them), for about 11,000 and 25,000 against a Lv200 Hero's 19,776 HP. The focus order fights both from the
 * left, so a member keeps to x <= -186 - 1 - KEEP_OUT_MARGIN_PX (30) = -217 while either lives; a member already
 * inside walks out first. A touch below TOUCH_KEEP_OUT_HP_PERCENT (25) of max HP, an area that does not reach the
 * member's floor (a ledge above it) or no deadly part left frees the member.
 */
class AgentBossTouchKeepOutTest {
    private static final int ALTAR = 240_060_200;
    private static final int FIRST_STAGE = 240_060_000;
    private static final int LEFT_HEAD = 8_810_000;
    private static final int LEGS = 8_810_008;
    private static final int TAIL = 8_810_009;
    private static final int WINGS = 8_810_007;
    private static final int SOLOMON = 8_820_003;
    private static final int HUGIN = 8_820_005;
    private static final Rectangle LEGS_TOUCH = new Rectangle(-186, 169, 514, 91);
    private static final Rectangle TAIL_TOUCH = new Rectangle(191, 193, 307, 71);
    private static final int EDGE = -186 - 1 - 30;

    private final PrimitiveCapabilityGateway gateway = mock(PrimitiveCapabilityGateway.class);
    private final MapleMap altar = mock(MapleMap.class);
    private final MapleMap stage = mock(MapleMap.class);
    private final List<Monster> monsters = new ArrayList<>();
    private final List<Monster> stageMonsters = new ArrayList<>();
    private final Map<Integer, AgentRuntimeEntry> entries = new HashMap<>();
    private final Map<Integer, Rectangle> areas = new HashMap<>();
    private final Map<Integer, Double> touches = new HashMap<>();
    private final EventInstanceManager event = mock(EventInstanceManager.class);
    private int maxHp = 19_776;
    private AgentBossPriorityFight fight;

    @BeforeEach
    void setUp() {
        when(altar.getId()).thenReturn(ALTAR);
        when(stage.getId()).thenReturn(FIRST_STAGE);
        when(gateway.groundPoint(any(), any())).thenAnswer(call -> new Point((Point) call.getArgument(1)));
        areas.put(LEGS, LEGS_TOUCH);
        areas.put(TAIL, TAIL_TOUCH);
        touches.put(LEGS, 11_100.0d);
        touches.put(TAIL, 25_200.0d);
        touches.put(WINGS, 7_300.0d);
        touches.put(LEFT_HEAD, 15_100.0d);
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
            public List<Monster> monsters(MapleMap map) {
                return map == altar ? monsters : map == stage ? stageMonsters : List.of();
            }

            @Override
            public boolean hittable(Monster monster) {
                return true;
            }

            @Override
            public Rectangle touchArea(Monster monster) {
                return areas.get(monster.getId());
            }

            @Override
            public double touchDamage(Character member, Monster monster) {
                return touches.getOrDefault(monster.getId(), 0.0d);
            }

            @Override
            public int maxHp(Character member) {
                return maxHp;
            }
        });
    }

    @Test
    void leftOfTheLegsAndTailAMemberIsHeldThereAndFightsTheLegs() {
        mobs(LEGS, TAIL, WINGS);
        AgentRuntimeEntry hero = entry(10);

        fight.tick(List.of(member(10, new Point(-400, 260))), event, 0L);

        verify(gateway).setHorizontalBoundary(hero, ALTAR, Integer.MIN_VALUE, EDGE);
        verify(gateway).grind(hero, Set.of(LEGS), Set.of());
    }

    @Test
    void aMemberInsideTheTouchAreaWalksOutToItsLeftEdgeFirst() {
        mobs(LEGS, TAIL);
        AgentRuntimeEntry hero = entry(10);

        fight.tick(List.of(member(10, new Point(257, 260))), event, 0L);

        verify(gateway).navigate(hero, new Point(EDGE, 260), false);
        verify(gateway, never()).grind(any(), anySet(), anySet());
        verify(gateway, never()).setHorizontalBoundary(any(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void withTheLegsDownTheTailIsFoughtFromItsLeftAndTheBoundaryMovesUp() {
        mobs(TAIL, WINGS);
        AgentRuntimeEntry hero = entry(10);

        fight.tick(List.of(member(10, new Point(-100, 260))), event, 0L);

        verify(gateway).setHorizontalBoundary(hero, ALTAR, Integer.MIN_VALUE, 191 - 1 - 30);
        verify(gateway).grind(hero, Set.of(TAIL), Set.of());
    }

    @Test
    void aTouchUnderAQuarterOfTheMembersHpLeavesItFree() {
        mobs(LEGS);
        maxHp = 50_000;
        AgentRuntimeEntry hero = entry(10);

        fight.tick(List.of(member(10, new Point(0, 260))), event, 0L);

        verify(gateway, never()).setHorizontalBoundary(any(), anyInt(), anyInt(), anyInt());
        verify(gateway, never()).navigate(any(), any(), anyBoolean());
        verify(gateway).grind(hero, Set.of(LEGS), Set.of());
    }

    @Test
    void underHyperBodyTheLegsStillCountAtAThirdOfMaxHp() {
        mobs(LEGS);
        maxHp = 30_000;
        AgentRuntimeEntry darkKnight = entry(10);

        fight.tick(List.of(member(10, new Point(-400, 260))), event, 0L);

        verify(gateway).setHorizontalBoundary(darkKnight, ALTAR, Integer.MIN_VALUE, EDGE);
    }

    @Test
    void onALedgeAboveTheTouchAreaAMemberIsFree() {
        mobs(LEGS, TAIL);
        AgentRuntimeEntry hero = entry(10);

        fight.tick(List.of(member(10, new Point(450, 80))), event, 0L);

        verify(gateway, never()).setHorizontalBoundary(any(), anyInt(), anyInt(), anyInt());
        verify(gateway, never()).navigate(any(), any(), anyBoolean());
    }

    @Test
    void onceTheLegsAndTailAreDownTheBoundaryGoes() {
        mobs(LEGS, TAIL);
        AgentRuntimeEntry hero = entry(10);
        Character member = member(10, new Point(-400, 260));
        fight.tick(List.of(member), event, 0L);

        monsters.clear();
        mobs(WINGS);
        fight.tick(List.of(member), event, 500L);

        verify(gateway).clearHorizontalBoundary(hero);
    }

    @Test
    void withNoSideTheMemberKeepsToTheNearerSide() {
        mobs(LEGS, TAIL);

        assertArrayEquals(new int[] {Integer.MIN_VALUE, EDGE},
                fight.touchBand(altar, member(10, new Point(-300, 260)), null));
        assertArrayEquals(new int[] {497 + 1 + 30, Integer.MAX_VALUE},
                fight.touchBand(altar, member(11, new Point(600, 260)), null));
        assertArrayEquals(new int[] {497 + 1 + 30, Integer.MAX_VALUE},
                fight.touchBand(altar, member(12, new Point(600, 260)), "right"));
        assertNull(fight.touchBand(altar, member(13, new Point(600, 80)), null), "the ledge is above the touch");
    }

    @Test
    void withNoSideTwoTouchesApartLeaveTheGapBetweenThemFree() {
        // 2026-10-15 (P20 run 11): Solomon's and Hugin's touches on Pink Bean's left ledge (y -99) lie 220 px apart,
        // and joining them walled off the ledge, the only foothold from which Hugin can be hit
        areas.put(SOLOMON, new Rectangle(-737, -150, 120, 60));
        areas.put(HUGIN, new Rectangle(-397, -150, 157, 60));
        touches.put(SOLOMON, 17_000.0d);
        touches.put(HUGIN, 20_000.0d);
        mobs(SOLOMON, HUGIN);
        int solomonRight = -618 + 1 + 30;
        int huginLeft = -397 - 1 - 30;

        assertArrayEquals(new int[] {solomonRight, huginLeft},
                fight.touchBand(altar, member(10, new Point(-500, -99)), null), "in the gap: stay in it");
        assertArrayEquals(new int[] {solomonRight, huginLeft},
                fight.touchBand(altar, member(11, new Point(-380, -99)), null), "inside Hugin's, nearer its left edge");
        assertArrayEquals(new int[] {-241 + 1 + 30, Integer.MAX_VALUE},
                fight.touchBand(altar, member(12, new Point(-250, -99)), null), "nearer its right edge");
        assertArrayEquals(new int[] {Integer.MIN_VALUE, -737 - 1 - 30},
                fight.touchBand(altar, member(13, new Point(-800, -99)), null), "left of both");
        assertArrayEquals(new int[] {Integer.MIN_VALUE, -737 - 1 - 30},
                fight.touchBand(altar, member(14, new Point(-500, -99)), "left"), "a named side: the whole span");
    }

    @Test
    void touchesWhoseMarginsMeetStillJoin() {
        areas.put(SOLOMON, new Rectangle(-737, -150, 120, 60));
        areas.put(HUGIN, new Rectangle(-617 + 60, -150, 100, 60));
        touches.put(SOLOMON, 17_000.0d);
        touches.put(HUGIN, 20_000.0d);
        mobs(SOLOMON, HUGIN);

        assertArrayEquals(new int[] {Integer.MIN_VALUE, -737 - 1 - 30},
                fight.touchBand(altar, member(10, new Point(-600, -99)), null), "60 px apart: no room between");
    }

    @Test
    void onAStageMapThatIsDoneTheBoundaryGoesSoTheMemberReachesThePortal() {
        areas.put(LEFT_HEAD, new Rectangle(696, 128, 224, 136));
        stageMonsters.add(mob(LEFT_HEAD));
        AgentRuntimeEntry archer = entry(10);
        Character member = member(10, stage, new Point(500, 260));
        fight.tick(List.of(member), event, 0L);
        verify(gateway).setHorizontalBoundary(archer, FIRST_STAGE, Integer.MIN_VALUE, 696 - 1 - 30);

        stageMonsters.clear();
        fight.tick(List.of(member), event, 500L);

        verify(gateway).clearHorizontalBoundary(archer);
    }

    @Test
    void afterTheClearTheBoundaryGoes() {
        mobs(LEGS, TAIL);
        AgentRuntimeEntry hero = entry(10);
        Character member = member(10, new Point(-400, 260));
        fight.tick(List.of(member), event, 0L);

        when(event.isEventCleared()).thenReturn(true);
        fight.tick(List.of(member), event, 500L);

        verify(gateway).clearHorizontalBoundary(hero);
    }

    @Test
    void theSameBandOnAnotherMapIsSetThereToo() {
        mobs(LEGS);
        areas.put(LEFT_HEAD, LEGS_TOUCH);
        stageMonsters.add(mob(LEFT_HEAD));
        AgentRuntimeEntry hero = entry(10);

        fight.tick(List.of(member(10, stage, new Point(-400, 260))), event, 0L);
        fight.tick(List.of(member(10, new Point(-400, 260))), event, 500L);

        verify(gateway).setHorizontalBoundary(hero, FIRST_STAGE, Integer.MIN_VALUE, EDGE);
        verify(gateway).setHorizontalBoundary(hero, ALTAR, Integer.MIN_VALUE, EDGE);
    }

    private void mobs(int... ids) {
        for (int id : ids) {
            monsters.add(mob(id));
        }
    }

    private Monster mob(int id) {
        Monster monster = mock(Monster.class);
        when(monster.getId()).thenReturn(id);
        when(monster.isAlive()).thenReturn(true);
        when(monster.getPosition()).thenReturn(new Point(71, 260));
        return monster;
    }

    private Character member(int id, Point at) {
        return member(id, altar, at);
    }

    private Character member(int id, MapleMap map, Point at) {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(id);
        when(character.getMap()).thenReturn(map);
        int mapId = map.getId();
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
