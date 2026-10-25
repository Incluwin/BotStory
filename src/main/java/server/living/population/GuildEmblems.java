package server.living.population;

import server.living.population.PopulationContent.EmblemColor;
import server.living.population.PopulationContent.EmblemWord;
import server.living.population.PopulationContent.Emblems;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * A bot guild's emblem, read off its name (guild-names.yaml {@code emblems}). The client draws an emblem only from real
 * GuildMark.img art in UI.wz: a background shape 1000-1029 (1030 is "no background") and a mark, animals 2000-2020,
 * plants 3000-3006, patterns 4000-4021, letters and digits 5000-5035, symbols 9000-9025 (9026 is "no mark"), each in
 * colour 1-16. Bot guilds used to get backgrounds 1-18 and marks 1-25, which name no art, so they showed no emblem.
 *
 * <p>The mark comes from the name's head word (the words before "Of", else all of them; the last one with marks), else
 * from any word with marks, else the guild's initial. The mark's colour comes from the first word with colours, else
 * the default colours; the background colour is one whose sampled fill stands out from the mark's sampled ink by at
 * least {@code minContrast} (WCAG contrast ratio), never the mark's own colour. Every choice among options hashes the
 * name, so a guild always gets the same emblem.
 */
public final class GuildEmblems {
    public record Emblem(int background, int backgroundColor, int mark, int markColor) { }

    static final int BACKGROUND_FIRST = 1000;
    static final int BACKGROUND_LAST = 1029;
    static final int LETTER_A = 5000;
    static final int DIGIT_0 = 5026;
    static final int STAR = 4001;
    /** Inclusive id ranges of the marks GuildMark.img draws ("no mark" 9026 left out). */
    static final int[][] MARK_RANGES = {{2000, 2020}, {3000, 3006}, {4000, 4021}, {5000, 5035}, {9000, 9025}};
    static final int COLORS = 16;

    private GuildEmblems() {
    }

    public static Emblem pick(String name, Emblems content) {
        List<String> words = words(name);
        int of = -1;
        for (int i = 1; i < words.size() && of < 0; i++) {
            if (words.get(i).equalsIgnoreCase("of")) {
                of = i;
            }
        }
        List<String> head = of > 0 ? words.subList(0, of) : words;
        EmblemWord markWord = lastWithMarks(head, content);
        if (markWord == null) {
            markWord = lastWithMarks(words, content);
        }
        int mark = markWord != null ? choose(markWord.marks(), name, "mark") : initial(name);

        EmblemWord colorWord = null;
        for (String word : words) {
            EmblemWord known = word(word, content);
            if (known != null && !known.colors().isEmpty()) {
                colorWord = known;
                break;
            }
        }
        List<String> colorNames = colorWord != null ? colorWord.colors() : content.defaultColors();
        EmblemColor ink = colorNames.isEmpty() ? null : content.color(choose(colorNames, name, "color"));
        if (ink == null) {
            ink = content.colors().isEmpty() ? new EmblemColor(16, "black", 0x161616, 0x141414)
                    : choose(new ArrayList<>(content.colors().values()), name, "color");
        }

        int shape = 0;
        for (String word : words) {
            EmblemWord known = word(word, content);
            if (known != null && known.background() > 0) {
                shape = known.background();
                break;
            }
        }
        if (shape == 0) {
            shape = content.backgrounds().isEmpty() ? BACKGROUND_FIRST : choose(content.backgrounds(), name, "shape");
        }
        EmblemColor ground = ground(ink, name, content);
        return new Emblem(shape, ground == null ? (ink.id() == 15 ? 16 : 15) : ground.id(), mark, ink.id());
    }

    /** True when the client draws this as an emblem: a real background and mark, colours 1-16. */
    public static boolean isClientArt(int background, int backgroundColor, int mark, int markColor) {
        return background >= BACKGROUND_FIRST && background <= BACKGROUND_LAST && isMark(mark)
                && backgroundColor >= 1 && backgroundColor <= COLORS && markColor >= 1 && markColor <= COLORS;
    }

    static boolean isMark(int mark) {
        for (int[] range : MARK_RANGES) {
            if (mark >= range[0] && mark <= range[1]) {
                return true;
            }
        }
        return false;
    }

    /** The colours whose background fill stands out from the ink's mark colour (none: the content lint says so). */
    static List<EmblemColor> grounds(EmblemColor ink, Emblems content) {
        List<EmblemColor> out = new ArrayList<>();
        for (EmblemColor color : content.colors().values()) {
            if (color.id() != ink.id() && contrast(color.backgroundRgb(), ink.markRgb()) >= content.minContrast()) {
                out.add(color);
            }
        }
        return out;
    }

    /** WCAG contrast ratio of two 0xRRGGBB colours, 1 (same) to 21 (black on white). */
    static double contrast(int rgbA, int rgbB) {
        double a = luminance(rgbA);
        double b = luminance(rgbB);
        return (Math.max(a, b) + 0.05d) / (Math.min(a, b) + 0.05d);
    }

    /** The name's words: split at spaces and symbols, lower-to-upper case changes and letter/digit changes. */
    static List<String> words(String name) {
        List<String> out = new ArrayList<>();
        if (name == null) {
            return out;
        }
        for (String part : name.split("[^A-Za-z0-9]+|(?<=[a-z])(?=[A-Z])|(?<=[A-Za-z])(?=[0-9])|(?<=[0-9])(?=[A-Za-z])")) {
            if (!part.isEmpty()) {
                out.add(part);
            }
        }
        return out;
    }

    /** A word's emblem hints, matched without case and with a trailing "s" either way ("Wolvess", "Knight"). */
    static EmblemWord word(String word, Emblems content) {
        String key = word.toLowerCase(Locale.ROOT);
        EmblemWord known = content.words().get(key);
        if (known == null && key.length() > 3 && key.endsWith("s")) {
            known = content.words().get(key.substring(0, key.length() - 1));
        }
        if (known == null) {
            known = content.words().get(key + "s");
        }
        return known;
    }

    /** Lint errors for the emblem content (PopulationLoader.lint): every id real art, every colour known and backed. */
    static List<String> lint(Emblems content) {
        List<String> out = new ArrayList<>();
        String where = "ERROR guild-names.yaml emblems";
        if (content.colors().isEmpty()) {
            return out; // no emblems: bot guilds wear their initial in black on white
        }
        if (content.minContrast() < 1.0d) {
            out.add(where + ": minContrast must be at least 1");
        }
        for (int id = 1; id <= COLORS; id++) {
            if (!content.colors().containsKey(id)) {
                out.add(where + ": colour " + id + " is missing (the client has 16)");
            }
        }
        for (EmblemColor color : content.colors().values()) {
            if (color.id() < 1 || color.id() > COLORS) {
                out.add(where + ": colour " + color.id() + " is not one of the client's 1-16");
            } else if (grounds(color, content).isEmpty()) {
                out.add(where + ": no background colour stands out from a " + color.name() + " mark");
            }
        }
        for (int background : content.backgrounds()) {
            if (background < BACKGROUND_FIRST || background > BACKGROUND_LAST) {
                out.add(where + ": background " + background + " is not GuildMark.img art (1000-1029)");
            }
        }
        if (content.defaultColors().isEmpty()) {
            out.add(where + ": defaultColors is empty");
        }
        colorNames(content.defaultColors(), content, where + " defaultColors", out);
        for (var entry : content.words().entrySet()) {
            String at = where + " word " + entry.getKey();
            EmblemWord word = entry.getValue();
            for (int mark : word.marks()) {
                if (!isMark(mark)) {
                    out.add(at + ": mark " + mark + " is not GuildMark.img art");
                }
            }
            colorNames(word.colors(), content, at, out);
            if (word.background() != 0 && (word.background() < BACKGROUND_FIRST || word.background() > BACKGROUND_LAST)) {
                out.add(at + ": background " + word.background() + " is not GuildMark.img art (1000-1029)");
            }
            if (word.marks().isEmpty() && word.colors().isEmpty() && word.background() == 0) {
                out.add(at + ": gives no mark, colour or shape");
            }
        }
        return out;
    }

    private static void colorNames(List<String> names, Emblems content, String at, List<String> out) {
        for (String name : names) {
            if (content.color(name) == null) {
                out.add(at + ": unknown colour '" + name + "'");
            }
        }
    }

    private static EmblemColor ground(EmblemColor ink, String name, Emblems content) {
        List<EmblemColor> grounds = grounds(ink, content);
        if (!grounds.isEmpty()) {
            return choose(grounds, name, "ground");
        }
        return content.colors().values().stream().filter(color -> color.id() != ink.id())
                .max(Comparator.comparingDouble(color -> contrast(color.backgroundRgb(), ink.markRgb()))).orElse(null);
    }

    private static EmblemWord lastWithMarks(List<String> words, Emblems content) {
        for (int i = words.size() - 1; i >= 0; i--) {
            EmblemWord known = word(words.get(i), content);
            if (known != null && !known.marks().isEmpty()) {
                return known;
            }
        }
        return null;
    }

    private static int initial(String name) {
        char first = name == null || name.isEmpty() ? '*' : Character.toUpperCase(name.charAt(0));
        if (first >= 'A' && first <= 'Z') {
            return LETTER_A + (first - 'A');
        }
        if (first >= '0' && first <= '9') {
            return DIGIT_0 + (first - '0');
        }
        return STAR;
    }

    private static <T> T choose(List<T> options, String name, String salt) {
        String key = (name == null ? "" : name.toLowerCase(Locale.ROOT)) + "|" + salt;
        return options.get(Math.floorMod(key.hashCode(), options.size()));
    }

    private static double luminance(int rgb) {
        double[] channels = Arrays.stream(new int[] {(rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff})
                .mapToDouble(value -> {
                    double v = value / 255.0d;
                    return v <= 0.03928d ? v / 12.92d : Math.pow((v + 0.055d) / 1.055d, 2.4d);
                }).toArray();
        return 0.2126d * channels[0] + 0.7152d * channels[1] + 0.0722d * channels[2];
    }
}
