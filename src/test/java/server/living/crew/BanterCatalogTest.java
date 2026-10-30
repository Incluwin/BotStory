package server.living.crew;

import org.junit.jupiter.api.Test;
import server.living.config.ContentLoader;
import server.living.config.LivingContent;
import server.living.config.Node;
import server.living.crew.BanterCatalog.Exchange;
import server.living.crew.BanterCatalog.Moment;
import server.living.crew.BanterCatalog.Stat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static server.living.crew.CrewKit.persona;

/**
 * The shipped banter catalog (scripts/config/living-world/banter.yaml, F6.4) loads and lints clean against the shipped
 * personas, has a line for every tie and moment it promises, and the lint catches a digit, a slot its exchange does not
 * fill, a rival call with no number, and a pair that is no recruitable.
 */
class BanterCatalogTest {
    private static final Path CONTENT = Path.of("scripts", "config", "living-world");

    @Test
    void theShippedCatalogLintsCleanAgainstTheShippedPersonas() {
        LivingContent content = ContentLoader.load(CONTENT);
        BanterCatalog catalog = BanterCatalog.load(CONTENT);
        List<String> problems = catalog.lint(content.personas().values());
        assertEquals(List.of(), problems);
        assertFalse(catalog.exchanges().isEmpty());
        assertEquals(60, catalog.seeds().get(TieKind.SPOUSE));
        assertFalse(catalog.lines(BanterCatalog.MUTE_OK).isEmpty());
        assertFalse(catalog.lines(BanterCatalog.UNMUTE_OK).isEmpty());
    }

    @Test
    void everyTieHasAnExchangeAndRivalsHaveOneForEveryStat() {
        BanterCatalog catalog = BanterCatalog.load(CONTENT);
        for (TieKind kind : TieKind.values()) {
            assertTrue(catalog.exchanges().stream().anyMatch(e -> e.ties().contains(kind)), kind.key());
        }
        for (Stat stat : Stat.values()) {
            assertTrue(catalog.exchanges().stream().anyMatch(e -> e.ties().contains(TieKind.RIVAL) && e.stat() == stat),
                    stat.key());
        }
        assertTrue(catalog.exchanges().stream().anyMatch(e -> e.moments().contains(Moment.CLEAR) && e.stat() == null),
                "an after-clear exchange for any crew");
        assertTrue(catalog.exchanges().stream().anyMatch(e -> e.pair().equals(List.of("dorran", "fenna"))),
                "the pilot rivals' own lines");
    }

    @Test
    void theLintCatchesDigitsUnfilledSlotsAndRivalCallsWithoutANumber() {
        BanterCatalog catalog = parse(Map.of("exchanges", List.of(
                exchange("digit", List.of("rival"), List.of("town"), null, "i did 41% of it", "ok"),
                exchange("slot", List.of("friend"), List.of("town"), null, "{quest} was fun", "ok"),
                exchange("nonumber", List.of("rival"), List.of("town"), "deaths", "you died a lot in {act}", "lag"),
                exchange("good", List.of("rival"), List.of("town"), "level", "level {lead} now", "{gap} is nothing"))));

        List<String> problems = catalog.lint(List.of());

        assertTrue(problems.stream().anyMatch(p -> p.contains("exchange digit") && p.contains("a digit")), "" + problems);
        assertTrue(problems.stream().anyMatch(p -> p.contains("exchange slot") && p.contains("{quest}")), "" + problems);
        assertTrue(problems.stream().anyMatch(p -> p.contains("exchange nonumber") && p.contains("quotes no number")),
                "" + problems);
        assertTrue(problems.stream().noneMatch(p -> p.contains("exchange good")), "" + problems);
    }

    @Test
    void theLintRefusesAPairThatIsNoRecruitableAndNamesUnreadKinds() {
        BanterCatalog catalog = parse(Map.of("exchanges", List.of(Map.of("id", "pair", "pair", List.of("fenna", "olwen"),
                "moments", List.of("town"), "call", List.of("hey"), "answer", List.of("hi")))));
        List<String> problems = catalog.lint(List.of(
                persona("fenna", true, List.of(CrewKit.rel("dorran", "WATERS_THE_ROSES_WITH")), null),
                persona("dorran", true, List.of(), null),
                persona("olwen", false, List.of(), null)));
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("ERROR") && p.contains("'olwen' is no recruitable")),
                "" + problems);
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("WARN") && p.contains("WATERS_THE_ROSES_WITH")),
                "" + problems);
    }

    @Test
    void aClearsTimeOnlyFillsAClearOnlyExchange() {
        BanterCatalog catalog = parse(Map.of("exchanges", List.of(
                exchange("mixed", List.of("friend"), List.of("town", "clear"), null, "{quest} in {time}", "gg"),
                exchange("clear", List.of("friend"), List.of("clear"), null, "{quest} in {time}", "gg"))));
        List<String> problems = catalog.lint(List.of());
        assertTrue(problems.stream().anyMatch(p -> p.contains("exchange mixed")), "" + problems);
        assertTrue(problems.stream().noneMatch(p -> p.contains("exchange clear")), "" + problems);
        Exchange clear = catalog.exchanges().get(1);
        assertEquals(10, clear.weight(), "the default weight");
        assertEquals(100, clear.maxAffinity());
    }

    private static Map<String, Object> exchange(String id, List<String> ties, List<String> moments, String stat,
                                                String call, String answer) {
        Map<String, Object> out = new java.util.HashMap<>();
        out.put("id", id);
        out.put("ties", ties);
        out.put("moments", moments);
        if (stat != null) {
            out.put("stat", stat);
        }
        out.put("call", List.of(call));
        out.put("answer", List.of(answer));
        return out;
    }

    private static BanterCatalog parse(Map<String, Object> root) {
        return BanterCatalog.parse(Node.root(root, "banter.yaml"));
    }
}
