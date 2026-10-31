package net.server.channel.handlers;

import client.BotClient;
import client.BuffStat;
import client.Character;
import client.Client;
import client.Job;
import client.Skill;
import client.SkillFactory;
import constants.skills.ThunderBreaker;
import net.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.TimerManager;
import server.life.Monster;
import server.maps.MapleMap;
import server.security.SecurityEvent;
import server.security.SecurityEventRuntime;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-10-30: a Thunder Breaker's Spark is a follow-up the client sends on its own after a swing while the buff is on.
 * In a human capture (Lv255 Thunder Breaker, Spark 20) each of the 24 sparks re-broadcast the Spark cast effect: the
 * handler recast the buff for every proc and charged its 30 MP, so one with less MP lost the spark. A spark costs
 * nothing and leaves the buff as it is. Its targets jump on from the struck monster across the map (up to 1479 px from
 * the Thunder Breaker, 22 DISTANCE_HACK alerts in three minutes), so an Agent's spark raises no distance alert, and a
 * human's is checked on its first jump only: the nearest of its targets, 65-251 px out in every captured spark.
 */
class SparkProcHandlerTest {
    private static final int AGENT_ID = 42_000_081;
    private static final int HUMAN_ID = 42_000_082;
    private static final int SPARK_LEVEL = 20;

    private MockedStatic<TimerManager> timers;
    private MockedStatic<Server> servers;

    @BeforeAll
    static void loadSkills() {
        SkillFactory.loadAllSkills();
    }

    @BeforeEach
    void setUp() {
        timers = mockStatic(TimerManager.class);
        timers.when(TimerManager::getInstance).thenReturn(mock(TimerManager.class));
        servers = mockStatic(Server.class);
        servers.when(Server::getInstance).thenReturn(mock(Server.class));
    }

    @AfterEach
    void tearDown() {
        servers.close();
        timers.close();
    }

    @Test
    void aSparkLandsWithoutTheBuffsMp() {
        Character thunderBreaker = thunderBreaker(0);

        assertTrue(AbstractDealDamageHandler.applyAttack(spark(), thunderBreaker, 1),
                "with 0 MP the spark still lands");
    }

    @Test
    void aSparkNeitherChargesMpNorRecastsTheBuff() {
        Character thunderBreaker = thunderBreaker(5_000);

        assertTrue(AbstractDealDamageHandler.applyAttack(spark(), thunderBreaker, 1));

        verify(thunderBreaker, never()).applyHpMpChange(anyInt(), anyInt(), anyInt(), anyBoolean());
        verify(thunderBreaker, never()).applyHpMpChange(anyInt(), anyInt(), anyInt());
    }

    @Test
    void aSparkWithoutTheSparkBuffIsRefused() {
        Character thunderBreaker = thunderBreaker(5_000);
        when(thunderBreaker.getBuffedValue(BuffStat.SPARK)).thenReturn(null);

        assertFalse(AbstractDealDamageHandler.applyAttack(spark(), thunderBreaker, 1),
                "a client that sends a spark with no Spark buff gets no free attack");
    }

    @Test
    void anAgentsSparkOnAMonsterAcrossTheMapRaisesNoAlertAndNoRefresh() {
        Character agent = attacker(AGENT_ID, new BotClient(0, 1));
        Monster far = monsterAt(new Point(agent.getPosition().x - 1_000, agent.getPosition().y));
        long floor = latestSequence();

        AbstractDealDamageHandler.checkAttackDistance(spark(), agent, far);

        assertEquals(List.of(), distanceAlertsSince(floor, AGENT_ID));
        verify(far, never()).refreshMobPosition();
    }

    @Test
    void aHumansSparkAcrossTheMapIsStillChecked() {
        Character human = attacker(HUMAN_ID, Client.createMock());
        Monster far = monsterAt(new Point(human.getPosition().x - 1_000, human.getPosition().y));
        long floor = latestSequence();

        AbstractDealDamageHandler.checkAttackDistance(spark(), human, far);

        assertEquals(1, distanceAlertsSince(floor, HUMAN_ID).size());
        verify(far).refreshMobPosition();
    }

    /** 13:19:20.406 in the capture: the Thunder Breaker at (1616,215) sparked monsters at 1384, 758 and 631. */
    @Test
    void aHumansSparkThatJumpsAcrossTheMapRaisesNoAlert() {
        Character human = humanThunderBreakerAt(new Point(1_616, 215));
        Monster first = targetOf(human, 0xCB5E, new Point(1_384, 215));
        Monster second = targetOf(human, 0xCA77, new Point(758, 215));
        Monster third = targetOf(human, 0xCA3D, new Point(631, 215));
        long floor = latestSequence();

        assertTrue(AbstractDealDamageHandler.applyAttack(spark(first, second, third), human, 1));

        assertEquals(List.of(), distanceAlertsSince(floor, HUMAN_ID),
                "live, the jumps 858 and 985 px out raised two alerts");
        verify(first, never()).refreshMobPosition();
        verify(second, never()).refreshMobPosition();
        verify(third, never()).refreshMobPosition();
    }

    @Test
    void aHumansSparkWithNoTargetWithinReachIsFlaggedOnItsNearest() {
        Character human = humanThunderBreakerAt(new Point(1_616, 215));
        Monster nearest = targetOf(human, 1, new Point(616, 215));
        Monster farther = targetOf(human, 2, new Point(2_816, 215));
        Monster farthest = targetOf(human, 3, new Point(216, 215));
        long floor = latestSequence();

        assertTrue(AbstractDealDamageHandler.applyAttack(spark(nearest, farther, farthest), human, 1));

        List<String> alerts = distanceAlertsSince(floor, HUMAN_ID);
        assertEquals(1, alerts.size(), "a spark is checked once, on its first jump: " + alerts);
        assertTrue(alerts.getFirst().startsWith("Distance Sq to monster: 1000000.0 SID: 15111006"), alerts.getFirst());
        verify(nearest).refreshMobPosition();
        verify(farther, never()).refreshMobPosition();
        verify(farthest, never()).refreshMobPosition();
    }

    /** The spark a client sends after a swing that struck a monster which then died: no monster listed. */
    private static AbstractDealDamageHandler.AttackInfo spark() {
        AbstractDealDamageHandler.AttackInfo attack = new AbstractDealDamageHandler.AttackInfo();
        attack.skill = ThunderBreaker.SPARK;
        attack.skilllevel = SPARK_LEVEL;
        attack.numAttackedAndDamage = 0x01;
        attack.numDamage = 1;
        attack.display = 0x80;
        attack.direction = 0x2C;
        attack.stance = 0x80;
        attack.ranged = true;
        attack.targets = new HashMap<>();
        return attack;
    }

    /** {@link #spark()} with one line on each of {@code targets}, in the order it jumps. */
    private static AbstractDealDamageHandler.AttackInfo spark(Monster... targets) {
        AbstractDealDamageHandler.AttackInfo attack = spark();
        for (Monster target : targets) {
            attack.targets.put(target.getObjectId(),
                    new AbstractDealDamageHandler.AttackTarget((short) 400, new ArrayList<>(List.of(50_000))));
        }
        attack.numAttacked = targets.length;
        attack.numAttackedAndDamage = targets.length << 4 | 1;
        return attack;
    }

    private static Character thunderBreaker(int mp) {
        MapleMap map = mock(MapleMap.class);
        Character chr = mock(Character.class);
        // Mockito answers 0 for an unstubbed Integer; the live API answers null for a buff that is absent
        when(chr.getBuffedValue(any(BuffStat.class))).thenReturn(null);
        when(chr.getBuffedValue(BuffStat.SPARK)).thenReturn(SPARK_LEVEL);
        when(chr.getMap()).thenReturn(map);
        when(chr.getJob()).thenReturn(Job.THUNDERBREAKER3);
        when(chr.getSkillLevel(any(Skill.class))).thenAnswer(invocation ->
                (byte) (invocation.<Skill>getArgument(0).getId() == ThunderBreaker.SPARK ? SPARK_LEVEL : 0));
        when(chr.getSkillLevel(ThunderBreaker.SPARK)).thenReturn(SPARK_LEVEL);
        when(chr.getMp()).thenReturn(mp);
        when(chr.getHp()).thenReturn(5_000);
        when(chr.isAlive()).thenReturn(true);
        return chr;
    }

    /** A human Thunder Breaker with Spark on, standing at {@code position}. */
    private static Character humanThunderBreakerAt(Point position) {
        Character chr = thunderBreaker(5_000);
        Client client = Client.createMock();
        client.setPlayer(chr);
        when(chr.getClient()).thenReturn(client);
        when(chr.getId()).thenReturn(HUMAN_ID);
        when(chr.getName()).thenReturn("Bossfriend");
        when(chr.getPosition()).thenReturn(position);
        return chr;
    }

    /** A monster on {@code attacker}'s map with object id {@code objectId}, standing at {@code position}. */
    private static Monster targetOf(Character attacker, int objectId, Point position) {
        Monster monster = monsterAt(position);
        when(monster.getObjectId()).thenReturn(objectId);
        when(attacker.getMap().getMonsterByOid(objectId)).thenReturn(monster);
        return monster;
    }

    private static Character attacker(int id, Client client) {
        Character chr = Character.getDefault(client);
        chr.setName(id == AGENT_ID ? "Volt" : "Bossfriend");
        Character spied = spy(chr);
        doReturn(id).when(spied).getId();
        client.setPlayer(spied);
        spied.setPosition(new Point(1_290, 215));
        return spied;
    }

    private static Monster monsterAt(Point position) {
        Monster monster = mock(Monster.class);
        when(monster.getId()).thenReturn(100_101);
        when(monster.getObjectId()).thenReturn(777);
        when(monster.getMap()).thenReturn(mock(MapleMap.class));
        when(monster.isAlive()).thenReturn(true);
        when(monster.getPosition()).thenReturn(position);
        return monster;
    }

    private static List<String> distanceAlertsSince(long floor, int characterId) {
        return SecurityEventRuntime.snapshot().stream()
                .filter(event -> event.sequence() > floor)
                .filter(event -> event.characterId() == characterId)
                .filter(event -> "DISTANCE_HACK".equals(event.evidence().get("signal")))
                .map(event -> event.evidence().get("reason"))
                .toList();
    }

    private static long latestSequence() {
        List<SecurityEvent> events = SecurityEventRuntime.snapshot();
        return events.isEmpty() ? 0L : events.getLast().sequence();
    }
}
