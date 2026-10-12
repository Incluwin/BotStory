package server.agents.capabilities.expedition.boss;

import client.BotClient;
import client.Character;
import client.Client;
import client.Job;
import config.AgentYamlConfig;
import net.packet.Packet;
import net.server.services.task.channel.ServerMobAutonomyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.combat.AgentCombatCooldownStateRuntime;
import server.agents.capabilities.combat.data.AgentDefenseDataProvider;
import server.agents.capabilities.mobcontrol.AgentBossScope;
import server.agents.capabilities.movement.AgentMovementStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.expeditions.Expedition;
import server.expeditions.ExpeditionType;
import server.life.Monster;
import server.life.MonsterStats;
import server.life.autonomy.BossAction;
import server.life.autonomy.ServerMobActionCatalog;
import server.maps.MapleMap;
import testutil.LogLines;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Agent half of the boss attack mirror (P8 step 7). A human client that controls a Zakum part reports each attack
 * it plays; the mirror accepts one report per attack animation, waits the attack's attackAfter on the timer, and hits
 * every INSIDE member of the open companion run standing in the attack's shape, each on its own mailbox. Humans are
 * never hit by the mirror (their clients report their own hits), only predicted for calibration. The scheduler, the
 * mailbox and the clock are fakes; the damage roll is fixed at 1000.
 */
class AgentBossAttackMirrorTest {
    private static final String PREFIX = "server.agents.capabilities.expedition.boss.AgentBossAttackMirror.";
    private static final String MODE = PREFIX + "MODE";
    private static final String DEDUP_MAX_MS = PREFIX + "DEDUP_MAX_MS";
    private static final String MAX_SHOWN_HITS = PREFIX + "MAX_SHOWN_HITS";
    private static final String DAMAGE_PERCENT = PREFIX + "DAMAGE_PERCENT";
    private static final String SHARED_HIT_WINDOW = PREFIX + "SHARED_HIT_WINDOW";
    private static final String CALIBRATION_WINDOW_MS = PREFIX + "CALIBRATION_WINDOW_MS";
    private static final String SUMMARY_LOG_MS = PREFIX + "SUMMARY_LOG_MS";
    private static final String BLAST_MODE = PREFIX + "BLAST_MODE";
    private static final String BOSS_IDS = "server.agents.capabilities.mobcontrol.AgentBossScope.BOSS_IDS";
    private static final List<String> TUNING = List.of(MODE, DEDUP_MAX_MS, MAX_SHOWN_HITS, DAMAGE_PERCENT,
            SHARED_HIT_WINDOW, CALIBRATION_WINDOW_MS, SUMMARY_LOG_MS, BLAST_MODE, BOSS_IDS,
            "server.agents.capabilities.mobcontrol.AgentBossScope.ENABLED",
            "server.agents.capabilities.mobcontrol.AgentBossScope.EXPEDITION_TYPES");

    /** Right arm 2: attack1 is a magic box lt(-1100,-600) rb(800,250), attackAfter 1150, stored facing left (noFlip). */
    private static final int ARM = 8_800_004;
    /** Left arm 4: attack1 is a region attack, 6 of 12 regions 100 px apart from -600 px (start -6). */
    private static final int REGION_ARM = 8_800_006;
    /** Right arm 3: attack1 is a jump attack, a physical box lt(-800,-250) rb(1100,0). */
    private static final int JUMP_ARM = 8_800_009;
    /** A Papulatus bomb: self-destructs at half HP; attack1 is a physical box lt(-65,-100) rb(65,0). */
    private static final int PAPULATUS_BOMB = 8_500_003;
    private static final int OID = 1_234;
    private static final int OWNER = 42_000_081;
    private static final int THANE = 42_000_090;
    private static final int ISOLDE = 42_000_091;
    private static final int SORREL = 42_000_092;
    private static final int STRANGER = 42_000_099;
    private static final long NOW = 1_000_000L;
    private static final int ROLL = 1_000;
    private static final Point ORIGIN = new Point(0, 0);
    private static final Point IN_RANGE = new Point(700, 0);
    private static final Point OUT_OF_RANGE = new Point(-1150, 0);

    private final Map<String, String> shipped = new HashMap<>();
    private final List<Scheduled> impacts = new ArrayList<>();
    private final List<Dispatched> hits = new ArrayList<>();
    private final List<Character> players = new ArrayList<>();
    private final Map<Integer, AgentRuntimeEntry> entries = new HashMap<>();
    private long now = NOW;
    private int nextOid = OID;
    private AgentBossAttackMirror mirror;
    private MockedStatic<AgentDefenseDataProvider> defenses;
    private MapleMap map;
    private EventInstanceManager event;
    private AgentBossRunSession session;
    private Character owner;
    private Monster part;

    @BeforeEach
    void setUp() {
        for (String key : TUNING) {
            shipped.put(key, AgentYamlConfig.config.tuning.get(key));
        }
        tune(MODE, "APPLY");
        tune(DEDUP_MAX_MS, "6000");
        tune(MAX_SHOWN_HITS, "30");
        tune(DAMAGE_PERCENT, "100");
        tune(SHARED_HIT_WINDOW, "true");
        tune(CALIBRATION_WINDOW_MS, "1500");
        tune(SUMMARY_LOG_MS, "60000");
        tune("server.agents.capabilities.mobcontrol.AgentBossScope.ENABLED", "true");
        tune("server.agents.capabilities.mobcontrol.AgentBossScope.EXPEDITION_TYPES", "ZAKUM");

        AgentDefenseDataProvider defense = mock(AgentDefenseDataProvider.class);
        when(defense.rollMagicAttackDamage(any(), any(), anyInt())).thenReturn(ROLL);
        when(defense.rollPhysicalTouchDamage(any(), any(), anyInt())).thenReturn(ROLL);
        defenses = mockStatic(AgentDefenseDataProvider.class);
        defenses.when(AgentDefenseDataProvider::getInstance).thenReturn(defense);

        mirror = new AgentBossAttackMirror(new FakePorts());
        event = mock(EventInstanceManager.class);
        Expedition expedition = mock(Expedition.class);
        when(expedition.getType()).thenReturn(ExpeditionType.ZAKUM);
        when(event.getExpedition()).thenReturn(expedition);
        map = mapWith(event);
        owner = human(OWNER, map, event, IN_RANGE);
        part = part(ARM, map, owner);
        session = new AgentBossRunSession(7L, AgentBossRunCatalog.ZAKUM, expedition,
                new FakeBossRunPorts.Fight(), NOW);
        AgentBossRunRegistry.put(session);
    }

    @AfterEach
    void tearDown() {
        defenses.close();
        AgentBossScope.endTrial();
        AgentBossRules.replaceForTest(null);
        AgentBossRunHost.install(null);
        AgentBossRunRegistry.resetForTest();
        AgentBossClientLoad.resetForTest();
        AgentRuntimeRegistry.clear();
        shipped.forEach((key, value) -> {
            if (value == null) {
                AgentYamlConfig.config.tuning.remove(key);
            } else {
                AgentYamlConfig.config.tuning.put(key, value);
            }
        });
    }

    @Test
    void aMemberInRangeLosesHpOnceAtAttackAfterAndGetsOneDamagePacket() {
        Character thane = member(THANE, IN_RANGE);

        attack(part, owner, 0);

        assertEquals(1, impacts.size());
        assertEquals(attack1(ARM).impactDelayMs(), impacts.get(0).delayMs(), "the hit lands at attackAfter");
        assertEquals(1_150L, impacts.get(0).delayMs());
        verify(thane, never()).addMPHPAndTriggerAutopot(anyInt(), anyInt());
        runImpacts();
        runHits();
        verify(thane, times(1)).addMPHPAndTriggerAutopot(-ROLL, 0);
        verify(map, times(1)).broadcastMessage(eq(thane), any(Packet.class), eq(true));
    }

    @Test
    void theHitRunsOnTheTargetsOwnMailbox() {
        Character thane = member(THANE, IN_RANGE);

        attack(part, owner, 0);
        runImpacts();

        assertEquals(1, hits.size());
        assertSame(entries.get(THANE), hits.get(0).entry());
        verify(thane, never()).addMPHPAndTriggerAutopot(anyInt(), anyInt());
        runHits();
        verify(thane).addMPHPAndTriggerAutopot(-ROLL, 0);
    }

    @Test
    void noHitOutOfRangeOnAHumanOnANonMemberAgentOrInAnotherEvent() {
        Character inRange = member(THANE, IN_RANGE);
        Character outOfRange = member(ISOLDE, OUT_OF_RANGE);
        Character human = human(43_000_000, map, event, IN_RANGE);
        Character stranger = bot(STRANGER, IN_RANGE, event);
        Character elsewhere = member(SORREL, IN_RANGE);
        when(elsewhere.getEventInstance()).thenReturn(mock(EventInstanceManager.class));

        attack(part, owner, 0);
        runImpacts();
        assertEquals(1, hits.size(), "one mailbox hit: the member in range");
        runHits();

        verify(inRange).addMPHPAndTriggerAutopot(-ROLL, 0);
        verify(outOfRange, never()).addMPHPAndTriggerAutopot(anyInt(), anyInt());
        verify(stranger, never()).addMPHPAndTriggerAutopot(anyInt(), anyInt());
        verify(elsewhere, never()).addMPHPAndTriggerAutopot(anyInt(), anyInt());
        verify(human, never()).addMPHP(anyInt(), anyInt());
        verify(owner, never()).addMPHP(anyInt(), anyInt());
    }

    @Test
    void aLeavingMemberIsStillHitButAnOutOrRegisteredOneIsNot() {
        Character leaving = member(THANE, IN_RANGE);
        session.member(THANE).leaving(AgentBossRunHost.Out.EXITED, NOW);
        Character out = member(ISOLDE, IN_RANGE);
        session.member(ISOLDE).leftRun(AgentBossRunHost.Out.DIED);
        Character registered = bot(SORREL, IN_RANGE, event);
        session.addMember(SORREL, OWNER, NOW).registered();

        attack(part, owner, 0);
        runImpacts();
        runHits();

        verify(leaving).addMPHPAndTriggerAutopot(-ROLL, 0);
        verify(out, never()).addMPHPAndTriggerAutopot(anyInt(), anyInt());
        verify(registered, never()).addMPHPAndTriggerAutopot(anyInt(), anyInt());
    }

    @Test
    void aDuplicateInsideTheWindowGivesOneImpactAndOneAfterTheWindowGivesTwo() {
        int window = Math.min(6_000, Math.max(attack1(ARM).impactDelayMs(),
                ServerMobActionCatalog.resolvedAnimationMs(ARM, 0)));
        assertTrue(window > attack1(ARM).impactDelayMs(), "the resolved animation outlasts attackAfter");

        attack(part, owner, 0);
        now = NOW + window - 1;
        attack(part, owner, 0);
        assertEquals(1, impacts.size(), "a repeat inside the animation is the same attack");

        now = NOW + window;
        attack(part, owner, 0);
        assertEquals(2, impacts.size(), "a report after the window is the next attack");
    }

    @Test
    void theDedupWindowIsCappedByDedupMaxMsAndKeptPerAttack() {
        tune(DEDUP_MAX_MS, "500");

        attack(part, owner, 0);
        now = NOW + 499;
        attack(part, owner, 0);
        attack(part, owner, 1);
        assertEquals(2, impacts.size(), "attack2 has its own window");
        now = NOW + 500;
        attack(part, owner, 0);
        assertEquals(3, impacts.size());
    }

    @Test
    void anAirborneTargetSkipsAJumpAttackButNotAnOrdinaryOne() {
        Monster jumpArm = part(JUMP_ARM, map, owner);
        assertTrue(ServerMobActionCatalog.jumpAttack(JUMP_ARM, 0));
        Character grounded = member(THANE, new Point(500, 0));
        Character airborne = member(ISOLDE, new Point(500, 0));
        AgentMovementStateRuntime.setInAir(entries.get(ISOLDE), true);

        attack(jumpArm, owner, 0);
        runImpacts();
        runHits();

        verify(grounded).addMPHPAndTriggerAutopot(-ROLL, 0);
        verify(airborne, never()).addMPHPAndTriggerAutopot(anyInt(), anyInt());

        attack(part, owner, 0);
        runImpacts();
        runHits();
        verify(airborne).addMPHPAndTriggerAutopot(-ROLL, 0);
    }

    @Test
    void theSharedHitWindowBlocksAHitAndAHitSetsIt() {
        Character guarded = member(THANE, IN_RANGE);
        Character open = member(ISOLDE, IN_RANGE);
        AgentCombatCooldownStateRuntime.setMobHitCooldownMs(entries.get(THANE), 1_500);
        assertFalse(AgentCombatCooldownStateRuntime.hasMobHitCooldown(entries.get(ISOLDE)));

        attack(part, owner, 0);
        runImpacts();
        runHits();

        verify(guarded, never()).addMPHPAndTriggerAutopot(anyInt(), anyInt());
        verify(open).addMPHPAndTriggerAutopot(-ROLL, 0);
        assertTrue(AgentCombatCooldownStateRuntime.hasMobHitCooldown(entries.get(ISOLDE)),
                "a mirrored hit opens the same window touch damage uses");
    }

    @Test
    void withoutTheSharedWindowAHitIgnoresAndLeavesTheCooldown() {
        tune(SHARED_HIT_WINDOW, "false");
        Character guarded = member(THANE, IN_RANGE);
        Character open = member(ISOLDE, IN_RANGE);
        AgentCombatCooldownStateRuntime.setMobHitCooldownMs(entries.get(THANE), 1_500);

        attack(part, owner, 0);
        runImpacts();
        runHits();

        verify(guarded).addMPHPAndTriggerAutopot(-ROLL, 0);
        verify(open).addMPHPAndTriggerAutopot(-ROLL, 0);
        assertFalse(AgentCombatCooldownStateRuntime.hasMobHitCooldown(entries.get(ISOLDE)));
    }

    @Test
    void maxShownHitsOneWithThreeTargetsGivesThreeHpLossesAndOneDamagePacket() {
        tune(MAX_SHOWN_HITS, "1");
        Character thane = member(THANE, IN_RANGE);
        Character isolde = member(ISOLDE, IN_RANGE);
        Character sorrel = member(SORREL, IN_RANGE);

        attack(part, owner, 0);
        runImpacts();
        runHits();

        verify(thane).addMPHPAndTriggerAutopot(-ROLL, 0);
        verify(isolde).addMPHPAndTriggerAutopot(-ROLL, 0);
        verify(sorrel).addMPHPAndTriggerAutopot(-ROLL, 0);
        verify(map, times(1)).broadcastMessage(any(Character.class), any(Packet.class), eq(true));
    }

    @Test
    void aWindowBlockedTargetDoesNotUseUpAShownHit() {
        tune(MAX_SHOWN_HITS, "1");
        member(THANE, IN_RANGE);
        Character isolde = member(ISOLDE, IN_RANGE);
        AgentCombatCooldownStateRuntime.setMobHitCooldownMs(entries.get(THANE), 1_500);

        attack(part, owner, 0);
        runImpacts();
        runHits();

        verify(map, times(1)).broadcastMessage(eq(isolde), any(Packet.class), eq(true));
    }

    @Test
    void damagePercentFiftyHalvesTheDamage() {
        tune(DAMAGE_PERCENT, "50");
        Character thane = member(THANE, IN_RANGE);

        attack(part, owner, 0);
        runImpacts();
        runHits();

        verify(thane).addMPHPAndTriggerAutopot(-ROLL / 2, 0);
    }

    @Test
    void observeChangesNoHpAndLogsTheDecodeAndThePrediction() {
        tune(MODE, "OBSERVE");
        Character thane = member(THANE, IN_RANGE);

        try (LogLines lines = new LogLines(AgentBossAttackMirror.class)) {
            attack(part, owner, 0);
            runImpacts();

            assertEquals(0, hits.size());
            verify(thane, never()).addMPHPAndTriggerAutopot(anyInt(), anyInt());
            List<String> observed = lines.startingWith("Boss mirror observe");
            assertEquals(1, observed.size(), observed.toString());
            assertTrue(observed.get(0).contains("mob=" + ARM) && observed.get(0).contains("attack=1")
                    && observed.get(0).contains("agents=[" + THANE + "]")
                    && observed.get(0).contains("humans=[" + OWNER + "]"), observed.get(0));
        }
    }

    @Test
    void offDoesNothingAndTheRuntimeOverrideBeatsTheYamlValue() {
        Character thane = member(THANE, IN_RANGE);
        tune(MODE, "OFF");
        attack(part, owner, 0);
        assertEquals(0, impacts.size());
        mirror.reportedHit(owner, part, 0, 500);
        assertTrue(mirror.calibration().isEmpty(), "OFF does not calibrate either");

        mirror.overrideMode(AgentBossAttackMirror.Mode.APPLY);
        assertEquals(AgentBossAttackMirror.Mode.APPLY, mirror.mode());
        attack(part, owner, 0);
        assertEquals(1, impacts.size());

        mirror.overrideMode(AgentBossAttackMirror.Mode.OFF);
        runImpacts();
        assertEquals(0, hits.size(), "a mode switched OFF before the impact stops it");
        verify(thane, never()).addMPHPAndTriggerAutopot(anyInt(), anyInt());

        mirror.overrideMode(null);
        assertEquals(AgentBossAttackMirror.Mode.OFF, mirror.mode(), "null goes back to the yaml value");
    }

    @Test
    void noOpenRunMeansNothing() {
        member(THANE, IN_RANGE);
        AgentBossRunRegistry.remove(session);

        attack(part, owner, 0);

        assertEquals(0, impacts.size());
        AgentBossRunRegistry.put(session);
        attack(part, owner, 0);
        assertEquals(1, impacts.size(), "the same report while the run is open");
    }

    @Test
    void anAllHumanZakumOrAnEasyBalrogPartIsNeverMirrored() {
        member(THANE, IN_RANGE);
        Expedition other = mock(Expedition.class);
        when(other.getType()).thenReturn(ExpeditionType.ZAKUM);
        when(event.getExpedition()).thenReturn(other);
        attack(part, owner, 0);
        assertEquals(0, impacts.size(), "an open run for another expedition does not count");

        when(event.getExpedition()).thenReturn(session.expedition());
        when(session.expedition().getType()).thenReturn(ExpeditionType.BALROG_EASY);
        attack(part, owner, 0);
        assertEquals(0, impacts.size(), "BALROG_EASY is not a guarded type");

        when(session.expedition().getType()).thenReturn(ExpeditionType.ZAKUM);
        attack(part, owner, 0);
        assertEquals(1, impacts.size(), "the run's own Zakum");
    }

    @Test
    void aDeadHiddenBotOffMapLoggedOutOrOtherControllerMeansNothing() {
        member(THANE, IN_RANGE);

        when(owner.isAlive()).thenReturn(false);
        attack(part, owner, 0);
        when(owner.isAlive()).thenReturn(true);
        when(owner.isHidden()).thenReturn(true);
        attack(part, owner, 0);
        when(owner.isHidden()).thenReturn(false);
        when(owner.getMap()).thenReturn(mock(MapleMap.class));
        attack(part, owner, 0);
        when(owner.getMap()).thenReturn(map);
        when(owner.isLoggedinWorld()).thenReturn(false);
        attack(part, owner, 0);
        when(owner.isLoggedinWorld()).thenReturn(true);
        Character other = human(43_000_001, map, event, IN_RANGE);
        attack(part, other, 0);
        assertEquals(0, impacts.size());

        Character botController = bot(STRANGER, IN_RANGE, event);
        when(part.getController()).thenReturn(botController);
        attack(part, botController, 0);
        assertEquals(0, impacts.size(), "a part a bot controls is not guarded");

        when(part.getController()).thenReturn(owner);
        attack(part, owner, 0);
        assertEquals(1, impacts.size(), "the same report from the engaged controller is mirrored");
    }

    @Test
    void aFakePartOrAnAttackPastTheMobsAttacksMeansNothing() {
        member(THANE, IN_RANGE);
        when(part.isFake()).thenReturn(true);
        attack(part, owner, 0);
        when(part.isFake()).thenReturn(false);
        attack(part, owner, ServerMobActionCatalog.forMob(ARM).attacks().size());
        assertEquals(0, impacts.size());
        attack(part, owner, ServerMobActionCatalog.forMob(ARM).attacks().size() - 1);
        assertEquals(1, impacts.size(), "the real part's last attack");
    }

    @Test
    void aPartDeadGoneOrServerOwnedAtImpactMeansNothing() {
        Character thane = member(THANE, IN_RANGE);

        attack(part, owner, 0);
        when(part.isAlive()).thenReturn(false);
        runImpacts();
        assertEquals(0, hits.size(), "dead");
        when(part.isAlive()).thenReturn(true);

        now += 10_000;
        attack(part, owner, 0);
        when(map.getMonsterByOid(OID)).thenReturn(null);
        runImpacts();
        assertEquals(0, hits.size(), "no longer on the map");
        when(map.getMonsterByOid(OID)).thenReturn(part);

        now += 10_000;
        attack(part, owner, 0);
        try (MockedStatic<ServerMobAutonomyService> autonomy = mockStatic(ServerMobAutonomyService.class)) {
            autonomy.when(() -> ServerMobAutonomyService.isActiveInstance(part)).thenReturn(true);
            runImpacts();
        }
        assertEquals(0, hits.size(), "server-owned");
        verify(thane, never()).addMPHPAndTriggerAutopot(anyInt(), anyInt());

        now += 10_000;
        attack(part, owner, 0);
        runImpacts();
        assertEquals(1, hits.size(), "the same part alive, on its map and client-driven is mirrored");
    }

    @Test
    void aRegionAttackHitsOnlyTheRegionsOfTheReportedMask() {
        Monster regionArm = part(REGION_ARM, map, owner);
        BossAction.OrdinaryAttack attack = attack1(REGION_ARM);
        assertTrue(attack.hasDistributedRegions());
        int mask = 0b0000_0011_1111;   // regions 0-5, the six left-most
        Character inRegion = member(THANE, regionCentre(attack, 2));
        Character outOfRegion = member(ISOLDE, regionCentre(attack, 9));

        mirror.controllerAttack(owner, regionArm, 0, false, mask, 1);
        runImpacts();
        runHits();

        verify(inRegion).addMPHPAndTriggerAutopot(-ROLL, 0);
        verify(outOfRegion, never()).addMPHPAndTriggerAutopot(anyInt(), anyInt());
    }

    @Test
    void theTrialMirrorsTheTrialGmsPartsOnTheTrialMapOntoTheCompanionsTheGmLeads() {
        AgentBossRunRegistry.remove(session);
        MapleMap field = mapWith(null);
        Character gm = human(OWNER + 1, field, null, IN_RANGE);
        Monster spawned = part(ARM, field, gm);
        when(spawned.isBoss()).thenReturn(false);
        Character led = bot(THANE, IN_RANGE, null, field, gm.getId());
        Character notLed = bot(ISOLDE, IN_RANGE, null, field, OWNER + 2);

        attack(spawned, gm, 0);
        assertEquals(0, impacts.size(), "no trial, no run: nothing");

        AgentBossScope.startTrial(gm.getId(), field);
        attack(spawned, gm, 0);
        runImpacts();
        runHits();

        verify(led).addMPHPAndTriggerAutopot(-ROLL, 0);
        verify(notLed, never()).addMPHPAndTriggerAutopot(anyInt(), anyInt());

        Character otherGm = human(OWNER + 3, field, null, IN_RANGE);
        when(spawned.getController()).thenReturn(otherGm);
        now += 10_000;
        attack(spawned, otherGm, 0);
        assertEquals(0, impacts.size(), "only the trial GM's reports count");
        assertTrue(mirror.statusLines().stream().anyMatch(line -> line.startsWith("Run session=trial attacks=")
                && !line.contains("botAttacks=")), "the trial is no run, so it has no botAttacks count");
    }

    @Test
    void calibrationCountsMatchedAndUnmatchedReportsAndPredictionsPerAttack() {
        // the owner stands in attack1's shape: each attack1 impact predicts one hit on them
        attack(part, owner, 0);
        now += 1_150;
        runImpacts();
        now += 200;
        mirror.reportedHit(owner, part, 0, 1_234);   // matches the prediction

        now += 10_000;
        attack(part, owner, 0);
        now += 1_000;
        mirror.reportedHit(owner, part, 0, 1_234);   // the report first, the prediction 150 ms later
        now += 150;
        runImpacts();

        now += 10_000;
        attack(part, owner, 0);
        now += 1_150;
        runImpacts();                                 // predicted, never reported
        mirror.reportedHit(owner, part, 1, 99);       // attack2 reported, never predicted
        now += 1_501;

        assertEquals(List.of(
                new AgentBossAttackMirror.CalibrationCounts(ARM, 0, 2, 2, 0, 1),
                new AgentBossAttackMirror.CalibrationCounts(ARM, 1, 1, 0, 1, 0)), mirror.calibration());
        assertTrue(mirror.statusLines().stream().anyMatch(line -> line.contains(ARM + " attack1")
                && line.contains("reported=2") && line.contains("matched=2")
                && line.contains("predictedNotReported=1")), mirror.statusLines().toString());
        verify(owner, never()).addMPHP(anyInt(), anyInt());
    }

    @Test
    void aReportFromAPartWithNoOpenRunIsNotCalibrated() {
        AgentBossRunRegistry.remove(session);

        mirror.reportedHit(owner, part, 0, 1_234);
        now += 5_000;
        assertTrue(mirror.calibration().isEmpty());

        AgentBossRunRegistry.put(session);
        mirror.reportedHit(owner, part, 0, 1_234);
        now += 5_000;
        assertEquals(List.of(new AgentBossAttackMirror.CalibrationCounts(ARM, 0, 1, 0, 1, 0)),
                mirror.calibration(), "the same report while the run is open");
    }

    @Test
    void theSummaryLineCountsTheRunOncePerSummaryInterval() {
        tune(MAX_SHOWN_HITS, "1");
        Character thane = member(THANE, IN_RANGE);
        member(ISOLDE, IN_RANGE);
        AgentCombatCooldownStateRuntime.setMobHitCooldownMs(entries.get(ISOLDE), 1_500);
        // the members' own attacks on the altar, counted by AgentBossClientLoad (botAttacks=)
        when(thane.getMapId()).thenReturn(AgentBossRunCatalog.ZAKUM.battleMapId());
        AgentBossClientLoad.attackCommitted(thane);
        AgentBossClientLoad.attackCommitted(thane);

        try (LogLines lines = new LogLines(AgentBossAttackMirror.class)) {
            attack(part, owner, 0);
            attack(part, owner, 0);
            runImpacts();
            runHits();
            assertTrue(lines.startingWith("Boss mirror summary").isEmpty());

            now += 60_000;
            attack(part, owner, 0);
            List<String> summaries = lines.startingWith("Boss mirror summary");
            assertEquals(List.of("Boss mirror summary session=7 attacks=3 mirrored=2 dispatched=2 applied=1"
                    + " shown=1 window=1 airborne=0 unengaged=0 regionsMask=0 regionsSampled=0 botAttacks=2"),
                    summaries);
        }
    }

    // ---- P12: the field scope and the blast step -------------------------------------------

    @Test
    void aFieldBossWhoseKeyIsOnHitsEveryAgentInRangeCitizensTooAndNothingWhileItsKeyIsOff() {
        AgentBossRunRegistry.remove(session);
        tune(BOSS_IDS, String.valueOf(ARM));
        MapleMap field = mapWith(null);
        Character player = human(OWNER + 1, field, null, IN_RANGE);
        Monster fieldBoss = part(ARM, field, player);
        Character companion = bot(THANE, IN_RANGE, null, field, player.getId());
        Character citizen = bot(ISOLDE, IN_RANGE, null, field, ISOLDE);
        Character outOfRange = bot(SORREL, OUT_OF_RANGE, null, field, SORREL);
        boolean[] keyOn = fieldGroup("field_test", ARM);

        attack(fieldBoss, player, 0);
        assertEquals(0, impacts.size(), "the field key is off");

        keyOn[0] = true;
        attack(fieldBoss, player, 0);
        runImpacts();
        runHits();

        verify(companion).addMPHPAndTriggerAutopot(-ROLL, 0);
        verify(citizen).addMPHPAndTriggerAutopot(-ROLL, 0);
        verify(outOfRange, never()).addMPHPAndTriggerAutopot(anyInt(), anyInt());
        assertTrue(mirror.statusLines().stream().anyMatch(line -> line.startsWith("Run field=")),
                mirror.statusLines().toString());
    }

    @Test
    void aBombsBlastLogsInObserveAndHitsTheRunsMembersInItsBoxInApplyEvenAfterItDied() {
        Character inBox = member(THANE, new Point(30, -10));
        Character outOfBox = member(ISOLDE, new Point(300, -10));
        Monster bomb = part(PAPULATUS_BOMB, map, owner);
        tune(BLAST_MODE, "OBSERVE");

        try (LogLines lines = new LogLines(AgentBossAttackMirror.class)) {
            mirror.selfDestructed(bomb);

            assertEquals(0, hits.size(), "OBSERVE changes no HP");
            List<String> blasts = lines.startingWith("Boss mirror blast");
            assertEquals(1, blasts.size(), blasts.toString());
            assertTrue(blasts.get(0).contains("mob=" + PAPULATUS_BOMB) && blasts.get(0).contains("agents=[" + THANE + "]"),
                    blasts.get(0));
        }

        tune(BLAST_MODE, "APPLY");
        mirror.selfDestructed(bomb);
        assertEquals(1, hits.size(), "one mailbox hit: the member in the bomb's attack1 box");
        when(bomb.isAlive()).thenReturn(false); // the map kills it before the mailbox runs the hit
        runHits();
        verify(inBox).addMPHPAndTriggerAutopot(-ROLL, 0);
        verify(outOfBox, never()).addMPHPAndTriggerAutopot(anyInt(), anyInt());

        mirror.selfDestructed(bomb);
        assertEquals(0, hits.size(), "a bomb already dead blows up no more");
    }

    @Test
    void aBlastOutsideEveryScopeOrWithBlastModeOffDoesNothing() {
        Character thane = member(THANE, new Point(30, -10));
        Monster bomb = part(PAPULATUS_BOMB, map, owner);
        tune(BLAST_MODE, "OFF");
        mirror.selfDestructed(bomb);
        assertEquals(0, hits.size(), "OFF");

        tune(BLAST_MODE, "APPLY");
        AgentBossRunRegistry.remove(session);
        mirror.selfDestructed(bomb);
        assertEquals(0, hits.size(), "no run in its event");

        MapleMap field = mapWith(null);
        mirror.selfDestructed(part(PAPULATUS_BOMB, field, null));
        assertEquals(0, hits.size(), "a field with no key on and no trial");
        verify(thane, never()).addMPHPAndTriggerAutopot(anyInt(), anyInt());
    }

    /** Lists {@code mobId} under field group {@code key} and installs a host whose answer for it is the returned flag. */
    private boolean[] fieldGroup(String key, int mobId) {
        AgentBossRules.replaceForTest(List.of(new AgentBossRules.Boss(key, "Test field", null, null, null, null,
                List.of(mobId), false, null)));
        boolean[] keyOn = {false};
        AgentBossRunHost.install(new AgentBossRunHost.Rule() {
            @Override
            public boolean seat(AgentBossRunHost.Seat seat) {
                return true;
            }

            @Override
            public boolean bossEnabled(String asked) {
                return keyOn[0] && key.equals(asked);
            }
        });
        return keyOn;
    }

    // ---- fixture ---------------------------------------------------------------------------

    private void attack(Monster attacker, Character controller, int attackIndex) {
        mirror.controllerAttack(controller, attacker, attackIndex, false, 0, 1);
    }

    private void runImpacts() {
        List<Scheduled> due = List.copyOf(impacts);
        impacts.clear();
        due.forEach(scheduled -> scheduled.task().run());
    }

    private void runHits() {
        List<Dispatched> due = List.copyOf(hits);
        hits.clear();
        due.forEach(dispatched -> dispatched.hit().run());
    }

    private static BossAction.OrdinaryAttack attack1(int mobId) {
        return ServerMobActionCatalog.forMob(mobId).attacks().get(0);
    }

    /** The centre of {@code region} of a noFlip part at {@link #ORIGIN}, at the bottom of its box. */
    private static Point regionCentre(BossAction.OrdinaryAttack attack, int region) {
        int offset = (attack.areaStart() + region) * 100;
        return new Point(ORIGIN.x + offset + (attack.lt().x + attack.rb().x) / 2, ORIGIN.y + attack.rb().y);
    }

    private void tune(String key, String value) {
        AgentYamlConfig.config.tuning.put(key, value);
    }

    private MapleMap mapWith(EventInstanceManager instance) {
        MapleMap created = mock(MapleMap.class);
        when(created.getEventInstance()).thenReturn(instance);
        when(created.getAllPlayers()).thenAnswer(invocation -> List.copyOf(players));
        return created;
    }

    /** A live, client-driven boss part at {@link #ORIGIN}; the first one gets {@link #OID}, the next ones the ids after it. */
    private Monster part(int mobId, MapleMap on, Character controller) {
        Monster monster = mock(Monster.class);
        MonsterStats stats = new MonsterStats();
        stats.setFixedStance(5);   // Mob.wz info/noFlip 1: every Zakum part
        when(monster.getId()).thenReturn(mobId);
        int oid = nextOid++;
        when(monster.getObjectId()).thenReturn(oid);
        when(monster.isBoss()).thenReturn(true);
        when(monster.isAlive()).thenReturn(true);
        when(monster.getMap()).thenReturn(on);
        when(monster.getController()).thenReturn(controller);
        when(monster.getPosition()).thenReturn(new Point(ORIGIN));
        when(monster.getStats()).thenReturn(stats);
        when(on.getMonsterByOid(oid)).thenReturn(monster);
        return monster;
    }

    private Character human(int id, MapleMap on, EventInstanceManager instance, Point position) {
        Character human = mock(Character.class);
        when(human.getClient()).thenReturn(mock(Client.class));
        when(human.getId()).thenReturn(id);
        when(human.isLoggedinWorld()).thenReturn(true);
        when(human.isAlive()).thenReturn(true);
        when(human.getMap()).thenReturn(on);
        when(human.getEventInstance()).thenReturn(instance);
        when(human.getPosition()).thenReturn(position);
        when(human.getJob()).thenReturn(Job.BEGINNER);
        players.add(human);
        return human;
    }

    /** An INSIDE member of the open run on the battle map. */
    private Character member(int id, Point position) {
        Character bot = bot(id, position, event);
        AgentBossRunMember member = session.addMember(id, OWNER, NOW);
        member.registered();
        member.adopted();
        return bot;
    }

    private Character bot(int id, Point position, EventInstanceManager instance) {
        return bot(id, position, instance, map, OWNER);
    }

    private Character bot(int id, Point position, EventInstanceManager instance, MapleMap on, int leaderId) {
        Character bot = mock(Character.class);
        when(bot.getClient()).thenReturn(mock(BotClient.class));
        when(bot.getId()).thenReturn(id);
        when(bot.isLoggedinWorld()).thenReturn(true);
        when(bot.isAlive()).thenReturn(true);
        when(bot.getMap()).thenReturn(on);
        when(bot.getEventInstance()).thenReturn(instance);
        when(bot.getPosition()).thenReturn(position);
        when(bot.getJob()).thenReturn(Job.BEGINNER);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(bot, null, null);
        AgentRuntimeRegistry.registerEntry(leaderId, entry);
        entries.put(id, entry);
        players.add(bot);
        return bot;
    }

    private final class FakePorts implements AgentBossAttackMirror.Ports {
        private final RandomGenerator random = new Random(7L);

        @Override
        public long nowMs() {
            return now;
        }

        @Override
        public void schedule(Runnable impact, long delayMs) {
            impacts.add(new Scheduled(impact, delayMs));
        }

        @Override
        public void dispatch(AgentRuntimeEntry entry, Runnable hit) {
            hits.add(new Dispatched(entry, hit));
        }

        @Override
        public RandomGenerator random() {
            return random;
        }
    }

    private record Scheduled(Runnable task, long delayMs) {
    }

    private record Dispatched(AgentRuntimeEntry entry, Runnable hit) {
    }
}
