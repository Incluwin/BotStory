package server.life.autonomy.balrog;

import net.server.channel.Channel;
import net.server.services.task.channel.OverallService;
import net.server.services.task.channel.ServerMobAutonomyService;
import net.server.services.type.ChannelServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.life.Monster;
import server.life.MonsterStats;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A lost or reset Easy Balrog fight tears its parts down; it must not run the event's "Balrog slain" path
 * (BalrogBattle_Easy.js monsterKilled: clear effect, clearPQ, quest counts, victory broadcast). Uses a real map so the
 * real removal and kill paths run, with only the channel, the scheduler and the spawn placement stubbed.
 */
class EasyBalrogEncounterAbortTest {
    private static final int BATTLE_MAP = 105_100_400;

    private MapleMap map;
    private EventInstanceManager eim;
    private final AtomicReference<Runnable> release = new AtomicReference<>();
    private final List<Runnable> helperSpawns = new ArrayList<>();
    private final List<Monster> spawned = new ArrayList<>();

    @BeforeEach
    void setUp() {
        map = spy(new MapleMap(BATTLE_MAP, 0, 1, 105_100_100, 1.0f));
        eim = mock(EventInstanceManager.class);
        map.setEventInstance(eim);
        Channel channel = mock(Channel.class);
        OverallService scheduler = mock(OverallService.class);
        doReturn(channel).when(map).getChannelServer();
        when(channel.getServiceAccess(ChannelServices.OVERALL)).thenReturn(scheduler);
        when(channel.getServiceAccess(ChannelServices.MOB_AUTONOMY))
                .thenReturn(mock(ServerMobAutonomyService.class));
        doAnswer(invocation -> {
            Monster monster = place(invocation.getArgument(0), invocation.getArgument(1));
            monster.setFake(true);
            return null;
        }).when(map).spawnFakeMonsterOnGroundBelow(any(Monster.class), any(Point.class));
        doAnswer(invocation -> {
            place(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(map).spawnMonsterOnGroundBelow(any(Monster.class), any(Point.class));
        doAnswer(invocation -> {
            ((Monster) invocation.getArgument(0)).setFake(false);
            return null;
        }).when(map).makeMonsterReal(any(Monster.class));
        doAnswer(invocation -> {
            long delay = invocation.getArgument(2);
            if (delay == EasyBalrogEncounterService.RELEASE_DELAY_MS) {
                release.set(invocation.getArgument(1));
            } else {
                helperSpawns.add(invocation.getArgument(1));
            }
            return null;
        }).when(scheduler).registerOverallAction(eq(BATTLE_MAP), any(Runnable.class), anyLong());
    }

    @AfterEach
    void tearDown() {
        EasyBalrogEncounterService.stop(map, "test-cleanup");
    }

    @Test
    void lostFightRemovesEveryPartWithoutTellingTheEventItWasKilled() {
        EasyBalrogEncounterService.EncounterHandle encounter = EasyBalrogEncounterService.start(
                map, new Point(412, 258), EasyBalrogEncounterAbortTest::monster);
        release.get().run();
        Monster initial = mob(EasyBalrogEncounterService.INITIAL_CLAW_ID);
        map.killMonster(initial, null, false, (short) 0);
        helperSpawns.forEach(Runnable::run);
        assertEquals(EasyBalrogEncounterService.Phase.ONE_CLAW, encounter.phase());
        List<Monster> standing = map.getAllMonsters();
        assertEquals(3, standing.size(), "body, released claw and the initial claw's remains stand");
        clearInvocations(eim);

        EasyBalrogEncounterService.stop(map, "event-end");

        assertEquals(EasyBalrogEncounterService.Phase.ABORTED, encounter.phase());
        assertFalse(EasyBalrogEncounterService.isActive(map));
        assertTrue(map.getAllMonsters().isEmpty(), "every part left the map");
        for (Monster part : standing) {
            assertNull(map.getMonsterByOid(part.getObjectId()));
            assertFalse(part.isAlive());
        }
        verify(eim, never()).monsterKilled(any(Monster.class), anyBoolean());
    }

    @Test
    void stillSealedFightResetRemovesBodyClawAndSealWithoutAKill() {
        EasyBalrogEncounterService.EncounterHandle encounter = EasyBalrogEncounterService.start(
                map, new Point(412, 258), EasyBalrogEncounterAbortTest::monster);
        assertEquals(3, map.getAllMonsters().size(), "body, initial claw and seal");

        EasyBalrogEncounterService.stop(map, "event-dispose");

        assertEquals(EasyBalrogEncounterService.Phase.ABORTED, encounter.phase());
        assertTrue(map.getAllMonsters().isEmpty());
        verify(eim, never()).monsterKilled(any(Monster.class), anyBoolean());
    }

    @Test
    void realBodyKillStillReachesTheEventAndClears() {
        EasyBalrogEncounterService.EncounterHandle encounter = EasyBalrogEncounterService.start(
                map, new Point(412, 258), EasyBalrogEncounterAbortTest::monster);
        release.get().run();
        map.killMonster(mob(EasyBalrogEncounterService.INITIAL_CLAW_ID), null, false, (short) 0);
        map.killMonster(mob(EasyBalrogEncounterService.RELEASED_CLAW_ID), null, false, (short) 0);
        assertEquals(EasyBalrogEncounterService.Phase.BODY, encounter.phase());
        Monster body = mob(EasyBalrogEncounterService.BODY_ID);

        map.killMonster(body, null, false, (short) 0);

        verify(eim).monsterKilled(body, false);
        assertEquals(EasyBalrogEncounterService.Phase.CLEARED, encounter.phase());
        assertNull(map.getMonsterByOid(body.getObjectId()));
    }

    private Monster place(Monster monster, Point position) {
        monster.setPosition(new Point(position));
        monster.setMap(map);
        map.addMapObject(monster);
        spawned.add(monster);
        return monster;
    }

    private Monster mob(int id) {
        return spawned.stream().filter(monster -> monster.getId() == id)
                .findFirst().orElseThrow();
    }

    private static Monster monster(int id) {
        MonsterStats stats = new MonsterStats();
        stats.hp = 100;
        stats.mp = 100;
        stats.name = Integer.toString(id);
        stats.boss = true;
        EasyBalrogHpBarPolicy.applyMissingStyle(id, stats);
        return new Monster(id, stats);
    }
}
