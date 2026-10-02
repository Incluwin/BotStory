package server.knowledge;

import config.WorldConfig;
import config.YamlConfig;
import server.knowledge.ContentAnswers.Ask;
import server.knowledge.ContentAnswers.Asker;
import server.knowledge.ContentAnswers.ContentAnswer;
import server.knowledge.ContentAnswers.PriceQuote;
import server.knowledge.ContentAnswers.Rates;
import server.knowledge.ContentAnswers.Responder;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The step-3 live-data probe (docs/SERVER_KNOWLEDGE_PLAN.md 6.7): the committed knowledge file plus the live MySQL
 * overlay and price index, read-only, and the deterministic answer to every probe question (KnowledgeProbeTest's
 * positives and negatives plus {@link #EXTRA}), once from a bot that knows everything and once from a Victoria citizen
 * of the asker's level, then (step 4) to four bots with different histories ({@link #personal}), for a person to eyeball.
 * Nothing is written: the connection is read-only and runs SELECTs only.
 * A developer tool, so it lives with the tests and never ships in the server jar.
 *
 * <p>Run from the server directory after {@code mvnw test-compile}:
 * {@code java -cp "target\test-classes;target\classes;<deps>" server.knowledge.KnowledgeProbeMain [out]}.
 * The DB is config.yaml's {@code DB_URL_FORMAT} at localhost (or -Dknowledge.probe.url); the user and password come
 * from DB_USER / DB_PASS in the environment, else from the {@code .env} beside the server directory. Rates are
 * config.yaml's world 0 (the live World may differ after GM commands).
 */
public final class KnowledgeProbeMain {
    private static final Pattern BASKET = Pattern.compile("itemId:\\s*(\\d+),\\s*price:\\s*(\\d+)");
    private static final Pattern ENV = Pattern.compile("^\\s*(DB_USER|DB_PASS)\\s*=\\s*(.*?)\\s*$");
    /** Old and corrected market.yaml basket ids (plan section 11): their price-index rows, for the repair note. */
    private static final int[] MARKET_IDS = {2040002, 2040001, 2044501, 2044701, 2040802, 2040804, 4000000, 4000019, 4000012,
            1302020, 1302004, 1302030, 2070005, 2070003, 2070006, 2070004};
    private static final List<String> STYLES = List.of("friendly-casual-v1", "quiet-practical-v1", "playful-social-v1",
            "curious-millennial-v1");

    /**
     * "{map} {asker level} {asker job} | {line}": lines beyond KnowledgeProbeTest's positives and negatives (which the
     * probe also runs): the horizon, the market.yaml fix, and the 2026-10-01 review's lines.
     */
    private static final List<String> EXTRA = List.of(
            // horizon and the market.yaml fix
            "100000000 10 0 | how do i get to leafre",
            "100000000 50 210 | how do i get to leafre",
            "100000000 10 0 | where is zakum",
            "100000000 20 410 | what do drakes drop",
            "100000000 30 410 | pc steelies",
            "100000000 30 410 | how much are kumbis",
            "100000000 30 410 | pc tobis",
            "100000000 30 410 | price check claw att 60",
            "100000000 30 410 | how much is a bow att 60",
            "100000000 30 410 | how much is a cutlass",
            "100000000 25 400 | how do i join kpq",
            "100000000 25 400 | ok where can i buy manas",
            // the review: routes, prices, places, phrasing
            "200000000 30 410 | how do i get to henesys",
            "100000000 30 410 | how do i get to aqua",
            "100000000 30 410 | how do i get to magatia",
            "100000000 30 410 | how much are red pots",
            "100000000 30 410 | how much are subis",
            "100000000 30 410 | where are jr wraiths",
            "101000000 30 410 | where can i buy pots",
            "101000000 30 410 | where is the weapon store",
            "100000000 30 410 | where is mushmom",
            "100000000 30 410 | what drops brown work gloves",
            "100000000 30 410 | whats the meso rate",
            "100000000 30 410 | can i use haste",
            "100000000 30 410 | what drops orihalcon",
            "100000000 30 410 | where is the fm entrance",
            "100000000 30 410 | how i get to ludi",
            "100000000 30 410 | wtf where is the potion shop",
            // must stay silent (the review's ordinary party and PQ chat)
            "100000000 30 410 | where is the leader",
            "100000000 30 410 | where are the others",
            "100000000 30 410 | where is the exit",
            "103000800 25 400 | where is the pass",
            "100000000 30 410 | who drops first",
            "100000000 30 410 | how do i get out of here",
            "100000000 30 410 | nice exp map",
            "100000000 30 410 | best place to train is ant tunnel",
            "100000000 30 410 | is this good",
            "100000000 30 410 | what level is maya");

    private KnowledgeProbeMain() {
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(YamlConfig.config.knowledge.CONTENT_DIR);
        KnowledgeFile file = KnowledgeFile.read(dir.resolve(KnowledgeSources.FILE_NAME));
        List<TravelLeg> travel = TravelCatalog.load(dir.resolve("travel.yaml"), file);
        AliasCatalog aliases = AliasCatalog.load(dir.resolve("aliases.yaml"), file);
        Path population = Path.of(YamlConfig.config.population.CONTENT_DIR);
        Map<Integer, Integer> fields = KnowledgeLoader.readFields(population.resolve("fields.yaml"));
        Map<Integer, Long> basket = basket(population.resolve("market.yaml"));

        String url = System.getProperty("knowledge.probe.url", String.format(YamlConfig.config.server.DB_URL_FORMAT, "localhost"));
        Map<String, String> credentials = credentials();
        KnowledgeOverlay overlay;
        Map<Integer, long[]> index = new HashMap<>();
        long started = System.nanoTime();
        try (Connection con = DriverManager.getConnection(url, credentials.get("DB_USER"), credentials.get("DB_PASS"))) {
            con.setReadOnly(true);
            overlay = KnowledgeOverlayLoader.load(con);
            try (PreparedStatement ps = con.prepareStatement("SELECT item_id, price, samples FROM living_price_index");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    index.put(rs.getInt(1), new long[]{rs.getLong(2), rs.getLong(3)});
                }
            }
        }
        long dbMs = (System.nanoTime() - started) / 1_000_000;
        WorldKnowledge knowledge = WorldKnowledge.build(file, travel, aliases, fields, overlay, 1);
        WorldConfig world = YamlConfig.config.worlds.get(0);
        Rates rates = new Rates(world.exp_rate, world.meso_rate, world.drop_rate, world.boss_drop_rate, world.mob_rate,
                world.max_mob_per_spawnpoint);
        ContentAnswers answers = new ContentAnswers(() -> knowledge, worldId -> rates,
                itemId -> known(index, basket, itemId), YamlConfig.config.knowledge.HORIZON_LEVEL_SLACK,
                YamlConfig.config.knowledge.MAX_REPLY_CHARS, false);

        try (PrintStream out = args.length > 0
                ? new PrintStream(Files.newOutputStream(Path.of(args[0])), true, StandardCharsets.UTF_8) : System.out) {
            out.printf("knowledge probe: %s, live DB %s (read-only), %d drops, %d global, %d shop rows, %d plife, %d price rows in %d ms;"
                            + " rates exp %dx meso %dx drop %dx boss %dx (config.yaml world 0)%n", knowledge.dataVersion(), url,
                    overlay.dropRows(), overlay.globalDropRows(), overlay.shopRows(), overlay.lifeRows(), index.size(), dbMs,
                    rates.exp(), rates.meso(), rates.drop(), rates.bossDrop());
            out.println("columns: line | kind outcome subject | all-knowing bot (lv 200) | Victoria citizen of the asker's level");
            out.println();
            int answered = 0;
            List<String> questions = questions();
            for (int i = 0; i < questions.size(); i++) {
                String[] parts = questions.get(i).split(" \\| ", 2);
                String[] who = parts[0].split(" ");
                Asker asker = new Asker(7000 + i, "Tester", Integer.parseInt(who[0]), Integer.parseInt(who[1]),
                        Integer.parseInt(who[2]), 0);
                String style = STYLES.get(i % STYLES.size());
                ContentAnswer top = answers.explain(new Ask(parts[1], asker, new Responder(9000 + i, 200, "victoria", style, Set.of()),
                        name -> name.equalsIgnoreCase("bob"), 1_000_000L * (i + 1)));
                ContentAnswer peer = answers.explain(new Ask(parts[1], asker, new Responder(9500 + i, asker.level(), "victoria",
                        style, Set.of()), name -> name.equalsIgnoreCase("bob"), 1_000_000L * (i + 1)));
                answered += top.reply() == null ? 0 : 1;
                out.printf("%-38s | %-11s %-9s %-24s | %-70s | %s%n", parts[1], top.kind(), top.outcome(),
                        top.subject() == null ? "-" : top.subject().kind() + " " + top.subject().say(),
                        top.reply() == null ? "(silent)" : top.reply(), peer.reply() == null ? "(silent)" : peer.reply());
            }
            out.println();
            out.println(answered + " of " + questions.size() + " lines got a deterministic reply from the all-knowing bot.");
            out.println();
            personal(out, knowledge, answers, rates, questions);
            out.println();
            out.println("living_price_index rows for the market.yaml ids (old -> corrected), [price, samples]; basket = market.yaml now:");
            for (int id : MARKET_IDS) {
                long[] row = index.get(id);
                out.printf("  %d %-34s index %-18s basket %s%n", id, knowledge.item(id).map(ItemFact::name).orElse("?"),
                        row == null ? "(no row)" : "[" + row[0] + ", " + row[1] + "]", basket.containsKey(id) ? basket.get(id) : "-");
            }
        }
    }

    /**
     * Step 4 (docs/SERVER_KNOWLEDGE_PLAN.md 7.9 item 8): every line to the legacy level-35 peer and to the four
     * {@link PersonalProbe} bots, then how many each answered, the lines where A and B differ (B asked under A's id, so the
     * same seed: only their histories differ), and the lines where the companion K differs from the same bot without a known
     * world (only the rate lines may).
     */
    private static void personal(PrintStream out, WorldKnowledge knowledge, ContentAnswers answers, Rates rates, List<String> questions) {
        List<PersonalProbe.Bot> bots = PersonalProbe.bots(() -> knowledge, rates);
        PersonalProbe.Bot a = bots.get(1);
        PersonalProbe.Bot b = bots.get(2);
        PersonalProbe.Bot k = bots.get(4);
        out.println("personal knowledge (step 4): legacy lv35 | A lv35 victoria | B lv35 ludibrium | V lv80 el-nath veteran"
                + " | K companion lv35, one visit to Kerning");
        for (PersonalProbe.Bot bot : bots) {
            out.println("  " + bot.label() + ": " + (bot.world() == null ? "level horizon only" : bot.world().summary()
                    + "; regions " + bot.world().regions()));
        }
        out.println();
        int[] answered = new int[bots.size()];
        List<String> abDiffer = new ArrayList<>();
        List<String> kDiffer = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            String[] parts = questions.get(i).split(" \\| ", 2);
            String[] who = parts[0].split(" ");
            Asker asker = new Asker(7000 + i, "Tester", Integer.parseInt(who[0]), Integer.parseInt(who[1]), Integer.parseInt(who[2]), 0);
            String style = STYLES.get(i % STYLES.size());
            long at = 1_000_000L * (i + 1);
            StringBuilder row = new StringBuilder(String.format("%-38s", parts[1]));
            List<ContentAnswer> said = new ArrayList<>();
            for (int j = 0; j < bots.size(); j++) {
                ContentAnswer answer = answers.explain(new Ask(parts[1], asker, bots.get(j).responder(style),
                        name -> name.equalsIgnoreCase("bob"), at));
                said.add(answer);
                answered[j] += answer.outcome() == ContentAnswers.Outcome.ANSWERED ? 1 : 0;
                row.append(" | ").append(answer.reply() == null ? "(" + answer.outcome() + ")" : answer.reply());
            }
            out.println(row);
            ContentAnswer bAsA = answers.explain(new Ask(parts[1], asker, new Responder(a.id(), b.level(), b.homeRegion(), style, Set.of(),
                    true, b.world()), name -> name.equalsIgnoreCase("bob"), at));
            if (!Objects.equals(said.get(1).reply(), bAsA.reply())) {
                abDiffer.add(parts[1] + ": A " + said.get(1).reply() + " | B " + bAsA.reply());
            }
            ContentAnswer kLegacy = answers.explain(new Ask(parts[1], asker, new Responder(k.id(), k.level(), k.homeRegion(), style,
                    Set.of(), true, null), name -> name.equalsIgnoreCase("bob"), at));
            if (!Objects.equals(said.get(4).reply(), kLegacy.reply())) {
                kDiffer.add(parts[1] + ": legacy " + kLegacy.reply() + " | K " + said.get(4).reply());
            }
        }
        out.println();
        out.printf("answered (not deflected or silent): legacy %d, A %d, B %d, V %d, K %d of %d lines%n", answered[0], answered[1], answered[2],
                answered[3], answered[4], questions.size());
        out.println("A and B differ on " + abDiffer.size() + " lines (B asked under A's id):");
        abDiffer.forEach(line -> out.println("  " + line));
        out.println("K differs from the same bot without a known world on " + kDiffer.size() + " lines:");
        kDiffer.forEach(line -> out.println("  " + line));
    }

    /** KnowledgeProbeTest's positives (with their asker) and negatives, then {@link #EXTRA}. */
    private static List<String> questions() {
        List<String> out = new ArrayList<>();
        for (KnowledgeProbeTest.Probe probe : KnowledgeProbeTest.POSITIVES) {
            out.add(probe.map() + " " + probe.level() + " " + probe.job() + " | " + probe.line());
        }
        out.addAll(EXTRA);
        for (String line : KnowledgeProbeTest.NEGATIVES) {
            out.add("100000000 30 410 | " + line);
        }
        return out;
    }

    /** {@code Market.known}'s rule over the live rows: a sale-nudged row, else a basket good at its basket price. */
    private static PriceQuote known(Map<Integer, long[]> index, Map<Integer, Long> basket, int itemId) {
        long[] row = index.get(itemId);
        Long seed = basket.get(itemId);
        if (row != null && row[1] > 0) {
            return new PriceQuote(row[0], (int) row[1], seed != null);
        }
        return seed == null ? null : new PriceQuote(seed, 0, true);
    }

    private static Map<Integer, Long> basket(Path marketYaml) throws IOException {
        Map<Integer, Long> basket = new HashMap<>();
        for (String line : Files.readAllLines(marketYaml, StandardCharsets.US_ASCII)) {
            Matcher m = BASKET.matcher(line);
            if (m.find()) {
                basket.put(Integer.parseInt(m.group(1)), Long.parseLong(m.group(2)));
            }
        }
        return basket;
    }

    private static Map<String, String> credentials() throws IOException, SQLException {
        Map<String, String> out = new HashMap<>();
        for (String key : new String[]{"DB_USER", "DB_PASS"}) {
            String value = System.getenv(key);
            if (value != null && !value.isBlank()) {
                out.put(key, value);
            }
        }
        Path env = Path.of("..", ".env");
        Set<String> missing = new HashSet<>(Set.of("DB_USER", "DB_PASS"));
        missing.removeAll(out.keySet());
        if (!missing.isEmpty() && Files.exists(env)) {
            for (String line : Files.readAllLines(env, StandardCharsets.UTF_8)) {
                Matcher m = ENV.matcher(line);
                if (m.matches() && missing.contains(m.group(1))) {
                    out.put(m.group(1), m.group(2));
                }
            }
        }
        if (!out.containsKey("DB_USER") || !out.containsKey("DB_PASS")) {
            throw new SQLException("DB_USER / DB_PASS not set (environment or ../.env)");
        }
        return out;
    }
}
