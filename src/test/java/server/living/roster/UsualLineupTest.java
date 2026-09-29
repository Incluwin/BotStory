package server.living.roster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterState;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.LITH;

/**
 * The usual lineup (docs/BOT_MANAGEMENT_PLAN.md, "Usual lineup"; P3 "Remember this lineup" / "Call my usual lineup"): it
 * saves itself the first time 2 or more companions are out, in their party window's order, and is left alone by later
 * calls; "Remember this lineup" saves the party as it is now; the usual call brings the benched ones in its order, each
 * joining at once and travelling over. The roster is the real one over LifecycleHarness.
 */
class UsualLineupTest {
    private final LifecycleHarness game = new LifecycleHarness();
    private final Persona hadrik = game.companion("hadrik", "Hadrik", 901, RosterState.BENCHED, LITH);
    private final Persona tobi = game.companion("tobi", "Tobi", 902, RosterState.BENCHED, LITH);
    private final Persona mira = game.companion("mira", "Mira", 903, RosterState.BENCHED, LITH);

    @BeforeEach
    void roster() {
        game.roster();
    }

    @AfterEach
    void close() {
        game.close();
    }

    @Test
    void theFirstTimeTwoAreOutTheLineupRemembersThemInPartyOrder() {
        game.roster().call(game.ann, tobi);
        assertEquals(Map.of(), game.store.store.lineupSlots(ANN), "one out is no lineup yet");

        game.roster().call(game.ann, hadrik);

        assertEquals(RosterState.TRAVELLING, game.row(hadrik).state());
        assertEquals(Map.of("tobi", 1, "hadrik", 2), game.store.store.lineupSlots(ANN));
    }

    @Test
    void onceRememberedALaterCallLeavesTheLineupAlone() {
        game.roster().call(game.ann, tobi);
        game.roster().call(game.ann, hadrik);

        game.roster().call(game.ann, mira);

        assertEquals(Map.of("tobi", 1, "hadrik", 2), game.store.store.lineupSlots(ANN));
    }

    @Test
    void rememberThisLineupSavesThePartyAsItIsNow() {
        game.roster().call(game.ann, tobi);
        game.roster().call(game.ann, hadrik);
        game.roster().call(game.ann, mira);

        game.roster().saveUsualLineup(game.ann);

        assertEquals(Map.of("tobi", 1, "hadrik", 2, "mira", 3), game.store.store.lineupSlots(ANN));
    }

    @Test
    void theUsualCallBringsTheBenchedOnesInLineupOrderJoiningAtOnce() {
        game.store.lineup.put(FakeLivingStore.key(ANN, "mira"), 1);
        game.store.lineup.put(FakeLivingStore.key(ANN, "hadrik"), 2);

        game.roster().callUsual(game.ann);

        assertEquals(List.of("join Mira", "join Hadrik"), game.calls.stream().filter(call -> call.startsWith("join")).toList());
        assertEquals(RosterState.TRAVELLING, game.row(mira).state());
        assertEquals(RosterState.BENCHED, game.row(tobi).state(), "not in the lineup");
        assertEquals(Map.of("mira", 1, "hadrik", 2), game.store.store.lineupSlots(ANN), "the call leaves its order as it was");
    }
}
