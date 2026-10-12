package server.agents.capabilities.expedition.boss;

import client.Character;
import client.Job;
import client.status.MonsterStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.expeditions.Expedition;
import server.life.Monster;
import server.maps.MapleMap;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P12 G4: in companion boss scope a member never hits a monster immune to its damage type or reflecting it. Physical
 * members avoid WEAPON_IMMUNITY and WEAPON_REFLECT, magic members MAGIC_IMMUNITY and MAGIC_REFLECT. Out of scope
 * nothing changes: an Agent that is no inside member (the EPQ stone bug while it is immune), a monster of another
 * event, a field monster whose field key is off.
 */
class AgentBossTargetGateTest {
    private static final int THANE = 10;
    private static final int MANO = 2_220_000;

    private final FakeBossRunPorts.Fight fight = new FakeBossRunPorts.Fight();
    private EventInstanceManager event;
    private MapleMap battle;
    private AgentBossRunSession session;

    @BeforeEach
    void setUp() {
        event = mock(EventInstanceManager.class);
        Expedition expedition = mock(Expedition.class);
        when(event.getExpedition()).thenReturn(expedition);
        battle = mock(MapleMap.class);
        when(battle.getEventInstance()).thenReturn(event);
        session = new AgentBossRunSession(1L, AgentBossRunCatalog.ZAKUM, expedition, fight, 0L);
        AgentBossRunRegistry.put(session);
    }

    @AfterEach
    void tearDown() {
        AgentBossRunRegistry.resetForTest();
        AgentBossRules.replaceForTest(null);
        AgentBossRunHost.install(null);
    }

    @Test
    void anInsidePhysicalMemberAvoidsWeaponImmunityAndWeaponReflectOnly() {
        Character warrior = inside(THANE, Job.FIGHTER);

        assertTrue(AgentBossTargetGate.refuses(warrior, monster(battle, MonsterStatus.WEAPON_IMMUNITY)));
        assertTrue(AgentBossTargetGate.refuses(warrior, monster(battle, MonsterStatus.WEAPON_REFLECT)));
        assertFalse(AgentBossTargetGate.refuses(warrior, monster(battle, MonsterStatus.MAGIC_IMMUNITY)));
        assertFalse(AgentBossTargetGate.refuses(warrior, monster(battle, MonsterStatus.MAGIC_REFLECT)));
        assertFalse(AgentBossTargetGate.refuses(warrior, monster(battle)));
    }

    @Test
    void anInsideMagicMemberAvoidsMagicImmunityAndMagicReflectOnly() {
        Character mage = inside(THANE, Job.FP_WIZARD);

        assertTrue(AgentBossTargetGate.refuses(mage, monster(battle, MonsterStatus.MAGIC_IMMUNITY)));
        assertTrue(AgentBossTargetGate.refuses(mage, monster(battle, MonsterStatus.MAGIC_REFLECT)));
        assertFalse(AgentBossTargetGate.refuses(mage, monster(battle, MonsterStatus.WEAPON_IMMUNITY)));
        assertFalse(AgentBossTargetGate.refuses(mage, monster(battle, MonsterStatus.WEAPON_REFLECT)));
    }

    @Test
    void anAgentThatIsNoInsideMemberKeepsHittingAnImmuneMonsterAsToday() {
        // EPQ's Poisoned Stone Bug (9300173) is physically immune and never in companion boss scope
        MapleMap epqStage = mock(MapleMap.class);
        EventInstanceManager epq = mock(EventInstanceManager.class);
        when(epqStage.getEventInstance()).thenReturn(epq);
        Character epqAgent = agent(THANE + 1, Job.FIGHTER, epq);
        assertFalse(AgentBossTargetGate.refuses(epqAgent, monster(epqStage, MonsterStatus.WEAPON_IMMUNITY)));

        Character registered = agent(THANE + 2, Job.FIGHTER, event);
        session.addMember(THANE + 2, 1, 0L).registered();
        assertFalse(AgentBossTargetGate.refuses(registered, monster(battle, MonsterStatus.WEAPON_IMMUNITY)),
                "a REGISTERED member is not in the fight yet");

        Character warrior = inside(THANE, Job.FIGHTER);
        MapleMap otherEvent = mock(MapleMap.class);
        when(otherEvent.getEventInstance()).thenReturn(mock(EventInstanceManager.class));
        assertFalse(AgentBossTargetGate.refuses(warrior, monster(otherEvent, MonsterStatus.WEAPON_IMMUNITY)),
                "a monster of another event");
    }

    @Test
    void onAFieldEveryAgentAvoidsAnImmuneFieldBossOnlyWhileItsKeyIsOn() {
        AgentBossRules.replaceForTest(List.of(new AgentBossRules.Boss("field_test", "Test field", null, null, null,
                null, List.of(MANO), false, null)));
        boolean[] keyOn = {false};
        AgentBossRunHost.install(new AgentBossRunHost.Rule() {
            @Override
            public boolean seat(AgentBossRunHost.Seat seat) {
                return true;
            }

            @Override
            public boolean bossEnabled(String key) {
                return keyOn[0];
            }
        });
        MapleMap field = mock(MapleMap.class);
        Monster mano = monster(field, MonsterStatus.WEAPON_IMMUNITY);
        when(mano.getId()).thenReturn(MANO);
        Monster snail = monster(field, MonsterStatus.WEAPON_IMMUNITY);
        Character citizen = agent(THANE, Job.FIGHTER, null);

        assertFalse(AgentBossTargetGate.refuses(citizen, mano), "the field key is off");
        keyOn[0] = true;
        assertTrue(AgentBossTargetGate.refuses(citizen, mano));
        assertFalse(AgentBossTargetGate.refuses(citizen, snail), "no field group lists it");
    }

    private Character inside(int agentId, Job job) {
        Character agent = agent(agentId, job, event);
        AgentBossRunMember member = session.addMember(agentId, 1, 0L);
        member.registered();
        member.adopted();
        return agent;
    }

    private static Character agent(int agentId, Job job, EventInstanceManager in) {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(agentId);
        when(agent.getJob()).thenReturn(job);
        when(agent.getEventInstance()).thenReturn(in);
        return agent;
    }

    private static Monster monster(MapleMap on, MonsterStatus... statuses) {
        Set<MonsterStatus> buffed = statuses.length == 0 ? Set.of() : EnumSet.of(statuses[0], statuses);
        Monster monster = mock(Monster.class);
        when(monster.getMap()).thenReturn(on);
        when(monster.isAlive()).thenReturn(true);
        when(monster.isBuffed(any(MonsterStatus.class))).thenAnswer(invocation ->
                buffed.contains((MonsterStatus) invocation.getArgument(0)));
        return monster;
    }
}
