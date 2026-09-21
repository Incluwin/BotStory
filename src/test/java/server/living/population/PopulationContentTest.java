package server.living.population;

import org.junit.jupiter.api.Test;
import server.living.config.ContentLoader;
import server.living.config.LivingContent;
import server.living.population.PopulationContent.Voice;

import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PopulationContentTest {
    private static final Path ROOT = Path.of("scripts/config/population");
    private static final Path LIVING = Path.of("scripts/config/living-world");

    @Test
    void contentLoadsWithoutErrors() {
        LivingContent living = ContentLoader.load(LIVING);
        PopulationContent content = PopulationLoader.load(ROOT, living);
        List<String> errors = content.warnings().stream().filter(w -> w.startsWith("ERROR")).toList();
        assertTrue(errors.isEmpty(), "lint errors: " + errors);
        assertEquals(5, content.cohorts().size());
        assertTrue(content.careers().size() >= 10);
        assertTrue(content.intents().size() >= 30);
        assertTrue(content.fields().size() > 300, "fields.yaml should hold the scanned maps");
        assertNotNull(content.field(104010001));
        assertFalse(content.field(104010001).town());
        assertTrue(content.field(100000000).town());
        assertFalse(content.basket().isEmpty());
    }

    @Test
    void cohortWeightsFollowLocalHours() {
        PopulationContent content = PopulationLoader.load(ROOT, ContentLoader.load(LIVING));
        PopulationContent.Cohort evening = content.cohort("evening-na");
        ZonedDateTime nineAtNight = ZonedDateTime.of(2026, 9, 22, 20, 0, 0, 0, ZoneId.of("America/New_York")); // a Tuesday evening
        ZonedDateTime fourInTheMorning = nineAtNight.withHour(4);
        assertEquals(10, evening.weight(nineAtNight));
        assertEquals(0, evening.weight(fourInTheMorning));
        PopulationContent.Cohort owl = content.cohort("nightowl");
        assertTrue(owl.weight(fourInTheMorning.withHour(1)) > owl.weight(nineAtNight.withHour(15)));
    }

    @Test
    void lifeCurveIsMonotonicAndPlateausPastTheCap() {
        int day30 = LifeCurve.levelTarget(30, 1.0d, 70, 1.0d);
        int day120 = LifeCurve.levelTarget(120, 1.0d, 70, 1.0d);
        int day400 = LifeCurve.levelTarget(400, 1.0d, 70, 1.0d);
        assertTrue(day30 >= 20 && day30 <= 40, "a month of casual play lands in the 20s-30s, got " + day30);
        assertTrue(day120 > day30);
        assertTrue(day400 > day120 && day400 < 100, "past the cap growth slows, got " + day400);
        assertEquals(1, LifeCurve.levelTarget(0, 1.0d, 70, 1.0d));
        assertTrue(LifeCurve.levelTarget(60, 1.4d, 70, 1.0d) > LifeCurve.levelTarget(60, 0.7d, 70, 1.0d));
    }

    @Test
    void namesAreVariedValidAndReproducible() {
        PopulationContent content = PopulationLoader.load(ROOT, ContentLoader.load(LIVING));
        NameGenerator names = new NameGenerator(content.names().patterns(), content.names().lists(), content.names().start(),
                content.names().middle(), content.names().end());
        Set<String> seen = new HashSet<>();
        Random random = new Random(7);
        for (int i = 0; i < 200; i++) {
            String name = names.next(random);
            assertTrue(name.matches("[A-Za-z0-9]+"), name);
            seen.add(name);
        }
        assertTrue(seen.size() > 150, "expected variety, got " + seen.size());
        assertEquals(names.next(new Random(42)), names.next(new Random(42)));
    }

    @Test
    void voiceTransformIsDeterministicAndKeepsMeaning() {
        Voice voice = new Voice("t", "friendly-casual-v1", 100, 1.0d, 0.0d, 1.0d,
                Map.of("you", "u", "please", "pls"), List.of(), 0.0d, List.of(), 0.0d, 50, 0.0d);
        String rendered = VoicePacks.renderWith(voice, "Can you please change channel?", 1234L);
        assertEquals("can u pls change channel?", rendered);
        assertEquals(rendered, VoicePacks.renderWith(voice, "Can you please change channel?", 1234L));
        Voice typos = new Voice("t2", "friendly-casual-v1", 100, 0.0d, 1.0d, 0.0d, Map.of(), List.of("^^"), 1.0d, List.of(), 0.0d, 50, 0.0d);
        String noisy = VoicePacks.renderWith(typos, "anyone selling snail shells", 99L);
        assertTrue(noisy.endsWith("^^"));
        assertFalse(noisy.equals("anyone selling snail shells ^^"), "typos should change at least one word: " + noisy);
    }
}
