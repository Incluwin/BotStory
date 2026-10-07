package server.life.autonomy.balrog;

import net.server.channel.Channel;
import net.server.coordinator.world.MonsterAggroCoordinator;
import net.server.services.task.channel.OverallService;
import net.server.services.task.channel.ServerMobAutonomyService;
import net.server.services.type.ChannelServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.life.Monster;
import server.life.MonsterStats;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The installed server authority rule decides once per start how every actor registers. */
class EasyBalrogEncounterAuthorityRuleTest {
    private final AtomicInteger nextOid = new AtomicInteger(1_000);
    private final Map<Integer, Monster> live = new HashMap<>();
    private final List<Monster> spawned = new ArrayList<>();
    private final AtomicReference<Runnable> release = new AtomicReference<>();
    private MapleMap map;
    private ServerMobAutonomyService autonomy;

    @BeforeEach
    void setUp() {
        map = mock(MapleMap.class);
        Channel channel = mock(Channel.class);
        OverallService scheduler = mock(OverallService.class);
        autonomy = mock(ServerMobAutonomyService.class);

        when(map.getId()).thenReturn(105100400);
        when(map.getAggroCoordinator()).thenReturn(mock(MonsterAggroCoordinator.class));
        when(map.getChannelServer()).thenReturn(channel);
        when(channel.getServiceAccess(ChannelServices.OVERALL)).thenReturn(scheduler);
        when(channel.getServiceAccess(ChannelServices.MOB_AUTONOMY)).thenReturn(autonomy);
        when(map.getMonsterByOid(anyInt())).thenAnswer(
                invocation -> live.get(invocation.<Integer>getArgument(0)));
        doAnswer(invocation -> {
            place(invocation.getArgument(0), invocation.getArgument(1));
            ((Monster) invocation.getArgument(0)).setFake(true);
            return null;
        }).when(map).spawnFakeMonsterOnGroundBelow(any(Monster.class), any(Point.class));
        doAnswer(invocation -> {
            place(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(map).spawnMonsterOnGroundBelow(any(Monster.class), any(Point.class));
        doAnswer(invocation -> {
            long delay = invocation.getArgument(2);
            if (delay == EasyBalrogEncounterService.RELEASE_DELAY_MS) {
                release.set(invocation.getArgument(1));
            }
            return null;
        }).when(scheduler).registerOverallAction(eq(105100400), any(Runnable.class), anyLong());
        doAnswer(invocation -> {
            Monster monster = invocation.getArgument(0);
            live.remove(monster.getObjectId());
            monster.dispatchMonsterKilled(false);
            return null;
        }).when(map).killMonster(any(Monster.class),
                org.mockito.ArgumentMatchers.isNull(), eq(false), eq((short) 0));
        doAnswer(invocation -> {
            ((Monster) invocation.getArgument(0)).setFake(false);
            return null;
        }).when(map).makeMonsterReal(any(Monster.class));
    }

    @AfterEach
    void tearDown() {
        EasyBalrogEncounterService.installServerAuthorityRule(null);
        EasyBalrogEncounterService.stop(map, "test-cleanup");
    }

    @Test
    void trueRuleRegistersEveryActorWithForcedServerAuthority() {
        EasyBalrogEncounterService.installServerAuthorityRule(battleMap -> true);

        EasyBalrogEncounterService.EncounterHandle encounter = startAndActivateBody();

        verify(autonomy, times(3)).registerEncounterActor(
                any(Monster.class), eq(encounter), eq(true));
        verify(autonomy, never()).registerEncounterActor(any(Monster.class), any());
    }

    @Test
    void noRuleKeepsTheStockRegistration() {
        assertStock(startAndActivateBody());
    }

    @Test
    void falseRuleKeepsTheStockRegistration() {
        EasyBalrogEncounterService.installServerAuthorityRule(battleMap -> false);

        assertStock(startAndActivateBody());
    }

    @Test
    void throwingRuleCountsAsFalseAndTheEncounterStillRuns() {
        EasyBalrogEncounterService.installServerAuthorityRule(battleMap -> {
            throw new IllegalStateException("rule failed");
        });

        assertStock(startAndActivateBody());
    }

    @Test
    void ruleIsEvaluatedOncePerStartWithTheBattleMap() {
        List<MapleMap> asked = new ArrayList<>();
        EasyBalrogEncounterService.installServerAuthorityRule(battleMap -> {
            asked.add(battleMap);
            return true;
        });

        startAndActivateBody();
        assertEquals(List.of(map), asked,
                "three actor registrations reuse the one answer read at start");

        EasyBalrogEncounterService.start(
                map, new Point(412, 258), EasyBalrogEncounterAuthorityRuleTest::monster);
        assertEquals(List.of(map, map), asked);
    }

    private EasyBalrogEncounterService.EncounterHandle startAndActivateBody() {
        EasyBalrogEncounterService.EncounterHandle encounter =
                EasyBalrogEncounterService.start(
                        map, new Point(412, 258), EasyBalrogEncounterAuthorityRuleTest::monster);
        assertNotNull(release.get());
        release.get().run();
        mob(EasyBalrogEncounterService.INITIAL_CLAW_ID).dispatchMonsterKilled(true);
        mob(EasyBalrogEncounterService.RELEASED_CLAW_ID).dispatchMonsterKilled(true);
        assertEquals(EasyBalrogEncounterService.Phase.BODY, encounter.phase());
        return encounter;
    }

    private void assertStock(EasyBalrogEncounterService.EncounterHandle encounter) {
        verify(autonomy, times(3)).registerEncounterActor(any(Monster.class), eq(encounter));
        verify(autonomy, never()).registerEncounterActor(any(Monster.class), any(), anyBoolean());
    }

    private void place(Monster monster, Point position) {
        monster.setMap(map);
        monster.setPosition(position);
        monster.setObjectId(nextOid.getAndIncrement());
        live.put(monster.getObjectId(), monster);
        spawned.add(monster);
    }

    private Monster mob(int id) {
        return spawned.stream().filter(monster -> monster.getId() == id)
                .reduce((first, second) -> second).orElseThrow();
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
