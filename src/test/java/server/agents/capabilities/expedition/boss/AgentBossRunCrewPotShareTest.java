package server.agents.capabilities.expedition.boss;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.supplies.AgentPotionRuntime;
import server.agents.capabilities.supplies.AgentPotionService;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.expeditions.Expedition;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * 2026-10-15 (P20 run 3): the bench ally Gunnar (Hero, 1005) ran out of HP potions about seven minutes into Pink Bean
 * and died four times. At 14:23:26 he asked his party for some ("asking the party instead"), but a bench ally leads
 * itself in a party of bots, so the share's donors (its cohort) were himself alone, while his owner's ten companions
 * in the same fight still held 328 to 1,976 HP potions each (the 14:24:47 "PQ potions" line). A self-led member's share
 * now also asks its crew: the run's members who stay with the same human (AgentBossRunRegistry.crewOf). A party
 * companion's share asks its cohort as before, and nothing changes in a run without allies.
 */
class AgentBossRunCrewPotShareTest {
    private static final int OWNER = 2;
    private static final int OTHER_OWNER = 3;
    private static final int GUNNAR = 1_005;
    private static final int GARRICK = 835;
    private static final int THANE = 840;
    private static final int INGRID = 877;
    private static final int FENNA = 336;
    private static final int SECOND_ALLY = 1_009;
    private static final int STRANGER = 9;
    private static final int BATTLE_MAP = 270_050_100;
    private static final int WAITING_ROOM = 270_050_200;
    private static final long NOW = 1_000_000L;

    @AfterEach
    void tearDown() {
        AgentBossRunRegistry.resetForTest();
        for (int leader : List.of(OWNER, OTHER_OWNER, GUNNAR, SECOND_ALLY)) {
            AgentRuntimeRegistry.unregisterLeader(leader);
            AgentPotionService.clearLeaderRuntimeState(leader);
        }
    }

    @Test
    void anAllysCrewIsItsOwnersMembersStillInTheRun() {
        AgentBossRunSession session = pinkBean();
        inside(session.addMember(GUNNAR, OWNER, NOW, AgentBossRunMember.Kind.SELF_LED));
        inside(session.addMember(GARRICK, OWNER, NOW));
        session.addMember(THANE, OWNER, NOW).registered();
        session.addMember(INGRID, OWNER, NOW).refused(AgentBossRunHost.Refusal.CLOSED);
        AgentBossRunMember fenna = session.addMember(FENNA, OWNER, NOW);
        inside(fenna);
        fenna.leftRun(AgentBossRunHost.Out.DIED);
        inside(session.addMember(SECOND_ALLY, OWNER, NOW, AgentBossRunMember.Kind.SELF_LED));
        inside(session.addMember(STRANGER, OTHER_OWNER, NOW));

        assertEquals(List.of(GARRICK, THANE, SECOND_ALLY), AgentBossRunRegistry.crewOf(GUNNAR),
                "not the refused, not the one out, not another owner's");
        assertEquals(OWNER, AgentBossRunRegistry.selfLedAnchor(GUNNAR));
        assertEquals(List.of(), AgentBossRunRegistry.crewOf(GARRICK), "a party companion asks its cohort as before");
        assertEquals(0, AgentBossRunRegistry.selfLedAnchor(GARRICK));
        assertEquals(List.of(), AgentBossRunRegistry.crewOf(424_242), "no member");

        session.member(GUNNAR).anchorTo(OTHER_OWNER); // its owner logged off: it sees the fight through for a human
        assertEquals(List.of(STRANGER), AgentBossRunRegistry.crewOf(GUNNAR), "it stays with its heir's crew");
        assertEquals(OTHER_OWNER, AgentBossRunRegistry.selfLedAnchor(GUNNAR));

        session.member(GUNNAR).leftRun(AgentBossRunHost.Out.EXITED);
        assertEquals(List.of(), AgentBossRunRegistry.crewOf(GUNNAR), "out of the run");
        assertEquals(0, AgentBossRunRegistry.selfLedAnchor(GUNNAR));
        AgentBossRunRegistry.remove(session);
        assertEquals(0, AgentBossRunRegistry.selfLedAnchor(SECOND_ALLY), "the run ended");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aDryAllysAskReachesTheBestStockedCompanionOfItsOwnerInTheFight() {
        AgentBossRunSession session = pinkBean();
        inside(session.addMember(GUNNAR, OWNER, NOW, AgentBossRunMember.Kind.SELF_LED));
        Character gunnar = agent(GUNNAR, BATTLE_MAP);
        AgentRuntimeEntry gunnarEntry = new AgentRuntimeEntry(gunnar, gunnar, null); // it leads itself
        AgentRuntimeRegistry.registerEntry(GUNNAR, gunnarEntry);
        Character owner = agent(OWNER, BATTLE_MAP);
        AgentRuntimeEntry garrick = companion(session, owner, GARRICK, BATTLE_MAP);
        AgentRuntimeEntry thane = companion(session, owner, THANE, BATTLE_MAP);
        AgentRuntimeEntry ingrid = companion(session, owner, INGRID, BATTLE_MAP);
        AgentRuntimeEntry fenna = companion(session, owner, FENNA, WAITING_ROOM); // down, waiting outside
        Character stranger = agent(STRANGER, BATTLE_MAP);
        AgentRuntimeEntry strangerEntry = new AgentRuntimeEntry(stranger, agent(OTHER_OWNER, BATTLE_MAP), null);
        AgentRuntimeRegistry.registerEntry(OTHER_OWNER, strangerEntry);
        inside(session.addMember(STRANGER, OTHER_OWNER, NOW));

        try (MockedStatic<AgentPotionService> potions = mockStatic(AgentPotionService.class, CALLS_REAL_METHODS);
             MockedStatic<AgentPotionRuntime> scheduler = mockStatic(AgentPotionRuntime.class)) {
            potions.when(() -> AgentPotionService.countPotions(gunnar)).thenReturn(new int[]{0, 536});
            potions.when(() -> AgentPotionService.countPotions(garrick.bot())).thenReturn(new int[]{1_976, 2_122});
            potions.when(() -> AgentPotionService.countPotions(thane.bot())).thenReturn(new int[]{1_716, 1_739});
            potions.when(() -> AgentPotionService.countPotions(ingrid.bot())).thenReturn(new int[]{328, 786});
            potions.when(() -> AgentPotionService.countPotions(fenna.bot())).thenReturn(new int[]{3_000, 3_000});
            potions.when(() -> AgentPotionService.countPotions(stranger)).thenReturn(new int[]{3_000, 3_000});

            // the critical resupply's ask: "has no HP_POTION supplier ... asking the party instead"
            assertTrue(AgentPotionService.requestPotShare(gunnarEntry, gunnar, true, true));

            scheduler.verify(() -> AgentPotionRuntime.afterDelay(eq(garrick), anyLong(), any(Runnable.class)));
            for (AgentRuntimeEntry notAsked : List.of(thane, ingrid, fenna, strangerEntry, gunnarEntry)) {
                scheduler.verify(() -> AgentPotionRuntime.afterDelay(eq(notAsked), anyLong(), any(Runnable.class)),
                        never());
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void aCompanionsShareStillAsksOnlyItsCohortWithAnAllyBesideIt() {
        AgentBossRunSession session = pinkBean();
        inside(session.addMember(GUNNAR, OWNER, NOW, AgentBossRunMember.Kind.SELF_LED));
        Character gunnar = agent(GUNNAR, BATTLE_MAP);
        AgentRuntimeEntry gunnarEntry = new AgentRuntimeEntry(gunnar, gunnar, null);
        AgentRuntimeRegistry.registerEntry(GUNNAR, gunnarEntry);
        Character owner = agent(OWNER, BATTLE_MAP);
        AgentRuntimeEntry ingrid = companion(session, owner, INGRID, BATTLE_MAP);
        AgentRuntimeEntry thane = companion(session, owner, THANE, BATTLE_MAP);

        try (MockedStatic<AgentPotionService> potions = mockStatic(AgentPotionService.class, CALLS_REAL_METHODS);
             MockedStatic<AgentPotionRuntime> scheduler = mockStatic(AgentPotionRuntime.class)) {
            potions.when(() -> AgentPotionService.countPotions(ingrid.bot())).thenReturn(new int[]{0, 786});
            potions.when(() -> AgentPotionService.countPotions(thane.bot())).thenReturn(new int[]{0, 1_739});
            potions.when(() -> AgentPotionService.countPotions(gunnar)).thenReturn(new int[]{1_500, 600});

            assertTrue(AgentPotionService.requestPotShare(ingrid, ingrid.bot(), true, true));

            scheduler.verify(() -> AgentPotionRuntime.afterDelay(any(), anyLong(), any(Runnable.class)), never());
        }
    }

    /**
     * The arrival ask (CompanionBossAllies.askCrew): the ally said its own words already, so the share goes out with
     * no "anyone got spare pots?" of the Agent's own, and past the share's cooldown, once for each kind it lacks.
     */
    @Test
    @SuppressWarnings("unchecked")
    void anArrivalAskSharesWithNoAskLineOfItsOwnAndPastTheCooldown() {
        AgentBossRunSession session = pinkBean();
        session.addMember(GUNNAR, OWNER, NOW, AgentBossRunMember.Kind.SELF_LED);
        Character gunnar = agent(GUNNAR, WAITING_ROOM);
        AgentRuntimeEntry gunnarEntry = new AgentRuntimeEntry(gunnar, gunnar, null);
        AgentRuntimeRegistry.registerEntry(GUNNAR, gunnarEntry);
        AgentRuntimeEntry garrick = companion(session, agent(OWNER, WAITING_ROOM), GARRICK, WAITING_ROOM);

        try (MockedStatic<AgentPotionService> potions = mockStatic(AgentPotionService.class, CALLS_REAL_METHODS);
             MockedStatic<AgentPotionRuntime> scheduler = mockStatic(AgentPotionRuntime.class)) {
            potions.when(() -> AgentPotionService.countPotions(garrick.bot())).thenReturn(new int[]{1_976, 2_122});

            assertTrue(AgentPotionService.requestCrewPotShare(gunnar, true));
            assertTrue(AgentPotionService.requestCrewPotShare(gunnar, true), "no cohort cooldown holds it");
            assertFalse(AgentPotionService.requestCrewPotShare(agent(424_242, WAITING_ROOM), true), "no runtime");

            scheduler.verify(() -> AgentPotionRuntime.afterDelay(eq(garrick), anyLong(), any(Runnable.class)),
                    times(2));
            scheduler.verify(() -> AgentPotionRuntime.sayMapNow(eq(gunnar), anyString()), never());
        }
    }

    private static AgentBossRunSession pinkBean() {
        AgentBossRunSession session = new AgentBossRunSession(2L, AgentBossRunCatalog.PINKBEAN, mock(Expedition.class),
                new FakeBossRunPorts.Fight(), NOW);
        AgentBossRunRegistry.put(session);
        return session;
    }

    private static void inside(AgentBossRunMember member) {
        member.registered();
        member.adopted();
    }

    /** One of the owner's party companions in the run: in the owner's cohort, a FOLLOWER member inside. */
    private static AgentRuntimeEntry companion(AgentBossRunSession session, Character owner, int id, int mapId) {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent(id, mapId), owner, null);
        AgentRuntimeRegistry.registerEntry(owner.getId(), entry);
        inside(session.addMember(id, owner.getId(), NOW));
        return entry;
    }

    private static Character agent(int id, int mapId) {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(id);
        when(agent.getMapId()).thenReturn(mapId);
        return agent;
    }
}
