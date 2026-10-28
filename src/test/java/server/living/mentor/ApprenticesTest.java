package server.living.mentor;

import client.Character;
import client.Job;
import config.LivingWorldConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.progression.AgentCareerBuildBundle;
import server.agents.progression.AgentCareerBuildBundleService;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.population.Citizen;
import server.living.population.CitizenSession;
import server.living.population.PersonMemories;
import server.living.population.Population;
import server.living.population.PresenceScheduler;
import server.living.population.needs.Need;
import server.living.population.needs.NeedLedger;
import server.living.resident.ResidentRuntime;
import server.living.roster.CompanionExecutor;
import server.living.roster.CompanionRoles;
import server.living.roster.HiredHands;
import server.living.roster.RosterService;
import server.living.store.LivingStore;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Apprentices (docs/BOT_CONCEPT_PLAN.md F4.5): who may be adopted (a loaded Beginner citizen of up to
 * APPRENTICE_ADOPT_MAX_LEVEL at REGULAR standing, or one that asked for a mentor on a board, by a player of
 * APPRENTICE_ADOPT_MIN_OWNER_LEVEL+), which jobs its owner picks from at each advancement and with which build, the
 * pick written onto its roster row, and its graduation at the second job: a plain companion again, its friendship (its
 * hire bond) untouched.
 */
class ApprenticesTest {
    private static final int ANN = 10;
    private static final int TOBY = 831;
    private static final String TOBY_ID = "hire:" + TOBY;

    private final LivingWorldConfig config = new LivingWorldConfig();
    private final LivingWorld world = mock(LivingWorld.class);
    private final LivingStore store = mock(LivingStore.class);
    private final Population population = mock(Population.class);
    private final PersonMemories memories = mock(PersonMemories.class);
    private final NeedLedger needs = mock(NeedLedger.class);
    private final Citizen citizen = mock(Citizen.class);
    private final CitizenSession session = mock(CitizenSession.class);
    private final Character toby = mock(Character.class);
    private final Character ann = mock(Character.class);
    private final Persona persona = mock(Persona.class);
    private MockedStatic<Population> populations;
    private CompanionRoles roles;
    private Apprentices apprentices;

    @BeforeEach
    void setUp() {
        config.APPRENTICE_ENABLED = true;
        CompanionExecutor executor = mock(CompanionExecutor.class);
        when(executor.submit(anyString(), any(Runnable.class))).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return CompletableFuture.completedFuture(null);
        });
        when(world.config()).thenReturn(config);
        when(world.store()).thenReturn(store);
        when(world.executor()).thenReturn(executor);
        LivingContent content = mock(LivingContent.class);
        when(content.persona(TOBY_ID)).thenReturn(persona);
        when(world.content()).thenReturn(content);
        when(world.residents()).thenReturn(mock(ResidentRuntime.class));
        HiredHands hires = mock(HiredHands.class);
        when(hires.voice(anyInt(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(world.hiredHands()).thenReturn(hires);
        RosterService roster = mock(RosterService.class);
        when(roster.onlineCharacter(ANN)).thenReturn(ann);
        when(world.roster()).thenReturn(roster);
        when(store.saveRosterRole(anyInt(), anyString(), any(), any())).thenReturn(true);
        roles = new CompanionRoles(world);
        when(world.roles()).thenReturn(roles);
        when(persona.id()).thenReturn(TOBY_ID);
        when(persona.name()).thenReturn("Toby");

        when(ann.getId()).thenReturn(ANN);
        when(ann.getLevel()).thenReturn(35);
        when(citizen.hired()).thenReturn(false);
        when(citizen.state()).thenReturn(Citizen.State.ACTIVE);
        when(session.agent()).thenReturn(toby);
        when(toby.getId()).thenReturn(TOBY);
        when(toby.getLevel()).thenReturn(6);
        when(toby.getJob()).thenReturn(Job.BEGINNER);
        when(toby.isBeginnerJob()).thenReturn(true);
        PresenceScheduler presence = mock(PresenceScheduler.class);
        when(presence.session(TOBY)).thenReturn(session);
        when(population.citizen(TOBY)).thenReturn(citizen);
        when(population.presence()).thenReturn(presence);
        when(population.memories()).thenReturn(memories);
        when(population.needs()).thenReturn(needs);
        populations = mockStatic(Population.class);
        populations.when(Population::get).thenReturn(population);
        apprentices = new Apprentices(world);
    }

    @AfterEach
    void tearDown() {
        populations.close();
    }

    // ---- adoption ------------------------------------------------------------------------------

    @Test
    void aBeginnerAtRegularStandingMayBeAdoptedAndBelowItMayNot() {
        when(memories.warmth(TOBY, ANN)).thenReturn(15);
        assertTrue(apprentices.mayAdopt(ann, TOBY, false), "REGULAR: warmth 15");
        when(memories.warmth(TOBY, ANN)).thenReturn(14);
        assertFalse(apprentices.mayAdopt(ann, TOBY, false), "a stranger does not take you as their mentor");
    }

    @Test
    void oneThatAskedForAMentorNeedsNoStanding() {
        when(memories.warmth(TOBY, ANN)).thenReturn(0);
        when(needs.mentorWanted(TOBY)).thenReturn(mock(Need.class));
        assertTrue(apprentices.mayAdopt(ann, TOBY, true), "its request on a board");
        assertTrue(apprentices.mayAdopt(ann, TOBY, false), "or in person, while its request is up");
    }

    @Test
    void notABeginnerNotLoadedAlreadyHiredTooHighOrTooLowAnOwnerOrSwitchedOffMayNotBeAdopted() {
        when(memories.warmth(TOBY, ANN)).thenReturn(50);
        assertTrue(apprentices.mayAdopt(ann, TOBY, false));
        when(ann.getLevel()).thenReturn(29);
        assertFalse(apprentices.mayAdopt(ann, TOBY, false), "its mentor is Lv 30+");
        when(ann.getLevel()).thenReturn(35);
        when(toby.getLevel()).thenReturn(11);
        assertFalse(apprentices.mayAdopt(ann, TOBY, false), "past APPRENTICE_ADOPT_MAX_LEVEL");
        when(toby.getLevel()).thenReturn(6);
        when(toby.getJob()).thenReturn(Job.MAGICIAN);
        when(toby.isBeginnerJob()).thenReturn(false);
        assertFalse(apprentices.mayAdopt(ann, TOBY, false), "it has its job already");
        when(toby.getJob()).thenReturn(Job.BEGINNER);
        when(toby.isBeginnerJob()).thenReturn(true);
        when(citizen.hired()).thenReturn(true);
        assertFalse(apprentices.mayAdopt(ann, TOBY, false), "somebody's already");
        when(citizen.hired()).thenReturn(false);
        session.virtual = true;
        assertFalse(apprentices.mayAdopt(ann, TOBY, false), "not loaded: nobody to double-click");
        session.virtual = false;
        config.APPRENTICE_ENABLED = false;
        assertFalse(apprentices.mayAdopt(ann, TOBY, false));
        assertFalse(apprentices.mayAdopt(null, TOBY, false));
    }

    // ---- the owner's pick ----------------------------------------------------------------------

    @Test
    void theOwnerPicksFromTheLegalNextJobs() {
        assertEquals(List.of(Job.MAGICIAN), Apprentices.choices(Job.BEGINNER, 8, true), "a Magician from Lv 8");
        assertEquals(List.of(), Apprentices.choices(Job.BEGINNER, 8, false), "a level-up asks at Lv 10, for all five");
        assertEquals(MentorRoute.BRANCHES, Apprentices.choices(Job.BEGINNER, 10, false));
        assertEquals(List.of(Job.FIGHTER, Job.PAGE, Job.SPEARMAN), Apprentices.choices(Job.WARRIOR, 30, true));
        assertEquals(List.of(Job.ASSASSIN, Job.BANDIT), Apprentices.choices(Job.THIEF, 30, true));
        assertEquals(List.of(), Apprentices.choices(Job.WARRIOR, 29, true));
        assertEquals(List.of(), Apprentices.choices(Job.FIGHTER, 70, true), "from its second job on, its line decides");
        assertEquals(List.of(), Apprentices.choices(Job.NOBLESSE, 10, true), "an Explorer's path only");
    }

    @Test
    void theBuildIsOfferedWhereItsBranchHasMoreThanOne() {
        List<AgentCareerBuildBundle> thief = AgentCareerBuildBundleService.choicesFor(Job.THIEF.getId());
        assertEquals(List.of("Claw", "Dagger"), thief.stream().map(Apprentices::buildName).toList());
        assertEquals(List.of("Gun", "Knuckle"), AgentCareerBuildBundleService.choicesFor(Job.PIRATE.getId()).stream()
                .map(Apprentices::buildName).toList());
        assertEquals(1, AgentCareerBuildBundleService.choicesFor(Job.WARRIOR.getId()).size(), "one Warrior build: no menu");
        assertEquals(1, AgentCareerBuildBundleService.choicesFor(Job.ASSASSIN.getId()).size(), "a claw Assassin");
        assertTrue(AgentCareerBuildBundleService.choicesFor(Job.BEGINNER.getId()).isEmpty());
    }

    @Test
    void aPickIsOpenUntilTheOwnerPicksAndIsWrittenOntoItsRow() {
        roles.set(ANN, TOBY_ID, CompanionRoles.Role.APPRENTICE, null);
        when(store.characterLevelAndJob(TOBY)).thenReturn(new int[] {10, 0});
        assertTrue(apprentices.pickOpen(TOBY_ID), "Lv 10 Beginner, no pick");

        apprentices.picked(ANN, persona, Job.THIEF, "thief-dagger-standard-v1");
        verify(store).saveRosterRole(ANN, TOBY_ID, "APPRENTICE", "THIEF");
        verify(store).event(ANN, TOBY_ID, Apprentices.PICK, "THIEF");
        verify(store).event(ANN, TOBY_ID, Apprentices.BUILD, "thief-dagger-standard-v1");
        assertEquals("THIEF", roles.of(TOBY_ID).target());
        assertFalse(apprentices.pickOpen(TOBY_ID), "picked: its instructor errand takes it from here");

        when(store.characterLevelAndJob(TOBY)).thenReturn(new int[] {30, Job.THIEF.getId()});
        assertTrue(apprentices.pickOpen(TOBY_ID), "a Thief at Lv 30: its second job is its owner's pick again");
        when(store.characterLevelAndJob(TOBY)).thenReturn(new int[] {29, Job.THIEF.getId()});
        assertFalse(apprentices.pickOpen(TOBY_ID));
        config.APPRENTICE_ENABLED = false;
        when(store.characterLevelAndJob(TOBY)).thenReturn(new int[] {30, Job.THIEF.getId()});
        assertFalse(apprentices.pickOpen(TOBY_ID), "switched off: a plain hire");
    }

    // ---- graduation ----------------------------------------------------------------------------

    @Test
    void itsSecondJobGraduatesItIntoAPlainCompanionItsFriendshipKept() {
        roles.set(ANN, TOBY_ID, CompanionRoles.Role.APPRENTICE, "ASSASSIN");
        CompanionRoles.Held held = roles.of(TOBY_ID);

        apprentices.advanced(held, TOBY, Job.ASSASSIN);

        verify(store).saveRosterRole(ANN, TOBY_ID, null, null);
        verify(store).event(ANN, TOBY_ID, Apprentices.GRADUATED, "ASSASSIN");
        assertNull(roles.of(TOBY_ID), "no role: a companion like any other");
        assertFalse(CompanionRoles.apprentice(world, TOBY_ID));
        verify(world, never()).bonds(); // its hire:<id> bond, and the friendship on it, stay as they were
    }

    @Test
    void itsFirstJobIsNoGraduation() {
        roles.set(ANN, TOBY_ID, CompanionRoles.Role.APPRENTICE, "THIEF");
        when(store.personaEvents(ANN, TOBY_ID, List.of(Apprentices.BUILD), 1)).thenReturn(List.of());
        apprentices.advanced(roles.of(TOBY_ID), TOBY, Job.THIEF);
        assertEquals(CompanionRoles.Role.APPRENTICE, roles.role(TOBY_ID));
        verify(store, never()).event(anyInt(), anyString(), org.mockito.ArgumentMatchers.eq(Apprentices.GRADUATED),
                anyString());
    }
}
