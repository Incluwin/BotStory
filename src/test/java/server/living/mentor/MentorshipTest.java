package server.living.mentor;

import client.Character;
import client.Client;
import client.Job;
import client.inventory.Item;
import config.LivingWorldConfig;
import constants.game.ExpTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import server.TimerManager;
import server.agents.runtime.AgentMailboxAction;
import server.agents.runtime.AgentMailboxRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.knowledge.ContentAnswers;
import server.living.LivingWorld;
import server.living.bond.BondService;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.population.PersonMemories;
import server.living.population.Population;
import server.living.resident.LivingTalk;
import server.living.resident.ResidentRuntime;
import server.living.roster.CompanionExecutor;
import server.living.roster.JobErrands;
import server.living.roster.RosterService;
import server.living.store.LivingStore;
import server.living.store.LivingStore.EventRow;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;

import java.awt.Point;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A mentorship on companion-ops (docs/BOT_CONCEPT_PLAN.md F4.1-F4.4): the once-only offer at a first login (D8), the
 * mentor staying its lead above its newcomer one delayed level at a time, its milestone tips and "quiet please", and
 * the goodbye at the newcomer's second job (D9): lines, then the gift dropped for them, then (a step later) home as a
 * friend.
 */
class MentorshipTest {
    private static final int ANN = 10;
    private static final int BO = 900;
    private static final String BO_ID = "hire:" + BO;

    private final AtomicLong clock = new AtomicLong(10_000L);
    private final LivingWorldConfig config = new LivingWorldConfig();
    private final LivingWorld world = mock(LivingWorld.class);
    private final LivingStore store = mock(LivingStore.class);
    private final RosterService roster = mock(RosterService.class);
    private final ResidentRuntime residents = mock(ResidentRuntime.class);
    private final LivingContent content = mock(LivingContent.class);
    private final Persona persona = mock(Persona.class);
    private final PersonMemories memories = mock(PersonMemories.class);
    private final BondService bonds = mock(BondService.class);
    private final MapleMap map = mock(MapleMap.class);
    private final Character ann = mock(Character.class);
    private final Character bo = mock(Character.class);
    private final AgentRuntimeEntry boRuntime = mock(AgentRuntimeEntry.class);
    private final TimerManager timer = mock(TimerManager.class);
    private final LivingTalk talk = mock(LivingTalk.class, RETURNS_SELF);
    private final ContentAnswers answers = mock(ContentAnswers.class);
    private final List<Runnable> timers = new ArrayList<>();
    private final List<MockedStatic<?>> statics = new ArrayList<>();
    private MockedStatic<LivingTalk> talks;
    private Mentorship mentors;

    @BeforeEach
    void setUp() {
        config.MENTOR_ENABLED = true;
        config.HIRE_FRIENDSHIP_ENABLED = true;
        CompanionExecutor executor = mock(CompanionExecutor.class);
        when(executor.submit(anyString(), any(Runnable.class))).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return CompletableFuture.completedFuture(null);
        });
        when(world.config()).thenReturn(config);
        when(world.store()).thenReturn(store);
        when(world.roster()).thenReturn(roster);
        when(world.residents()).thenReturn(residents);
        when(world.content()).thenReturn(content);
        when(world.executor()).thenReturn(executor);
        when(world.bonds()).thenReturn(bonds);
        when(world.jobErrands()).thenReturn(mock(JobErrands.class));
        when(content.persona(BO_ID)).thenReturn(persona);

        Client client = mock(Client.class);
        when(ann.getId()).thenReturn(ANN);
        when(ann.getName()).thenReturn("Ann");
        when(ann.getLevel()).thenReturn(5);
        when(ann.getJob()).thenReturn(Job.BEGINNER);
        when(ann.isBeginnerJob()).thenReturn(true);
        when(ann.getMapId()).thenReturn(200_000_000); // Orbis: nowhere to lead, so these tests see no lead
        when(ann.getMap()).thenReturn(map);
        when(ann.getHp()).thenReturn(100);
        when(ann.getCurrentMaxHp()).thenReturn(100);
        when(ann.isAlive()).thenReturn(true);
        when(ann.getClient()).thenReturn(client);
        when(ann.getPosition()).thenReturn(new Point(100, 50));
        when(bo.getId()).thenReturn(BO);
        when(bo.getName()).thenReturn("Bo");
        when(bo.getLevel()).thenReturn(8);
        when(bo.getMap()).thenReturn(map);
        when(bo.getMapId()).thenReturn(200_000_000);
        when(boRuntime.bot()).thenReturn(bo);
        when(roster.onlineCharacter(ANN)).thenReturn(ann);
        RosterEntry row = mock(RosterEntry.class);
        when(row.state()).thenReturn(RosterState.ACTIVE);
        when(roster.entry(ANN, BO_ID)).thenReturn(row);
        when(roster.companion(row)).thenReturn(bo);

        MockedStatic<TimerManager> timerManager = mockStatic(TimerManager.class);
        timerManager.when(TimerManager::getInstance).thenReturn(timer);
        when(timer.schedule(any(TimerManager.SchedulerLane.class), any(Runnable.class), anyLong())).thenAnswer(inv -> {
            timers.add(inv.getArgument(1));
            return null;
        });
        statics.add(timerManager);
        MockedStatic<AgentRuntimeRegistry> registry = mockStatic(AgentRuntimeRegistry.class);
        registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(BO)).thenReturn(boRuntime);
        statics.add(registry);
        MockedStatic<AgentMailboxRuntime> mailbox = mockStatic(AgentMailboxRuntime.class);
        mailbox.when(() -> AgentMailboxRuntime.dispatch(any(AgentRuntimeEntry.class), any(AgentMailboxAction.class)))
                .thenAnswer(inv -> CompletableFuture.completedFuture(
                        ((AgentMailboxAction<?>) inv.getArgument(1)).execute(inv.getArgument(0))));
        statics.add(mailbox);
        Population population = mock(Population.class);
        when(population.memories()).thenReturn(memories);
        MockedStatic<Population> populations = mockStatic(Population.class);
        populations.when(Population::get).thenReturn(population);
        statics.add(populations);
        talks = mockStatic(LivingTalk.class);
        talks.when(() -> LivingTalk.open(any(Character.class), anyInt())).thenReturn(talk);
        talks.when(() -> LivingTalk.mayShow(any(Character.class))).thenReturn(true);
        talks.when(() -> LivingTalk.active(any(Client.class))).thenReturn(true);
        statics.add(talks);
        MockedStatic<ContentAnswers> knowledge = mockStatic(ContentAnswers.class);
        knowledge.when(ContentAnswers::shared).thenReturn(answers);
        statics.add(knowledge);

        mentors = new Mentorship(world, clock::get);
        mentors.lines(MentorLines.load(Path.of("scripts", "config", "living-world")));
    }

    @AfterEach
    void tearDown() {
        statics.forEach(MockedStatic::close);
    }

    private Mentorship.Mentee mentoring(int lead) {
        Mentorship.Mentee mentee = new Mentorship.Mentee(ANN, BO_ID, BO, null, lead);
        mentors.put(mentee);
        return mentee;
    }

    private List<String> said() {
        ArgumentCaptor<String> lines = ArgumentCaptor.forClass(String.class);
        verify(residents, org.mockito.Mockito.atLeast(0)).whisper(eq(persona), eq(ann), lines.capture());
        return lines.getAllValues();
    }

    // ---- the offer (D8) --------------------------------------------------------------------------

    private void newcomer() {
        when(ann.getLevel()).thenReturn(1);
        when(ann.getMapId()).thenReturn(10_000);
        when(store.latestCharacterEvent(ANN, FirstLogin.KINDS)).thenReturn(Optional.empty());
        when(store.neverLoggedOut(ANN)).thenReturn(true);
    }

    @Test
    void aFirstLoginIsOfferedOnceTheOfferWrittenBeforeItsWindowShows() {
        newcomer();
        mentors.afterLogin(ANN);

        verify(store).event(ANN, null, FirstLogin.PENDING, "first login");
        assertEquals(1, timers.size(), "the window comes OFFER_DELAY_MS later");
        verify(timer).schedule(eq(TimerManager.SchedulerLane.LOW_PRIORITY), any(Runnable.class),
                eq(config.MENTOR_OFFER_DELAY_MS));
        timers.get(0).run();

        InOrder order = inOrder(store, talk);
        order.verify(store).event(eq(ANN), isNull(), eq(FirstLogin.OFFERED), anyString());
        order.verify(talk).show();
        ArgumentCaptor<Runnable> no = ArgumentCaptor.forClass(Runnable.class);
        verify(talk).yesNo(contains("show you around"), any(Runnable.class), no.capture());
        no.getValue().run();
        verify(store).event(ANN, null, FirstLogin.DECLINED, "the window");

        when(store.latestCharacterEvent(ANN, FirstLogin.KINDS))
                .thenReturn(Optional.of(new EventRow(FirstLogin.DECLINED, "the window")));
        mentors.afterLogin(ANN);
        assertEquals(1, timers.size(), "declined is final: never again");
        verify(store, times(1)).event(eq(ANN), isNull(), eq(FirstLogin.PENDING), anyString());
    }

    @Test
    void aWindowThatCouldNotShowIsTriedAgainAndIsNoDecline() {
        newcomer();
        talks.when(() -> LivingTalk.mayShow(any(Character.class))).thenReturn(false); // another window is open
        mentors.offer(ANN, 3);
        verify(store, never()).event(eq(ANN), isNull(), eq(FirstLogin.OFFERED), anyString());
        assertEquals(1, timers.size(), "another window open: again OFFER_RETRY_MS later");
        verify(timer).schedule(any(TimerManager.SchedulerLane.class), any(Runnable.class),
                eq(config.MENTOR_OFFER_RETRY_MS));
        mentors.offer(ANN, 1);
        assertEquals(1, timers.size(), "out of tries: it waits for the next login");
        verify(talk, never()).show();

        talks.when(() -> LivingTalk.mayShow(any(Character.class))).thenReturn(true);
        talks.when(() -> LivingTalk.active(any(Client.class))).thenReturn(false); // the window never got on screen
        mentors.offer(ANN, 2);
        verify(store).event(ANN, null, FirstLogin.PENDING, "the window did not show");
        assertEquals(2, timers.size(), "tried again");

        when(store.latestCharacterEvent(ANN, FirstLogin.KINDS))
                .thenReturn(Optional.of(new EventRow(FirstLogin.PENDING, "the window did not show")));
        when(ann.getLevel()).thenReturn(3);
        when(store.neverLoggedOut(ANN)).thenReturn(false);
        mentors.afterLogin(ANN);
        assertEquals(3, timers.size(), "next login: offered again, it was never a decline");
    }

    @Test
    void aMentorshipOutBringsItsMentorBackAtLoginAndTheSwitchOffSendsItHome() {
        mentoring(3);
        RosterEntry benched = mock(RosterEntry.class);
        when(benched.state()).thenReturn(RosterState.BENCHED);
        when(roster.entry(ANN, BO_ID)).thenReturn(benched);
        mentors.afterLogin(ANN);
        verify(roster).restAt(ANN, BO_ID, 200_000_000);
        verify(roster).call(ann, persona);
        verify(store, never()).latestCharacterEvent(anyInt(), any());

        config.MENTOR_ENABLED = false;
        mentors.afterLogin(ANN);
        verify(roster).release(ANN, persona, "mentors switched off");
        assertFalse(mentors.mentored(ANN));
    }

    // ---- shaping relative to the newcomer (F4.1) ------------------------------------------------

    @Test
    void itStaysItsLeadAboveOneDelayedLevelAtATime() {
        mentoring(4); // Ann 5, Bo 8: one level short of 9
        mentors.tick();
        verify(bo, never()).gainExp(anyInt(), anyBoolean(), anyBoolean());
        clock.addAndGet(config.MENTOR_LEVEL_DELAY_MIN_MS - 1);
        mentors.tick();
        verify(bo, never()).gainExp(anyInt(), anyBoolean(), anyBoolean());
        clock.addAndGet(config.MENTOR_LEVEL_DELAY_MAX_MS);
        mentors.tick();
        verify(bo).gainExp(ExpTable.getExpNeededForLevel(8), true, false); // its real level-up: effect, AP, SP

        when(bo.getLevel()).thenReturn(9);
        clock.addAndGet(config.MENTOR_LEVEL_DELAY_MAX_MS * 2);
        mentors.tick();
        mentors.tick();
        verify(bo, times(1)).gainExp(anyInt(), anyBoolean(), anyBoolean());
    }

    @Test
    void itNeverComesDownToANewcomerBehindIt() {
        mentoring(3);
        when(bo.getLevel()).thenReturn(20);
        clock.addAndGet(config.MENTOR_LEVEL_DELAY_MAX_MS * 3);
        mentors.tick();
        mentors.tick();
        verify(bo, never()).gainExp(anyInt(), anyBoolean(), anyBoolean());
    }

    @Test
    void aMentorEarnsNoKillExpOnlyWhileTheSwitchIsOn() {
        assertFalse(mentors.isMentor(BO));
        mentoring(3);
        assertTrue(mentors.isMentor(BO));
        assertFalse(mentors.isMentor(ANN), "its newcomer earns as ever");
        config.MENTOR_ENABLED = false;
        assertFalse(mentors.isMentor(BO));
    }

    // ---- tips and mute (F4.2) -----------------------------------------------------------------

    @Test
    void lowHpIsTippedOnceAndQuietPleaseHoldsTheRestUntilTipsOn() {
        Mentorship.Mentee mentee = mentoring(3);
        when(ann.getHp()).thenReturn(20);
        mentors.tick();
        verify(store).event(ANN, BO_ID, Mentorship.TIP_EVENT, "LOW_HP");
        assertTrue(said().stream().anyMatch(line -> line.contains("hp's low")), said().toString());
        mentors.tick();
        verify(store, times(1)).event(ANN, BO_ID, Mentorship.TIP_EVENT, "LOW_HP");

        mentors.chat(ann, "quiet please");
        verify(store).event(ANN, BO_ID, Mentorship.MUTE_EVENT, "on");
        assertTrue(mentee.tips.muted());
        mentors.died(ann);
        mentors.tick();
        verify(store, never()).event(ANN, BO_ID, Mentorship.TIP_EVENT, "FIRST_DEATH");

        when(answers.explain(any())).thenReturn(new ContentAnswers.ContentAnswer(ContentAnswers.Outcome.ANSWERED, null,
                null, null, null, "snail garden is easier", Map.of()));
        mentors.chat(ann, "ok tips on");
        verify(store).event(ANN, BO_ID, Mentorship.MUTE_EVENT, "off");
        mentors.tick();
        verify(store).event(ANN, BO_ID, Mentorship.TIP_EVENT, "FIRST_DEATH");
        assertTrue(said().stream().anyMatch(line -> line.startsWith("rip") && line.contains("snail garden is easier")),
                "the fact is ContentAnswers' own: " + said());
    }

    @Test
    void eachMilestoneComesFromItsHookAndTheHourCapHoldsTheRest() {
        config.MENTOR_TIPS_PER_HOUR = 1;
        mentoring(3);
        when(ann.getLevel()).thenReturn(10);
        mentors.levelUp(ann); // LV8 and LV10 at once
        mentors.died(ann);
        mentors.tick();
        mentors.tick();
        verify(store, times(1)).event(eq(ANN), eq(BO_ID), eq(Mentorship.TIP_EVENT), anyString());
        clock.addAndGet(3_600_000L);
        mentors.tick();
        verify(store, times(2)).event(eq(ANN), eq(BO_ID), eq(Mentorship.TIP_EVENT), anyString());
    }

    @Test
    void aBranchWordIsTheNewcomersPick() {
        Mentorship.Mentee mentee = mentoring(3);
        mentors.chat(ann, "i think i wanna be a thief");
        assertEquals(Job.THIEF, mentee.pick);
        verify(store).event(ANN, BO_ID, Mentorship.PICK_EVENT, "THIEF");
    }

    // ---- graduation (F4.4, D9) -------------------------------------------------------------------

    @Test
    void atTheSecondJobItSaysGoodbyeDropsItsGiftThenGoesHomeAFriend() {
        mentoring(3);
        when(ann.getJob()).thenReturn(Job.FIGHTER);
        when(ann.isBeginnerJob()).thenReturn(false);
        LivingStore.Bond bond = mock(LivingStore.Bond.class);
        when(bond.points()).thenReturn(10);
        when(bonds.bond(ann, persona)).thenReturn(bond);
        mentors.jobAdvanced(ann);

        mentors.tick();
        assertTrue(said().isEmpty(), "a beat before the goodbye");
        clock.addAndGet(3_500L);
        mentors.tick();
        assertEquals(List.of("warrior already, look at you"), said());
        clock.addAndGet(3_500L);
        mentors.tick();
        assertEquals(2, said().size());
        clock.addAndGet(3_500L);
        mentors.tick();
        assertTrue(said().get(said().size() - 1).contains("take these"));
        verify(map, times(2)).spawnItemDrop(eq(bo), eq(ann), any(Item.class), any(Point.class), eq(false), eq(true));
        verify(roster, never()).release(anyInt(), any(), anyString());
        verify(memories, never()).warmAtLeast(anyInt(), anyInt(), anyInt());

        clock.addAndGet(3_500L);
        mentors.tick();
        assertTrue(said().get(said().size() - 1).startsWith("see you around Ann"));
        verify(memories).warmAtLeast(BO, ANN, 40);
        verify(bonds).addPoints(ann, persona, 75 - 10, "mentor graduated");
        verify(store).event(ANN, BO_ID, FirstLogin.GRADUATED, "FIGHTER");
        verify(roster).release(ANN, persona, "mentor graduated");
        assertFalse(mentors.mentored(ANN));
        assertFalse(mentors.isMentor(BO));
    }

    @Test
    void itWaitsForItsNewcomerToBeOnItsMapToSayGoodbye() {
        mentoring(3);
        when(ann.getJob()).thenReturn(Job.ASSASSIN);
        when(bo.getMap()).thenReturn(mock(MapleMap.class));
        mentors.jobAdvanced(ann);
        clock.addAndGet(60_000L);
        mentors.tick();
        assertTrue(said().isEmpty());
        verify(roster, never()).release(anyInt(), any(), anyString());
    }

    @Test
    void aFirstJobIsNoGraduation() {
        mentoring(3);
        when(ann.getJob()).thenReturn(Job.WARRIOR);
        mentors.jobAdvanced(ann);
        assertEquals(0L, mentors.mentee(ANN).goodbyeAtMs);
    }
}
