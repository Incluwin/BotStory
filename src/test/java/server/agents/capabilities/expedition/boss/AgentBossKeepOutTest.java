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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P20, from the shipped boss rules: while Solomon (deadly box 200-900 px to his left) or Rex (the same to his right)
 * lives, a ranged or magic member keeps to the band past those boxes, KEEP_OUT_MARGIN_PX inside. Outside the band it
 * walks in first; inside, a horizontal boundary holds it, set once per change and cleared once both are gone. Melee
 * members are never held. A member revived on the resurrection map (a stage map) walks back through in00. Ariel, the
 * healer, comes first.
 */
class AgentBossKeepOutTest {
    private static final int BATTLE = 270_050_100;
    private static final int RESURRECTION = 270_050_200;
    private static final int ARIEL = 8_820_002;
    private static final int SOLOMON = 8_820_003;
    private static final int REX = 8_820_004;
    private static final int PINK_BEAN = 8_820_001;
    private static final Point IN00 = new Point(-1156, -41);

    private final PrimitiveCapabilityGateway gateway = mock(PrimitiveCapabilityGateway.class);
    private final Map<Integer, MapleMap> maps = new HashMap<>();
    private final Map<MapleMap, List<Monster>> monsters = new HashMap<>();
    private final Map<Integer, AgentRuntimeEntry> entries = new HashMap<>();
    private final Set<Integer> melee = new HashSet<>();
    private final List<Integer> portalTries = new ArrayList<>();
    private final EventInstanceManager event = mock(EventInstanceManager.class);
    private AgentBossPriorityFight fight;
    private int margin;

    @BeforeEach
    void setUp() {
        margin = AgentBossPriorityFight.keepOutMarginPx();
        fight = new AgentBossPriorityFight("pinkbean", new AgentBossPriorityFight.Ports() {
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
                return "in00".equals(name) && member.getMapId() == RESURRECTION ? IN00 : null;
            }

            @Override
            public boolean enterPortal(Character member, String name) {
                portalTries.add(member.getId());
                return true;
            }
        });
    }

    @Test
    void theShippedRulesNameSolomonsAndRexsBoxesTheResurrectionMapAndNoEntryItem() {
        AgentBossRules.Boss rules = AgentBossRules.of("pinkbean");
        assertEquals(List.of(new AgentBossRules.KeepOut(SOLOMON, -900, -200),
                new AgentBossRules.KeepOut(REX, 200, 900)), rules.keepOut());
        assertEquals(List.of(RESURRECTION), rules.stageMaps());
        assertEquals("in00", rules.stagePortal());
        assertEquals(ARIEL, rules.targetPriority().getFirst(), "the healer first");
        assertEquals(PINK_BEAN, rules.targetPriority().getLast());
        assertEquals(List.of(4_001_193), rules.markerItems(), "the stage-5 drop stays on the floor");
        assertEquals(List.of(), AgentBossRules.entryItems("pinkbean"), "the owner carries nothing in");
        assertEquals(List.of(4_001_017), AgentBossRules.entryItems("zakum"), "Zakum's entry item is its marker");
        assertEquals(List.of(4_000_138), AgentBossRules.entryItems("showa"), "Showa's too");
        assertTrue(AgentBossRunCatalog.PINKBEAN.fightMap(RESURRECTION));
    }

    @Test
    void theBandLiesPastBothBoxes() {
        mobs(BATTLE, mob(SOLOMON, 5), mob(REX, 5));
        AgentBossRules.Boss rules = AgentBossRules.of("pinkbean");

        assertArrayEquals(new int[] {5 - 200 + margin, 5 + 200 - margin}, fight.keepOutBand(map(BATTLE), rules));

        mobs(BATTLE, mob(SOLOMON, 5));
        assertArrayEquals(new int[] {5 - 200 + margin, Integer.MAX_VALUE}, fight.keepOutBand(map(BATTLE), rules));

        mobs(BATTLE, mob(ARIEL, 5));
        assertNull(fight.keepOutBand(map(BATTLE), rules), "no keep-out part lives");
    }

    @Test
    void aRangedMemberInSolomonsBoxWalksOutFirstThenIsHeldWhileItFights() {
        mobs(BATTLE, mob(SOLOMON, 5), mob(PINK_BEAN, 300));
        AgentRuntimeEntry thane = entry(10);
        Character member = member(10, BATTLE, new Point(-500, -42));
        Point edge = new Point(5 - 200 + margin, -42);
        when(gateway.groundPoint(any(), eq(edge))).thenReturn(edge);

        fight.tick(List.of(member), event, 0L);
        verify(gateway).navigate(thane, edge, false);
        verify(gateway, never()).grind(any(AgentRuntimeEntry.class), anySet(), anySet());
        verify(gateway, never()).setHorizontalBoundary(any(), anyInt(), anyInt(), anyInt());

        when(member.getPosition()).thenReturn(new Point(0, -42));
        fight.tick(List.of(member), event, 1L);
        fight.tick(List.of(member), event, 2L);
        verify(gateway, times(1)).setHorizontalBoundary(thane, BATTLE, 5 - 200 + margin, Integer.MAX_VALUE);
        verify(gateway, times(2)).grind(thane, Set.of(SOLOMON), Set.of(PINK_BEAN));

        mobs(BATTLE, mob(PINK_BEAN, 300));
        fight.tick(List.of(member), event, 3L);
        verify(gateway).clearHorizontalBoundary(thane);
    }

    @Test
    void aMeleeMemberIsNeverHeld() {
        mobs(BATTLE, mob(SOLOMON, 5), mob(REX, 5));
        AgentRuntimeEntry warrior = entry(11);
        melee.add(11);

        fight.tick(List.of(member(11, BATTLE, new Point(-500, -42))), event, 0L);

        verify(gateway, never()).setHorizontalBoundary(any(), anyInt(), anyInt(), anyInt());
        verify(gateway, never()).navigate(any(), any(), anyBoolean());
        verify(gateway).grind(warrior, Set.of(REX), Set.of(SOLOMON)); // Rex first (a5c81c851)
    }

    @Test
    void aMemberRevivedOnTheResurrectionMapWalksBackThroughIn00() {
        mobs(RESURRECTION);
        AgentRuntimeEntry thane = entry(10);
        Character member = member(10, RESURRECTION, new Point(470, -41));

        fight.tick(List.of(member), event, 0L);
        verify(gateway).navigate(thane, IN00, true);

        when(member.getPosition()).thenReturn(new Point(IN00));
        fight.tick(List.of(member), event, 1L);
        assertEquals(List.of(10), portalTries);
    }

    private void mobs(int mapId, Monster... live) {
        monsters.put(map(mapId), List.of(live));
    }

    private static Monster mob(int id, int x) {
        Monster monster = mock(Monster.class);
        when(monster.getId()).thenReturn(id);
        when(monster.isAlive()).thenReturn(true);
        when(monster.getPosition()).thenReturn(new Point(x, -42));
        return monster;
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
