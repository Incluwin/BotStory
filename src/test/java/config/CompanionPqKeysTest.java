package config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The keys the companion-PQ kill switch may list, and what validation says of any other: with KPQ and HPQ it is
 * today's message byte for byte; a third quest's key joins the list as "a, b or c".
 */
class CompanionPqKeysTest {

    @Test
    void twoKeysReadAsTodaysMessage() {
        assertEquals("living_world.COMPANION_PQ_ENABLED_KEYS must list only kpq or hpq",
                CompanionPqKeys.message(List.of("kpq", "hpq")));
    }

    @Test
    void threeOrMoreKeysAreCommaSeparatedWithTheLastAfterOr() {
        assertTrue(CompanionPqKeys.message(List.of("kpq", "hpq", "lpq")).endsWith("only kpq, hpq or lpq"),
                CompanionPqKeys.message(List.of("kpq", "hpq", "lpq")));
        assertEquals("living_world.COMPANION_PQ_ENABLED_KEYS must list only kpq, hpq, lpq or epq",
                CompanionPqKeys.message(List.of("kpq", "hpq", "lpq", "epq")));
    }

    @Test
    void theMessageNamesEveryKnownKey() {
        assertEquals(CompanionPqKeys.message(CompanionPqKeys.KNOWN), CompanionPqKeys.message());
        assertEquals(List.of("kpq", "hpq"), CompanionPqKeys.KNOWN.subList(0, 2), "KPQ's first, then HPQ's");
    }

    @Test
    void aKeyIsKnownInAnyCaseWithSpacesAroundIt() {
        assertTrue(CompanionPqKeys.known(" HPQ "));
        assertTrue(CompanionPqKeys.known("kpq"));
        assertFalse(CompanionPqKeys.known("zzpq"));
        assertFalse(CompanionPqKeys.known(""));
        assertFalse(CompanionPqKeys.known(null));
    }
}
