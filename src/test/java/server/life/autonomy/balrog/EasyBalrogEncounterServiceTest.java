package server.life.autonomy.balrog;

import client.BotClient;
import client.Character;
import client.Client;
import net.packet.Packet;
import net.server.channel.Channel;
import net.server.coordinator.world.MonsterAggroCoordinator;
import net.server.services.task.channel.OverallService;
import net.server.services.task.channel.ServerMobAutonomyService;
import net.server.services.type.ChannelServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import server.life.Monster;
import server.life.MonsterStats;
import server.maps.MapleMap;
import tools.PacketCreator;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EasyBalrogEncounterServiceTest {
    private MapleMap map;

    @AfterEach
    void tearDown() {
        EasyBalrogEncounterService.stop(map, "test-cleanup");
    }

    @Test
    void sealAndTwoDistinctClawDeathsActivateBodyAndClear() {
        Fixture fixture = fixture();
        ServerMobAutonomyService autonomy = fixture.autonomy();
        List<Monster> spawned = fixture.spawned();
        AtomicReference<Runnable> release = fixture.release();

        EasyBalrogEncounterService.EncounterHandle encounter =
                EasyBalrogEncounterService.start(
                        map, new Point(412, 258), EasyBalrogEncounterServiceTest::monster);

        assertEquals(EasyBalrogEncounterService.Phase.SEALED, encounter.phase());
        assertNotNull(release.get());
        Monster body = mob(spawned, EasyBalrogEncounterService.BODY_ID);
        Monster initial = mob(spawned, EasyBalrogEncounterService.INITIAL_CLAW_ID);
        assertTrue(body.dropsDisabled());
        assertTrue(initial.dropsDisabled());
        assertTrue(body.isFake());
        EasyBalrogEncounterService.HpBarSnapshot initialGauge =
                EasyBalrogEncounterService.hpBarSnapshot(initial).orElseThrow();
        assertEquals(EasyBalrogEncounterService.BODY_ID, initialGauge.mobId());
        assertEquals(300, initialGauge.currentHp());
        assertEquals(300, initialGauge.maxHp());
        assertEquals(body.hashCode(), initialGauge.identityHash());
        assertTrue(body.hasBossHPBar());
        assertEquals(body.bossHpBarHash(), initial.bossHpBarHash());

        initial.applyAndGetHpDamage(25, false);
        assertEquals(275, EasyBalrogEncounterService.hpBarSnapshot(initial)
                .orElseThrow().currentHp());

        release.get().run();
        assertEquals(EasyBalrogEncounterService.Phase.TWO_CLAWS, encounter.phase());
        Monster released = mob(spawned, EasyBalrogEncounterService.RELEASED_CLAW_ID);
        assertEquals(body.bossHpBarHash(), released.bossHpBarHash());

        initial.applyAndGetHpDamage(75, false);
        initial.dispatchMonsterKilled(true);
        assertEquals(EasyBalrogEncounterService.Phase.ONE_CLAW, encounter.phase());
        assertEquals(200, EasyBalrogEncounterService.hpBarSnapshot(released)
                .orElseThrow().currentHp());
        released.applyAndGetHpDamage(100, false);
        released.dispatchMonsterKilled(true);
        assertEquals(EasyBalrogEncounterService.Phase.BODY, encounter.phase());
        assertFalse(body.isFake());
        assertEquals(100, EasyBalrogEncounterService.hpBarSnapshot(body)
                .orElseThrow().currentHp());

        body.dispatchMonsterKilled(true);
        assertEquals(EasyBalrogEncounterService.Phase.CLEARED, encounter.phase());
        assertFalse(EasyBalrogEncounterService.isActive(map));
        assertTrue(EasyBalrogEncounterService.hpBarSnapshot(body).isEmpty());
        verify(autonomy, times(3)).registerEncounterActor(any(Monster.class), eq(encounter));
    }

    /**
     * The fake body went out as SPAWN_MONSTER_CONTROL, which gives control to every client that
     * received it. When the server owns the body, each human client must drop that control after
     * the body becomes real; Agents and clients that are changing maps get no STOP.
     */
    @Test
    void serverOwnedBodyActivationStopsStaleHumanControl() {
        Fixture fixture = fixture();
        Character human = player(mock(Client.class), false);
        Character changingMaps = player(mock(Client.class), true);
        Character agent = player(mock(BotClient.class), false);
        when(map.getAllPlayers()).thenReturn(List.of(human, changingMaps, agent));

        EasyBalrogEncounterService.EncounterHandle encounter =
                EasyBalrogEncounterService.start(
                        map, new Point(412, 258), EasyBalrogEncounterServiceTest::monster);
        Monster body = mob(fixture.spawned(), EasyBalrogEncounterService.BODY_ID);
        when(fixture.autonomy().isActive(body)).thenReturn(true);

        killBothClaws(fixture);

        assertEquals(EasyBalrogEncounterService.Phase.BODY, encounter.phase());
        assertFalse(body.isFake());
        InOrder order = inOrder(map, human);
        order.verify(map).makeMonsterReal(body);
        ArgumentCaptor<Packet> stop = ArgumentCaptor.forClass(Packet.class);
        order.verify(human).sendPacket(stop.capture());
        assertArrayEquals(PacketCreator.stopControllingMonster(body.getObjectId()).getBytes(),
                stop.getValue().getBytes());
        verify(human, times(1)).sendPacket(any(Packet.class));
        verify(changingMaps, never()).sendPacket(any(Packet.class));
        verify(agent, never()).sendPacket(any(Packet.class));
    }

    @Test
    void nativeBodyActivationSendsNoStop() {
        Fixture fixture = fixture();
        Character human = player(mock(Client.class), false);
        Character changingMaps = player(mock(Client.class), true);
        Character agent = player(mock(BotClient.class), false);
        when(map.getAllPlayers()).thenReturn(List.of(human, changingMaps, agent));

        EasyBalrogEncounterService.EncounterHandle encounter =
                EasyBalrogEncounterService.start(
                        map, new Point(412, 258), EasyBalrogEncounterServiceTest::monster);
        Monster body = mob(fixture.spawned(), EasyBalrogEncounterService.BODY_ID);
        when(fixture.autonomy().isActive(body)).thenReturn(false);

        killBothClaws(fixture);

        assertEquals(EasyBalrogEncounterService.Phase.BODY, encounter.phase());
        assertFalse(body.isFake());
        verify(map).makeMonsterReal(body);
        verify(human, never()).sendPacket(any(Packet.class));
        verify(changingMaps, never()).sendPacket(any(Packet.class));
        verify(agent, never()).sendPacket(any(Packet.class));
    }

    private record Fixture(ServerMobAutonomyService autonomy, List<Monster> spawned,
                           AtomicReference<Runnable> release) {
    }

    private Fixture fixture() {
        map = mock(MapleMap.class);
        Channel channel = mock(Channel.class);
        OverallService scheduler = mock(OverallService.class);
        ServerMobAutonomyService autonomy = mock(ServerMobAutonomyService.class);
        AtomicInteger nextOid = new AtomicInteger(1_000);
        Map<Integer, Monster> live = new HashMap<>();
        List<Monster> spawned = new ArrayList<>();
        AtomicReference<Runnable> release = new AtomicReference<>();

        when(map.getId()).thenReturn(105100400);
        when(map.getAggroCoordinator()).thenReturn(mock(MonsterAggroCoordinator.class));
        when(map.getChannelServer()).thenReturn(channel);
        when(channel.getServiceAccess(ChannelServices.OVERALL)).thenReturn(scheduler);
        when(channel.getServiceAccess(ChannelServices.MOB_AUTONOMY)).thenReturn(autonomy);
        when(map.getMonsterByOid(anyInt())).thenAnswer(
                invocation -> live.get(invocation.<Integer>getArgument(0)));
        doAnswer(invocation -> {
            Monster monster = invocation.getArgument(0);
            Point position = invocation.getArgument(1);
            place(monster, position, map, nextOid, live, spawned);
            monster.setFake(true);
            return null;
        }).when(map).spawnFakeMonsterOnGroundBelow(any(Monster.class), any(Point.class));
        doAnswer(invocation -> {
            Monster monster = invocation.getArgument(0);
            Point position = invocation.getArgument(1);
            place(monster, position, map, nextOid, live, spawned);
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
        return new Fixture(autonomy, spawned, release);
    }

    private static void killBothClaws(Fixture fixture) {
        fixture.release().get().run();
        mob(fixture.spawned(), EasyBalrogEncounterService.INITIAL_CLAW_ID)
                .dispatchMonsterKilled(true);
        mob(fixture.spawned(), EasyBalrogEncounterService.RELEASED_CLAW_ID)
                .dispatchMonsterKilled(true);
    }

    private static Character player(Client client, boolean changingMaps) {
        Character player = mock(Character.class);
        when(player.getClient()).thenReturn(client);
        when(player.isChangingMaps()).thenReturn(changingMaps);
        return player;
    }

    private static void place(Monster monster, Point position, MapleMap map,
                              AtomicInteger nextOid, Map<Integer, Monster> live,
                              List<Monster> spawned) {
        monster.setMap(map);
        monster.setPosition(position);
        monster.setObjectId(nextOid.getAndIncrement());
        live.put(monster.getObjectId(), monster);
        spawned.add(monster);
    }

    private static Monster mob(List<Monster> monsters, int id) {
        return monsters.stream().filter(monster -> monster.getId() == id)
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
