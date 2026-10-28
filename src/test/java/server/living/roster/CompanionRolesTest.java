package server.living.roster;

import config.LivingWorldConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.living.LivingWorld;
import server.living.store.LivingStore;
import server.living.store.LivingStore.RosterEntry;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Hires with a role (docs/BOT_CONCEPT_PLAN.md F4): read from living_roster at boot, written through, forgotten with
 * their row. A mentor holds a party seat but no roster slot and turns bench work down in its own words; with
 * MENTOR_ENABLED off it is a plain hire again. A world built without the roles (the roster's own tests) treats every
 * companion as before.
 */
class CompanionRolesTest {
    private final LivingWorldConfig config = new LivingWorldConfig();
    private final LivingWorld world = mock(LivingWorld.class);
    private final LivingStore store = mock(LivingStore.class);
    private CompanionRoles roles;

    @BeforeEach
    void setUp() {
        config.MENTOR_ENABLED = true;
        config.APPRENTICE_ENABLED = true;
        when(world.config()).thenReturn(config);
        when(world.store()).thenReturn(store);
        when(store.saveRosterRole(anyInt(), anyString(), any(), any())).thenReturn(true);
        roles = new CompanionRoles(world);
        when(world.roles()).thenReturn(roles);
    }

    private static RosterEntry row(String personaId) {
        RosterEntry entry = mock(RosterEntry.class);
        when(entry.personaId()).thenReturn(personaId);
        return entry;
    }

    @Test
    void bootReadsTheRolesTheRowsHold() {
        when(store.rosterRoles()).thenReturn(List.of(new LivingStore.RosterRole(10, "hire:900", "MENTOR", null),
                new LivingStore.RosterRole(11, "hire:901", "APPRENTICE", "THIEF"),
                new LivingStore.RosterRole(12, "hire:902", "SOMETHING_ELSE", null)));
        roles.load();
        assertEquals(CompanionRoles.Role.MENTOR, roles.role("hire:900"));
        assertEquals("THIEF", roles.of("hire:901").target());
        assertNull(roles.of("hire:902"), "a role this build does not know is none");
        assertEquals(10, roles.ofOwner(10, CompanionRoles.Role.MENTOR).ownerId());
        assertNull(roles.ofOwner(10, CompanionRoles.Role.APPRENTICE));
        assertEquals(1, roles.all(CompanionRoles.Role.APPRENTICE).size());
    }

    @Test
    void aMentorHoldsNoRosterSlotWhileTheSwitchIsOn() {
        roles.set(10, "hire:900", CompanionRoles.Role.MENTOR, null);
        List<RosterEntry> rows = List.of(row("hire:900"), row("rurik"), row("hire:901"));
        assertEquals(2, CompanionRoles.rosterSlots(world, rows));
        assertFalse(CompanionRoles.takesRosterSlot(world, "hire:900"));
        config.MENTOR_ENABLED = false;
        assertEquals(3, CompanionRoles.rosterSlots(world, rows), "off: a plain hire");
    }

    @Test
    void aMentorTurnsBenchWorkDownInItsOwnWordsAndNobodyElseDoes() {
        roles.set(10, "hire:900", CompanionRoles.Role.MENTOR, null);
        RosterResult refusal = CompanionRoles.refusal(world, "hire:900", "Bo");
        assertInstanceOf(RosterResult.Failed.class, refusal);
        assertTrue(refusal.toString().contains("just here to show you around"), refusal.toString());
        assertNull(CompanionRoles.refusal(world, "rurik", "Rurik"));
        roles.set(11, "hire:901", CompanionRoles.Role.APPRENTICE, null);
        assertNull(CompanionRoles.refusal(world, "hire:901", "Toby"), "an apprentice works like any hire");
    }

    @Test
    void aRoleIsWrittenThroughAndARowThatDidNotTakeItChangesNothing() {
        assertTrue(roles.set(11, "hire:901", CompanionRoles.Role.APPRENTICE, "WARRIOR"));
        assertTrue(CompanionRoles.apprentice(world, "hire:901"));
        when(store.saveRosterRole(11, "hire:901", "APPRENTICE", "FIGHTER")).thenReturn(false);
        assertFalse(roles.set(11, "hire:901", CompanionRoles.Role.APPRENTICE, "FIGHTER"));
        assertEquals("WARRIOR", roles.of("hire:901").target(), "the cache follows the row");
        roles.forget("hire:901");
        assertNull(roles.of("hire:901"), "released: its row and role went");
        config.APPRENTICE_ENABLED = false;
        roles.set(11, "hire:901", CompanionRoles.Role.APPRENTICE, null);
        assertFalse(CompanionRoles.apprentice(world, "hire:901"), "off: a plain hire");
    }

    @Test
    void aPendingRoleCountsWhileItsHireEnrolls() {
        roles.pending(10, "hire:900", CompanionRoles.Role.MENTOR);
        assertFalse(CompanionRoles.takesRosterSlot(world, "hire:900"), "no roster book handed over for it");
        roles.forget("hire:900");
        assertTrue(CompanionRoles.takesRosterSlot(world, "hire:900"));
    }

    @Test
    void aWorldWithoutRolesTreatsEveryCompanionAsBefore() {
        LivingWorld bare = mock(LivingWorld.class);
        assertTrue(CompanionRoles.takesRosterSlot(bare, "hire:900"));
        assertFalse(CompanionRoles.mentor(bare, "hire:900"));
        assertFalse(CompanionRoles.apprentice(null, "hire:900"));
        assertNull(CompanionRoles.refusal(bare, "hire:900", "Bo"));
    }
}
