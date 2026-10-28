package server.living.mentor;

import org.junit.jupiter.api.Test;
import server.living.store.LivingStore.EventRow;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The mentor's offer gate (docs/BOT_CONCEPT_PLAN.md F4.1, D8: offered automatically, once, at a new character's first
 * login; declining is final). There is no new-character hook: a first login is a human's Lv 1 Explorer Beginner on Maple
 * Island that never logged out and has no mentor row yet. A window that never got on screen is offered again; one that
 * showed, was answered, accepted or ended is never offered again.
 */
class FirstLoginTest {
    private static final Optional<EventRow> NONE = Optional.empty();

    private static Optional<EventRow> row(String kind) {
        return Optional.of(new EventRow(kind, ""));
    }

    @Test
    void aBrandNewCharactersFirstLoginIsOffered() {
        assertEquals(FirstLogin.Gate.OFFER, FirstLogin.gate(true, 1, true, 10_000, true, NONE));
        assertEquals(FirstLogin.Gate.OFFER, FirstLogin.gate(true, 1, true, 20_000, true, NONE), "anywhere on the island");
    }

    @Test
    void anythingButANewHumanBeginnerOnTheIslandIsNot() {
        assertEquals(FirstLogin.Gate.NO, FirstLogin.gate(false, 1, true, 10_000, true, NONE), "a bot");
        assertEquals(FirstLogin.Gate.NO, FirstLogin.gate(true, 2, true, 10_000, true, NONE), "it played already");
        assertEquals(FirstLogin.Gate.NO, FirstLogin.gate(true, 1, false, 10_000, true, NONE), "a Noblesse or a Legend");
        assertEquals(FirstLogin.Gate.NO, FirstLogin.gate(true, 1, true, 104_000_000, true, NONE), "off the island");
        assertEquals(FirstLogin.Gate.NO, FirstLogin.gate(true, 1, true, 10_000, false, NONE), "it logged out before");
        assertEquals(FirstLogin.Gate.NO, FirstLogin.gate(true, 1, true, 10_000, null, NONE), "unknown: no offer");
        assertEquals(FirstLogin.Gate.NO, FirstLogin.gate(true, 1, true, 10_000, true, null), "the read failed: no offer");
    }

    @Test
    void theOfferIsOnceWhateverBecameOfIt() {
        for (String kind : new String[] {FirstLogin.OFFERED, FirstLogin.ACCEPTED, FirstLogin.DECLINED,
                FirstLogin.GRADUATED, FirstLogin.ENDED}) {
            assertEquals(FirstLogin.Gate.NO, FirstLogin.gate(true, 1, true, 10_000, true, row(kind)), kind + " is final");
        }
    }

    @Test
    void anOfferThatNeverShowedIsOfferedAgainWhileItIsStillANewcomerOnTheIsland() {
        assertEquals(FirstLogin.Gate.RETRY, FirstLogin.gate(true, 3, true, 30_000, false, row(FirstLogin.PENDING)),
                "another window was open all along last time: no decline");
        assertEquals(FirstLogin.Gate.NO, FirstLogin.gate(true, 9, true, 104_000_000, false, row(FirstLogin.PENDING)),
                "it left the island meanwhile");
    }
}
