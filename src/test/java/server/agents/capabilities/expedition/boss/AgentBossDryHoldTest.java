package server.agents.capabilities.expedition.boss;

import client.Character;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.runtime.AgentRuntimeEntry;
import server.life.Monster;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-10-15 (P20 run 9): Pink Bean's expedition fails at its fifth death, and a member that dies revives in
 * 270050200 and walks back in through in00. One with no HP potion left waits there instead (Ingrid revived dry three
 * times in 46 seconds and spent the run's last three lives), says so once per stay, and walks back in once it holds a
 * potion again. A run whose deaths do not count (Horntail: its dead re-enter) walks back as before.
 */
class AgentBossDryHoldTest {
    private static final int REVIVE_ROOM = 270_050_200;
    private static final int HORNTAIL_STAGE = 240_060_000;
    private static final Point IN00 = new Point(0, -42);

    private final PrimitiveCapabilityGateway gateway = mock(PrimitiveCapabilityGateway.class);
    private final Map<Integer, Integer> potions = new HashMap<>();
    private final List<Integer> portalTries = new ArrayList<>();
    private final List<Integer> heldDry = new ArrayList<>();
    private final AgentRuntimeEntry entry = mock(AgentRuntimeEntry.class);
    private final EventInstanceManager event = mock(EventInstanceManager.class);

    @Test
    void aPinkBeanMemberRevivedWithNoHpPotionWaitsInTheRoomAndSaysSoOnce() {
        AgentBossPriorityFight fight = fight("pinkbean");
        potions.put(10, 0);
        Character ingrid = member(10, REVIVE_ROOM);

        fight.tick(List.of(ingrid), event, 0L);
        fight.tick(List.of(ingrid), event, 10_000L);

        verify(gateway, never()).navigate(eq(entry), any(), anyBoolean());
        assertTrue(portalTries.isEmpty(), "it never walks back in dry");
        assertEquals(List.of(10), heldDry, "one line per stay");
    }

    @Test
    void oneWithPotionsWalksBackIn() {
        AgentBossPriorityFight fight = fight("pinkbean");
        potions.put(10, 556);

        fight.tick(List.of(member(10, REVIVE_ROOM)), event, 0L);

        assertEquals(List.of(10), portalTries);
        assertTrue(heldDry.isEmpty());
    }

    @Test
    void handedPotionsItWalksBackInAndADryStayLaterSaysSoAgain() {
        AgentBossPriorityFight fight = fight("pinkbean");
        Character ingrid = member(10, REVIVE_ROOM);
        potions.put(10, 0);
        fight.tick(List.of(ingrid), event, 0L);

        potions.put(10, 50);
        fight.tick(List.of(ingrid), event, 1_000L);
        assertEquals(List.of(10), portalTries, "back in once it holds a potion");

        potions.put(10, 0);
        fight.tick(List.of(ingrid), event, 60_000L);
        assertEquals(List.of(10, 10), heldDry);
    }

    @Test
    void aHorntailMemberWithNoPotionStillTakesTheStagePortal() {
        AgentBossPriorityFight fight = fight("horntail");
        potions.put(10, 0);

        fight.tick(List.of(member(10, HORNTAIL_STAGE)), event, 0L);

        assertEquals(List.of(10), portalTries, "its deaths do not count against the run");
        assertTrue(heldDry.isEmpty());
    }

    private AgentBossPriorityFight fight(String key) {
        return new AgentBossPriorityFight(key, new AgentBossPriorityFight.Ports() {
            @Override
            public PrimitiveCapabilityGateway gateway() {
                return gateway;
            }

            @Override
            public AgentRuntimeEntry entry(int characterId) {
                return entry;
            }

            @Override
            public List<Monster> monsters(MapleMap on) {
                return List.of();
            }

            @Override
            public boolean hittable(Monster monster) {
                return true;
            }

            @Override
            public Point portalPosition(Character member, String name) {
                return new Point(IN00);
            }

            @Override
            public boolean enterPortal(Character member, String name) {
                portalTries.add(member.getId());
                return true;
            }

            @Override
            public int hpPotions(Character member) {
                return potions.getOrDefault(member.getId(), Integer.MAX_VALUE);
            }

            @Override
            public void heldDry(Character member) {
                heldDry.add(member.getId());
            }
        });
    }

    /** A member standing beside the stage portal of {@code mapId}. */
    private Character member(int id, int mapId) {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(mapId);
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(id);
        when(character.getMap()).thenReturn(map);
        when(character.getMapId()).thenReturn(mapId);
        when(character.getPosition()).thenReturn(new Point(IN00));
        return character;
    }
}
