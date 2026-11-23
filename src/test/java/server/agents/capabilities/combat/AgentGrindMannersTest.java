package server.agents.capabilities.combat;

import client.Character;
import net.server.world.Party;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.integration.AgentPresence;
import server.life.Monster;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Citizens' manners (docs/WORLD_SYSTEMS_PLAN.md N2.4): a polite bot leaves alone a mob a human outside its party hit
 * first; its own mob, a party mate's, and every mob for a bot not installed as polite stay targets.
 */
class AgentGrindMannersTest {
    private final Character bot = mock(Character.class);
    private final Character human = mock(Character.class);
    private final MapleMap map = mock(MapleMap.class);
    private final Monster theirs = mock(Monster.class);
    private final Monster free = mock(Monster.class);
    private final Monster both = mock(Monster.class);
    private MockedStatic<AgentPresence> presences;

    @BeforeEach
    void aHumanFightingOneMob() {
        when(bot.getId()).thenReturn(1);
        when(human.getId()).thenReturn(2);
        when(bot.getMap()).thenReturn(map);
        when(map.getAllPlayers()).thenReturn(List.of(bot, human));
        when(theirs.isAttackedBy(human)).thenReturn(true);
        when(both.isAttackedBy(human)).thenReturn(true);
        when(both.isAttackedBy(bot)).thenReturn(true);
        presences = mockStatic(AgentPresence.class);
        presences.when(() -> AgentPresence.isAgent(bot)).thenReturn(true);
        presences.when(() -> AgentPresence.isAgent(human)).thenReturn(false);
        AgentGrindManners.install(id -> id == 1);
    }

    @AfterEach
    void close() {
        AgentGrindManners.install(null);
        presences.close();
    }

    @Test
    void aPoliteBotLeavesAHumansMobAloneButKeepsOneItHitToo() {
        List<Monster> candidates = new ArrayList<>(List.of(theirs, free, both));
        AgentGrindManners.leaveTheirs(bot, candidates);
        assertEquals(List.of(free, both), candidates);
        List<Monster> aoe = new ArrayList<>();
        AgentGrindManners.mannerly(bot, List.of(theirs, free, both)).forEach(aoe::add);
        assertEquals(List.of(free, both), aoe);
    }

    @Test
    void aPartyMatesMobIsFair() {
        Party party = mock(Party.class);
        when(party.getId()).thenReturn(5);
        when(bot.getParty()).thenReturn(party);
        when(human.getParty()).thenReturn(party);
        List<Monster> candidates = new ArrayList<>(List.of(theirs, free));
        AgentGrindManners.leaveTheirs(bot, candidates);
        assertEquals(List.of(theirs, free), candidates);
    }

    @Test
    void notPoliteOrNoHumanIsAsBefore() {
        AgentGrindManners.install(null);
        List<Monster> candidates = new ArrayList<>(List.of(theirs, free));
        AgentGrindManners.leaveTheirs(bot, candidates);
        assertEquals(List.of(theirs, free), candidates);
        AgentGrindManners.install(id -> true);
        when(map.getAllPlayers()).thenReturn(List.of(bot));
        Iterable<Monster> all = List.of(theirs, free);
        assertSame(all, AgentGrindManners.mannerly(bot, all), "no human on the map: the same list, nothing copied");
    }
}
