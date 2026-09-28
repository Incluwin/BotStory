package server.living.roster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterState;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.HENESYS_FIELD;
import static server.living.roster.LifecycleHarness.LITH;
import static server.living.roster.LifecycleHarness.portalX;

/**
 * A companion sent on an errand (plan 11.2, "Delegated remote work") never vanishes beside its owner: it keeps its
 * party seat as an AWAY reservation, leaves the party at once and walks out to the nearest portal, and is gone when it
 * gets there (docs/BOT_MANAGEMENT_PLAN.md P2b: every disappearance is a walk-out).
 */
class DelegateWalkOutTest {
    private final LifecycleHarness game = new LifecycleHarness();
    private Persona hadrik;

    @BeforeEach
    void outWithAnn() {
        hadrik = game.companion("hadrik", "Hadrik", 901, RosterState.ACTIVE, LITH);
        game.outWithAnn(hadrik);
        game.roster();
    }

    @AfterEach
    void close() {
        game.close();
    }

    @Test
    void aDelegatedCompanionWalksOutToThePortalAndKeepsItsSeat() {
        RosterResult result = game.roster().delegate(game.ann, hadrik, Assignment.REST, 0, 0, 0);

        assertTrue(result.ok(), String.valueOf(result));
        String walk = "walk Hadrik to " + portalX(HENESYS_FIELD) + ",200";
        assertEquals(List.of("leave Hadrik", walk), game.calls, "no pop-out beside her");
        assertEquals(RosterState.AWAY, game.row(hadrik).state());
        assertEquals(CompanionSlots.Kind.AWAY, game.slots.reservation("hadrik").kind(), "its seat is kept");
        assertFalse(game.members.contains(901));
        assertEquals(new CompanionLeases.Lease(CompanionLeases.Kind.COMPANION, ANN), game.leases.holder(901));

        game.runTimers();

        assertEquals(List.of("leave Hadrik", walk, "remove Hadrik"), game.calls, "gone at the portal");
        assertEquals(CompanionSlots.Kind.AWAY, game.slots.reservation("hadrik").kind());
    }

    @Test
    void oneOffItsOwnersMapGoesAtOnce() {
        game.moveAnn(LifecycleHarness.KERNING, 1500, 120); // Hadrik stayed on the field she left

        game.roster().delegate(game.ann, hadrik, Assignment.REST, 0, 0, 0);

        assertEquals(List.of("leave Hadrik", "remove Hadrik"), game.calls);
        assertEquals(RosterState.AWAY, game.row(hadrik).state());
    }
}
