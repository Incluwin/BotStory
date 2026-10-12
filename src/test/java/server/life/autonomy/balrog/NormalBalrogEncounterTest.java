package server.life.autonomy.balrog;

import net.server.channel.Channel;
import net.server.coordinator.world.MonsterAggroCoordinator;
import net.server.services.task.channel.OverallService;
import net.server.services.task.channel.ServerMobAutonomyService;
import net.server.services.type.ChannelServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.life.Monster;
import server.life.MonsterStats;
import server.life.autonomy.BossActorBehavior;
import server.life.autonomy.ServerMobBehaviorRegistry;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

/**
 * Normal Balrog's fight in EasyBalrogEncounterService (P11, decision 4): the server runs it only while the
 * companion-fight rule says a companion signed up for it; otherwise startNormal answers false and the stock
 * BalrogBattle.js spawn runs. Its graph is Easy's with Normal's ids (8830000 body, 8830001 released claw, 8830002
 * initial claw, 8830006 seal, helpers 8830004/8830005); its body keeps its stock drops, and it shows the stock HP bars,
 * not Easy's combined gauge. Its parts' behaviours never take a part up as it spawns, so a stock all-human fight is
 * never the server's.
 */
class NormalBalrogEncounterTest {
    private MapleMap map;

    @AfterEach
    void tearDown() {
        EasyBalrogEncounterService.stop(map, "test-cleanup");
        EasyBalrogEncounterService.installCompanionFightRule(null);
        EasyBalrogEncounterService.installServerAuthorityRule(null);
    }

    @Test
    void withNoCompanionSignedUpTheStockSpawnRuns() {
        Fixture fixture = fixture();

        assertFalse(EasyBalrogEncounterService.startNormal(map, new Point(412, 258),
                NormalBalrogEncounterTest::monster), "no rule installed");
        EasyBalrogEncounterService.installCompanionFightRule(battleMap -> false);
        assertFalse(EasyBalrogEncounterService.startNormal(map, new Point(412, 258),
                NormalBalrogEncounterTest::monster));
        EasyBalrogEncounterService.installCompanionFightRule(battleMap -> {
            throw new IllegalStateException("broken");
        });
        assertFalse(EasyBalrogEncounterService.startNormal(map, new Point(412, 258),
                NormalBalrogEncounterTest::monster), "a failing rule is the stock choice");

        assertEquals(List.of(), fixture.spawned());
        assertFalse(EasyBalrogEncounterService.isActive(map));
    }

    @Test
    void aCompanionFightRunsNormalsIdsKeepsTheBodysDropsAndTheStockBars() {
        Fixture fixture = fixture();
        AtomicBoolean forced = new AtomicBoolean();
        EasyBalrogEncounterService.installCompanionFightRule(battleMap -> battleMap == map);
        EasyBalrogEncounterService.installServerAuthorityRule(battleMap -> {
            forced.set(true);
            return true;
        });

        assertTrue(EasyBalrogEncounterService.startNormal(map, new Point(412, 258),
                NormalBalrogEncounterTest::monster));

        assertTrue(EasyBalrogEncounterService.isActive(map));
        assertTrue(forced.get(), "the server-authority rule is asked as for Easy");
        Monster body = mob(fixture.spawned(), 8830000);
        Monster initial = mob(fixture.spawned(), 8830002);
        Monster seal = mob(fixture.spawned(), 8830006);
        assertTrue(body.isFake());
        assertFalse(body.dropsDisabled(), "Normal's body drops as the stock spawn's does");
        assertTrue(initial.dropsDisabled());
        assertTrue(seal.dropsDisabled());
        assertTrue(EasyBalrogEncounterService.hpBarSnapshot(initial).isEmpty(), "no combined gauge");
        verify(fixture.autonomy()).registerEncounterActor(eq(initial), any(), eq(true));

        fixture.release().get().run();
        Monster released = mob(fixture.spawned(), 8830001);
        initial.dispatchMonsterKilled(true);
        released.dispatchMonsterKilled(true);
        assertFalse(body.isFake(), "both claws down: the body is real");
        verify(fixture.autonomy(), times(3)).registerEncounterActor(any(Monster.class), any(), anyBoolean());

        body.dispatchMonsterKilled(true);
        assertFalse(EasyBalrogEncounterService.isActive(map), "cleared");
    }

    @Test
    void aFailedStartRemovesItsPartsAndAnswersFalseForTheStockSpawn() {
        Fixture fixture = fixture();
        EasyBalrogEncounterService.installCompanionFightRule(battleMap -> true);

        assertFalse(EasyBalrogEncounterService.startNormal(map, new Point(412, 258),
                id -> id == 8830006 ? null : monster(id)), "the seal is missing");

        assertFalse(EasyBalrogEncounterService.isActive(map));
        verify(map, never()).spawnMonsterOnGroundBelow(any(Monster.class), any(Point.class));
    }

    @Test
    void normalsPartsHaveBehavioursThatNeverStartOnSpawnAndItsAddsChaseTheirAgent() {
        for (int id : List.of(8830000, 8830001, 8830002)) {
            BossActorBehavior behavior = ServerMobBehaviorRegistry.behaviorFor(id).orElseThrow();
            assertFalse(behavior.autoStartOnSpawn(), "a stock fight stays the clients': " + id);
        }
        assertInstanceOf(NormalBalrogBodyBehavior.class, ServerMobBehaviorRegistry.behaviorFor(8830000).orElseThrow());
        for (int id : List.of(8830007, 8830008, 8830009)) {
            assertTrue(ServerMobBehaviorRegistry.behaviorFor(id).orElseThrow().autoStartOnSpawn(), "Easy as before");
        }
        assertTrue(BalrogSummonedAddBehavior.behaviorFor(6400005).isPresent());
        assertTrue(BalrogSummonedAddBehavior.behaviorFor(6400006).isPresent());
        assertFalse(BalrogSummonedAddBehavior.behaviorFor(6400007).isPresent(), "the 1200-limit summon is no add");
    }

    @Test
    void theNormalBodyFollowsTheEasyBodysTelegraphSwitch() {
        NormalBalrogBodyBehavior body = new NormalBalrogBodyBehavior();
        boolean before = EasyBalrogBodyBehavior.telegraphSkillActions();
        try {
            EasyBalrogBodyBehavior.setTelegraphSkillActions(false);
            assertFalse(body.telegraphSkillAction(null));
            EasyBalrogBodyBehavior.setTelegraphSkillActions(true);
            assertTrue(body.telegraphSkillAction(null));
        } finally {
            EasyBalrogBodyBehavior.setTelegraphSkillActions(before);
        }
    }

    private record Fixture(ServerMobAutonomyService autonomy, List<Monster> spawned,
                           AtomicReference<Runnable> release) {
    }

    private Fixture fixture() {
        map = mock(MapleMap.class);
        Channel channel = mock(Channel.class);
        OverallService scheduler = mock(OverallService.class);
        ServerMobAutonomyService autonomy = mock(ServerMobAutonomyService.class);
        AtomicInteger nextOid = new AtomicInteger(2_000);
        Map<Integer, Monster> live = new HashMap<>();
        List<Monster> spawned = new ArrayList<>();
        AtomicReference<Runnable> release = new AtomicReference<>();

        when(map.getId()).thenReturn(105100300);
        when(map.getAggroCoordinator()).thenReturn(mock(MonsterAggroCoordinator.class));
        when(map.getChannelServer()).thenReturn(channel);
        when(channel.getServiceAccess(ChannelServices.OVERALL)).thenReturn(scheduler);
        when(channel.getServiceAccess(ChannelServices.MOB_AUTONOMY)).thenReturn(autonomy);
        when(map.getMonsterByOid(anyInt())).thenAnswer(invocation -> live.get(invocation.<Integer>getArgument(0)));
        doAnswer(invocation -> {
            Monster monster = invocation.getArgument(0);
            place(monster, invocation.getArgument(1), nextOid, live, spawned);
            monster.setFake(true);
            return null;
        }).when(map).spawnFakeMonsterOnGroundBelow(any(Monster.class), any(Point.class));
        doAnswer(invocation -> {
            place(invocation.getArgument(0), invocation.getArgument(1), nextOid, live, spawned);
            return null;
        }).when(map).spawnMonsterOnGroundBelow(any(Monster.class), any(Point.class));
        doAnswer(invocation -> {
            long delay = invocation.getArgument(2);
            if (delay == EasyBalrogEncounterService.RELEASE_DELAY_MS) {
                release.set(invocation.getArgument(1));
            }
            return null;
        }).when(scheduler).registerOverallAction(eq(105100300), any(Runnable.class), anyLong());
        doAnswer(invocation -> {
            Monster monster = invocation.getArgument(0);
            live.remove(monster.getObjectId());
            monster.dispatchMonsterKilled(false);
            return null;
        }).when(map).killMonster(any(Monster.class), org.mockito.ArgumentMatchers.isNull(), eq(false),
                eq((short) 0));
        doAnswer(invocation -> {
            ((Monster) invocation.getArgument(0)).setFake(false);
            return null;
        }).when(map).makeMonsterReal(any(Monster.class));
        return new Fixture(autonomy, spawned, release);
    }

    private void place(Monster monster, Point position, AtomicInteger nextOid, Map<Integer, Monster> live,
                       List<Monster> spawned) {
        monster.setMap(map);
        monster.setPosition(position);
        monster.setObjectId(nextOid.getAndIncrement());
        live.put(monster.getObjectId(), monster);
        spawned.add(monster);
    }

    private static Monster mob(List<Monster> monsters, int id) {
        return monsters.stream().filter(monster -> monster.getId() == id).findFirst().orElseThrow();
    }

    private static Monster monster(int id) {
        MonsterStats stats = new MonsterStats();
        stats.hp = 100;
        stats.mp = 100;
        stats.name = Integer.toString(id);
        stats.boss = true;
        return new Monster(id, stats);
    }
}
