package server.living.roster;

import net.server.Server;
import net.server.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.HENESYS_FIELD;
import static server.living.roster.LifecycleHarness.LITH;
import static server.living.roster.LifecycleHarness.portalX;

/**
 * Decision 8a in a party with another human (docs/BOT_MANAGEMENT_PLAN.md flow 7 step 3, flow 5 step 9): Ann leads a
 * party with her friend Ben and his companion. At her logout one of hers says goodbye and they all leave the party at
 * once, so Ben gets the seats back; their rows are PARKED roster-only, and Ben's companion stays. The lead passes to
 * Ben by the stock successor rule (LeaderSuccessionTest.theLeadersLogoutReallyHandsThePartyToTheHuman); the roster
 * makes no leader change. The grace ends without touching Ben's party, and at Ann's login within the grace hers walk
 * back in while her fair share has room, or go to their duty with returnWhenRoom when it has none.
 */
class LogoutSharedPartyTest {
    private static final int BEN = 20;
    private static final int BRANNOCK = 930;

    private final LifecycleHarness game = new LifecycleHarness();
    private Persona hadrik;
    private Persona jinx;

    @BeforeEach
    void annLeadsAPartyWithBen() {
        hadrik = game.companion("hadrik", "Hadrik", 901, RosterState.ACTIVE, LITH);
        jinx = game.companion("jinx", "Jinx", 902, RosterState.ACTIVE, LITH);
        game.outWithAnn(hadrik);
        game.outWithAnn(jinx);
        game.humans.add(BEN);
        game.members.add(BEN);
        game.members.add(BRANNOCK); // Ben's companion
        game.roster();
    }

    @AfterEach
    void close() {
        game.close();
    }

    @Test
    void herCompanionsSayByeAndLeaveAtOnceSoTheFriendGetsTheSeats() {
        game.roster().onOwnerLogout(game.ann, null);

        assertEquals(List.of("Hadrik: gn!"), game.partyChat);
        assertEquals(List.of("leave Hadrik", "remove Hadrik", "leave Jinx", "remove Jinx"), game.calls);
        assertEquals(List.of(ANN, BEN, BRANNOCK), game.members, "Ben's companion stays; two seats are free");
        assertEquals(List.of(RosterService.CAUSE, RosterService.CAUSE), game.causes, "the sync takes the leaves as echoes");
        for (Persona persona : List.of(hadrik, jinx)) {
            RosterEntry row = game.row(persona);
            assertEquals(RosterState.PARKED, row.state(), persona.name());
            assertTrue(row.parkedAtMs() > 0L);
        }
        assertEquals(2, game.store.events("companion.parked").size());
        assertTrue(game.store.events("companion.parked").get(0).endsWith("out of the party"), "roster-only");
    }

    @Test
    void theGraceEndsWithoutTouchingTheFriendsParty() {
        game.roster().onOwnerLogout(game.ann, null);
        game.annOnline = false;
        for (Persona persona : List.of(hadrik, jinx)) {
            ParkGraceTest.parkedAgo(game, persona, game.config.LEADER_OFFLINE_BENCH_MS + 1_000L);
        }

        game.roster().benchParkedPastGrace();

        assertEquals(RosterState.BENCHED, game.row(hadrik).state());
        assertEquals(RosterState.BENCHED, game.row(jinx).state());
        World world = Server.getInstance().getWorld(0);
        verify(world, never()).updateParty(anyInt(), any(), any());
        assertEquals(List.of(ANN, BEN, BRANNOCK), game.members);
    }

    @Test
    void backWithinTheGraceTheyWalkInWhileHerShareHasRoom() {
        game.roster().onOwnerLogout(game.ann, null);
        game.calls.clear();

        game.roster().onOwnerLogin(game.ann);
        game.runDueTimers();

        String portal = HENESYS_FIELD + " " + portalX(HENESYS_FIELD) + ",200";
        assertEquals(List.of("spawn Hadrik at " + portal, "join Hadrik", "follow Hadrik",
                "spawn Jinx at " + portal, "join Jinx", "follow Jinx"), game.calls);
        assertEquals(RosterState.ACTIVE, game.row(hadrik).state());
        assertEquals(RosterState.ACTIVE, game.row(jinx).state());
    }

    @Test
    void backWithinTheGraceToAFullPartyTheyGoToTheirDutyAndWaitForRoom() {
        game.roster().onOwnerLogout(game.ann, null);
        game.members.addAll(List.of(931, 932, 933)); // Ben called three more meanwhile: 6/6
        game.calls.clear();

        game.roster().onOwnerLogin(game.ann);
        game.runDueTimers();

        assertTrue(game.calls.stream().noneMatch(call -> call.startsWith("spawn")), String.valueOf(game.calls));
        for (Persona persona : List.of(hadrik, jinx)) {
            assertEquals(RosterState.BENCHED, game.row(persona).state(), persona.name());
            assertTrue(game.slots.waitingForRoom(persona.id()), "it comes back by itself once there is room");
        }
    }
}
