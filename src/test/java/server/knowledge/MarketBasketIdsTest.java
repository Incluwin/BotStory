package server.knowledge;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * population/market.yaml's basket labels name the item each id really is (docs/SERVER_KNOWLEDGE_PLAN.md 11): seed
 * prices sat on swapped ids (claw/bow scrolls, kumbi/tobi on steelies/ilbis, maple sword on Maple Soul Singer), and
 * content PRICE answers read that index. Every label word is in the item's String.wz name and every "N%" is the
 * scroll's success rate.
 */
class MarketBasketIdsTest {
    private static final Path MARKET = Path.of("scripts/config/population/market.yaml");
    private static final Pattern ROW = Pattern.compile("itemId:\\s*(\\d+),\\s*price:\\s*(\\d+)\\s*}\\s*#\\s*(.+)$");
    private static final Pattern PERCENT = Pattern.compile("(\\d+)%");
    private static final Set<String> FILLER = Set.of("for", "per", "unit");
    /** String.wz spells it "Cutlus". */
    private static final Map<String, String> SPELLING = Map.of("cutlass", "cutlus");

    @Test
    void everyBasketLabelNamesItsItem() throws IOException {
        List<String> wrong = new ArrayList<>();
        int rows = 0;
        for (String line : Files.readAllLines(MARKET, StandardCharsets.US_ASCII)) {
            Matcher row = ROW.matcher(line);
            if (!row.find()) {
                continue;
            }
            rows++;
            int id = Integer.parseInt(row.group(1));
            String label = row.group(3).trim();
            ItemFact item = SeedOverlay.knowledge().item(id).orElse(null);
            if (item == null) {
                wrong.add(id + " '" + label + "': no such item");
                continue;
            }
            Set<String> name = new HashSet<>(Arrays.asList(item.name().toLowerCase(Locale.ROOT).split("[^a-z0-9]+")));
            for (String word : label.replaceAll("\\(.*\\)", "").toLowerCase(Locale.ROOT).split("[^a-z]+")) {
                if (word.length() > 1 && !FILLER.contains(word) && !name.contains(SPELLING.getOrDefault(word, word))) {
                    wrong.add(id + " '" + label + "' is " + item.name());
                }
            }
            Matcher percent = PERCENT.matcher(label);
            if (percent.find() && Integer.parseInt(percent.group(1)) != item.success()) {
                wrong.add(id + " '" + label + "' succeeds " + item.success() + "%");
            }
        }
        assertEquals(24, rows, "every basket row was read");
        assertEquals(List.of(), wrong);
    }
}
