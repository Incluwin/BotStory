package server.knowledge;

import org.junit.jupiter.api.Test;
import server.knowledge.ContentAnswers.Ask;
import server.knowledge.ContentAnswers.Asker;
import server.knowledge.ContentAnswers.ContentAnswer;
import server.knowledge.ContentAnswers.Rates;
import server.knowledge.ContentAnswers.Responder;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The replies the 2026-10-01 review read as wrong, over the real knowledge file and the seed overlay, across many bots
 * (each bot's seed picks other templates): routes name where a ride really lands and the rest of the way, "kpq till 30"
 * is never said to a 30, "its close" only for a short walk, names ending in s get "is", buckets carry no second verb,
 * the proper voice says you're, generic map names become their street, unreachable shops are not offered, the store
 * price stands in for an unseen one, the rate asked about is the one answered, Ellinia's shops belong to Ellinia.
 */
class ContentRepliesReadRightTest {
    private static final int HENESYS = 100_000_000;
    private static final int ELLINIA = 101_000_000;
    private static final int ORBIS = 200_000_000;
    private static final int BOTS = 40;
    private static final Rates RATES = new Rates(10, 3, 3, 3, 4f, 4);

    private final ContentAnswers answers = new ContentAnswers(SeedOverlay::knowledge, world -> RATES, item -> null, 10, 70, false);

    /** Every reply to {@code line} from {@link #BOTS} all-knowing bots in {@code style}, asked from {@code map} at {@code level}. */
    private List<String> replies(int map, int level, String style, String line) {
        List<String> out = new ArrayList<>();
        for (int bot = 1; bot <= BOTS; bot++) {
            ContentAnswer answer = answers.explain(new Ask(line, new Asker(5000 + bot, "Tester", map, level, 410, 0),
                    new Responder(bot, 200, "victoria", style, Set.of()), name -> false, 1_000_000L * bot));
            if (answer.reply() != null) {
                out.add(answer.reply());
            }
        }
        assertFalse(out.isEmpty(), line + ": no reply at all");
        return out;
    }

    private List<String> replies(int map, int level, String line) {
        return replies(map, level, "friendly-casual-v1", line);
    }

    @Test
    void routesNameWhereTheRideLandsAndTheRestOfTheWay() {
        for (String reply : replies(ORBIS, 30, "how do i get to henesys")) {
            assertTrue(reply.contains("ellinia") && reply.contains("henesys"), "the Orbis boat lands in Ellinia: " + reply);
        }
        for (String reply : replies(HENESYS, 30, "how do i get to el nath")) {
            assertTrue(reply.contains("orbis") && (reply.contains("then") || reply.contains("thru")), "orbis is not el nath: " + reply);
        }
    }

    @Test
    void aLevelThirtyIsNeverToldToKpqTillThirty() {
        for (String reply : replies(HENESYS, 30, "where shud i lvl")) {
            assertFalse(reply.contains("kpq"), reply);
        }
        assertTrue(replies(HENESYS, 25, "where shud i lvl").stream().anyMatch(reply -> reply.contains("kpq")), "25s still hear it");
    }

    @Test
    void onlyAShortWalkIsClose() {
        for (String reply : replies(HENESYS, 10, "where is zakum")) {
            assertFalse(reply.contains("close"), reply);
        }
    }

    @Test
    void phrasingReadsLikeAPlayer() {
        for (String reply : replies(HENESYS, 30, "where is lakelis")) {
            assertFalse(reply.contains("lakeliss"), reply);
        }
        for (String reply : replies(HENESYS, 30, "who drops pig heads")) {
            assertFalse(reply.matches(".*\\bdrops? (em|those|them)\\b.*\\bdrops\\b.*"), "one verb is enough: " + reply);
        }
        for (String reply : replies(HENESYS, 30, "where am i")) {
            assertFalse(reply.contains("henesys, near henesys"), reply);
        }
        for (String reply : replies(HENESYS, 30, "curious-millennial-v1", "where is henesys")) {
            assertFalse(reply.startsWith("Your "), reply);
        }
        for (String reply : replies(HENESYS, 30, "who sells pet food")) {
            assertFalse(reply.contains("here, doofus"), "Doofus is the NPC, not the asker: " + reply);
        }
    }

    @Test
    void genericMapNamesBecomeTheirStreet() {
        for (String reply : replies(HENESYS, 30, "where are jr wraiths")) {
            assertFalse(reply.endsWith(" line") || reply.contains(" line,"), reply);
        }
        assertEquals("kerning city subway", SeedOverlay.knowledge().names().say(EntityKind.MAP, 103_000_101));
    }

    @Test
    void shopsOnMapleIslandAreNotOfferedInVictoria() {
        for (String reply : replies(HENESYS, 30, "what drops brown work gloves")) {
            assertFalse(reply.contains("amherst"), reply);
        }
    }

    @Test
    void theStorePriceStandsInForAnUnseenOne() {
        assertTrue(replies(HENESYS, 30, "how much are red pots").stream().allMatch(reply -> reply.contains("50")));
    }

    @Test
    void theRateAskedAboutIsTheOneAnswered() {
        for (String reply : replies(HENESYS, 30, "whats the meso rate")) {
            assertTrue(reply.contains("meso") && reply.contains("3x"), reply);
        }
    }

    @Test
    void ellinasShopsBelongToEllinia() {
        WorldKnowledge k = SeedOverlay.knowledge();
        assertEquals(ELLINIA, k.townOf(101_000_002), "the department store's return map is itself, yet it is in Ellinia");
        assertFalse(k.isTown(101_000_002));
        assertEquals(600_000_000, k.townOf(600_010_001), "NLC's subway station is in NLC");
        for (String reply : replies(ELLINIA, 30, "where can i buy manas")) {
            assertFalse(reply.contains("ellinia department store sells") || reply.contains("in ellinia department store"), reply);
        }
    }
}
