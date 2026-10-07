package server.agents.capabilities.expedition.boss;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import server.agents.auth.AgentClaimGuard;
import server.agents.capabilities.expedition.balrog.AgentBalrogDefinition;
import server.agents.capabilities.partyquest.AgentPartyQuestRuntime;
import server.expeditions.Expedition;
import server.expeditions.ExpeditionType;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Enlisting adopts only the enlister's own claimed Agents that are free, into one session per expedition. */
class AgentBossRunServiceEnlistTest {
    private static final int OWNER = 1;
    private static final int SECOND_OWNER = 2;
    private static final long NOW = 1_000_000L;

    private final FakeBossRunPorts ports = new FakeBossRunPorts();
    private final FakeBossRunPorts.Host host = new FakeBossRunPorts.Host(ports.calls);
    private Character owner;
    private Character secondOwner;
    private Set<Integer> uncontrolled = Set.of();
    private Set<Integer> unclaimed = Set.of();

    @BeforeEach
    void setUp() {
        owner = human(OWNER);
        secondOwner = human(SECOND_OWNER);
        AgentClaimGuard.install(new AgentClaimGuard.Rule() {
            @Override
            public boolean mayControl(Character actor, int agentCharacterId) {
                return !uncontrolled.contains(agentCharacterId);
            }

            @Override
            public boolean isClaimed(int agentCharacterId) {
                return !unclaimed.contains(agentCharacterId);
            }
        });
    }

    @AfterEach
    void tearDown() {
        AgentClaimGuard.install(null);
        AgentBossRunHost.install(null);
        AgentBossRunRegistry.resetForTest();
    }

    @Test
    void agentTheEnlisterMayNotControlOrTheHostDoesNotClaimIsRefused() {
        uncontrolled = Set.of(11);
        unclaimed = Set.of(12);

        AgentBossRunService.Enlisted enlisted = enlist(owner, registering(), List.of(10, 11, 12));

        assertEquals(List.of(10), enlisted.accepted());
        assertEquals(List.of(11, 12), enlisted.refused());
        AgentBossRunSession session = AgentBossRunRegistry.byId(enlisted.sessionId());
        assertEquals(List.of(10), session.members().stream().map(AgentBossRunMember::agentId).toList());
        assertNull(AgentBossRunRegistry.forMember(11));
    }

    @Test
    void noHostClaimRefusesEveryAgent() {
        AgentClaimGuard.install(null);

        AgentBossRunService.Enlisted enlisted = enlist(owner, registering(), List.of(10));

        assertEquals(List.of(), enlisted.accepted());
        assertEquals(List.of(10), enlisted.refused());
        assertEquals(0L, enlisted.sessionId());
        assertEquals(List.of(), ports.scheduled);
    }

    @Test
    void agentInAnotherSessionOrInAPartyQuestIsRefused() {
        Expedition first = registering();
        Expedition other = registering();
        AgentBossRunService.Enlisted held = enlist(owner, first, List.of(10));
        assertEquals(List.of(10), held.accepted());

        try (MockedStatic<AgentPartyQuestRuntime> partyQuests = Mockito.mockStatic(AgentPartyQuestRuntime.class)) {
            partyQuests.when(() -> AgentPartyQuestRuntime.active(13)).thenReturn(true);

            AgentBossRunService.Enlisted enlisted = enlist(owner, other, List.of(10, 13, 14));

            assertEquals(List.of(14), enlisted.accepted());
            assertEquals(List.of(10, 13), enlisted.refused());
            assertNotEquals(held.sessionId(), enlisted.sessionId());
        }
        assertSame(AgentBossRunRegistry.byId(held.sessionId()), AgentBossRunRegistry.forMember(10));
    }

    @Test
    void sameExpeditionJoinsOneSessionWithOneMonitorAndPreparedMuYoungScripts() {
        Expedition expedition = registering();

        AgentBossRunService.Enlisted first = enlist(owner, expedition, List.of(10, 11));
        AgentBossRunService.Enlisted again = enlist(owner, expedition, List.of(11, 12));

        assertEquals(first.sessionId(), again.sessionId());
        assertEquals(List.of(12), again.accepted());
        assertEquals(List.of(11), again.refused(), "an Agent is enlisted once per run");
        AgentBossRunSession session = AgentBossRunRegistry.forExpedition(expedition);
        assertEquals(List.of(0, 1, 2), session.members().stream().map(AgentBossRunMember::ordinal).toList());
        assertEquals(1, ports.scheduled.size());
        assertEquals(List.of(AgentBossRunService.monitorMs()), ports.scheduledDelays);
        assertEquals(1L, ports.count("prepareScripts " + AgentBalrogDefinition.ENTRY_NPC));
        assertSame(AgentBossRunCatalog.EASY_BALROG, session.definition());
        assertTrue(AgentBossRunRegistry.anyWaiting());
    }

    @Test
    void catalogFindsEasyBalrogByItsKeyOnly() {
        assertSame(AgentBossRunCatalog.EASY_BALROG, AgentBossRunCatalog.of(" Easy_Balrog "));
        assertNull(AgentBossRunCatalog.of("zakum"));
        assertNull(AgentBossRunCatalog.of(" "));
        AgentBossRunDefinition balrog = AgentBossRunCatalog.EASY_BALROG;
        assertEquals(ExpeditionType.BALROG_EASY, balrog.type());
        assertEquals(List.of(AgentBalrogDefinition.RECRUIT_MAP, AgentBalrogDefinition.BATTLE_MAP,
                        AgentBalrogDefinition.CLEAR_MAP, AgentBalrogDefinition.ENTRY_NPC, 1_061_018, 2),
                List.of(balrog.recruitMapId(), balrog.battleMapId(), balrog.clearMapId(), balrog.entryNpcId(),
                        balrog.exitNpcId(), balrog.rewardExitPortalId()));
        assertEquals(List.of(1), balrog.joinSelections());
        assertNotSame(balrog.fight().get(), balrog.fight().get(), "each session gets its own fight");
    }

    @Test
    void secondEnlisterJoinsTheSameSession() {
        Expedition expedition = registering();

        AgentBossRunService.Enlisted first = enlist(owner, expedition, List.of(10));
        AgentBossRunService.Enlisted second = enlist(secondOwner, expedition, List.of(20));

        assertEquals(first.sessionId(), second.sessionId());
        AgentBossRunSession session = AgentBossRunRegistry.forExpedition(expedition);
        assertEquals(OWNER, session.member(10).enlistedBy());
        assertEquals(SECOND_OWNER, session.member(20).enlistedBy());
        assertEquals(SECOND_OWNER, session.member(20).anchorId());
        assertEquals(1, session.member(20).ordinal());
    }

    @Test
    void expeditionThatIsNotRegisteringTakesNobody() {
        Expedition started = registering();
        when(started.isRegistering()).thenReturn(false);

        AgentBossRunService.Enlisted enlisted = enlist(owner, started, List.of(10));

        assertEquals(List.of(10), enlisted.refused());
        assertNull(AgentBossRunRegistry.forExpedition(started));
    }

    @Test
    void failingTickEndsTheRunAsAnErrorAndStopsTheMonitor() {
        AgentBossRunHost.install(host);
        Expedition expedition = registering();
        when(expedition.getStartTime()).thenThrow(new IllegalStateException("broken expedition"));
        ports.place(10, AgentBossRunCatalog.EASY_BALROG.recruitMapId(), 567, 246, 70);
        enlist(owner, expedition, List.of(10));
        AgentBossRunSession session = AgentBossRunRegistry.forExpedition(expedition);

        ports.scheduled.getFirst().run();

        assertEquals(AgentBossRunSession.Phase.ENDED, session.phase());
        assertEquals(List.of("error"), host.ended.stream().map(AgentBossRunHost.RunEnded::reason).toList());
        assertEquals(1, ports.scheduled.size(), "an ended run schedules no further monitor");
        assertNull(AgentBossRunRegistry.forMember(10));
    }

    @Test
    void healthyMonitorReschedulesItself() {
        AgentBossRunHost.install(host);
        Expedition expedition = registering();
        enlist(owner, expedition, List.of(10));

        ports.scheduled.getFirst().run();

        assertEquals(2, ports.scheduled.size());
        assertEquals(List.of(), host.ended);
    }

    private AgentBossRunService.Enlisted enlist(Character human, Expedition expedition, List<Integer> ids) {
        return AgentBossRunService.enlist(ports, human, AgentBossRunCatalog.EASY_BALROG, expedition, ids, NOW);
    }

    private static Character human(int id) {
        Character human = mock(Character.class);
        when(human.getId()).thenReturn(id);
        when(human.getName()).thenReturn("Human" + id);
        return human;
    }

    private static Expedition registering() {
        Expedition expedition = mock(Expedition.class);
        when(expedition.isRegistering()).thenReturn(true);
        when(expedition.getStartTime()).thenReturn(NOW + 300_000L);
        when(expedition.getType()).thenReturn(ExpeditionType.BALROG_EASY);
        when(expedition.getMaxSize()).thenReturn(30);
        when(expedition.getMembers()).thenReturn(Map.of(OWNER, "Bossfriend"));
        return expedition;
    }
}
