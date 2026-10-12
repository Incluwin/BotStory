package server.living.roster;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.expedition.boss.AgentBossRunCatalog;
import server.agents.capabilities.expedition.boss.AgentBossRunDefinition;
import server.agents.capabilities.expedition.boss.AgentBossRunService;
import server.expeditions.Expedition;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;

/**
 * P12 G7, a party-instance boss's portal (the hook P14 and P15 call after the stock registerParty): on companion-ops,
 * each owner in the party's event admits their own following companions that stand in it into the boss's run, while
 * the owners rule lets them in. Nothing happens while the switch leaves the boss out, or for an expedition boss.
 */
class CompanionBossRunsPartyEventTest {
    private static final long SID = 12L;
    private static final int ISOLDE = 901;
    private static final int THANE = 902;
    private static final int SORREL = 903;
    private static final int GATE = 541010060;

    private final LifecycleHarness game = new LifecycleHarness();
    private final FakeOpen open = new FakeOpen();
    private final AgentBossRunDefinition latanica = new AgentBossRunDefinition("latanica", null, GATE, 541010100, -1,
            0, List.of(), 0, -1, () -> null, false, List.of(), false, true,
            AgentBossRunDefinition.DeathMode.WAIT_OUTSIDE);
    private final EventInstanceManager event = mock(EventInstanceManager.class);
    private final List<Character> inEvent = new ArrayList<>();
    private final Map<String, CompanionBossRun> parts = new ConcurrentHashMap<>();
    private CompanionBossRuns runs;

    @BeforeEach
    void annsPartyWentThroughTheGate() {
        Persona isolde = game.companion("isolde", "Isolde", ISOLDE, RosterState.ACTIVE, GATE);
        Persona thane = game.companion("thane", "Thane", THANE, RosterState.ACTIVE, GATE);
        Persona sorrel = game.companion("sorrel", "Sorrel", SORREL, RosterState.ACTIVE, GATE);
        game.moveAnn(GATE, 100, 0);
        game.roster();
        game.outWithAnn(isolde);
        game.outWithAnn(thane);
        game.outWithAnn(sorrel);
        game.config.COMPANION_BOSS_ENABLED_TYPES = "latanica";
        inEvent.add(game.ann);
        inEvent.add(game.bot(isolde));
        inEvent.add(game.bot(thane));
        when(event.getPlayers()).thenAnswer(invocation -> List.copyOf(inEvent));
        when(event.getPlayerById(anyInt())).thenAnswer(invocation -> inEvent.stream()
                .filter(character -> character.getId() == (int) invocation.getArgument(0)).findFirst().orElse(null));
        runs = new CompanionBossRuns(game.world, open, id -> new int[]{40, 30}, CompanionBossRuns.Supplies.LIVE, parts,
                new CompanionBossAllies(game.world, open, parts, new CompanionBossQuota(open)));
    }

    @AfterEach
    void close() {
        game.close();
    }

    @Test
    void theOwnersCompanionsInsideTheEventAreAdmittedAndNotedForTheirRecord() {
        runs.admitParty(event, latanica);

        // Sorrel stayed at the gate: not a player of the event
        assertEquals(List.of("admit " + ANN + " latanica [" + ISOLDE + ", " + THANE + "]"), open.admitted);
        CompanionBossRun run = parts.get(CompanionBossRun.key("latanica", SID, ANN));
        assertEquals(Set.of(ISOLDE, THANE), run.signedUp);
        assertEquals("isolde", run.personaByCompanion.get(ISOLDE));
        assertArrayEquals(new int[]{40, 30}, run.potionsAtSignUp.get(THANE));
    }

    @Test
    void aCompanionTiedToAnotherRunOrAlreadyInABossRunIsLeftOut() {
        game.commitments.commit("pq:kpq:s-1", ANN, ISOLDE);
        open.inRun.add(THANE);

        runs.admitParty(event, latanica);

        assertEquals(List.of(), open.admitted, "nobody of Ann's is free");
    }

    @Test
    void anotherOwnersCompanionsInTheRunKeepAnOwnerOutUnlessTheCapAllowsMore() {
        open.otherOwners = 1;
        runs.admitParty(event, latanica);
        assertEquals(List.of(), open.admitted, "Part A: one owner per run");

        game.config.COMPANION_BOSS_OWNERS_MAX = 2;
        runs.admitParty(event, latanica);
        assertEquals(1, open.admitted.size(), "a second owner fits under a cap of 2");
    }

    @Test
    void theHookHandsTheAdmitToCompanionOpsOnlyForAPartyBossTheSwitchLists() {
        List<Runnable> ops = DirectExecutor.queue();
        CompanionExecutor queued = DirectExecutor.queued(ops);
        when(game.world.executor()).thenReturn(queued);

        runs.partyEntered(event, "easy_balrog");
        runs.partyEntered(event, "no_such_boss");
        assertEquals(0, ops.size(), "an expedition boss and an unknown key admit nothing");
        assertTrue(AgentBossRunCatalog.of("latanica") == null, "no catalog row yet: P14 adds it");
    }

    /** The open runs as a test sets them; only the party-instance answers are under test here. */
    private static final class FakeOpen implements CompanionBossRuns.OpenRuns {
        final Set<Integer> inRun = new HashSet<>();
        final List<String> admitted = new ArrayList<>();
        int otherOwners;

        @Override
        public int otherOwnersIn(EventInstanceManager event, int ownerId) {
            return otherOwners;
        }

        @Override
        public AgentBossRunService.Enlisted admit(Character owner, AgentBossRunDefinition definition,
                                                  EventInstanceManager event, List<Integer> agentIds, long nowMs) {
            admitted.add("admit " + owner.getId() + " " + definition.key() + " " + agentIds);
            return new AgentBossRunService.Enlisted(SID, agentIds, List.of());
        }

        @Override
        public boolean open(long sessionId) {
            return sessionId == SID;
        }

        @Override
        public boolean waiting(long sessionId, int enlistedBy) {
            return false;
        }

        @Override
        public boolean registering(long sessionId) {
            return false;
        }

        @Override
        public boolean signedUpIn(EventInstanceManager event) {
            return false;
        }

        @Override
        public boolean heldByAnother(Expedition expedition, int ownerId) {
            return false;
        }

        @Override
        public boolean inRun(int agentId) {
            return inRun.contains(agentId);
        }

        @Override
        public int waitingOrdinal(int agentId, int anchorId) {
            return -1;
        }

        @Override
        public AgentBossRunService.Enlisted enlist(Character owner, AgentBossRunDefinition definition,
                                                   Expedition expedition, List<Integer> agentIds, long nowMs) {
            throw new UnsupportedOperationException("no expedition here");
        }

        @Override
        public boolean underway(long sessionId) {
            return false;
        }

        @Override
        public boolean inside(long sessionId, int agentId) {
            return false;
        }

        @Override
        public boolean handTo(long sessionId, int agentId, int heirId) {
            return false;
        }
    }
}
