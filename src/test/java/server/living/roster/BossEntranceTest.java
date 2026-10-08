package server.living.roster;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.expedition.boss.AgentBossRunCatalog;
import server.life.NPC;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A boss's entrance for companions: Mu Young's room (105100100) for Easy Balrog. A companion waiting to sign up
 * stands at its own spot beside her (she stands at 617,246 in the WZ): 50 px to her left for the first, 30 px further
 * for each next one, on the ground below.
 */
class BossEntranceTest {
    private static final int MU_YOUNG = 1061014;

    @Test
    void easyBalrogsEntranceIsMuYoungsRoom() {
        assertSame(AgentBossRunCatalog.EASY_BALROG, BossEntrance.EASY_BALROG.definition());
        assertSame(BossEntrance.EASY_BALROG, BossEntrance.atRecruitMap(105100100));
        assertNull(BossEntrance.atRecruitMap(105100400), "the battle map is no entrance");
        assertNull(BossEntrance.atRecruitMap(103000000), "Lakelis's is a party quest's");
        assertSame(BossEntrance.EASY_BALROG, BossEntrance.of(" Easy_Balrog "));
        assertNull(BossEntrance.of("zakum"));
        assertNull(BossEntrance.of(null));
    }

    @Test
    void easyBalrogsApproachWithAStoreIsAntTunnelPark() {
        assertEquals(105070001, BossEntrance.EASY_BALROG.approachMapId());
        assertSame(BossEntrance.EASY_BALROG, BossEntrance.atApproachMap(105070001));
        assertNull(BossEntrance.atApproachMap(105100100), "Mu Young's room is her entrance, not its approach");
        assertNull(BossEntrance.atApproachMap(105070000), "the field next to the park");
        assertNull(BossEntrance.atApproachMap(0));
    }

    @Test
    void eachOrdinalWaitsThirtyPixelsFurtherLeftOnTheGroundBelow() {
        MapleMap room = muYoungsRoom(new Point(617, 246));
        when(room.getPointBelow(any(Point.class))).thenAnswer(inv -> new Point(((Point) inv.getArgument(0)).x, 252));

        List<Point> spots = new ArrayList<>();
        for (int ordinal = 0; ordinal < 5; ordinal++) {
            spots.add(BossEntrance.EASY_BALROG.spot(room, ordinal));
        }

        assertEquals(List.of(new Point(567, 252), new Point(537, 252), new Point(507, 252), new Point(477, 252),
                new Point(447, 252)), spots);
        verify(room, times(1)).getNPCById(MU_YOUNG); // looked for once per map, not on every tick
    }

    @Test
    void noGroundBelowKeepsHerHeightAndNoMuYoungIsNoSpot() {
        MapleMap room = muYoungsRoom(new Point(617, 246));
        assertEquals(new Point(567, 246), BossEntrance.EASY_BALROG.spot(room, 0));

        assertNull(BossEntrance.EASY_BALROG.spot(mock(MapleMap.class), 0));
        assertNull(BossEntrance.EASY_BALROG.spot(null, 0));
    }

    private static MapleMap muYoungsRoom(Point at) {
        MapleMap room = mock(MapleMap.class);
        NPC muYoung = mock(NPC.class);
        when(muYoung.getPosition()).thenReturn(at);
        when(room.getNPCById(MU_YOUNG)).thenReturn(muYoung);
        return room;
    }
}
