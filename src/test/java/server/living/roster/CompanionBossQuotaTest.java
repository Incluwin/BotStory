package server.living.roster;

import client.Character;
import config.YamlConfig;
import net.server.channel.Channel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.expedition.boss.AgentBossRunCatalog;
import server.agents.capabilities.expedition.boss.AgentBossRunDefinition;
import server.agents.capabilities.expedition.boss.AgentBossRunHost.MemberResult;
import server.agents.capabilities.expedition.boss.AgentBossRunHost.Out;
import server.agents.capabilities.expedition.boss.AgentBossRunHost.RunEnded;
import server.agents.capabilities.expedition.boss.AgentBossRunHost.Seat;
import server.agents.capabilities.expedition.boss.AgentBossRunService.Enlisted;
import server.expeditions.Expedition;
import server.expeditions.ExpeditionBossLog;
import server.expeditions.ExpeditionType;
import server.integration.AgentPresence;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;
import testutil.BossLogDatabase;
import testutil.LogLines;

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;

/**
 * Companions' daily boss entries (P8 step 11, plan decisions Q3 and Q4): before a companion is enlisted for a boss that
 * keeps an entry log (Zakum), companion-ops asks the stock count for it; one with no entry left says so in its own
 * words and is not enlisted, and one allowed is kept (allowed answers only) for the run. The boss log's hook then
 * answers its sign-up inside the entry NPC's script and the Start from memory, with no JDBC and no insert; its entry
 * is logged once as it enters the fight, never when it missed the start, and the run's end forgets the answers. A
 * player always gets the stock count and insert. Easy Balrog keeps no entry log, so nothing here touches it.
 */
class CompanionBossQuotaTest {
    private static final long SID = 11L;
    private static final int ISOLDE = 901;
    private static final int THANE = 902;
    private static final int SORREL = 903;
    private static final int OTHER_PLAYER = 40;
    private static final int ADOBIS_ROOM = 211042400;
    private static final int CHANNEL = 3;

    private final LifecycleHarness game = new LifecycleHarness();
    private final FakeOpen open = new FakeOpen();
    private final FakeLog stock = new FakeLog();
    private final Expedition expedition = mock(Expedition.class);
    private MockedStatic<LivingWorld> living;
    private CompanionBossRuns runs;
    private boolean dailyWas;

    @BeforeEach
    void bossfriendLeadsAZakumListAtAdobis() {
        dailyWas = YamlConfig.config.server.USE_ENABLE_DAILY_EXPEDITIONS;
        YamlConfig.config.server.USE_ENABLE_DAILY_EXPEDITIONS = true;
        game.config.COMPANION_BOSS_ENABLED_TYPES = "zakum";
        Persona isolde = game.companion("isolde", "Isolde", ISOLDE, RosterState.ACTIVE, ADOBIS_ROOM);
        Persona thane = game.companion("thane", "Thane", THANE, RosterState.ACTIVE, ADOBIS_ROOM);
        Persona sorrel = game.companion("sorrel", "Sorrel", SORREL, RosterState.ACTIVE, ADOBIS_ROOM);
        game.moveAnn(ADOBIS_ROOM, -800, -236);
        game.roster();
        game.outWithAnn(isolde);
        game.outWithAnn(thane);
        game.outWithAnn(sorrel);
        MapleMap room = game.map(ADOBIS_ROOM);
        Channel channel = mock(Channel.class);
        when(channel.getId()).thenReturn(CHANNEL);
        when(room.getChannelServer()).thenReturn(channel);
        when(game.ann.getClient().getChannelServer().getExpedition(ExpeditionType.ZAKUM)).thenReturn(expedition);
        when(expedition.getType()).thenReturn(ExpeditionType.ZAKUM);
        when(expedition.isRegistering()).thenReturn(true);
        when(expedition.getLeader()).thenReturn(game.ann);
        when(expedition.contains(game.ann)).thenReturn(true);
        when(expedition.getRecruitingMap()).thenReturn(room);
        open.expedition = expedition;
        runs = new CompanionBossRuns(game.world, open, id -> null, new CompanionBossRuns.Supplies() {
            @Override
            public boolean restocking(int agentId, long nowMs) {
                return false;
            }

            @Override
            public void checkIfShort(int agentId, String bossKey) {
            }
        });
        when(game.world.bossRuns()).thenReturn(runs);
        living = mockStatic(LivingWorld.class);
        living.when(LivingWorld::get).thenReturn(game.world);
    }

    @AfterEach
    void close() {
        YamlConfig.config.server.USE_ENABLE_DAILY_EXPEDITIONS = dailyWas;
        living.close();
        AgentPresence.install(null);
        game.close();
    }

    // ---- the pre-check and the hook, through the real boss log -------------------------------------------------

    @Test
    void aCappedCompanionIsNotEnlistedAndSaysSoTheOthersAre() {
        try (BossLogDatabase db = new BossLogDatabase()) {
            db.counts.put(SORREL, 1); // Sorrel went in today

            runs.registerParty(ANN, "zakum");

            assertEquals(List.of("enlist " + ANN + " zakum [" + ISOLDE + ", " + THANE + "]"), open.enlisted);
            assertEquals(List.of("count " + ISOLDE + " ZAKUM", "count " + THANE + " ZAKUM",
                    "count " + SORREL + " ZAKUM"), db.statements, "the stock count on companion-ops, before enlisting");
            assertEquals(1, game.partyChat.size(), game.partyChat.toString());
            assertTrue(saysQuota("Sorrel", game.partyChat.get(0)), game.partyChat.get(0));
        }
    }

    @Test
    void everyCappedCompanionSaysSoAndNobodyIsEnlisted() {
        try (BossLogDatabase db = new BossLogDatabase()) {
            db.counts.put(ISOLDE, 1);
            db.counts.put(THANE, 1);
            db.counts.put(SORREL, 1);

            runs.registerParty(ANN, "zakum");

            assertEquals(List.of(), open.enlisted);
            assertEquals(3, game.partyChat.size(), game.partyChat.toString());
            assertTrue(saysQuota("Isolde", game.partyChat.get(0)), game.partyChat.get(0));
            assertTrue(saysQuota("Thane", game.partyChat.get(1)), game.partyChat.get(1));
            assertTrue(saysQuota("Sorrel", game.partyChat.get(2)), game.partyChat.get(2));
        }
    }

    @Test
    void signUpForAnEnlistedCompanionMakesNoJdbcCallAndTheStartLogsOnlyThePlayers() {
        try (BossLogDatabase db = new BossLogDatabase()) {
            runs.registerParty(ANN, "zakum");
            db.clear();

            // Adobis's script lists each companion (Expedition.addMember, inside the NPC script lock).
            assertTrue(ExpeditionBossLog.attemptBoss(ISOLDE, CHANNEL, expedition, false));
            assertTrue(ExpeditionBossLog.attemptBoss(THANE, CHANNEL, expedition, false));
            assertEquals(0, db.connections(), "no JDBC on the run's tick");

            // The Start (Expedition.start: every member, the log on): only the player is logged.
            assertTrue(ExpeditionBossLog.attemptBoss(ANN, CHANNEL, expedition, true));
            assertTrue(ExpeditionBossLog.attemptBoss(ISOLDE, CHANNEL, expedition, true));
            assertTrue(ExpeditionBossLog.attemptBoss(SORREL, CHANNEL, expedition, true));
            assertEquals(List.of("count " + ANN + " ZAKUM", "insert " + ANN + " ZAKUM"), db.statements);
        }
    }

    @Test
    void anAnswerHoldsOnlyForTheExpeditionItWasGivenFor() {
        try (BossLogDatabase db = new BossLogDatabase()) {
            runs.registerParty(ANN, "zakum");
            db.clear();
            Expedition another = mock(Expedition.class); // another channel's Zakum list
            when(another.getType()).thenReturn(ExpeditionType.ZAKUM);

            assertTrue(ExpeditionBossLog.attemptBoss(ISOLDE, CHANNEL, another, false));
            assertTrue(ExpeditionBossLog.attemptBoss(OTHER_PLAYER, CHANNEL, expedition, false));

            assertEquals(List.of("count " + ISOLDE + " ZAKUM", "count " + OTHER_PLAYER + " ZAKUM"), db.statements);
        }
    }

    @Test
    void adoptionLogsEachMembersEntryOnceAndAMissedStartLogsNothing() {
        try (BossLogDatabase db = new BossLogDatabase()) {
            runs.registerParty(ANN, "zakum");
            db.clear();

            runs.entered(seat(ISOLDE));
            runs.entered(seat(THANE));
            runs.entered(seat(ISOLDE)); // the same member and run again
            runs.entered(new Seat("zakum", SID + 1, SORREL, ANN)); // another run's seat
            // Sorrel missed the start: the run never reports him in.

            assertEquals(List.of(ISOLDE + " ZAKUM", THANE + " ZAKUM"), db.inserts());
            assertEquals(db.inserts().size(), db.statements.size(), "no count: the entry is logged as it is");
        }
    }

    @Test
    void theEntryIsLoggedOnCompanionOpsNeverOnTheRunsTick() {
        try (BossLogDatabase db = new BossLogDatabase()) {
            runs.registerParty(ANN, "zakum");
            db.clear();
            List<Runnable> ops = DirectExecutor.queue();
            CompanionExecutor queued = DirectExecutor.queued(ops);
            when(game.world.executor()).thenReturn(queued);

            runs.entered(seat(ISOLDE)); // on the run's tick, inside its session lock

            assertEquals(0, db.connections(), "no JDBC on the run's tick");
            assertEquals(List.of(), db.inserts());
            ops.forEach(Runnable::run);
            assertEquals(List.of(ISOLDE + " ZAKUM"), db.inserts(), "logged once companion-ops runs it");
        }
    }

    @Test
    void theRunsEndForgetsItsAnswersSoTheNextSignUpIsCountedAgain() {
        try (BossLogDatabase db = new BossLogDatabase()) {
            runs.registerParty(ANN, "zakum");
            runs.entered(seat(ISOLDE));
            db.clear();

            runs.runEnded(new RunEnded(SID, "zakum", true, false, "members-out", 1_000L, 61_000L, 2,
                    List.of(new MemberResult(ISOLDE, ANN, true, false, 0, Out.EXITED))));

            assertTrue(ExpeditionBossLog.attemptBoss(ISOLDE, CHANNEL, expedition, false));
            assertEquals(List.of("count " + ISOLDE + " ZAKUM"), db.statements);
            runs.entered(seat(THANE));
            assertEquals(List.of(), db.inserts(), "nothing is logged for a run that is over");
        }
    }

    @Test
    void aMemberOfTheOpenRunWithNoAllowedAnswerKeepsTheStockPathWithOneWarning() {
        open.anchors.put(ISOLDE, ANN); // enlisted while no answer was kept (the living world restarted, say)
        try (BossLogDatabase db = new BossLogDatabase();
             LogLines lines = new LogLines(CompanionBossQuota.class)) {
            assertTrue(ExpeditionBossLog.attemptBoss(ISOLDE, CHANNEL, expedition, false));
            assertTrue(ExpeditionBossLog.attemptBoss(ANN, CHANNEL, expedition, false));

            assertEquals(List.of("count " + ISOLDE + " ZAKUM", "count " + ANN + " ZAKUM"), db.statements);
            assertEquals(1, lines.startingWith("Boss quota: ").size(), lines.startingWith("").toString());
            assertTrue(lines.startingWith("Boss quota: ").get(0).contains(Integer.toString(ISOLDE)));
        }
    }

    // ---- the quota on its own (fake boss log) ------------------------------------------------------------------

    @Test
    void aBossWithNoEntryLogIsNeverCountedNorKept() {
        CompanionBossQuota quota = new CompanionBossQuota(open, stock);
        Expedition easy = mock(Expedition.class);

        assertTrue(quota.admit(AgentBossRunCatalog.EASY_BALROG, easy, ISOLDE));
        quota.enlisted(easy, new Enlisted(SID, List.of(ISOLDE), List.of()), List.of(ISOLDE));
        Seat seat = new Seat("easy_balrog", SID, ISOLDE, ANN);

        assertFalse(quota.counts(seat));
        quota.entered(seat);
        assertNull(quota.answer(ISOLDE, easy, false));
        assertEquals(List.of(), stock.calls, "neither counted nor logged");
    }

    @Test
    void anAllowedCompanionIsAnsweredTrueForItsExpeditionAndAPlayerNever() {
        CompanionBossQuota quota = new CompanionBossQuota(open, stock);

        assertTrue(quota.admit(zakum(), expedition, ISOLDE));

        assertEquals(Boolean.TRUE, quota.answer(ISOLDE, expedition, false));
        assertEquals(Boolean.TRUE, quota.answer(ISOLDE, expedition, true));
        assertNull(quota.answer(ISOLDE, mock(Expedition.class), false));
        assertNull(quota.answer(ANN, expedition, true));
        assertNull(quota.answer(ISOLDE, null, false));
        assertEquals(List.of("entryLeft " + ISOLDE), stock.calls);
    }

    @Test
    void aCappedCompanionIsRefusedAndTheNextEnlistAsksAgain() {
        CompanionBossQuota quota = new CompanionBossQuota(open, stock);
        stock.capped.add(ISOLDE);

        assertFalse(quota.admit(zakum(), expedition, ISOLDE));
        assertNull(quota.answer(ISOLDE, expedition, false), "a refusal is never kept");

        stock.capped.remove(ISOLDE); // a GM ran !resetboss
        assertTrue(quota.admit(zakum(), expedition, ISOLDE));
        assertEquals(Boolean.TRUE, quota.answer(ISOLDE, expedition, false));
        assertEquals(List.of("entryLeft " + ISOLDE, "entryLeft " + ISOLDE), stock.calls);
    }

    @Test
    void theEnlistKeepsOnlyTheAcceptedUnderTheirRun() {
        CompanionBossQuota quota = new CompanionBossQuota(open, stock);
        quota.admit(zakum(), expedition, ISOLDE);
        quota.admit(zakum(), expedition, THANE);
        quota.admit(zakum(), expedition, SORREL);

        quota.enlisted(expedition, new Enlisted(SID, List.of(ISOLDE, SORREL), List.of(THANE)),
                List.of(ISOLDE, THANE, SORREL));

        assertEquals(Boolean.TRUE, quota.answer(ISOLDE, expedition, false));
        assertNull(quota.answer(THANE, expedition, false), "the run refused him");
        assertTrue(quota.counts(new Seat("zakum", SID, SORREL, ANN)));
        assertFalse(quota.counts(new Seat("zakum", SID + 1, SORREL, ANN)), "another run's seat");

        quota.admit(zakum(), expedition, THANE);
        quota.enlisted(expedition, new Enlisted(0L, List.of(), List.of(THANE)), List.of(THANE));
        assertNull(quota.answer(THANE, expedition, false), "no run took him");
    }

    @Test
    void adoptionLogsOneEntryPerMemberAndRunWithTheBossesType() {
        CompanionBossQuota quota = new CompanionBossQuota(open, stock);
        quota.admit(zakum(), expedition, ISOLDE);
        quota.admit(zakum(), expedition, THANE);
        quota.enlisted(expedition, new Enlisted(SID, List.of(ISOLDE, THANE), List.of()), List.of(ISOLDE, THANE));
        stock.calls.clear();

        quota.entered(new Seat("zakum", SID, ISOLDE, ANN));
        quota.entered(new Seat("zakum", SID, ISOLDE, ANN));
        quota.entered(new Seat("zakum", SID, SORREL, ANN)); // never allowed here

        assertEquals(List.of("recordEntry " + ISOLDE + " ZAKUM"), stock.calls);
        assertFalse(quota.counts(new Seat("zakum", SID, ISOLDE, ANN)), "logged: nothing more to log for her");
        assertTrue(quota.counts(new Seat("zakum", SID, THANE, ANN)));
    }

    @Test
    void theRunsEndForgetsOnlyThatRunsAnswers() {
        CompanionBossQuota quota = new CompanionBossQuota(open, stock);
        Expedition other = mock(Expedition.class);
        quota.admit(zakum(), expedition, ISOLDE);
        quota.enlisted(expedition, new Enlisted(SID, List.of(ISOLDE), List.of()), List.of(ISOLDE));
        quota.admit(zakum(), other, THANE);
        quota.enlisted(other, new Enlisted(SID + 1, List.of(THANE), List.of()), List.of(THANE));

        quota.runEnded(SID);

        assertNull(quota.answer(ISOLDE, expedition, false));
        assertEquals(Boolean.TRUE, quota.answer(THANE, other, false));
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private static AgentBossRunDefinition zakum() {
        return AgentBossRunCatalog.ZAKUM;
    }

    private static Seat seat(int agentId) {
        return new Seat("zakum", SID, agentId, ANN);
    }

    private static boolean saysQuota(String name, String chat) {
        return chat.startsWith(name + ": ")
                && BossRunLines.GENERIC.get(BossRunLines.QUOTA).contains(chat.substring(name.length() + 2));
    }

    /** The boss log as the quota asks it: Zakum keeps an entry log, Easy Balrog none; calls are recorded. */
    private static final class FakeLog implements CompanionBossQuota.Ports {
        final List<String> calls = new ArrayList<>();
        final Set<Integer> capped = new HashSet<>();

        @Override
        public boolean limits(ExpeditionType type) {
            return type == ExpeditionType.ZAKUM;
        }

        @Override
        public boolean entryLeft(int characterId, Expedition expedition) {
            calls.add("entryLeft " + characterId);
            return !capped.contains(characterId);
        }

        @Override
        public boolean recordEntry(int characterId, ExpeditionType type) {
            calls.add("recordEntry " + characterId + " " + type);
            return true;
        }
    }

    /** The Agent layer's open runs: one run, {@link #SID}, takes every companion enlisted. */
    private static final class FakeOpen implements CompanionBossRuns.OpenRuns {
        /** "enlist <owner> <boss> <ids>" per enlist, in order. */
        final List<String> enlisted = new ArrayList<>();
        /** Agent id -> the human it stays with in the open run for {@link #expedition}. */
        final Map<Integer, Integer> anchors = new HashMap<>();
        Expedition expedition;

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
            return sessionId == SID;
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
            return false;
        }

        @Override
        public int waitingOrdinal(int agentId, int anchorId) {
            return -1;
        }

        @Override
        public Enlisted enlist(Character owner, AgentBossRunDefinition definition, Expedition expedition,
                               List<Integer> agentIds, long nowMs) {
            enlisted.add("enlist " + owner.getId() + " " + definition.key() + " " + agentIds);
            agentIds.forEach(id -> anchors.put(id, owner.getId()));
            return new Enlisted(SID, agentIds, List.of());
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

        @Override
        public int anchorIn(Expedition expedition, int agentId) {
            return expedition != null && expedition == this.expedition ? anchors.getOrDefault(agentId, 0) : 0;
        }
    }
}
