package client.command.commands.gm6;

import client.Character;
import client.Client;
import client.Job;
import client.LevelSetPlan;
import client.Skill;
import client.command.CommandTargetPolicy;
import client.command.commands.gm6.SetLevelCommand.Line;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.manipulator.InventoryManipulator;
import net.server.PlayerStorage;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import net.server.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.integration.AgentPresence;
import server.living.LivingWorld;
import server.living.quest.QuestMarkers;
import server.living.roster.CompanionExecutor;
import server.living.roster.CompanionLevelTool;
import server.living.roster.CompanionLevelTool.Ask;
import server.living.roster.CompanionPartyQuestService;
import server.living.roster.DirectExecutor;
import server.living.roster.GmLevelHolds;
import server.living.store.LivingStore;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SetLevelCommandTest {
    private static final int CREWFRIEND = 865;
    private static final String SAME = "lv=37 job=0 exp=1234 str=150 dex=47 int=4 luk=4 ap=0";

    private final Client client = mock(Client.class);
    private final Character gm = mock(Character.class);
    private final Character yara = mock(Character.class);
    private final Character friend = mock(Character.class);
    private final PlayerStorage players = mock(PlayerStorage.class);
    private final LivingWorld world = mock(LivingWorld.class);
    private final LivingStore store = mock(LivingStore.class);
    private final CompanionLevelTool tool = mock(CompanionLevelTool.class);
    /** What each player wears, by name (none unless a test says so). */
    private final java.util.Map<String, List<LevelSetPlan.GearPiece>> wearing = new java.util.HashMap<>();
    private final List<String> rows = new ArrayList<>();
    private final SetLevelCommand command = new SetLevelCommand(w -> tool,
            chr -> wearing.getOrDefault(chr.getName(), List.of()));

    private MockedStatic<LivingWorld> living;
    private MockedStatic<AgentPresence> presence;

    @BeforeEach
    void wire() {
        World channelWorld = mock(World.class);
        when(client.getPlayer()).thenReturn(gm);
        when(client.getWorldServer()).thenReturn(channelWorld);
        when(channelWorld.getPlayerStorage()).thenReturn(players);
        player(gm, CREWFRIEND, "Crewfriend", 40, Job.FIGHTER);
        when(gm.isGM()).thenReturn(true);
        when(gm.gmLevel()).thenReturn(6);
        when(gm.getClient()).thenReturn(client);
        player(friend, 866, "Reluctant", 30, Job.WARRIOR);
        player(yara, 901, "Yara", 30, Job.WARRIOR);
        when(players.getCharacterByName("Reluctant")).thenReturn(friend);
        when(players.getCharacterByName("Yara")).thenReturn(yara);
        when(world.store()).thenReturn(store);
        CompanionExecutor inline = DirectExecutor.inline(); // stubs inside: not within another when()
        when(world.executor()).thenReturn(inline);
        when(world.markers()).thenReturn(mock(QuestMarkers.class));
        when(world.gmLevelHolds()).thenReturn(new GmLevelHolds(world));
        when(store.latestGmLevel(anyInt(), anyString())).thenReturn(Optional.empty());
        when(store.eventChecked(anyInt(), anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            rows.add(invocation.getArgument(0) + " " + invocation.getArgument(2) + " " + invocation.getArgument(3));
            return true;
        });
        living = mockStatic(LivingWorld.class);
        living.when(LivingWorld::get).thenReturn(world);
        presence = mockStatic(AgentPresence.class);
        presence.when(() -> AgentPresence.isAgent(yara)).thenReturn(true);
    }

    @AfterEach
    void close() {
        presence.close();
        living.close();
    }

    private void player(Character chr, int id, String name, int level, Job job) {
        when(chr.getId()).thenReturn(id);
        when(chr.getName()).thenReturn(name);
        when(chr.getLevel()).thenReturn(level);
        when(chr.getJob()).thenReturn(job);
        when(chr.isAlive()).thenReturn(true);
        when(chr.getStr()).thenReturn(161);
        when(chr.getDex()).thenReturn(36);
        when(chr.getInt()).thenReturn(4);
        when(chr.getLuk()).thenReturn(4);
        when(chr.getRemainingSps()).thenReturn(new int[10]);
        when(chr.getInventory(InventoryType.EQUIP)).thenReturn(new Inventory(chr, InventoryType.EQUIP, (byte) 24));
        doAnswer(invocation -> { // not when(): a test may call this again for a character it already stubbed
            LevelSetPlan.Ok ok = invocation.getArgument(0);
            return new LevelSetPlan.AdminLevelChange(level, job, new LevelSetPlan.Stats(161, 36, 4, 4, 0), ok.level(),
                    ok.job(), 0, 0, 0, List.of());
        }).when(chr).adminSetLevel(any(), any());
    }

    private void run(String... params) {
        command.execute(client, params);
    }

    private void recorded(String detail) {
        when(store.latestGmLevel(CREWFRIEND, GmLevelHolds.SELF))
                .thenReturn(Optional.of(new LivingStore.EventRow(GmLevelHolds.HOLD, detail)));
    }

    /** Runs the line with CommandTargetPolicy letting the GM affect Reluctant (the real one asks the Cosmic adapter). */
    private void runOnReluctant(String... params) {
        // The real policy asks the Cosmic adapter, whose first use installs the AgentPresence provider: under this
        // test's AgentPresence mock that install would be swallowed for the rest of the JVM.
        try (MockedStatic<CommandTargetPolicy> policy = mockStatic(CommandTargetPolicy.class)) {
            policy.when(() -> CommandTargetPolicy.canAffect(gm, friend, false)).thenReturn(true);
            run(params);
        }
    }

    @Test
    void everyFormParses() {
        assertEquals(new Line(false, "me", Ask.to(40, Job.FIGHTER), true, false),
                SetLevelCommand.parse(new String[] {"me", "40", "fighter", "auto"}));
        assertEquals(new Line(false, "me", Ask.to(40, Job.FIGHTER), true, false),
                SetLevelCommand.parse(new String[] {"me", "40", "auto", "110"}));
        assertEquals(new Line(false, "Yara", Ask.to(70, null), false, false),
                SetLevelCommand.parse(new String[] {"Yara", "70"}));
        assertEquals(new Line(false, "Reluctant", Ask.to(9, null), false, true),
                SetLevelCommand.parse(new String[] {"Reluctant", "9", "force"}));
        assertEquals(new Line(false, "me", Ask.restoreIt(), false, false),
                SetLevelCommand.parse(new String[] {"me", "restore"}));
        assertEquals(new Line(false, "Yara", Ask.keepIt(), false, false),
                SetLevelCommand.parse(new String[] {"Yara", "keep"}));
        assertEquals(new Line(true, "party", Ask.to(50, null), false, false),
                SetLevelCommand.parse(new String[] {"party", "50"}));
        assertEquals(new Line(true, "party", Ask.to(20, null), false, true),
                SetLevelCommand.parse(new String[] {"party", "20", "force"}));
        assertEquals(new Line(true, "party", Ask.restoreIt(), false, false),
                SetLevelCommand.parse(new String[] {"party", "restore"}));
        assertNull(SetLevelCommand.parse(new String[] {"me"}));
        assertNull(SetLevelCommand.parse(new String[] {"me", "forty"}));
        assertNull(SetLevelCommand.parse(new String[] {"me", "40", "wizardking"}));
        assertNull(SetLevelCommand.parse(new String[] {"me", "40", "fighter", "hermit"}));
        assertNull(SetLevelCommand.parse(new String[] {"me", "40", "force", "force"}));
        assertNull(SetLevelCommand.parse(new String[] {"party", "keep"}));
        assertNull(SetLevelCommand.parse(new String[] {"me", "restore", "now"}));
        assertNull(SetLevelCommand.parse(new String[] {"me", "restore", "force"}));
    }

    @Test
    void meThirtySevenBeginnerIsAcceptedAndRecordsWhereHeWas() {
        run("me", "37", "beginner");

        verify(gm).adminSetLevel(new LevelSetPlan.Ok(Job.BEGINNER, 37, false), null);
        verify(gm).saveCharToDB();
        verify(world.markers()).forget(gm);
        verify(gm).yellowMessage(startsWith("Crewfriend is now level 37 Beginner (was 40 Fighter)"));
        assertEquals(List.of("865 gm.level.hold lv=40 job=110 exp=0 str=161 dex=36 int=4 luk=4 ap=0"), rows);
    }

    @Test
    void theRecordKeepsAPlayersExpAndUnspentAp() {
        when(gm.getExp()).thenReturn(5120);
        when(gm.getRemainingAp()).thenReturn(15);

        run("me", "37", "beginner");

        assertEquals(List.of("865 gm.level.hold lv=40 job=110 exp=5120 str=161 dex=36 int=4 luk=4 ap=15"), rows);
    }

    @Test
    void meRestoreGivesTheStoredStatsAndExpBack() {
        recorded(SAME);

        run("me", "restore");

        verify(gm).adminSetLevel(new LevelSetPlan.Ok(Job.BEGINNER, 37, true, 1234),
                new LevelSetPlan.Stats(150, 47, 4, 4, 0));
        verify(gm).yellowMessage(contains("record released"));
        assertFalse(world.gmLevelHolds().held(CREWFRIEND, GmLevelHolds.SELF));
    }

    @Test
    void aSetBackToExactlyTheRecordedLevelAndJobIsARestore() {
        recorded(SAME);

        run("me", "37", "beginner");

        verify(gm).adminSetLevel(new LevelSetPlan.Ok(Job.BEGINNER, 37, true, 1234),
                new LevelSetPlan.Stats(150, 47, 4, 4, 0));
        verify(gm).yellowMessage(contains("record released"));
        assertFalse(world.gmLevelHolds().held(CREWFRIEND, GmLevelHolds.SELF));
    }

    @Test
    void aRestoreAlreadyAtTheRecordedLevelStillGivesTheRecordedStatsBack() {
        player(gm, CREWFRIEND, "Crewfriend", 37, Job.BEGINNER); // levelled back naturally; 161/36 spent its own way
        when(gm.getExp()).thenReturn(800);
        recorded(SAME);

        run("me", "restore");

        // the stats only: its level, job and the EXP it earned since stay
        verify(gm).adminSetLevel(new LevelSetPlan.Ok(Job.BEGINNER, 37, true, 800),
                new LevelSetPlan.Stats(150, 47, 4, 4, 0));
        assertFalse(world.gmLevelHolds().held(CREWFRIEND, GmLevelHolds.SELF));
    }

    @Test
    void aRestoreWhoseRecordedStatsNoLongerFitKeepsTheRecord() {
        player(gm, CREWFRIEND, "Crewfriend", 37, Job.BEGINNER);
        recorded("lv=37 job=0 exp=0 str=150 dex=47 int=4 luk=4 ap=9"); // 9 AP more than level 37 gives

        run("me", "restore");

        verify(gm, never()).adminSetLevel(any(), any());
        verify(gm).yellowMessage(contains("no longer fit"));
        assertTrue(world.gmLevelHolds().held(CREWFRIEND, GmLevelHolds.SELF));
        assertEquals(List.of(), rows);
    }

    @Test
    void aRecordThatCannotBeReadRefusesTheSetAndWritesNothing() {
        when(store.latestGmLevel(CREWFRIEND, GmLevelHolds.SELF)).thenReturn(null);

        run("me", "37", "beginner");

        verify(gm, never()).adminSetLevel(any(), any());
        verify(gm).yellowMessage("Crewfriend's record could not be read; try again.");
        assertEquals(List.of(), rows);
    }

    @Test
    void aSetWaitsWhileAnEarlierUseOfTheRecordIsSettling() {
        GmLevelHolds holds = world.gmLevelHolds();
        GmLevelHolds.Pending earlier = holds.begin(CREWFRIEND, GmLevelHolds.SELF, Optional.empty(),
                new GmLevelHolds.Original(40, Job.FIGHTER, null), false, true, 30, Job.FIGHTER);

        run("me", "37", "beginner");
        verify(gm, never()).adminSetLevel(any(), any());
        verify(gm).yellowMessage(contains("still being recorded"));

        earlier.settle(false);
        run("me", "37", "beginner");
        verify(gm).adminSetLevel(new LevelSetPlan.Ok(Job.BEGINNER, 37, false), null);
    }

    @Test
    void theGmsOwnGearTheLevelForbidsComesOffBeforeTheSet() {
        wearing.put("Crewfriend", List.of(new LevelSetPlan.GearPiece((short) -11, 1302020, 70, 1, false)));

        try (MockedStatic<InventoryManipulator> inventory = mockStatic(InventoryManipulator.class)) {
            run("me", "21");
            inventory.verify(() -> InventoryManipulator.unequip(eq(client), eq((short) -11), anyShort()));
        }
        verify(gm).adminSetLevel(new LevelSetPlan.Ok(Job.WARRIOR, 21, false), null); // a Fighter at 21 is a Warrior
    }

    @Test
    void anotherPlayerWearingWhatTheLevelForbidsIsRefused() {
        wearing.put("Reluctant", List.of(new LevelSetPlan.GearPiece((short) -11, 1302020, 30, 1, false)));

        runOnReluctant("Reluctant", "20");

        verify(friend, never()).adminSetLevel(any(), any());
        verify(gm).yellowMessage(contains("take them off first"));
        assertEquals(List.of(), rows);
    }

    @Test
    void aPlayerTheGmMayNotAffectIsNotSet() {
        try (MockedStatic<CommandTargetPolicy> policy = mockStatic(CommandTargetPolicy.class)) {
            policy.when(() -> CommandTargetPolicy.canAffect(gm, friend, false)).thenReturn(false);
            run("Reluctant", "40");
        }

        verify(friend, never()).adminSetLevel(any(), any());
        verify(gm, never()).yellowMessage(anyString()); // CommandTargetPolicy answered (here: its mock, silently)
        assertEquals(List.of(), rows);
    }

    @Test
    void anotherPlayerIsSetWithoutAMessageOfTheirOwn() {
        runOnReluctant("Reluctant", "40");

        verify(friend).adminSetLevel(new LevelSetPlan.Ok(Job.WARRIOR, 40, false), null);
        verify(friend, never()).dropMessage(anyInt(), anyString());
        verify(gm).yellowMessage(startsWith("Reluctant is now level 40"));
    }

    @Test
    void aSetThatWouldDropAnotherPlayersSkillsNeedsForce() {
        Skill powerStrike = mock(Skill.class);
        when(powerStrike.getId()).thenReturn(1001004);
        when(friend.getSkills()).thenReturn(Map.of(powerStrike, new Character.SkillEntry((byte) 20, 0, -1)));

        runOnReluctant("Reluctant", "9"); // a Warrior set to 9 is a Beginner
        verify(friend, never()).adminSetLevel(any(), any());
        verify(gm).yellowMessage(contains("add 'force'"));
        assertEquals(List.of(), rows);

        runOnReluctant("Reluctant", "9", "force");
        verify(friend).adminSetLevel(new LevelSetPlan.Ok(Job.BEGINNER, 9, false), null);
    }

    @Test
    void aPlayerWhoIsDownInAnEventInstanceAPartyQuestOrACompanionRunIsNotSet() {
        when(gm.isAlive()).thenReturn(false);
        run("me", "37", "beginner");
        verify(gm).yellowMessage("Crewfriend is down.");

        when(gm.isAlive()).thenReturn(true);
        when(gm.getEventInstance()).thenReturn(mock(EventInstanceManager.class));
        run("me", "37", "beginner");
        verify(gm).yellowMessage("Crewfriend is in an event instance.");

        when(gm.getEventInstance()).thenReturn(null);
        try (MockedStatic<AgentPartyQuestEngagementRegistry> engagements =
                     mockStatic(AgentPartyQuestEngagementRegistry.class)) {
            engagements.when(() -> AgentPartyQuestEngagementRegistry.forMember(CREWFRIEND))
                    .thenReturn(mock(AgentPartyQuestEngagement.class));
            run("me", "37", "beginner");
        }
        verify(gm).yellowMessage("Crewfriend is in a party quest.");

        CompanionPartyQuestService pq = mock(CompanionPartyQuestService.class);
        when(pq.busyFor(CREWFRIEND)).thenReturn(true);
        when(world.partyQuests()).thenReturn(pq);
        run("me", "37", "beginner");
        verify(gm).yellowMessage("Crewfriend is in a companion party quest run.");

        verify(gm, never()).adminSetLevel(any(), any());
        assertEquals(List.of(), rows);
    }

    @Test
    void withoutTheLivingWorldPlayersAreSetWithNoRecordAndCompanionsAreRefused() {
        living.when(LivingWorld::get).thenReturn(null);

        run("me", "37", "beginner");
        verify(gm).adminSetLevel(new LevelSetPlan.Ok(Job.BEGINNER, 37, false), null);
        verify(gm).yellowMessage(contains("(no record: the living world is off)"));

        run("Yara", "40");
        verify(gm).yellowMessage(contains("companions need the living world"));
        verify(tool, never()).use(any(), anyString(), any(), anyBoolean());
    }

    @Test
    void aCompanionGoesToTheToolOnCompanionOps() {
        when(tool.use(gm, "Yara", Ask.to(40, Job.FIGHTER), true))
                .thenReturn(CompletableFuture.completedFuture("Yara is now level 40 Fighter (was 30 Warrior)."));

        run("Yara", "40", "fighter");

        verify(tool).use(gm, "Yara", Ask.to(40, Job.FIGHTER), true);
        verify(gm).yellowMessage("Yara is now level 40 Fighter (was 30 Warrior).");
    }

    private Party partyOf(Character... members) {
        Party party = mock(Party.class);
        List<PartyCharacter> entries = new ArrayList<>();
        for (Character member : members) {
            PartyCharacter entry = mock(PartyCharacter.class);
            String name = member.getName();
            when(entry.getPlayer()).thenReturn(member);
            when(entry.getName()).thenReturn(name);
            entries.add(entry);
        }
        when(party.getMembers()).thenReturn(entries);
        when(gm.getParty()).thenReturn(party);
        return party;
    }

    @Test
    void thePartyFormRepliesOnceOnCompanionOpsWithoutWaitingOnIt() {
        CompanionExecutor ops = new CompanionExecutor();
        when(world.executor()).thenReturn(ops);
        try {
            partyOf(gm, yara);
            List<Boolean> busyDuringTheLeg = new ArrayList<>();
            // the companion's leg answers from its own later companion-ops task, as the mailbox reconcile does
            when(tool.use(gm, "Yara", Ask.to(50, null), false)).thenAnswer(invocation -> {
                busyDuringTheLeg.add(world.gmLevelHolds().busy(CREWFRIEND));
                CompletableFuture<String> line = new CompletableFuture<>();
                ops.submit("reconcile", () -> line.complete("Yara is now level 50 Fighter (was 30 Warrior)."));
                return line;
            });
            CountDownLatch replied = new CountDownLatch(1);
            List<String> replies = new ArrayList<>();
            List<String> replyThreads = new ArrayList<>();
            doAnswer(invocation -> {
                replies.add(invocation.getArgument(0));
                replyThreads.add(Thread.currentThread().getName());
                replied.countDown();
                return null;
            }).when(gm).yellowMessage(startsWith("Party"));

            // on this thread (the static mocks are this thread's): a leg that waited on companion-ops would hang there
            assertTimeout(Duration.ofSeconds(10), () -> {
                run("party", "50");
                assertTrue(replied.await(5, TimeUnit.SECONDS), "the party reply never came");
            });

            verify(gm).adminSetLevel(new LevelSetPlan.Ok(Job.FIGHTER, 50, false), null);
            verify(tool).check(gm, "Yara", Ask.to(50, null));
            verify(tool).use(gm, "Yara", Ask.to(50, null), false);
            verify(yara, never()).adminSetLevel(any(), any());
            assertEquals(List.of(true), busyDuringTheLeg);
            assertEquals(List.of(CompanionExecutor.THREAD_NAME), replyThreads);
            assertTrue(replies.get(0).contains("Crewfriend is now level 50"), replies.get(0));
            assertTrue(replies.get(0).contains("Yara is now level 50"), replies.get(0));
            assertFalse(world.gmLevelHolds().busy(CREWFRIEND));
        } finally {
            ops.shutdown();
        }
    }

    @Test
    void thePartyFormSetsNobodyWhenACompanionCannotBeSet() {
        partyOf(gm, yara);
        when(tool.check(gm, "Yara", Ask.restoreIt())).thenReturn("Yara is in an event instance.");
        recorded(SAME);

        run("party", "restore");

        verify(gm, never()).adminSetLevel(any(), any());
        verify(tool, never()).use(any(), anyString(), any(), anyBoolean());
        verify(gm).yellowMessage("Party restore: nobody was set. Yara is in an event instance.");
        assertTrue(world.gmLevelHolds().held(CREWFRIEND, GmLevelHolds.SELF));
        assertFalse(world.gmLevelHolds().busy(CREWFRIEND));
    }

    @Test
    void thePartyFormSetsNobodyWhenAPlayerCannotBeSet() {
        partyOf(gm, friend, yara);
        when(friend.getEventInstance()).thenReturn(mock(EventInstanceManager.class));

        try (MockedStatic<CommandTargetPolicy> policy = mockStatic(CommandTargetPolicy.class)) {
            policy.when(() -> CommandTargetPolicy.canAffect(gm, friend, false)).thenReturn(true);
            run("party", "50");
        }

        verify(gm, never()).adminSetLevel(any(), any());
        verify(friend, never()).adminSetLevel(any(), any());
        verify(tool, never()).check(any(), anyString(), any());
        verify(tool, never()).use(any(), anyString(), any(), anyBoolean());
        verify(gm).yellowMessage("Party level 50: nobody was set. Reluctant is in an event instance.");
        assertEquals(List.of(), rows);
        assertFalse(world.gmLevelHolds().busy(CREWFRIEND));
    }

    @Test
    void thePartyFormTakesNoJob() {
        when(gm.getParty()).thenReturn(mock(Party.class));
        run("party", "50", "fighter");
        verify(gm).yellowMessage(contains("level or 'restore' only"));
        verify(gm, never()).adminSetLevel(any(), any());
    }

    @Test
    void aBadLineGetsTheUsage() {
        run("me");
        verify(gm).yellowMessage(SetLevelCommand.USAGE);
        verify(gm, never()).adminSetLevel(any(), eq(null));
    }
}
