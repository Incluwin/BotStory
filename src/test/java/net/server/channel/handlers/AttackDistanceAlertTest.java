package net.server.channel.handlers;

import client.BotClient;
import client.Character;
import client.Client;
import constants.skills.Magician;
import constants.skills.Warrior;
import net.server.Server;
import net.server.services.task.channel.ServerMobAutonomyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.TimerManager;
import server.expeditions.Expedition;
import server.expeditions.ExpeditionType;
import server.life.Monster;
import server.life.autonomy.balrog.EasyBalrogInitialClawBehavior;
import server.maps.MapleMap;
import server.security.SecurityEvent;
import server.security.SecurityEventRuntime;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Live run 2026-10-07 (Easy Balrog with five companions): every Balrog part reports the shared origin (412,257) while
 * the claw bodies stand 118-404 px to the side. Thane's legal Power Strike on the initial claw 8830009 from (886,258)
 * measured 224,677 px squared against the melee limit of 200,000, so DISTANCE_HACK fired 472 times in one fight, and
 * each alert resent the claw's position to every client. A headless Agent's hit on a server-owned boss actor raises
 * no alert and no position refresh. A human's hit, and an Agent's hit on an ordinary mob, are checked as before.
 */
class AttackDistanceAlertTest {
    private static final int AGENT_ID = 42_000_071;
    private static final int HUMAN_ID = 42_000_072;
    private static final Point BALROG_PART_ORIGIN = new Point(412, 257);
    private static final Point THANE_AT_THE_RIGHT_CLAW = new Point(886, 258);
    private static final double THANE_DISTANCE_SQ = 224_677.0;
    private static final int ORDINARY_MOB_ID = 100_100; // Snail: no server behaviour, never server-owned
    private static final int ZAKUM_ARM_ID = 8_800_003;
    private static final Point ZAKUM_PART_ORIGIN = new Point(-10, -204); // every part spawns at the altar reactor
    private static final double SEVEN_HUNDRED_PX_SQ = 490_000.0; // past the magic limit of 400,000

    private MockedStatic<TimerManager> timers;
    private MockedStatic<Server> servers;
    private ServerMobAutonomyService autonomy;

    @BeforeEach
    void setUp() {
        timers = mockStatic(TimerManager.class);
        timers.when(TimerManager::getInstance).thenReturn(mock(TimerManager.class));
        servers = mockStatic(Server.class);
        servers.when(Server::getInstance).thenReturn(mock(Server.class));
        autonomy = new ServerMobAutonomyService();
    }

    @AfterEach
    void tearDown() {
        autonomy.dispose();
        servers.close();
        timers.close();
    }

    @Test
    void anAgentsHitOnAServerOwnedBossRaisesNoAlertAndNoRefresh() {
        Monster claw = serverOwnedInitialClaw();
        Character thane = attacker("Thane", AGENT_ID, new BotClient(0, 1));
        long floor = latestSequence();

        AbstractDealDamageHandler.checkAttackDistance(powerStrike(), thane, claw);

        assertEquals(List.of(), distanceAlertsSince(floor, AGENT_ID), "no DISTANCE_HACK for the Agent");
        verify(claw, never()).refreshMobPosition();
    }

    @Test
    void aHumansHitOnTheSameServerOwnedBossIsStillChecked() {
        Monster claw = serverOwnedInitialClaw();
        Character human = attacker("Bossfriend", HUMAN_ID, Client.createMock());
        long floor = latestSequence();

        AbstractDealDamageHandler.checkAttackDistance(powerStrike(), human, claw);

        assertEquals(List.of("Distance Sq to monster: " + THANE_DISTANCE_SQ + " SID: " + Warrior.POWER_STRIKE
                        + " MID: " + EasyBalrogInitialClawBehavior.MOB_ID),
                distanceAlertsSince(floor, HUMAN_ID));
        verify(claw).refreshMobPosition();
    }

    @Test
    void anAgentsHitOnAnOrdinaryMobIsStillChecked() {
        Monster ordinary = monster(ORDINARY_MOB_ID, 702, mock(MapleMap.class));
        Character thane = attacker("Thane", AGENT_ID, new BotClient(0, 1));
        long floor = latestSequence();

        AbstractDealDamageHandler.checkAttackDistance(powerStrike(), thane, ordinary);

        assertEquals(1, distanceAlertsSince(floor, AGENT_ID).size(),
                "a mob the server does not own keeps the alert");
        verify(ordinary).refreshMobPosition();
    }

    @Test
    void anAgentsHitInRangeOfAServerOwnedBossRaisesNothing() {
        Monster claw = serverOwnedInitialClaw();
        Character thane = attacker("Thane", AGENT_ID, new BotClient(0, 1));
        thane.setPosition(new Point(600, 258));
        long floor = latestSequence();

        AbstractDealDamageHandler.checkAttackDistance(powerStrike(), thane, claw);

        assertEquals(List.of(), distanceAlertsSince(floor, AGENT_ID));
        verify(claw, never()).refreshMobPosition();
    }

    /**
     * P8 G2: every Zakum part reports the altar reactor spot (-10,-204) as its origin, so a mage at the end of the
     * altar floor hits an arm from 700 px. A headless Agent's hit on a guarded part raises no alert and no refresh,
     * because each refresh takes the arm's controller away from the human (Monster.aggroRemoveController).
     */
    @Test
    void anAgentsMagicHitOnAZakumArmFrom700PxRaisesNoAlertAndNoRefresh() {
        Monster arm = zakumArm();
        Character isolde = attacker("Isolde", AGENT_ID, new BotClient(0, 1));
        isolde.setPosition(new Point(ZAKUM_PART_ORIGIN.x + 700, ZAKUM_PART_ORIGIN.y));
        long floor = latestSequence();

        AbstractDealDamageHandler.checkAttackDistance(energyBolt(), isolde, arm);

        assertEquals(List.of(), distanceAlertsSince(floor, AGENT_ID), "no DISTANCE_HACK for the Agent");
        verify(arm, never()).refreshMobPosition();
    }

    @Test
    void aHumansMagicHitOnTheSameArmFrom700PxStillAlerts() {
        Monster arm = zakumArm();
        Character human = attacker("Bossfriend", HUMAN_ID, Client.createMock());
        human.setPosition(new Point(ZAKUM_PART_ORIGIN.x + 700, ZAKUM_PART_ORIGIN.y));
        long floor = latestSequence();

        AbstractDealDamageHandler.checkAttackDistance(energyBolt(), human, arm);

        assertEquals(List.of("Distance Sq to monster: " + SEVEN_HUNDRED_PX_SQ + " SID: " + Magician.ENERGY_BOLT
                        + " MID: " + ZAKUM_ARM_ID),
                distanceAlertsSince(floor, HUMAN_ID));
        verify(arm).refreshMobPosition();
    }

    /** Arm 8800003 of a Zakum expedition's event, controlled by a human client (not a server-owned actor). */
    private static Monster zakumArm() {
        MapleMap altar = mock(MapleMap.class);
        EventInstanceManager zakumEvent = mock(EventInstanceManager.class);
        Expedition zakum = mock(Expedition.class);
        when(zakum.getType()).thenReturn(ExpeditionType.ZAKUM);
        when(zakumEvent.getExpedition()).thenReturn(zakum);
        when(altar.getEventInstance()).thenReturn(zakumEvent);
        Monster arm = monster(ZAKUM_ARM_ID, 703, altar);
        when(arm.isBoss()).thenReturn(true);
        when(arm.getPosition()).thenReturn(new Point(ZAKUM_PART_ORIGIN));
        return arm;
    }

    private static AbstractDealDamageHandler.AttackInfo energyBolt() {
        AbstractDealDamageHandler.AttackInfo attack = new AbstractDealDamageHandler.AttackInfo();
        attack.skill = Magician.ENERGY_BOLT;
        attack.ranged = false;
        attack.magic = true;
        return attack;
    }

    /** The initial claw of a fight with no native-capable client: the server owns it (SERVER_STICKY). */
    private Monster serverOwnedInitialClaw() {
        MapleMap battle = mock(MapleMap.class);
        Monster claw = monster(EasyBalrogInitialClawBehavior.MOB_ID, 701, battle);
        when(battle.getMonsterByOid(701)).thenReturn(claw);
        autonomy.registerEncounterActor(claw, new Object(), true);
        assertTrue(ServerMobAutonomyService.isActiveInstance(claw), "fixture: the claw is a server-owned actor");
        return claw;
    }

    private static Monster monster(int mobId, int objectId, MapleMap map) {
        Monster monster = mock(Monster.class);
        when(monster.getId()).thenReturn(mobId);
        when(monster.getObjectId()).thenReturn(objectId);
        when(monster.getMap()).thenReturn(map);
        when(monster.isAlive()).thenReturn(true);
        when(monster.getPosition()).thenReturn(new Point(BALROG_PART_ORIGIN));
        return monster;
    }

    private static AbstractDealDamageHandler.AttackInfo powerStrike() {
        AbstractDealDamageHandler.AttackInfo attack = new AbstractDealDamageHandler.AttackInfo();
        attack.skill = Warrior.POWER_STRIKE;
        attack.ranged = false;
        attack.magic = false;
        return attack;
    }

    /** A real character at Thane's live position whose client reports it as the logged-in player. */
    private static Character attacker(String name, int id, Client client) {
        Character chr = Character.getDefault(client);
        chr.setName(name);
        Character spied = spy(chr);
        doReturn(id).when(spied).getId();
        client.setPlayer(spied);
        spied.setPosition(new Point(THANE_AT_THE_RIGHT_CLAW));
        return spied;
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
