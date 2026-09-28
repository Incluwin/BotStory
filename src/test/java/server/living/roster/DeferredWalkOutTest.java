package server.living.roster;

import org.junit.jupiter.api.Test;
import server.living.bond.ContractService;
import server.living.config.Content.Persona;
import server.living.roster.ContractPolicyTest.Game;
import server.living.roster.ContractPolicyTest.Setting;
import server.living.store.LivingStore.RosterState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.HENESYS_FIELD;
import static server.living.roster.LifecycleHarness.portalX;

/**
 * Walk-outs wait for the run (docs/BOT_MANAGEMENT_PLAN.md, "Commitment", "Contract policy + deferral", flow 10 step
 * 5): in every mode, a companion whose contract ends while it is committed to an event instance or expedition says
 * "after this run, i'm heading home." once and stays; when its commitment ends (it left the run, or the run ended) it
 * leaves by the normal release path, with its goodbye, or quietly when its owner is offline by then. The same holds
 * for an arc's explicit end and for a dismissal. One let go beside its owner never vanishes on the spot: it leaves the
 * party and walks out to the nearest portal.
 */
class DeferredWalkOutTest {
    private static final String RUN = "kpq-3";
    private static final String GOODBYE = "that's it for me. see you around.";

    @Test
    void aDismissalMidRunWaitsForTheRunThenGoesHome() {
        try (Game game = new Game(Setting.PERMANENT_ON_ROSTER, -1)) {
            Persona hadrik = game.hadrik(RosterState.ACTIVE);
            game.h.commitments.commit(RUN, ANN, 501);

            assertEquals("Hadrik heads home once this run is over.", game.contracts.dismiss(game.h.ann, hadrik));

            assertNotNull(game.h.row(hadrik));
            assertEquals(List.of(ContractService.AFTER_THIS_RUN_LINE), game.said);
            assertEquals(List.of(ANN + " hadrik contract.after_the_run dismissed"), game.h.store.events("contract.after_the_run"));

            game.h.commitments.releaseKey(RUN);

            assertNull(game.h.row(hadrik));
            assertEquals(List.of(ANN + " hadrik roster.release dismissed"), game.h.store.events("roster.release"));
            assertEquals(List.of(ContractService.AFTER_THIS_RUN_LINE, GOODBYE), game.said);
            assertEquals(List.of(ANN + " hadrik contract.ended dismissed"), game.h.store.events("contract.ended"));
        }
    }

    @Test
    void onlyTheFirstReasonIsKeptAndItIsSaidOnce() {
        try (Game game = new Game(Setting.AUTHORED, -1)) {
            Persona hadrik = game.hadrik(RosterState.ACTIVE);
            game.h.commitments.commit(RUN, ANN, 501);

            game.contracts.end(game.h.ann, hadrik, "arc: the last voyage");
            game.bond(6 * 3_600);
            game.contracts.tick(); // its contract hours run out too
            game.contracts.dismiss(game.h.ann, hadrik);

            assertEquals(List.of(ContractService.AFTER_THIS_RUN_LINE), game.said);
            game.h.commitments.releaseKey(RUN);
            assertEquals(List.of(ANN + " hadrik roster.release arc: the last voyage"), game.h.store.events("roster.release"));
        }
    }

    @Test
    void anOwnerOfflineWhenTheRunEndsStillLosesTheCompanionWithoutAWord() {
        try (Game game = new Game(Setting.AUTHORED, -1)) {
            Persona hadrik = game.hadrik(RosterState.ACTIVE);
            game.h.commitments.commit(RUN, ANN, 501);
            game.due(ContractPolicyTest.Rule.CONTRACT_END);
            game.h.annOnline = false; // logged off; the companion finished the run for a friend (decision 8b)

            game.h.commitments.releaseKey(RUN);

            assertNull(game.h.row(hadrik));
            assertEquals(List.of(ANN + " hadrik roster.release contract ended"), game.h.store.events("roster.release"));
            assertEquals(List.of(ContractService.AFTER_THIS_RUN_LINE), game.said, "no goodbye to nobody");
        }
    }

    @Test
    void aMemberThatLeavesTheRunGoesWhileTheOthersStay() {
        try (Game game = new Game(Setting.AUTHORED, -1)) {
            Persona hadrik = game.hadrik(RosterState.ACTIVE);
            Persona jinx = game.h.companion("jinx", "Jinx", 503, RosterState.ACTIVE, LifecycleHarness.KERNING);
            game.h.outWithAnn(jinx);
            game.h.commitments.commit(RUN, ANN, 501);
            game.h.commitments.commit(RUN, ANN, 503);
            game.contracts.end(game.h.ann, hadrik, "arc: the last voyage");

            game.h.commitments.releaseMember(503); // Jinx left the run: nothing of Hadrik's is due

            assertNotNull(game.h.row(hadrik));
            assertNotNull(game.h.row(jinx));

            game.h.commitments.releaseMember(501); // Hadrik left it: he goes now

            assertNull(game.h.row(hadrik));
            assertNotNull(game.h.row(jinx));
        }
    }

    @Test
    void oneCommittedToAnotherRunBeforeItsWalkOutRanWaitsForThatOne() {
        List<Runnable> ops = new ArrayList<>();
        try (Game game = new Game(Setting.AUTHORED, -1, ops)) {
            Persona hadrik = game.hadrik(RosterState.ACTIVE);
            game.h.commitments.commit(RUN, ANN, 501);
            game.contracts.end(game.h.ann, hadrik, "contract ended");
            game.h.commitments.releaseKey(RUN);
            game.h.commitments.commit("zakum-1", ANN, 501); // straight into the next run, ahead of companion-ops

            ops.remove(0).run();

            assertNotNull(game.h.row(hadrik), "it waits for the second run");
            game.h.commitments.releaseKey("zakum-1");
            ops.remove(0).run();
            assertNull(game.h.row(hadrik));
        }
    }

    @Test
    void notCommittedItGoesAtOnceWithItsGoodbye() {
        try (Game game = new Game(Setting.AUTHORED, -1)) {
            Persona hadrik = game.hadrik(RosterState.ACTIVE);

            game.contracts.end(game.h.ann, hadrik, "contract ended");

            assertNull(game.h.row(hadrik));
            assertEquals(List.of(GOODBYE), game.said);
            assertEquals(List.of(), game.h.store.events("contract.after_the_run"));
        }
    }

    @Test
    void aCompanionLetGoBesideItsOwnerWalksOutToThePortalInsteadOfVanishing() {
        try (Game game = new Game(Setting.AUTHORED, -1)) {
            Persona hadrik = game.hadrik(RosterState.ACTIVE);
            game.h.calls.clear();

            game.contracts.end(game.h.ann, hadrik, "contract ended");

            assertNull(game.h.row(hadrik));
            String walk = "walk Hadrik to " + portalX(HENESYS_FIELD) + ",200";
            assertEquals(List.of("leave Hadrik", walk), game.h.calls, "it leaves the party and walks to the portal");
            assertEquals(new CompanionLeases.Lease(CompanionLeases.Kind.COMPANION, ANN), game.h.leases.holder(501),
                    "its lease holds its character until it is gone");

            game.h.runTimers();

            assertEquals(List.of("leave Hadrik", walk, "remove Hadrik"), game.h.calls, "gone at the portal");
            assertNull(game.h.leases.holder(501));
        }
    }

    @Test
    void livingWorldHandsTheWalkOutsAfterARunToCompanionOps() throws IOException {
        String source = Files.readString(Path.of("src/main/java/server/living/LivingWorld.java"));
        assertTrue(Pattern.compile("commitments\\.addListener\\(ended -> world\\.executor\\.submit\\([^;]*"
                + "world\\.contracts\\.commitmentEnded\\(ended\\)").matcher(source).find(), "the listener only hands over");
    }
}
