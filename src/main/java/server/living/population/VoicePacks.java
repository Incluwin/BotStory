package server.living.population;

import server.living.population.PopulationContent.Voice;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * How a citizen types (plan 5.3): a deterministic transform (abbreviations, lowercase, typos,
 * pet phrases, emoticons) applied to every line it says, templated or generated, so one voice
 * looks the same across sources. Seeded per line so the same line from the same citizen renders
 * the same way.
 */
public final class VoicePacks {
    private static final Voice DEFAULT = new Voice("default", "friendly-casual-v1", 100, 0.8d, 0.02d, 0.4d,
            Map.of("you", "u", "please", "pls", "thanks", "ty"), List.of("lol", ":)"), 0.2d, List.of(), 0.0d, 50, 0.15d);
    private static final int MIN_TYPO_WORD = 4;

    private final Population population;

    VoicePacks(Population population) {
        this.population = population;
    }

    void reload() {
        // voices are read from content on every call
    }

    public Voice voiceOf(Citizen citizen) {
        Voice voice = citizen == null ? null : population.content().voice(citizen.voice());
        return voice == null ? DEFAULT : voice;
    }

    /** Style id (dialogue-style-profiles.json) the chatter engine prefers for this citizen. */
    public String styleOf(Citizen citizen) {
        return voiceOf(citizen).style();
    }

    public boolean ignores(Citizen citizen, long seed) {
        return new Random(seed).nextDouble() < voiceOf(citizen).ignoreRate();
    }

    public long delayMs(Citizen citizen, String line) {
        Voice voice = voiceOf(citizen);
        return Math.min(9_000L, 600L + (long) voice.delayMsPerChar() * (line == null ? 0 : line.length()));
    }

    public String render(Citizen citizen, String line, long seed) {
        return renderWith(voiceOf(citizen), line, seed ^ (citizen == null ? 0 : citizen.characterId()));
    }

    /** The transform itself, exposed for tests and previews. */
    static String renderWith(Voice voice, String line, long seed) {
        if (line == null || line.isBlank()) {
            return line;
        }
        Random random = new Random(seed);
        String text = line.trim();
        if (text.endsWith(".")) {
            text = text.substring(0, text.length() - 1);
        }
        text = abbreviate(text, voice, random);
        if (random.nextDouble() < voice.lowercase()) {
            text = text.toLowerCase(Locale.ROOT);
        }
        text = typos(text, voice, random);
        String[] words = text.split(" ");
        if (words.length >= 3 && !voice.petPhrases().isEmpty() && random.nextDouble() < voice.petPhraseRate()) {
            String phrase = voice.petPhrases().get(random.nextInt(voice.petPhrases().size()));
            text = random.nextBoolean() ? phrase + " " + text : text + " " + phrase;
        }
        if (!voice.emoticons().isEmpty() && random.nextDouble() < voice.emoticonRate()) {
            text = text + " " + voice.emoticons().get(random.nextInt(voice.emoticons().size()));
        }
        return text;
    }

    private static String abbreviate(String text, Voice voice, Random random) {
        String out = text;
        for (Map.Entry<String, String> entry : voice.abbreviations().entrySet()) {
            String key = entry.getKey();
            if (key.contains(" ")) {
                if (random.nextDouble() < voice.abbreviationRate()) {
                    out = out.replaceAll("(?i)\\b" + java.util.regex.Pattern.quote(key) + "\\b", entry.getValue());
                }
            }
        }
        String[] words = out.split(" ", -1);
        List<String> result = new ArrayList<>(words.length);
        for (String word : words) {
            String bare = word.replaceAll("[^A-Za-z']", "").toLowerCase(Locale.ROOT);
            String replacement = voice.abbreviations().get(bare);
            if (replacement != null && random.nextDouble() < voice.abbreviationRate()) {
                result.add(word.toLowerCase(Locale.ROOT).replace(bare, replacement));
            } else {
                result.add(word);
            }
        }
        return String.join(" ", result);
    }

    private static String typos(String text, Voice voice, Random random) {
        if (voice.typoRate() <= 0) {
            return text;
        }
        String[] words = text.split(" ", -1);
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (word.length() < MIN_TYPO_WORD || word.contains("{") || random.nextDouble() >= voice.typoRate()) {
                continue;
            }
            int at = 1 + random.nextInt(word.length() - 2);
            char[] chars = word.toCharArray();
            if (random.nextBoolean()) {
                char tmp = chars[at];
                chars[at] = chars[at + 1];
                chars[at + 1] = tmp;
                words[i] = new String(chars);
            } else {
                words[i] = word.substring(0, at) + word.substring(at + 1);
            }
        }
        return String.join(" ", words);
    }
}
