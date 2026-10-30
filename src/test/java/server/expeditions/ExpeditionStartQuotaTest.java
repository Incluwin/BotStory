package server.expeditions;

import client.Character;
import net.server.PlayerStorage;
import net.server.channel.Channel;
import net.server.world.World;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.TimerManager;
import server.maps.MapleMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * The Start logs the day's boss entry (ExpeditionBossLog.attemptBoss with log) of each member who goes in: the event
 * registers and warps only the members standing on the recruiting map (EventInstanceManager.registerExpedition). It
 * logged every online member, so one who had walked off kept no entry for a run it never saw.
 */
class ExpeditionStartQuotaTest {
    private static final int RECRUIT_MAP = 211042300;
    private static final int CHANNEL = 2;

    @Test
    void theStartLogsTheEntryOfTheMembersOnTheRecruitingMapOnly() {
        MapleMap recruit = mock(MapleMap.class);
        when(recruit.getId()).thenReturn(RECRUIT_MAP);
        Channel channel = mock(Channel.class);
        when(channel.getId()).thenReturn(CHANNEL);
        when(recruit.getChannelServer()).thenReturn(channel);
        PlayerStorage online = new PlayerStorage();
        World world = mock(World.class);
        when(world.getPlayerStorage()).thenReturn(online);
        when(recruit.getWorldServer()).thenReturn(world);
        Character leader = member(online, 1, "Leader", RECRUIT_MAP);
        when(leader.getMap()).thenReturn(recruit);
        Character inside = member(online, 2, "Inside", RECRUIT_MAP);
        Character away = member(online, 3, "Away", 211000000);

        try (MockedStatic<TimerManager> timers = mockStatic(TimerManager.class);
             MockedStatic<ExpeditionBossLog> bossLog = mockStatic(ExpeditionBossLog.class)) {
            timers.when(TimerManager::getInstance).thenReturn(mock(TimerManager.class));
            bossLog.when(() -> ExpeditionBossLog.attemptBoss(anyInt(), anyInt(), any(),
                    anyBoolean())).thenReturn(true);
            Expedition zakum = new Expedition(leader, ExpeditionType.ZAKUM, true, 0, 0);
            zakum.beginRegistration();
            zakum.addMemberInt(inside);
            zakum.addMemberInt(away);

            zakum.start();

            bossLog.verify(() -> ExpeditionBossLog.attemptBoss(1, CHANNEL, zakum, true));
            bossLog.verify(() -> ExpeditionBossLog.attemptBoss(2, CHANNEL, zakum, true));
            bossLog.verify(() -> ExpeditionBossLog.attemptBoss(eq(3), anyInt(), eq(zakum), anyBoolean()), never());
        }
    }

    private static Character member(PlayerStorage online, int id, String name, int mapId) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getName()).thenReturn(name);
        when(chr.getMapId()).thenReturn(mapId);
        when(chr.isLoggedinWorld()).thenReturn(true);
        online.addPlayer(chr);
        return chr;
    }
}
