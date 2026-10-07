package server.living.roster;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import scripting.npc.NPCConversationManager;
import server.agents.capabilities.expedition.boss.AgentBossRunDefinition;
import server.agents.capabilities.expedition.boss.AgentBossRunHost.MemberResult;
import server.agents.capabilities.expedition.boss.AgentBossRunHost.Out;
import server.agents.capabilities.expedition.boss.AgentBossRunHost.RunEnded;
import server.agents.capabilities.expedition.boss.AgentBossRunService;
import server.expeditions.Expedition;
import server.expeditions.ExpeditionType;
import server.integration.AgentPresence;
import server.life.NPC;
import server.living.LivingWorld;
import server.living.LivingWorldHooks;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.HENESYS;

/**
 * Mu Young's offer to an owner (P7 Part A step 9): with the switch listing easy_balrog, her Easy window offers a human
 * owner "My party will register too." while the channel's Easy expedition takes sign-ups, a human leads it, no other
 * owner's companions are on it, and one of the owner's own party companions can sign up: loaded, following them, tied
 * to no run, and in her room or gone to the store for potions. Picking it answers in her words and, on companion-ops,
 * enlists those companions (5 at most, in party order, the owner on her list), each short one looking at its potions
 * at once. A companion still waiting to sign up walks to its own spot beside her while its owner is in her room. An
 * Agent's headless sign-up ([1]) never sees the offer: its window stays the stock one.
 */
class CompanionBossRunsOfferTest {
    private static final long SID = 9L;
    private static final int ISOLDE = 901;
    private static final int THANE = 902;
    private static final int SORREL = 903;
    private static final int INGRID = 904;
    private static final int FLINT = 905;
    private static final int ORRIN = 906;
    private static final int MU_YOUNGS_ROOM = 105100100;
    private static final int MU_YOUNG = 1061014;

    private final LifecycleHarness game = new LifecycleHarness();
    private final FakeOpen open = new FakeOpen();
    private final FakeSupplies supplies = new FakeSupplies();
    private final Expedition expedition = mock(Expedition.class);
    private final Map<String, Persona> crew = new HashMap<>();
    private CompanionBossRuns runs;

    @BeforeEach
    void bossfriendCreatedTheEasyExpeditionInMuYoungsRoom() {
        game.config.COMPANION_BOSS_ENABLED_TYPES = "easy_balrog";
        crew.put("isolde", game.companion("isolde", "Isolde", ISOLDE, RosterState.ACTIVE, MU_YOUNGS_ROOM));
        crew.put("thane", game.companion("thane", "Thane", THANE, RosterState.ACTIVE, MU_YOUNGS_ROOM));
        crew.put("sorrel", game.companion("sorrel", "Sorrel", SORREL, RosterState.ACTIVE, MU_YOUNGS_ROOM));
        // three more of Bossfriend's crew, not out with them unless a test calls them
        crew.put("flint", game.companion("flint", "Flint", FLINT, RosterState.ACTIVE, MU_YOUNGS_ROOM));
        crew.put("ingrid", game.companion("ingrid", "Ingrid", INGRID, RosterState.ACTIVE, MU_YOUNGS_ROOM));
        crew.put("orrin", game.companion("orrin", "Orrin", ORRIN, RosterState.ACTIVE, MU_YOUNGS_ROOM));
        game.moveAnn(MU_YOUNGS_ROOM, 617, 246);
        game.roster();
        game.outWithAnn(crew.get("isolde"));
        game.outWithAnn(crew.get("thane"));
        game.outWithAnn(crew.get("sorrel"));
        MapleMap room = game.map(MU_YOUNGS_ROOM);
        NPC muYoung = mock(NPC.class);
        when(muYoung.getPosition()).thenReturn(new Point(617, 246)); // WZ 105100100 life/0
        when(room.getNPCById(MU_YOUNG)).thenReturn(muYoung);
        when(room.getPointBelow(any(Point.class))).thenAnswer(inv -> new Point(((Point) inv.getArgument(0)).x, 252));
        when(game.ann.getClient().getChannelServer().getExpedition(ExpeditionType.BALROG_EASY)).thenReturn(expedition);
        when(expedition.isRegistering()).thenReturn(true);
        when(expedition.getLeader()).thenReturn(game.ann);
        when(expedition.contains(game.ann)).thenReturn(true);
        runs = new CompanionBossRuns(game.world, open, id -> null, supplies);
        when(game.world.bossRuns()).thenReturn(runs);
    }

    @AfterEach
    void close() {
        AgentPresence.install(null);
        game.close();
    }

    // ---- the offer -----------------------------------------------------------------------------

    @Test
    void muYoungOffersAnOwnerWhoseCompanionsStandInHerRoom() {
        assertTrue(runs.offers(game.ann, "easy_balrog"));
        assertTrue(runs.offers(game.ann, " Easy_Balrog "), "the key in any case");
        assertFalse(runs.offers(game.ann, "zakum"), "no other boss in Part A");
        assertFalse(runs.offers(null, "easy_balrog"));
    }

    @Test
    void theKillSwitchEmptyOffersNothing() {
        game.config.COMPANION_BOSS_ENABLED_TYPES = "";

        assertFalse(runs.offers(game.ann, "easy_balrog"));
    }

    @Test
    void anAgentClickerIsNeverOfferedSoItsHeadlessJoinStaysStock() {
        // everything else holds: an owner with companions in her room joins an expedition another human leads
        Character leader = mock(Character.class);
        when(leader.getId()).thenReturn(20);
        when(expedition.getLeader()).thenReturn(leader);
        assertTrue(runs.offers(game.ann, "easy_balrog"));

        AgentPresence.install(chr -> chr.getId() == ANN); // the clicker is an Agent joining headless ([1])

        assertFalse(runs.offers(game.ann, "easy_balrog"), "an Agent's window stays the stock one");
        assertEquals(CompanionBossRuns.NONE_CAN_SIGN_UP, runs.registerFromNpc(game.ann, "easy_balrog"));
        assertEquals(List.of(), open.enlisted);
    }

    @Test
    void noOfferWhileHerListIsClosedOrABotLeadsTheExpedition() {
        when(expedition.isRegistering()).thenReturn(false); // the leader pressed Start
        assertFalse(runs.offers(game.ann, "easy_balrog"));

        when(expedition.isRegistering()).thenReturn(true);
        Character bot = game.bot(950, "Balrog01");
        when(expedition.getLeader()).thenReturn(bot);
        AgentPresence.install(chr -> chr.getId() == 950); // a GM's !balrogtest lobby
        assertFalse(runs.offers(game.ann, "easy_balrog"));

        when(game.ann.getClient().getChannelServer().getExpedition(ExpeditionType.BALROG_EASY)).thenReturn(null);
        assertFalse(runs.offers(game.ann, "easy_balrog"), "no Easy expedition on her channel");
    }

    @Test
    void noOfferOnceAnotherOwnersCompanionsAreOnHerList() {
        open.heldByAnother.add(expedition);

        assertFalse(runs.offers(game.ann, "easy_balrog"));
    }

    @Test
    void noOfferWithoutACompanionThatCanSignUp() {
        game.commitments.commit("pq:kpq:s-1", ANN, ISOLDE); // tied to a party quest
        open.inRun.add(THANE); // already on a boss run
        assertTrue(runs.offers(game.ann, "easy_balrog"), "Sorrel still can");

        moveBot(SORREL, HENESYS);
        assertFalse(runs.offers(game.ann, "easy_balrog"), "off her room and not at the store");

        moveBot(SORREL, MU_YOUNGS_ROOM);
        game.selfLed.add(SORREL);
        assertFalse(runs.offers(game.ann, "easy_balrog"), "leading itself (a bench slice)");

        game.selfLed.remove(SORREL);
        game.loaded.remove(SORREL);
        assertFalse(runs.offers(game.ann, "easy_balrog"), "not loaded");

        game.loaded.add(SORREL);
        game.members.remove((Integer) SORREL);
        assertFalse(runs.offers(game.ann, "easy_balrog"), "not in the owner's party");

        game.members.add(SORREL);
        assertTrue(runs.offers(game.ann, "easy_balrog"));
    }

    @Test
    void aCompanionAtTheStoreCountsAndIsEnlisted() {
        game.commitments.commit("pq:kpq:s-1", ANN, ISOLDE);
        open.inRun.add(THANE);
        moveBot(SORREL, HENESYS);
        assertFalse(runs.offers(game.ann, "easy_balrog"));

        supplies.restocking.add(SORREL); // gone to the store for potions: it signs up once back

        assertTrue(runs.offers(game.ann, "easy_balrog"));
        assertEquals(CompanionBossRuns.SIGN_UP_REPLY, runs.registerFromNpc(game.ann, "easy_balrog"));
        assertEquals(List.of("enlist " + ANN + " easy_balrog [903]"), open.enlisted);
    }

    // ---- picking it ----------------------------------------------------------------------------

    @Test
    void pickingItAnswersInHerWordsAndEnlistsTheCompanionsOnCompanionOps() {
        List<Runnable> ops = DirectExecutor.queue();
        CompanionExecutor queued = DirectExecutor.queued(ops);
        when(game.world.executor()).thenReturn(queued);

        assertEquals("Very well. Have them come and speak with me before time runs out.",
                runs.registerFromNpc(game.ann, "easy_balrog"));
        assertEquals(1, ops.size());
        assertEquals(List.of(), open.enlisted, "nothing enlisted on her I/O thread");

        ops.forEach(Runnable::run);

        assertEquals(List.of("enlist " + ANN + " easy_balrog [901, 902, 903]"), open.enlisted);
        assertEquals(List.of(ISOLDE, THANE, SORREL), supplies.checked, "each one looks at its potions at once");
    }

    @Test
    void withNothingToSignUpSheSaysSoAndNothingIsSubmitted() {
        List<Runnable> ops = DirectExecutor.queue();
        CompanionExecutor queued = DirectExecutor.queued(ops);
        when(game.world.executor()).thenReturn(queued);
        open.heldByAnother.add(expedition);

        assertEquals("None of your companions can sign up right now.", runs.registerFromNpc(game.ann, "easy_balrog"));
        assertEquals(0, ops.size());
    }

    @Test
    void registerPartyTakesFiveAtMostInPartyOrderAndNeedsTheOwnerOnHerList() {
        game.outWithAnn(crew.get("flint"));
        game.outWithAnn(crew.get("ingrid"));
        game.outWithAnn(crew.get("orrin"));
        when(expedition.contains(game.ann)).thenReturn(false); // her list does not hold the owner (yet)

        runs.registerParty(ANN, "easy_balrog");
        assertEquals(List.of(), open.enlisted, "the owner must be on her list");

        when(expedition.contains(game.ann)).thenReturn(true);
        runs.registerParty(ANN, "easy_balrog");

        assertEquals(List.of("enlist " + ANN + " easy_balrog [901, 902, 903, 905, 904]"), open.enlisted);
    }

    @Test
    void anEnlistThatTakesNobodyOpensNoRunAndAsksNobodyToShop() {
        open.refuse.addAll(Set.of(ISOLDE, THANE, SORREL)); // the claim guard refuses them all

        runs.registerParty(ANN, "easy_balrog");

        assertEquals(1, open.enlisted.size());
        assertEquals(List.of(), supplies.checked);
    }

    @Test
    void theOwnersPartOfTheRunIsKeptFromTheEnlist() {
        runs.registerParty(ANN, "easy_balrog");

        // the run ends before any report of Isolde's reached companion-ops: the owner's part of it is known anyway
        runs.finish(new RunEnded(SID, "easy_balrog", true, false, "members-out", 1_000L, 61_000L, 2,
                List.of(new MemberResult(ISOLDE, ANN, true, false, 0, Out.EXITED))));

        assertEquals(1, game.store.performance.size());
        assertEquals("easy_balrog", game.store.performance.get(0).activity());
        assertEquals("isolde", game.store.performance.get(0).personaId());
    }

    // ---- the walk up to her --------------------------------------------------------------------

    @Test
    void aCompanionWaitingToSignUpWalksToItsSpotBesideHerWhileItsOwnerIsInHerRoom() {
        open.waiting.put(THANE, new int[]{ANN, 1}); // enlisted by Ann, second in the run
        CompanionFollowGuard guard = new CompanionFollowGuard(game.world);

        assertEquals(new Point(537, 252), runs.waitsAt(THANE, game.ann));
        assertEquals(new Point(537, 252), guard.waitsAt(THANE, game.ann), "the follow guard asks the boss runs first");

        assertNull(runs.waitsAt(ISOLDE, game.ann), "not waiting to sign up");
        assertNull(runs.waitsAt(THANE, game.bot(951, "Stranger")), "not the human it stays with");
        assertNull(runs.waitsAt(THANE, null));

        game.moveAnn(HENESYS, 1000, 200);
        assertNull(runs.waitsAt(THANE, game.ann), "its owner is anywhere but her room");
        assertNull(guard.waitsAt(THANE, game.ann));

        game.moveAnn(MU_YOUNGS_ROOM, 617, 246);
        open.waiting.remove(THANE); // on her list now
        assertNull(runs.waitsAt(THANE, game.ann));
        assertNull(guard.waitsAt(THANE, game.ann));
    }

    // ---- the hooks in 1061014.js ---------------------------------------------------------------

    @Test
    void theHooksStandAsideWithNoLivingWorldForAnAgentAndOnAFailure() {
        NPCConversationManager cm = mock(NPCConversationManager.class);
        when(cm.getPlayer()).thenReturn(game.ann);
        try (MockedStatic<LivingWorld> living = mockStatic(LivingWorld.class)) {
            living.when(LivingWorld::get).thenReturn(null);
            assertFalse(LivingWorldHooks.bossPartyOffered(cm, "easy_balrog"));
            assertEquals(CompanionBossRuns.NONE_CAN_SIGN_UP, LivingWorldHooks.bossPartyRegister(cm, "easy_balrog"));

            living.when(LivingWorld::get).thenReturn(game.world);
            assertTrue(LivingWorldHooks.bossPartyOffered(cm, "easy_balrog"));
            assertEquals(CompanionBossRuns.SIGN_UP_REPLY, LivingWorldHooks.bossPartyRegister(cm, "easy_balrog"));

            CompanionBossRuns asked = mock(CompanionBossRuns.class);
            when(game.world.bossRuns()).thenReturn(asked);
            AgentPresence.install(chr -> chr.getId() == ANN);
            assertFalse(LivingWorldHooks.bossPartyOffered(cm, "easy_balrog"));
            assertEquals(CompanionBossRuns.NONE_CAN_SIGN_UP, LivingWorldHooks.bossPartyRegister(cm, "easy_balrog"));
            verify(asked, never()).offers(any(), anyString());
            verify(asked, never()).registerFromNpc(any(), anyString());

            AgentPresence.install(null);
            when(asked.offers(any(), anyString())).thenThrow(new IllegalStateException("boom"));
            when(asked.registerFromNpc(any(), anyString())).thenThrow(new IllegalStateException("boom"));
            assertFalse(LivingWorldHooks.bossPartyOffered(cm, "easy_balrog"));
            assertEquals(CompanionBossRuns.NONE_CAN_SIGN_UP, LivingWorldHooks.bossPartyRegister(cm, "easy_balrog"));

            when(game.world.bossRuns()).thenReturn(null);
            assertFalse(LivingWorldHooks.bossPartyOffered(cm, "easy_balrog"));
            assertEquals(CompanionBossRuns.NONE_CAN_SIGN_UP, LivingWorldHooks.bossPartyRegister(cm, "easy_balrog"));
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** The companion steps onto {@code mapId} (the same map mock the harness hands out for it). */
    private void moveBot(int characterId, int mapId) {
        MapleMap map = game.map(mapId);
        game.bots.get(characterId).changeMap(map, new Point(100, 200));
    }

    /** The Agent layer's open runs as a test sets them. */
    private static final class FakeOpen implements CompanionBossRuns.OpenRuns {
        final Set<Expedition> heldByAnother = new HashSet<>();
        final Set<Integer> inRun = new HashSet<>();
        /** Agent id -> {the human it stays with, its ordinal} while it waits to sign up. */
        final Map<Integer, int[]> waiting = new HashMap<>();
        /** Ids the Agent layer turns away (the claim guard, a party quest). */
        final Set<Integer> refuse = new HashSet<>();
        /** "enlist <owner> <boss> <ids>" per enlist, in order. */
        final List<String> enlisted = new ArrayList<>();

        @Override
        public boolean open(long sessionId) {
            return false;
        }

        @Override
        public boolean waiting(long sessionId, int enlistedBy) {
            return false;
        }

        @Override
        public boolean registering(long sessionId) {
            return true;
        }

        @Override
        public boolean signedUpIn(EventInstanceManager event) {
            return false;
        }

        @Override
        public boolean heldByAnother(Expedition expedition, int ownerId) {
            return heldByAnother.contains(expedition);
        }

        @Override
        public boolean inRun(int agentId) {
            return inRun.contains(agentId);
        }

        @Override
        public int waitingOrdinal(int agentId, int anchorId) {
            int[] waits = waiting.get(agentId);
            return waits != null && waits[0] == anchorId ? waits[1] : -1;
        }

        @Override
        public AgentBossRunService.Enlisted enlist(Character owner, AgentBossRunDefinition definition,
                                                   Expedition expedition, List<Integer> agentIds, long nowMs) {
            enlisted.add("enlist " + owner.getId() + " " + definition.key() + " " + agentIds);
            List<Integer> accepted = agentIds.stream().filter(id -> !refuse.contains(id)).toList();
            List<Integer> refused = agentIds.stream().filter(refuse::contains).toList();
            return new AgentBossRunService.Enlisted(accepted.isEmpty() ? 0L : SID, accepted, refused);
        }

        // the logout handoff (CompanionBossRunsDelegateTest) is not under test here

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

    /** The companions' own potion duty as a test sets it. */
    private static final class FakeSupplies implements CompanionBossRuns.Supplies {
        final Set<Integer> restocking = new HashSet<>();
        /** Every companion asked to look at its potions, in order. */
        final List<Integer> checked = new ArrayList<>();

        @Override
        public boolean restocking(int agentId, long nowMs) {
            return restocking.contains(agentId);
        }

        @Override
        public void checkIfShort(int agentId, String bossKey) {
            checked.add(agentId);
        }
    }
}
