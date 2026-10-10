package server.living.phrases;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.dialogue.jev.AgentChatIntentRuntime;
import server.living.LivingWorld;
import server.living.config.Content.Phrase;
import server.living.store.LivingStore.LearnedPhrase;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Deterministic free-form -> canonical resolution ahead of the intent judge (plan section 12):
 * a shared normalizer, phrase packs from {@code command-phrases.yaml}, and phrases the judge
 * resolved confidently before (stored, GM-reviewable, matched first).
 */
public final class PhraseMatcher {
    private static final Logger log = LoggerFactory.getLogger(PhraseMatcher.class);
    private static final int MAX_UNKNOWN_TAIL_TOKENS = 2;
    private static final Pattern PUNCT = Pattern.compile("[\\p{Punct}&&[^']]+");
    private static final Pattern REPEATS = Pattern.compile("([a-z])\\1{2,}");
    private static final List<String> FILLERS = List.of(
            "please", "pls", "plz", "hey", "yo", "ok", "okay", "alright", "just", "now", "dude", "bro", "buddy", "man",
            "can you", "could you", "would you", "will you", "can", "could", "would", "u", "ya", "yeah", "hmm", "uh", "um",
            "lol", "kk", "thx", "ty");

    /** One compiled command: canonical, alias tokens, allowed prefix and tail tokens. */
    record Compiled(Phrase phrase, List<String[]> aliases, List<String[]> prefixes, List<String[]> tails) { }

    private final LivingWorld world;
    private volatile List<Compiled> compiled = List.of();
    private final Map<String, String> learned = new ConcurrentHashMap<>();

    public PhraseMatcher(LivingWorld world) {
        this.world = world;
    }

    public void reload() {
        compiled = compileAll(world.content().phrases());
        learned.clear();
        for (LearnedPhrase entry : world.store().learnedPhrases()) {
            learned.put(entry.normalizedText(), entry.canonical());
        }
        world.store().expirePhrases(world.config().LEARNED_PHRASE_MIN_HITS, world.config().LEARNED_PHRASE_TTL_DAYS);
        AgentChatIntentRuntime.installPrejudge(this::resolve);
        AgentChatIntentRuntime.installLearner(this::learn);
        log.info("Phrase packs: {} commands, {} learned phrases", compiled.size(), learned.size());
    }

    static List<Compiled> compileAll(List<Phrase> phrases) {
        List<Compiled> list = new ArrayList<>();
        for (Phrase phrase : phrases) {
            list.add(new Compiled(phrase, tokens(phrase.aliases()), tokens(phrase.prefixes()), tokens(phrase.tails())));
        }
        return List.copyOf(list);
    }

    private static List<String[]> tokens(List<String> phrases) {
        List<String[]> out = new ArrayList<>();
        for (String phrase : phrases) {
            String normalized = normalize(phrase);
            if (!normalized.isEmpty()) {
                out.add(normalized.split(" "));
            }
        }
        return out;
    }

    // ---- normalization -------------------------------------------------------------------

    /** Lowercase, punctuation-free, fillers removed, repeated letters collapsed, single-spaced. */
    public static String normalize(String message) {
        if (message == null) {
            return "";
        }
        String text = message.toLowerCase(Locale.ROOT);
        text = PUNCT.matcher(text).replaceAll(" ");
        text = text.replace("'", "");
        text = REPEATS.matcher(text).replaceAll("$1");
        text = " " + text.trim().replaceAll("\\s+", " ") + " ";
        for (String filler : FILLERS) {
            text = text.replace(" " + filler + " ", " ");
        }
        return text.trim().replaceAll("\\s+", " ");
    }

    // ---- resolution ----------------------------------------------------------------------

    /** Canonical command for the message, {@code reply:<text>} for planned commands, or null. */
    public String resolve(String message) {
        String normalized = normalize(message);
        if (normalized.isEmpty()) {
            return null;
        }
        String remembered = learned.get(normalized);
        if (remembered != null) {
            world.store().learnPhrase(normalized, remembered, "JEV");
            return remembered;
        }
        return match(compiled, normalized);
    }

    /**
     * What {@link #resolve} reads {@code message} as (a canonical command, or {@code reply:<text>} for a planned one),
     * the command the Agents' prejudge would act on, or null; nothing is recorded (a learned phrase's hit counts when
     * the Agent resolves it).
     */
    public String commandFor(String message) {
        String normalized = normalize(message);
        if (normalized.isEmpty()) {
            return null;
        }
        String remembered = learned.get(normalized);
        return remembered != null ? remembered : match(compiled, normalized);
    }

    /** Pack resolution only (no learned phrases): canonical, {@code reply:...}, or null. */
    static String match(List<Compiled> commands, String normalized) {
        String[] words = normalized.split(" ");
        Compiled best = null;
        int bestConsumed = -1;
        for (Compiled command : commands) {
            int consumed = consumed(command, words);
            if (consumed > bestConsumed) {
                bestConsumed = consumed;
                best = command;
            }
        }
        if (best == null) {
            return null;
        }
        if ("PLANNED".equals(best.phrase().status())) {
            return "reply:" + (best.phrase().reply() == null ? "can't do that yet." : best.phrase().reply());
        }
        return best.phrase().canonical();
    }

    /**
     * Match score: tokens the command explains (prefix + alias + tails) times ten, plus the alias
     * length as a tie-breaker, or -1 when it does not match. The longest explanation wins so
     * "stop buffing" goes to support-off, not to stop; among equals the more specific alias wins
     * so "farm around here" goes to farm-here, not to grind.
     */
    static int consumed(Compiled command, String[] words) {
        int best = -1;
        for (int start : prefixEnds(command, words)) {
            for (String[] alias : command.aliases()) {
                if (!startsWith(words, start, alias)) {
                    continue;
                }
                int index = start + alias.length;
                boolean progress = true;
                while (index < words.length && progress) {
                    progress = false;
                    for (String[] tail : command.tails()) {
                        if (startsWith(words, index, tail)) {
                            index += tail.length;
                            progress = true;
                            break;
                        }
                    }
                }
                if (words.length - index <= MAX_UNKNOWN_TAIL_TOKENS) {
                    int score = index * 10 + Math.min(9, alias.length);
                    if (score > best) {
                        best = score;
                    }
                }
            }
        }
        return best;
    }

    private static int[] prefixEnds(Compiled command, String[] words) {
        int[] ends = new int[command.prefixes().size() + 1];
        int count = 0;
        ends[count++] = 0;
        for (String[] prefix : command.prefixes()) {
            if (startsWith(words, 0, prefix)) {
                ends[count++] = prefix.length;
            }
        }
        return java.util.Arrays.copyOf(ends, count);
    }

    private static boolean startsWith(String[] words, int offset, String[] fragment) {
        if (offset + fragment.length > words.length) {
            return false;
        }
        for (int index = 0; index < fragment.length; index++) {
            if (!words[offset + index].equals(fragment[index])) {
                return false;
            }
        }
        return true;
    }

    // ---- learning ------------------------------------------------------------------------

    /** A player's confirmation outranks any model confidence and is stored as source PLAYER. */
    public void learn(String message, String canonical, double confidence, boolean confirmedByPlayer) {
        if ((!confirmedByPlayer && confidence < world.config().LEARNED_PHRASE_MIN_CONFIDENCE)
                || canonical == null || canonical.isBlank()) {
            return;
        }
        String normalized = normalize(message);
        if (normalized.isEmpty() || normalized.length() > 180) {
            return;
        }
        learned.put(normalized, canonical);
        String source = confirmedByPlayer ? "PLAYER" : "JEV";
        world.store().learnPhrase(normalized, canonical, source);
        log.info("[phrase] learned \"{}\" -> {} ({} {})", normalized, canonical, source,
                String.format(Locale.ROOT, "%.2f", confidence));
    }

    public void teach(String phrase, String canonical) {
        String normalized = normalize(phrase);
        learned.put(normalized, canonical);
        world.store().learnPhrase(normalized, canonical, "GM");
        world.store().setPhraseApproved(normalized, true);
    }

    public boolean forget(String phrase) {
        String normalized = normalize(phrase);
        world.store().deletePhrase(normalized);
        return learned.remove(normalized) != null;
    }

    public int commandCount() {
        return compiled.size();
    }

    public int learnedCount() {
        return learned.size();
    }
}
