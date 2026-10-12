package server.agents.capabilities.expedition.boss;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.agents.auth.AgentClaimGuard;
import server.expeditions.Expedition;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P12 G7: a party-instance boss (no expedition) keeps its companion run by the party's event. The portal admits the
 * companions it took in, the run adopts each one on the battle map in that event, and every reader that looked a run
 * up by the event's expedition finds it by the event instead (AgentBossRunRegistry.forEvent).
 */
class AgentBossRunPartyEventTest {
    private static final int OWNER = 1;
    private static final int SECOND_OWNER = 2;
    private static final int THANE = 10;
    private static final int ISOLDE = 11;
    private static final long NOW = 1_000_000L;
    private static final int GATE_MAP = 541010060;
    private static final int BATTLE_MAP = 541010100;

    private final FakeBossRunPorts ports = new FakeBossRunPorts();
    private final FakeBossRunPorts.Host host = new FakeBossRunPorts.Host(ports.calls);
    private final FakeBossRunPorts.Fight fight = new FakeBossRunPorts.Fight();
    private final AgentBossRunDefinition latanica = new AgentBossRunDefinition("latanica_test", null, GATE_MAP,
            BATTLE_MAP, -1, 0, List.of(), 0, -1, () -> fight, false, List.of(), false, true,
            AgentBossRunDefinition.DeathMode.WAIT_OUTSIDE);
    private EventInstanceManager event;
    private Character owner;

    @BeforeEach
    void setUp() {
        event = mock(EventInstanceManager.class);
        when(event.getExpedition()).thenReturn(null);
        when(event.getPlayerCount()).thenReturn(3);
        when(event.getPlayerById(anyInt())).thenReturn(null);
        owner = ports.human(OWNER);
        when(event.getPlayers()).thenReturn(List.of(owner));
        when(event.getPlayerById(OWNER)).thenReturn(owner);
        AgentClaimGuard.install(new AgentClaimGuard.Rule() {
            @Override
            public boolean mayControl(Character actor, int agentCharacterId) {
                return true;
            }

            @Override
            public boolean isClaimed(int agentCharacterId) {
                return true;
            }
        });
    }

    @AfterEach
    void tearDown() {
        AgentClaimGuard.install(null);
        AgentBossRunRegistry.resetForTest();
    }

    @Test
    void theFirstThreeCatalogBossesAreExpeditionsThePartyBossesAreNotAndEveryDeadOneWaitsOutside() {
        for (AgentBossRunDefinition definition : AgentBossRunCatalog.all()) {
            boolean party = definition.key().equals("latanica") || definition.key().startsWith("neo_")
                    || definition.key().equals("papulatus");
            assertEquals(party, definition.partyInstance(), definition.key());
            assertEquals(Set.of("scarga", "horntail").contains(definition.key())
                    ? AgentBossRunDefinition.DeathMode.REENTER
                    : AgentBossRunDefinition.DeathMode.WAIT_OUTSIDE, definition.deathMode(), definition.key());
        }
        assertTrue(latanica.partyInstance());
        assertThrows(IllegalArgumentException.class, () -> new AgentBossRunDefinition("x", null, 1, 2, 3, 4,
                List.of(), 5, 6, () -> fight, false, List.of(), false, false));
    }

    @Test
    void portalAdmitsTheCompanionsAsRegisteredInARunKeyedByTheEvent() {
        AgentBossRunService.Enlisted admitted = AgentBossRunService.openForEvent(ports, owner, latanica, event,
                List.of(THANE, ISOLDE), NOW);

        assertEquals(List.of(THANE, ISOLDE), admitted.accepted());
        AgentBossRunSession session = AgentBossRunRegistry.byId(admitted.sessionId());
        assertSame(session, AgentBossRunRegistry.forEvent(event));
        assertSame(session, AgentBossRunRegistry.forMember(THANE));
        assertNull(session.expedition());
        assertSame(event, session.event());
        assertEquals(AgentBossRunSession.Phase.STARTING, session.phase());
        assertEquals(AgentBossRunMember.Phase.REGISTERED, session.member(THANE).phase());
        assertEquals(1, session.member(ISOLDE).ordinal());
        assertEquals(1, ports.scheduled.size(), "the run's monitor is scheduled once");
        assertNull(AgentBossRunRegistry.forEvent(mock(EventInstanceManager.class)), "another event has no run");
    }

    @Test
    void secondOwnerOfTheSameEventJoinsTheOneRun() {
        long first = AgentBossRunService.openForEvent(ports, owner, latanica, event, List.of(THANE), NOW).sessionId();

        AgentBossRunService.Enlisted second = AgentBossRunService.openForEvent(ports, ports.human(SECOND_OWNER),
                latanica, event, List.of(ISOLDE), NOW + 100L);

        assertEquals(first, second.sessionId());
        assertEquals(SECOND_OWNER, AgentBossRunRegistry.byId(first).member(ISOLDE).enlistedBy());
        assertEquals(1, ports.scheduled.size());
    }

    @Test
    void expeditionBossOrDisposedEventAdmitsNobody() {
        AgentBossRunService.Enlisted expeditionBoss = AgentBossRunService.openForEvent(ports, owner,
                AgentBossRunCatalog.EASY_BALROG, event, List.of(THANE), NOW);
        assertEquals(0L, expeditionBoss.sessionId());
        assertEquals(List.of(THANE), expeditionBoss.refused());

        when(event.isEventDisposed()).thenReturn(true);
        AgentBossRunService.Enlisted disposed = AgentBossRunService.openForEvent(ports, owner, latanica, event,
                List.of(THANE), NOW);
        assertEquals(0L, disposed.sessionId());
        assertNull(AgentBossRunRegistry.forEvent(event));
    }

    @Test
    void memberOnTheBattleMapInTheEventIsAdoptedAndFights() {
        AgentBossRunSession session = admitted(List.of(THANE));
        inside(THANE);

        session.tick(ports, host, NOW + 500L);

        assertEquals(AgentBossRunMember.Phase.INSIDE, session.member(THANE).phase());
        assertEquals(AgentBossRunSession.Phase.FIGHTING, session.phase());
        assertEquals(List.of("adopt " + THANE + " 0", "tick [" + THANE + "]"), fight.calls);
        assertTrue(host.held.contains(THANE), "tied to the owner's run as it is adopted");
    }

    /**
     * P18: a fight over several maps (boss-rules.json stageMaps: Horntail's pre-head maps) adopts and fights a member
     * on a stage map, where the Start puts everyone; the recruit map is no fight map.
     */
    @Test
    void aMemberOnAPreHeadMapOfHorntailsFightIsAdoptedAndFights() {
        AgentBossRunDefinition horntail = new AgentBossRunDefinition("horntail", null, 240_050_400, 240_060_200, -1,
                0, List.of(), 0, -1, () -> fight, true, List.of(), false, true,
                AgentBossRunDefinition.DeathMode.REENTER);
        long sessionId = AgentBossRunService.openForEvent(ports, owner, horntail, event, List.of(THANE, ISOLDE), NOW)
                .sessionId();
        AgentBossRunSession session = AgentBossRunRegistry.byId(sessionId);
        ports.place(THANE, 240_060_000, 100, 0, 70);
        ports.eventOf.put(THANE, event);
        ports.place(ISOLDE, 240_050_400, 100, 0, 70);
        ports.eventOf.put(ISOLDE, event);

        session.tick(ports, host, NOW + 500L);

        assertEquals(AgentBossRunMember.Phase.INSIDE, session.member(THANE).phase());
        assertEquals(AgentBossRunMember.Phase.REGISTERED, session.member(ISOLDE).phase());
        assertTrue(fight.calls.contains("tick [" + THANE + "]"), fight.calls.toString());
    }

    @Test
    void memberInAnotherEventIsNeverAdoptedAndMissesTheStartAfterTheGrace() {
        AgentBossRunSession session = admitted(List.of(THANE));
        ports.place(THANE, BATTLE_MAP, 100, 0, 70);
        ports.eventOf.put(THANE, mock(EventInstanceManager.class));

        session.tick(ports, host, NOW + 500L);
        assertEquals(AgentBossRunMember.Phase.REGISTERED, session.member(THANE).phase());

        session.tick(ports, host, NOW + AgentBossRunSession.startGraceMs() + 1L);

        assertEquals(AgentBossRunHost.Out.MISSED_START, session.member(THANE).out());
        assertEquals(AgentBossRunSession.Phase.ENDED, session.phase());
        assertNull(AgentBossRunRegistry.forEvent(event), "the ended run left the event index");
        assertFalse(fight.calls.contains("end"), "a fight that adopted nobody is not told of the end");
    }

    @Test
    void readersThatLookUpByEventFindThePartyRun() {
        AgentBossRunSession session = admitted(List.of(THANE));
        inside(THANE);
        when(event.getPlayerById(THANE)).thenReturn(ports.agent(THANE));

        assertTrue(AgentBossRunRegistry.signedUpIn(event), "a registered companion stands in the event");
        session.tick(ports, host, NOW + 500L);
        assertTrue(AgentBossRunRegistry.signedUpIn(event), "and an inside one");
    }

    @Test
    void expeditionRunIsStillFoundByItsEvent() {
        Expedition expedition = mock(Expedition.class);
        EventInstanceManager expeditionEvent = mock(EventInstanceManager.class);
        when(expeditionEvent.getExpedition()).thenReturn(expedition);
        AgentBossRunSession session = new AgentBossRunSession(9L, AgentBossRunCatalog.EASY_BALROG, expedition, fight,
                NOW);
        AgentBossRunRegistry.put(session);

        assertSame(session, AgentBossRunRegistry.forEvent(expeditionEvent));
        assertNull(session.admit(THANE, OWNER, NOW, AgentBossRunMember.Kind.FOLLOWER),
                "an expedition run takes members only by sign-up");
    }

    @Test
    void runPastItsClearAdmitsNoMore() {
        AgentBossRunSession session = admitted(List.of(THANE));
        inside(THANE);
        session.tick(ports, host, NOW + 500L);
        when(event.isEventCleared()).thenReturn(true);
        session.tick(ports, host, NOW + 1_000L);
        assertEquals(AgentBossRunSession.Phase.POST_CLEAR, session.phase());

        assertNull(session.admit(ISOLDE, OWNER, NOW + 1_500L, AgentBossRunMember.Kind.FOLLOWER));
        assertEquals(Set.of(THANE), Set.copyOf(session.members().stream().map(AgentBossRunMember::agentId).toList()));
    }

    private AgentBossRunSession admitted(List<Integer> agentIds) {
        long sessionId = AgentBossRunService.openForEvent(ports, owner, latanica, event, agentIds, NOW).sessionId();
        ports.calls.clear();
        return AgentBossRunRegistry.byId(sessionId);
    }

    /** The portal's warp: the member stands on the battle map in the party's event. */
    private void inside(int agentId) {
        ports.place(agentId, BATTLE_MAP, 100 + agentId, 0, 70);
        ports.eventOf.put(agentId, event);
    }
}
