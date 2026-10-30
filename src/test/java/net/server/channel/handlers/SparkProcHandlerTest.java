package net.server.channel.handlers;

import client.BotClient;
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
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * the Thunder Breaker, 20 DISTANCE_HACK alerts in three minutes), so an Agent's spark raises no distance alert; a
 * human's is checked as before.
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

    private static Character thunderBreaker(int mp) {
        MapleMap map = mock(MapleMap.class);
        Character chr = mock(Character.class);
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
