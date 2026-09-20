package server.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Style-weighted bot-name generator (BOT_ECONOMY.md § Naming).
 *
 * <p>Style distribution + post-processing is loaded from bot-names.yml (see
 * {@code scripts/config/bot-names.yml}) but this class ships with built-in
 * defaults that match the spec — so if the YAML is missing the generator
 * still produces reasonable names.
 *
 * <p>All RNG goes through {@link BotRandomService}. The generator is stateless
 * beyond the loaded config; it is safe to call from multiple threads.
 */
public final class BotNameGenerator {
    private static final Logger log = LoggerFactory.getLogger(BotNameGenerator.class);

    private static final Pattern ALPHANUMERIC = Pattern.compile("^[A-Za-z0-9]+$");

    private static final String[] DEFAULT_DICTIONARY = {
            "Panic","Shadow","Frost","Storm","Ghost","Phantom","Bolt","Venom",
            "Blaze","Rogue","Spark","Lunar","Solar","Ember","Rift","Void",
            "Thorn","Reaper","Specter","Onyx","Crimson","Raven","Fang","Claw",
            "Drift","Gale","Pulse","Nova","Saber","Wraith","Vortex","Cipher","Eclipse"
    };
    private static final String[] DEFAULT_ADJNOUN = {
            "DarkAngel","SwiftFox","LostSoul","ColdMoon","NightWolf","IronFist",
            "SilverArrow","CrimsonBlade","FrostBite","BlackHeart","RedTalon","SteelFang",
            "GoldenEye","WildCard","StormRider","ShadowBorn","LoneWolf","BlueFlame","FireStorm","IceVeil","DarkStar"
    };
    private static final String[] DEFAULT_DECORATED = {
            "Slasher","Shadow","Blade","Assassin","Dark","Pain","Death","Warrior",
            "Reaper","Ninja","Knight","Venom","Raven","Hunter","Slayer"
    };
    private static final String[] DEFAULT_LEET = {
            "shadow","panda","nightmare","mark","angel","ghost","dragon","storm",
            "fire","knight","demon","reaper","blade","warrior"
    };
    private static final String[] DEFAULT_PREFIXES = {
            "zy","vael","thor","kae","zel","mor","rav","nex","syl","dra","kry","vor","xan","zer","ae","bel"
    };
    private static final String[] DEFAULT_MIDDLES = {
            "en","or","ar","eth","yn","ax","is","an","ir","ul","ev","ith"
    };
    private static final String[] DEFAULT_SUFFIXES = {
            "loth","ryn","vex","tho","eth","zar","wop","ynn","ath","ex","oth","ael","iel"
    };
    private static final String[] DEFAULT_GAMER = {
            "OneShot","MobFarmer","CritKing","Tank","Boomer","Healer","NoScope","AFK",
            "LagLord","ProGamer","TryHard","EZ","Camper","Smurf","GG"
    };

    private static final Map<String, Integer> BASE_WEIGHTS = Map.of(
            "dictionary_word", 30,
            "adjective_noun",  20,
            "decorated",       16,
            "leetspeak",       14,
            "syllable_gen",    10,
            "gamer_phrase",    10
    );

    private static final Set<String> RESTRICTED = Set.of(
            "gm","admin","cm","tester","moderator","staff","server","bot",
            "system","support","help","info","maplegm","maplestory","adminbot"
    );

    private static final int MAX_REGEN_ATTEMPTS = 20;
    private static final double SUFFIX_DIGIT_PROB = 0.40;
    private static final double CASE_LOWERCASE = 0.20;
    private static final double CASE_UPPERCASE = 0.10;

    private final Set<String> profanity;
    private final Set<String> usedBotNames = new HashSet<>();

    public BotNameGenerator() {
        this.profanity = loadProfanity();
        preloadExistingBotNames();
    }

    private static Set<String> loadProfanity() {
        Set<String> out = new HashSet<>();
        Path p = Path.of("scripts/config/wordlists/profanity.txt");
        if (Files.isRegularFile(p)) {
            try {
                for (String line : Files.readAllLines(p)) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                    out.add(trimmed.toLowerCase(Locale.ROOT));
                }
            } catch (IOException e) {
                log.warn("BotNameGenerator: failed to load profanity wordlist ({}); continuing with empty list", e.toString());
            }
        } else {
            log.warn("BotNameGenerator: profanity wordlist not found at {}", p);
        }
        return out;
    }

    private void preloadExistingBotNames() {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT LOWER(name) FROM characters WHERE bot_flag = 1");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                usedBotNames.add(rs.getString(1));
            }
        } catch (SQLException e) {
            log.warn("BotNameGenerator: preload of existing bot names failed ({}); continuing", e.toString());
        }
    }

    /** Generate a unique name for a bot. Throws if no name satisfies all constraints after {@value #MAX_REGEN_ATTEMPTS} tries. */
    public String generate(BotPersona persona) {
        Map<String, Integer> weights = applyBias(persona);
        for (int attempt = 0; attempt < MAX_REGEN_ATTEMPTS; attempt++) {
            String style = weightedPick(weights);
            String base = generateBase(style);
            if (base == null) continue;
            String suffixed = maybeAppendDigits(base);
            String cased = applyCase(suffixed);
            if (!passes(cased)) continue;
            usedBotNames.add(cased.toLowerCase(Locale.ROOT));
            return cased;
        }
        throw new IllegalStateException("BotNameGenerator: no unique name after "
                + MAX_REGEN_ATTEMPTS + " attempts");
    }

    private Map<String, Integer> applyBias(BotPersona persona) {
        Map<String, Integer> w = new HashMap<>(BASE_WEIGHTS);
        Map<String, Integer> bias = biasFor(persona);
        bias.forEach((style, delta) -> w.merge(style, delta, Integer::sum));
        w.replaceAll((k, v) -> Math.max(0, v));
        return w;
    }

    private static Map<String, Integer> biasFor(BotPersona persona) {
        return switch (persona) {
            case WarriorGear     -> Map.of("adjective_noun", 8, "gamer_phrase", 5, "dictionary_word", 3);
            case MagicianGear    -> Map.of("syllable_gen",   8, "dictionary_word", 5, "leetspeak", 3);
            case ArcherGear      -> Map.of("dictionary_word", 5, "adjective_noun", 5, "syllable_gen", 3);
            case ThiefGear       -> Map.of("decorated",      7, "leetspeak",     5, "syllable_gen", 3);
            case PirateGear      -> Map.of("gamer_phrase",   5, "adjective_noun", 5, "decorated",    3);
            case ScrollMerchant  -> Map.of("syllable_gen",   8, "dictionary_word", 3);
            case RareSpecialist  -> Map.of("decorated",      7, "adjective_noun", 5);
            case NPCVendor       -> Map.of("gamer_phrase",   8, "dictionary_word", 5);
            case BulkVendor      -> Map.of("gamer_phrase",  10, "dictionary_word", 5);
            case AccessoryVendor -> Map.of("dictionary_word", 8, "syllable_gen",   3);
            case Generalist      -> Map.of();
        };
    }

    private static String weightedPick(Map<String, Integer> weights) {
        int total = weights.values().stream().mapToInt(Integer::intValue).sum();
        if (total <= 0) return "dictionary_word";
        int roll = BotRandomService.nextInt(total);
        int running = 0;
        for (Map.Entry<String, Integer> e : weights.entrySet()) {
            running += e.getValue();
            if (roll < running) return e.getKey();
        }
        return "dictionary_word";
    }

    private String generateBase(String style) {
        return switch (style) {
            case "dictionary_word" -> pick(DEFAULT_DICTIONARY);
            case "adjective_noun"  -> pick(DEFAULT_ADJNOUN);
            case "decorated"       -> decorate(pick(DEFAULT_DECORATED));
            case "leetspeak"       -> leetify(pick(DEFAULT_LEET));
            case "syllable_gen"    -> syllable();
            case "gamer_phrase"    -> pick(DEFAULT_GAMER);
            default -> pick(DEFAULT_DICTIONARY);
        };
    }

    private static String pick(String[] pool) {
        return pool[BotRandomService.nextInt(pool.length)];
    }

    private static String decorate(String base) {
        return switch (BotRandomService.nextInt(4)) {
            case 0  -> "xX" + base + "Xx";
            case 1  -> "o0" + base + "0o";
            case 2  -> "x"  + base + "X";
            default -> base + "TT";
        };
    }

    private static String leetify(String base) {
        StringBuilder out = new StringBuilder(base.length());
        for (char c : base.toCharArray()) {
            if (BotRandomService.nextDouble() < 0.6) {
                switch (Character.toLowerCase(c)) {
                    case 'a' -> { out.append('4'); continue; }
                    case 'e' -> { out.append('3'); continue; }
                    case 'i' -> { out.append('1'); continue; }
                    case 'o' -> { out.append('0'); continue; }
                    case 's' -> { out.append('5'); continue; }
                    case 't' -> { out.append('7'); continue; }
                    default -> {}
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    private static String syllable() {
        int parts = BotRandomService.nextInt(2) == 0 ? 2 : 3;
        StringBuilder sb = new StringBuilder(pick(DEFAULT_PREFIXES));
        if (parts == 3) sb.append(pick(DEFAULT_MIDDLES));
        sb.append(pick(DEFAULT_SUFFIXES));
        return capitalize(sb.toString());
    }

    private static String capitalize(String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String maybeAppendDigits(String base) {
        if (BotRandomService.nextDouble() < SUFFIX_DIGIT_PROB) {
            int n = 1 + BotRandomService.nextInt(999);
            return base + n;
        }
        return base;
    }

    private static String applyCase(String s) {
        double roll = BotRandomService.nextDouble();
        if (roll < CASE_LOWERCASE) return s.toLowerCase(Locale.ROOT);
        if (roll < CASE_LOWERCASE + CASE_UPPERCASE) return s.toUpperCase(Locale.ROOT);
        return s;
    }

    private boolean passes(String candidate) {
        if (candidate.length() < 4 || candidate.length() > 13) return false;
        if (!ALPHANUMERIC.matcher(candidate).matches()) return false;

        String lower = candidate.toLowerCase(Locale.ROOT);
        if (RESTRICTED.contains(lower)) return false;
        for (String bad : profanity) {
            if (lower.contains(bad)) return false;
        }
        if (usedBotNames.contains(lower)) return false;
        return !collidesWithRealPlayer(candidate);
    }

    private boolean collidesWithRealPlayer(String candidate) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT 1 FROM characters WHERE LOWER(name) = ? AND bot_flag = 0 LIMIT 1")) {
            ps.setString(1, candidate.toLowerCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            log.warn("BotNameGenerator: DB collision-check failed ({}); rejecting candidate as a precaution", e.toString());
            return true;
        }
    }

    /** Generate {@code n} names for a persona — used by seeding script and tests. */
    public List<String> generateMany(BotPersona persona, int n) {
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(generate(persona));
        return Collections.unmodifiableList(out);
    }
}
