package server.agents.capabilities.expedition.boss;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.runtime.AgentRuntimeEntry;
import server.life.Monster;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P12 G3: the fight of a boss with no driver of its own grinds the first live part of the catalog's priority and falls
 * back to the rest; a part a human must drive counts only while one does, and after the clear or with nothing to hit a
 * member holds.
 */
class AgentBossPriorityFightTest {
    private static final String KEY = "test_chain";
    private static final int FIRST = 9_400_112;
    private static final int SECOND = 9_400_113;
    private static final int ADD = 9_400_120;
    private static final int DISPLAY = 9_400_999;

    private final PrimitiveCapabilityGateway gateway = mock(PrimitiveCapabilityGateway.class);
    private final MapleMap map = mock(MapleMap.class);
    private final List<Monster> monsters = new ArrayList<>();
    private final Set<Monster> undriven = new HashSet<>();
    private final Map<Integer, AgentRuntimeEntry> entries = new HashMap<>();
    private final EventInstanceManager event = mock(EventInstanceManager.class);
    private AgentBossPriorityFight fight;

    @BeforeEach
    void setUp() {
        AgentBossRules.replaceForTest(List.of(new AgentBossRules.Boss(KEY, "Test chain", null,
                List.of(FIRST, SECOND), List.of(ADD), List.of(DISPLAY), null, false, null)));
        fight = new AgentBossPriorityFight(KEY, new AgentBossPriorityFight.Ports() {
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
                return on == map ? monsters : List.of();
            }

            @Override
            public boolean hittable(Monster monster) {
                return !undriven.contains(monster);
            }
        });
    }

    @AfterEach
    void tearDown() {
        AgentBossRules.replaceForTest(null);
    }

    @Test
    void membersGrindTheFirstLivePriorityPartAndFallBackToTheRest() {
        monsters.addAll(List.of(mob(SECOND), mob(FIRST), mob(ADD), mob(DISPLAY)));
        AgentRuntimeEntry thane = entry(10);
        AgentRuntimeEntry isolde = entry(11);

        fight.tick(List.of(member(10), member(11)), event, 0L);

        verify(gateway).grind(thane, Set.of(FIRST), Set.of(SECOND, ADD));
        verify(gateway).grind(isolde, Set.of(FIRST), Set.of(SECOND, ADD));
    }

    @Test
    void aPartNoHumanDrivesIsSkippedUntilOneDoes() {
        Monster first = mob(FIRST);
        monsters.addAll(List.of(first, mob(ADD)));
        undriven.add(first);
        AgentRuntimeEntry thane = entry(10);

        fight.tick(List.of(member(10)), event, 0L);
        verify(gateway).grind(thane, Set.of(ADD), Set.of());

        undriven.clear();
        fight.tick(List.of(member(10)), event, 1L);
        verify(gateway).grind(thane, Set.of(FIRST), Set.of(ADD));
    }

    @Test
    void aMemberHoldsWithNothingToHitAndAfterTheClear() {
        monsters.add(mob(DISPLAY));
        AgentRuntimeEntry thane = entry(10);
        fight.tick(List.of(member(10)), event, 0L);
        verify(gateway).stop(thane);

        monsters.add(mob(FIRST));
        when(event.isEventCleared()).thenReturn(true);
        AgentRuntimeEntry isolde = entry(11);
        fight.tick(List.of(member(11)), event, 1L);
        verify(gateway).stop(isolde);
        verify(gateway, never()).grind(any(AgentRuntimeEntry.class), anySet(), anySet());
    }

    @Test
    void aFightNeedsABossKey() {
        assertThrows(IllegalArgumentException.class, () -> AgentBossPriorityFight.forKey(" ").get());
    }

    private Monster mob(int id) {
        Monster monster = mock(Monster.class);
        when(monster.getId()).thenReturn(id);
        when(monster.isAlive()).thenReturn(true);
        return monster;
    }

    private Character member(int id) {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(id);
        when(character.getMap()).thenReturn(map);
        return character;
    }

    private AgentRuntimeEntry entry(int id) {
        AgentRuntimeEntry entry = mock(AgentRuntimeEntry.class);
        entries.put(id, entry);
        return entry;
    }
}
