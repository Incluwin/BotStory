package server.agents.capabilities.partyquest.lpq;

import client.Character;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.maps.MapItem;
import server.maps.MapleMap;
import server.maps.Reactor;

import java.awt.Point;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P6g LPQ C2.3 (decision D-a): a companion run withholds every LPQ rescue. Each primitive with a companion run makes
 * no world call, returns "not applied", and logs one 'LPQ rescue: ... applied=false companionRun=true' line per
 * (kind, site, member, phase); a logging failure never lets the rescue through. A stock run's rescue acts and logs
 * applied=true as before.
 */
class AgentLpqRescueGateTest {
    private static final int S3 = AgentLpqDefinition.stage(3).mapId();
    private final LpqTickFixture fx = new LpqTickFixture();
    private final List<Character> crew = fx.crew(S3, new Point(0, 0));
    private final Character leader = crew.getFirst();
    private final Character kes = crew.get(2);

    @AfterEach
    void tearDown() {
        fx.close();
    }

    private AgentLpqSession companionRun() {
        return fx.session(true, AgentLpqSession.Phase.STAGE_3, crew.toArray(Character[]::new));
    }

    /** Every primitive, called once on {@code session}; each answers whether its rescue was applied. */
    private List<Supplier<Boolean>> everyPrimitive(AgentLpqSession session) {
        MapleMap map = fx.map(S3);
        Reactor box = mock(Reactor.class);
        when(box.isAlive()).thenReturn(true);
        when(box.isActive()).thenReturn(true);
        return List.of(
                () -> AgentLpqCoordinator.restoreEventMember(session, "dead-agent-reentry", kes, S3, 5_000L),
                () -> AgentLpqCoordinator.moveWithinEvent(session, "catch-up-absolute", kes, S3, 5_000L),
                () -> AgentLpqCoordinator.recoverStageTwoTrap(session, "catch-up-trap", kes, 5_000L),
                () -> AgentLpqCoordinator.rescueTeleport(session, "approach", fx.entry(kes), kes, new Point(9, 9)),
                () -> AgentLpqCoordinator.remoteBoxHits(session, "stage5-room-ceiling", kes, List.of(box)) > 0,
                () -> AgentLpqCoordinator.vacuumItemDrops(session, "coupon-regroup", leader, Set.of(map),
                        AgentLpqDefinition.PASS) > 0,
                () -> {
                    AgentLpqCoordinator.forcePassTransferToLeader(session, "final-rally", leader,
                            AgentLpqDefinition.stage(3), 0L, 5_000L);
                    return null;
                },
                () -> AgentLpqCoordinator.grantItem(session, AgentLpqRescue.Kind.KEY_AWARD, "boss-key-award", leader,
                        AgentLpqDefinition.BOSS_KEY, (short) 1),
                () -> {
                    AgentLpqCoordinator.killWithDrops(session, "stage4-hard-recovery", map, List.of(9_300_008));
                    return null;
                });
    }

    private void worldReady() {
        fx.drop(S3, AgentLpqDefinition.PASS, 2, new Point(40, 0), false, 0, 1_000L);
        fx.holds(kes, AgentLpqDefinition.PASS, 8);
        when(fx.inventory.addItem(any(), anyInt(), anyShort())).thenReturn(true);
        when(fx.actions.hitReactor(any(), anyInt())).thenReturn(true);
    }

    private void verifyNoWorldCall() {
        verify(fx.event, never()).registerPlayer(any(), anyBoolean());
        verify(fx.mapGateway, never()).changeMapNear(any(), any(), any());
        verify(fx.actions, never()).stagePosition(any(), any(), any());
        verify(fx.actions, never()).hitReactor(any(), anyInt());
        verify(fx.inventory, never()).addItem(any(), anyInt(), anyShort());
        verify(fx.inventory, never()).removeById(any(), any(), anyInt(), anyInt(), anyBoolean(), anyBoolean());
        verify(fx.mapGateway, never()).removeItemDrop(any(), any(MapItem.class), anyInt(), anyInt());
        verify(fx.map(S3), never()).killMonsterWithDrops(anyInt());
    }

    @Test
    void aCompanionRunWithholdsEveryRescueAndSaysSoOncePerSiteAndPhase() {
        worldReady();
        AgentLpqSession session = companionRun();
        try (LpqLogLines log = new LpqLogLines(AgentLpqRescue.class)) {
            for (Supplier<Boolean> primitive : everyPrimitive(session)) {
                Boolean applied = primitive.get();
                assertTrue(applied == null || !applied, "not applied");
            }
            verifyNoWorldCall();
            List<String> lines = log.startingWith("LPQ rescue: ");
            assertEquals(9, lines.size(), String.join("\n", lines));
            assertTrue(lines.stream().allMatch(line -> line.contains(" applied=false companionRun=true ")), "" + lines);
            assertTrue(lines.contains("LPQ rescue: session=" + session.sessionId() + " kind=TELEPORT site=approach"
                    + " phase=STAGE_3 member=Kes(3) applied=false companionRun=true detail=map=" + S3 + " to=9,9"));
            assertTrue(lines.stream().anyMatch(line -> line.contains("kind=KILL_WITH_DROPS site=stage4-hard-recovery "
                    + "phase=STAGE_3 member=-(-) applied=false")), "" + lines);

            everyPrimitive(session).forEach(Supplier::get);
            assertEquals(9, log.startingWith("LPQ rescue: ").size(), "the same (kind, site, member, phase): no line");

            session.transition(AgentLpqSession.Phase.STAGE_4, 6_000L);
            everyPrimitive(session).forEach(Supplier::get);
            assertEquals(18, log.startingWith("LPQ rescue: ").size(), "a new phase: noted again");
            verifyNoWorldCall();
        }
    }

    @Test
    void aSiteWithoutItsSessionFindsTheCompanionRunByTheMember() {
        AgentLpqSession session = companionRun();
        try (LpqLogLines log = new LpqLogLines(AgentLpqRescue.class)) {
            assertFalse(AgentLpqCoordinator.rescueTeleport(null, "stage5-unobserved-box", fx.entry(kes), kes,
                    new Point(1, 2)));
            verify(fx.actions, never()).stagePosition(any(), any(), any());
            assertEquals(1, log.startingWith("LPQ rescue: session=" + session.sessionId()
                    + " kind=TELEPORT site=stage5-unobserved-box").size());
        }
    }

    @Test
    void aLoggingFailureNeverLetsARescueThrough() {
        AgentLpqSession session = companionRun();
        LoggerContext context = LoggerContext.getContext(false);
        String name = AgentLpqRescue.class.getName();
        AbstractAppender throwing = new AbstractAppender("lpq-throwing", null, null, false, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                throw new IllegalStateException("appender down");
            }
        };
        throwing.start();
        LoggerConfig config = new LoggerConfig(name, Level.INFO, false);
        config.addAppender(throwing, Level.INFO, null);
        context.getConfiguration().addLogger(name, config);
        context.updateLoggers();
        try {
            assertFalse(AgentLpqCoordinator.rescueTeleport(session, "approach", fx.entry(kes), kes, new Point(1, 2)));
            verify(fx.actions, never()).stagePosition(any(), any(), any());
        } finally {
            context.getConfiguration().removeLogger(name);
            context.updateLoggers();
            throwing.stop();
        }
    }

    @Test
    void aStockRescueActsAndSaysItWasApplied() {
        worldReady();
        AgentLpqSession session = fx.session(false, AgentLpqSession.Phase.STAGE_3, crew.toArray(Character[]::new));
        try (LpqLogLines log = new LpqLogLines(AgentLpqRescue.class)) {
            assertTrue(AgentLpqCoordinator.rescueTeleport(session, "approach", fx.entry(kes), kes, new Point(9, 9)));
            verify(fx.actions).stagePosition(fx.entry(kes), kes, new Point(9, 9));
            assertTrue(AgentLpqCoordinator.restoreEventMember(session, "dead-agent-reentry", kes, S3, 5_000L));
            verify(fx.event).registerPlayer(kes, false);
            assertEquals(2, log.startingWith("LPQ rescue: ").stream()
                    .filter(line -> line.contains(" applied=true companionRun=false ")).count());
        }
    }
}
