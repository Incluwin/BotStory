package server.living.roster;

import client.Character;
import client.Job;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.build.AgentJobPath;
import server.agents.runtime.AgentInteractionRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.life.NPC;
import server.living.config.Content.Persona;
import server.living.mentor.Apprentices;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.LITH;

/**
 * An apprentice's jobs (docs/BOT_CONCEPT_PLAN.md F4.5): its owner picks each branch in a window (Apprentices), and it
 * takes the job at its instructor by the companions' own job errand, never by the instant chat change. With no pick
 * nobody asks in chat; with one it goes; after its second job (graduation) its line takes it on as any hire's. A job
 * word in chat holds the instant change only for an apprentice while APPRENTICE_ENABLED (coordinator ruling D-i);
 * every other companion keeps it, and a mentor ignores its newcomer's branch word for itself.
 */
class ApprenticeErrandTest {
    private static final int TOBY = 831;
    private static final String TOBY_ID = "hire:831";
    private static final int WARRIORS_SANCTUARY = 102000003;
    private static final int DANCES_WITH_BALROG = 1022000;

    private final LifecycleHarness game = new LifecycleHarness();
    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final List<String> advanced = new ArrayList<>();
    private final Apprentices apprentices = mock(Apprentices.class);
    private CompanionRoles roles;
    private JobErrands errands;
    private Persona toby;

    @BeforeEach
    void tobyOutWithAnn() {
        game.config.APPRENTICE_ENABLED = true;
        game.config.MENTOR_ENABLED = true;
        toby = game.companionAs("BEGINNER", TOBY_ID, "Toby", TOBY, RosterState.ACTIVE, LITH);
        game.outWithAnn(toby);
        game.roster();
        stats(game.bot(toby), Job.BEGINNER, 10);
        game.roster().settings().bind(game.bot(toby), ANN, TOBY_ID);
        when(game.residents.isPersonaCharacter(TOBY)).thenReturn(true);
        when(game.store.store.saveRosterRole(anyInt(), anyString(), any(), any())).thenReturn(true);
        roles = new CompanionRoles(game.world);
        when(game.world.roles()).thenReturn(roles);
        when(game.world.apprentices()).thenReturn(apprentices);
        assertTrue(roles.set(ANN, TOBY_ID, CompanionRoles.Role.APPRENTICE, null));
        errands = new JobErrands(game.world, clock::get);
        errands.goesAlong = (runtime, ownerId) -> true;
        errands.advance = (runtime, job) -> {
            advanced.add(runtime.bot().getName() + " " + job);
            when(runtime.bot().getJob()).thenReturn(job);
            return true;
        };
        game.interaction.when(() -> AgentInteractionRuntime.registerDirectorIdleAgent(any()))
                .thenAnswer(inv -> new AgentRuntimeEntry(inv.getArgument(0), inv.getArgument(0), null));
    }

    @AfterEach
    void close() {
        AgentJobPath.install(null);
        game.close();
    }

    @Test
    void withNoPickYetNobodyAsksInChatAndNothingIsDue() {
        assertEquals(Job.BEGINNER, errands.path(TOBY, Job.BEGINNER), "a path, so the build prompt stays quiet");
        assertNull(AgentJobPath.next(Job.BEGINNER, errands.path(TOBY, Job.BEGINNER), 10), "and no step: its owner picks");
        verify(game.store.store).saveRosterRole(ANN, TOBY_ID, "APPRENTICE", null);
    }

    @Test
    void itsOwnersPickSendsItToItsInstructorWhereItTakesTheJob() {
        assertTrue(roles.set(ANN, TOBY_ID, CompanionRoles.Role.APPRENTICE, "WARRIOR"));
        assertEquals(Job.WARRIOR, errands.path(TOBY, Job.BEGINNER));
        assertEquals(Job.WARRIOR, AgentJobPath.next(Job.BEGINNER, Job.WARRIOR, 10));

        errands.due(TOBY, Job.WARRIOR);
        assertEquals(RosterState.AWAY, game.row(toby).state(), "off to its instructor, its seat kept");
        assertEquals(1, game.store.events("job.errand").size());
        game.runTimers();
        RosterEntry away = game.row(toby);
        clock.addAndGet(game.config.RECALL_MAX_ETA_MS);
        assertEquals(WARRIORS_SANCTUARY, errands.form.mapFor(away, toby, TOBY), "at Dances with Balrog's");
        Character bot = game.bot(toby);
        visit(away, bot, WARRIORS_SANCTUARY, DANCES_WITH_BALROG, new Point(300, 150));
        game.runDueTimers();
        clock.addAndGet(game.config.COMPANION_JOB_ERRAND_TALK_MS);
        game.runDueTimers();

        assertEquals(List.of("Toby WARRIOR"), advanced, "the job its owner picked, at its instructor");
        assertEquals(1, game.store.events("job.advanced").size());
    }

    @Test
    void fromItsFirstJobItsOwnerPicksTheSecondAndAfterGraduationItsLineTakesOver() {
        roles.set(ANN, TOBY_ID, CompanionRoles.Role.APPRENTICE, "WARRIOR");
        assertEquals(Job.WARRIOR, errands.path(TOBY, Job.WARRIOR), "a Warrior with no second pick: nothing due");
        assertNull(AgentJobPath.next(Job.WARRIOR, errands.path(TOBY, Job.WARRIOR), 30));
        roles.set(ANN, TOBY_ID, CompanionRoles.Role.APPRENTICE, "FIGHTER");
        assertEquals(Job.FIGHTER, errands.path(TOBY, Job.WARRIOR));
        assertEquals(Job.FIGHTER, AgentJobPath.next(Job.WARRIOR, Job.FIGHTER, 30));

        roles.set(ANN, TOBY_ID, null, null); // graduated at its second job: a plain hire
        assertEquals(Job.HERO, errands.path(TOBY, Job.FIGHTER), "3rd and 4th job by errand, as every hire");
        assertNull(errands.path(TOBY, Job.BEGINNER), "a plain hire at Beginner: its owner picks in chat, as before");
    }

    @Test
    void theApprenticeSwitchOffMakesItAPlainHire() {
        roles.set(ANN, TOBY_ID, CompanionRoles.Role.APPRENTICE, "WARRIOR");
        game.config.APPRENTICE_ENABLED = false;
        assertNull(errands.path(TOBY, Job.BEGINNER));
        assertFalse(errands.holdsChatJobChange(TOBY, Job.WARRIOR));
    }

    @Test
    void aJobWordInChatHoldsTheInstantChangeOnlyForAnApprentice() {
        AgentJobPath.install(errands);
        assertTrue(AgentJobPath.holdsChatJobChange(TOBY, Job.WARRIOR), "an apprentice: its owner's window instead");
        verify(apprentices).askOwner(TOBY_ID);

        roles.set(ANN, TOBY_ID, null, null);
        assertFalse(AgentJobPath.holdsChatJobChange(TOBY, Job.WARRIOR), "a plain hire changes at once, as today");
        assertFalse(AgentJobPath.holdsChatJobChange(999, Job.WARRIOR), "not a companion's character");
        AgentJobPath.install(null);
        assertFalse(AgentJobPath.holdsChatJobChange(TOBY, Job.WARRIOR), "no rule installed: as before");
    }

    @Test
    void aMentorIgnoresItsNewcomersBranchWordForItself() {
        roles.set(ANN, TOBY_ID, CompanionRoles.Role.MENTOR, null);
        assertTrue(errands.holdsChatJobChange(TOBY, Job.THIEF));
        verify(apprentices, never()).askOwner(anyString());
        game.config.MENTOR_ENABLED = false;
        assertFalse(errands.holdsChatJobChange(TOBY, Job.THIEF));
    }

    private BenchDutyDispatcher.Slice visit(RosterEntry row, Character bot, int mapId, int npcId, Point npcAt) {
        MapleMap map = game.map(mapId);
        NPC npc = mock(NPC.class);
        when(npc.getPosition()).thenReturn(npcAt);
        when(map.getNPCById(npcId)).thenReturn(npc);
        bot.changeMap(map, new Point(LifecycleHarness.portalX(mapId), 200));
        BenchDutyDispatcher.Slice slice = new BenchDutyDispatcher.Slice(row, bot.getId(), errands.form, mapId, clock.get());
        assertTrue(errands.form.begin(slice, bot, clock.get()));
        return slice;
    }

    private static void stats(Character bot, Job job, int level) {
        when(bot.getJob()).thenReturn(job);
        when(bot.getLevel()).thenReturn(level);
        when(bot.getHp()).thenReturn(500);
        when(bot.getCurrentMaxHp()).thenReturn(500);
    }
}
