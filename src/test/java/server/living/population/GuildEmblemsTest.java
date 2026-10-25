package server.living.population;

import config.PopulationConfig;
import net.server.guild.Guild;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import server.living.config.ContentLoader;
import server.living.population.CitizenStore.GuildMeta;
import server.living.population.GuildEmblems.Emblem;
import server.living.population.PopulationContent.EmblemColor;
import server.living.population.PopulationContent.Emblems;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyByte;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bot guilds wear real emblems that fit their names (the user, 2026-10-25: "bot guilds don't use the guild icons, they
 * just name the guild"). Before, they got backgrounds 1-18 and marks 1-25, which name no GuildMark.img art, so the
 * client drew nothing.
 */
class GuildEmblemsTest {
    private static final Path ROOT = Path.of("scripts/config/population");
    private static final Path LIVING = Path.of("scripts/config/living-world");
    private static final Path GUILD_MARK = Path.of("wz/UI.wz/GuildMark.img.xml");

    private static PopulationContent content;
    private static Emblems emblems;

    @BeforeAll
    static void load() {
        content = PopulationLoader.load(ROOT, ContentLoader.load(LIVING));
        emblems = content.guildNames().emblems();
    }

    @Test
    void theIdsNamedAsArtAreExactlyTheClientsGuildMarkArt() throws IOException {
        Map<String, Set<Integer>> art = guildMarkArt();
        Set<Integer> backgrounds = new TreeSet<>();
        for (int id = GuildEmblems.BACKGROUND_FIRST; id <= GuildEmblems.BACKGROUND_LAST; id++) {
            backgrounds.add(id);
        }
        backgrounds.add(1030); // "no background"
        assertEquals(backgrounds, art.get("BackGround"));
        Set<Integer> marks = new TreeSet<>();
        for (String category : List.of("Animal", "Plant", "Pattern", "Letter", "Etc")) {
            marks.addAll(art.get(category));
        }
        Set<Integer> ours = new TreeSet<>();
        for (int[] range : GuildEmblems.MARK_RANGES) {
            for (int id = range[0]; id <= range[1]; id++) {
                ours.add(id);
            }
        }
        ours.add(9026); // "no mark"
        assertEquals(ours, marks);
        assertEquals(Set.of(GuildEmblems.COLORS), art.get("colours"), "every picture but 'no mark' comes in 16 colours");
    }

    @Test
    void shippedEmblemContentLintsClean() {
        assertTrue(GuildEmblems.lint(emblems).isEmpty(), GuildEmblems.lint(emblems).toString());
        assertEquals(16, emblems.colors().size());
        assertTrue(emblems.words().size() >= 60, "words: " + emblems.words().size());
    }

    @Test
    void everyWordTheNameGeneratorUsesHasAnEmblemHint() {
        for (Map.Entry<String, List<String>> list : content.guildNames().lists().entrySet()) {
            for (String word : list.getValue()) {
                assertNotNull(GuildEmblems.word(word, emblems), list.getKey() + " word " + word + " has no emblem hint");
            }
        }
    }

    @Test
    void generatedGuildNamesAlwaysWearDrawableEmblemsThatStandOut() {
        NameGenerator generator = new NameGenerator(content.guildNames().patterns(), content.guildNames().lists(),
                List.of(), List.of(), List.of());
        Random random = new Random(11);
        Set<Integer> marks = new HashSet<>();
        Set<String> adjectives = Set.copyOf(content.guildNames().lists().get("Adj"));
        for (int i = 0; i < 1000; i++) {
            String name = generator.next(random);
            Emblem emblem = GuildEmblems.pick(name, emblems);
            assertTrue(GuildEmblems.isClientArt(emblem.background(), emblem.backgroundColor(), emblem.mark(),
                    emblem.markColor()), name + " -> " + emblem);
            EmblemColor ground = emblems.colors().get(emblem.backgroundColor());
            EmblemColor ink = emblems.colors().get(emblem.markColor());
            assertTrue(GuildEmblems.contrast(ground.backgroundRgb(), ink.markRgb()) >= emblems.minContrast(),
                    name + ": a " + ink.name() + " mark on " + ground.name() + " does not stand out");
            assertEquals(emblem, GuildEmblems.pick(name, emblems), name + " always gets the same emblem");
            marks.add(emblem.mark());
            if (emblem.mark() >= GuildEmblems.LETTER_A && emblem.mark() < GuildEmblems.DIGIT_0) {
                assertTrue(adjectives.contains(name), name + " has a word with a mark yet wears its initial");
                assertEquals(GuildEmblems.LETTER_A + (name.charAt(0) - 'A'), emblem.mark(), name + " wears its own initial");
            }
        }
        assertTrue(marks.size() >= 30, "the guilds' marks vary, got " + marks.size());
    }

    @Test
    void theEmblemReadsTheName() {
        Emblem wolves = GuildEmblems.pick("CrimsonWolves", emblems);
        assertEquals(9016, wolves.mark(), "a paw print");
        assertEquals(1, wolves.markColor(), "crimson is red");

        Emblem dragons = GuildEmblems.pick("GoldenDragons", emblems);
        assertTrue(Set.of(2003, 2004, 2006).contains(dragons.mark()), "a dragon, got " + dragons.mark());
        assertEquals(3, dragons.markColor(), "gold");

        Emblem legion = GuildEmblems.pick("LegionOfOrbis", emblems);
        assertEquals(9006, legion.mark(), "the head word, Legion, gives the flag; Orbis only the colour if Legion had none");
        assertTrue(Set.of(1, 12).contains(legion.markColor()), "Legion's colours, got " + legion.markColor());

        assertEquals(9016, GuildEmblems.pick("Wolvess", emblems).mark(), "the {Noun}s pattern doubles an s");
        assertEquals(9009, GuildEmblems.pick("TheStorm", emblems).mark(), "lightning");
        assertEquals(1024, GuildEmblems.pick("SilentCircle", emblems).background(), "a circle on a circle");
        assertEquals(GuildEmblems.LETTER_A + ('Z' - 'A'), GuildEmblems.pick("Zyzzyva", emblems).mark(),
                "a name with no known word wears its initial");
        assertEquals(GuildEmblems.DIGIT_0 + 7, GuildEmblems.pick("7Seas", emblems).mark());
    }

    @Test
    void oldBotGuildsWithUndrawableEmblemsGetTheirNamesEmblemOncePerBoot() {
        Population population = mock(Population.class);
        CitizenStore store = mock(CitizenStore.class);
        when(population.config()).thenReturn(new PopulationConfig());
        when(population.store()).thenReturn(store);
        when(population.content()).thenReturn(content);
        when(store.guilds()).thenReturn(List.of(new GuildMeta(41, "open", 10, 40, "", "evening-na"),
                new GuildMeta(42, "open", 10, 40, "", "evening-na"), new GuildMeta(43, "open", 10, 40, "", "evening-na")));
        Guild old = guild(41, "CrimsonWolves", 7, 3, 12, 5); // founded with the old 1-18 / 1-25 numbers
        Guild dressed = guild(42, "TheStorm", 1024, 16, 9009, 3);
        Map<Integer, Guild> byId = new TreeMap<>(Map.of(41, old, 42, dressed)); // 43 disbanded since

        SocialFabric fabric = new SocialFabric(population);
        fabric.emblemOldGuilds(byId::get);
        fabric.emblemOldGuilds(byId::get);

        Emblem expected = GuildEmblems.pick("CrimsonWolves", emblems);
        verify(old, times(1)).setGuildEmblem((short) expected.background(), (byte) expected.backgroundColor(),
                (short) expected.mark(), (byte) expected.markColor());
        verify(dressed, never()).setGuildEmblem(anyShort(), anyByte(), anyShort(), anyByte());
        assertFalse(GuildEmblems.isClientArt(7, 3, 12, 5));
    }

    private static Guild guild(int id, String name, int background, int backgroundColor, int mark, int markColor) {
        Guild guild = mock(Guild.class);
        when(guild.getId()).thenReturn(id);
        when(guild.getName()).thenReturn(name);
        when(guild.getLogoBG()).thenReturn(background);
        when(guild.getLogoBGColor()).thenReturn(backgroundColor);
        when(guild.getLogo()).thenReturn(mark);
        when(guild.getLogoColor()).thenReturn(markColor);
        return guild;
    }

    /**
     * Ids per category of GuildMark.img ("BackGround", "Animal", ...), plus "colours": the canvas counts seen on every
     * picture but "no mark" (9026, one canvas).
     */
    private static Map<String, Set<Integer>> guildMarkArt() throws IOException {
        Map<String, Set<Integer>> out = new TreeMap<>();
        Pattern dir = Pattern.compile("^(\\s*)<imgdir name=\"([^\"]+)\">");
        Pattern canvas = Pattern.compile("^\\s*<canvas name=\"(\\d+)\"");
        String category = null;
        int canvases = -1;
        String picture = null;
        Set<Integer> counts = new TreeSet<>();
        for (String line : Files.readAllLines(GUILD_MARK, StandardCharsets.UTF_8)) {
            Matcher d = dir.matcher(line);
            if (d.find()) {
                String name = d.group(2);
                if (name.matches("\\d+")) {
                    if (canvases >= 0 && !"00009026".equals(picture)) {
                        counts.add(canvases);
                    }
                    picture = name;
                    canvases = 0;
                    out.computeIfAbsent(category, key -> new TreeSet<>()).add(Integer.parseInt(name));
                } else if (!name.equals("GuildMark.img") && !name.equals("Mark")) {
                    category = name;
                }
                continue;
            }
            if (canvas.matcher(line).find() && canvases >= 0) {
                canvases++;
            }
        }
        if (!"00009026".equals(picture)) {
            counts.add(canvases);
        }
        out.put("colours", counts);
        return out;
    }
}
