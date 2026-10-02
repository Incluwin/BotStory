package server.knowledge;

import org.junit.jupiter.api.Test;
import server.knowledge.ContentAnswers.Ask;
import server.knowledge.ContentAnswers.Asker;
import server.knowledge.ContentAnswers.ContentAnswer;
import server.knowledge.ContentAnswers.Outcome;
import server.knowledge.ContentAnswers.Rates;
import server.knowledge.ContentAnswers.Responder;
import server.knowledge.ContentQuestionDetector.Kind;
import server.knowledge.KnowledgeHorizon.Grade;
import server.knowledge.KnownWorld.Past;
import server.knowledge.KnownWorld.Recall;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Answers from what a bot lived and heard (docs/SERVER_KNOWLEDGE_PLAN.md 7.3, 7.9): two histories, two answers; hearsay
 * counts; an empty companion answers like the level horizon; percentages and rate numbers are a veteran's; nothing a bot
 * says names a mob or map it does not know.
 */
class PersonalAnswersTest {
    private static final Rates RATES = new Rates(10, 3, 3, 3, 4f, 4);
    private static final int HENESYS = 100_000_000;
    private static final int BLUE_SNAIL_SHELL = 4_000_000;
    private static final int BLUE_SNAIL = 100_101;
    private static final int STUMP = 130_100;
    private static final Pattern EXACT = Pattern.compile("\\d+(\\.\\d+)?%");
    private static final List<String> STYLES = List.of("friendly-casual-v1", "quiet-practical-v1", "playful-social-v1",
            "curious-millennial-v1");
    private final WorldKnowledge k = SeedOverlay.knowledge();
    private final ContentAnswers answers = new ContentAnswers(SeedOverlay::knowledge, world -> RATES, item -> null, 10, 70, false);
    private final IntFunction<String> regionOf = map -> k.map(map).map(MapFact::region).orElse(null);
    private long clock = 1_000_000;

    private KnownWorld world(int id, Past past, boolean veteran) {
        KnownWorld w = new KnownWorld(id);
        w.configure(past, Recall.NONE, veteran, false, regionOf);
        return w;
    }

    /** Trained on a map: visits plus kills there, and kills of its main mob (or {@code mob}). */
    private void trainedAt(KnownWorld w, int map, int mob, int kills) {
        w.lived(EntityKind.MAP, map, kills, regionOf.apply(map));
        w.lived(EntityKind.MOB, mob != 0 ? mob : ContentAnswers.mainMob(k.map(map).orElseThrow()), kills, null);
    }

    /** A field of the region where the mob spawns and {@code without} does not. */
    private int field(int mobId, String region, int without) {
        return k.spawnMaps(mobId).stream().filter(map -> k.map(map).map(m -> region.equals(m.region()) && m.forcedReturn() == 0
                && !m.town() && !m.mobs().contains(without)).orElse(false)).findFirst().orElseThrow();
    }

    private ContentAnswer ask(String line, KnownWorld w, int level, String style) {
        clock += 1_000_000;
        return answers.explain(new Ask(line, new Asker(7000, "Tester", HENESYS, level, 0, 0),
                new Responder(w == null ? 1 : w.characterId(), level, "victoria", style, Set.of(), true, w), name -> false, clock));
    }

    @Test
    void twoHistoriesTwoAnswers() {
        KnownWorld a = world(1, Past.SYNTHESIZED, false);
        trainedAt(a, field(BLUE_SNAIL, "victoria", 0), BLUE_SNAIL, 300);
        KnownWorld b = world(2, Past.SYNTHESIZED, false);
        trainedAt(b, field(STUMP, "victoria", BLUE_SNAIL), STUMP, 40);
        String snails = k.names().say(EntityKind.MOB, BLUE_SNAIL);
        ContentAnswer byA = ask("who drops blue snail shells", a, 20, STYLES.get(0));
        ContentAnswer byB = ask("who drops blue snail shells", b, 20, STYLES.get(0));
        assertEquals(Outcome.ANSWERED, byA.outcome(), byA.reply());
        assertTrue(byA.reply().contains(snails), byA.reply());
        assertEquals(Outcome.DEFLECTED, byB.outcome(), byB.reply());
        assertTrue(List.of("never seen those drop", "no idea, never had one", "idk, never seen em").stream()
                .anyMatch(byB.reply()::endsWith), "deflect.unseen.item: " + byB.reply());
    }

    @Test
    void aResidentKnowsTheDropsItsOwnQuestsAreAbout() {
        int stirgeWing = 4_000_042; // Oda's repeatable: "stirges under the platform. twelve wings"
        int zombieLupin = 4_230_101; // Pell, the Lupin Trapper: pell-zombie-lupins
        KnownWorld oda = world(31, Past.SYNTHESIZED, false); // its past never went near either
        clock += 1_000_000;
        ContentAnswer wings = answers.explain(new Ask("who drops stirge wings", new Asker(7000, "Tester", HENESYS, 30, 0, 0),
                new Responder(31, 30, "victoria", STYLES.get(0), Set.of(Entity.simple(EntityKind.ITEM, stirgeWing, "", "").key()),
                        true, oda), name -> false, clock));
        int stirge = k.dropSources(stirgeWing).stream().filter(drop -> !drop.global()).findFirst().orElseThrow().mobId();
        assertEquals(Outcome.ANSWERED, wings.outcome(), wings.reply());
        assertTrue(wings.reply().contains(k.names().say(EntityKind.MOB, stirge)), wings.reply());
        clock += 1_000_000;
        ContentAnswer lupins = answers.explain(new Ask("what do zombie lupins drop", new Asker(7000, "Tester", HENESYS, 30, 0, 0),
                new Responder(31, 30, "victoria", STYLES.get(0), Set.of(Entity.simple(EntityKind.MOB, zombieLupin, "", "").key()),
                        true, oda), name -> false, clock));
        assertEquals(Outcome.ANSWERED, lupins.outcome(), lupins.reply());
    }

    @Test
    void eachBotNamesItsOwnTrainingSpot() {
        List<Integer> fields = k.trainingMaps(20, "victoria", 20);
        int mine = fields.get(0);
        int theirs = fields.get(fields.size() - 1);
        KnownWorld a = world(1, Past.SYNTHESIZED, false);
        trainedAt(a, mine, 0, 50);
        KnownWorld b = world(2, Past.SYNTHESIZED, false);
        trainedAt(b, theirs, 0, 50);
        assertEquals(mine, ask("where should i train at 20", a, 25, STYLES.get(0)).subject().id());
        assertEquals(theirs, ask("where should i train at 20", b, 25, STYLES.get(0)).subject().id());
        ContentAnswer lost = ask("where should i train at 60", a, 25, STYLES.get(0));
        assertEquals(Outcome.DEFLECTED, lost.outcome(), "no spot at 60, past its level");
    }

    @Test
    void hearsayCounts() {
        KnownWorld c = world(3, Past.SYNTHESIZED, false);
        c.heard(EntityKind.ITEM, BLUE_SNAIL_SHELL, null);
        c.heard(EntityKind.MOB, BLUE_SNAIL, null);
        ContentAnswer answer = ask("who drops blue snail shells", c, 20, STYLES.get(0));
        assertEquals(Outcome.ANSWERED, answer.outcome(), answer.reply());
        assertTrue(answer.reply().contains(k.names().say(EntityKind.MOB, BLUE_SNAIL)), answer.reply());
        assertEquals(List.of(), k.names().mentions(answer.reply(), List.of(EntityKind.MAP)), "no spawn map it never heard of");
    }

    @Test
    void anEmptyCompanionAnswersLikeTheLevelHorizon() {
        List<String> differ = new ArrayList<>();
        for (int i = 0; i < KnowledgeProbeTest.POSITIVES.size(); i++) {
            KnowledgeProbeTest.Probe probe = KnowledgeProbeTest.POSITIVES.get(i);
            if (probe.kind() == Kind.RULES && probe.line().contains("rate")) {
                continue;
            }
            KnownWorld empty = world(500 + i, Past.UNKNOWN, false);
            for (int level : new int[]{15, 35, 200}) {
                ContentAnswer legacy = probe(probe, null, 500 + i, level, i);
                ContentAnswer companion = probe(probe, empty, 500 + i, level, i);
                if (legacy.outcome() != companion.outcome() || !Objects.equals(legacy.reply(), companion.reply())) {
                    differ.add("lv" + level + " " + probe.line() + ": " + legacy.reply() + " | " + companion.reply());
                }
            }
        }
        assertEquals(List.of(), differ);
    }

    @Test
    void percentagesAndRateNumbersAreAVeteransTalk() {
        KnownWorld veteran = world(4, Past.SYNTHESIZED, true);
        trainedAt(veteran, field(BLUE_SNAIL, "victoria", 0), BLUE_SNAIL, 300);
        boolean quoted = false;
        for (int asker = 0; asker < 40 && !quoted; asker++) {
            clock += 1_000_000;
            ContentAnswer answer = answers.explain(new Ask("who drops blue snail shells", new Asker(8000 + asker, "Tester", HENESYS, 20,
                    0, 0), new Responder(4, 80, "victoria", STYLES.get(asker % 4), Set.of(), true, veteran), name -> false, clock));
            quoted = answer.reply() != null && EXACT.matcher(answer.reply()).find();
        }
        assertTrue(quoted, "a veteran quotes the chance");
        assertTrue(answers.explain(new Ask("who drops blue snail shells", new Asker(1, "", HENESYS, 20, 0, 0),
                new Responder(4, 80, "victoria", null, Set.of(), true, veteran), name -> false, 1L)).facts()
                .get("content.item_source").matches(".*\\d%.*"), "its facts carry the percent");

        List<PersonalProbe.Bot> bots = PersonalProbe.bots(SeedOverlay::knowledge, RATES);
        for (PersonalProbe.Bot bot : bots) {
            if (bot.world() == null || bot.world().veteran()) {
                continue;
            }
            for (int i = 0; i < KnowledgeProbeTest.POSITIVES.size(); i++) {
                KnowledgeProbeTest.Probe probe = KnowledgeProbeTest.POSITIVES.get(i);
                ContentAnswer answer = probe(probe, bot.world(), bot.id(), bot.level(), i);
                String said = answer.reply() == null ? "" : answer.reply().replace(nameOf(answer), "");
                assertFalse(said.contains("%"), bot.label() + ": " + probe.line() + " -> " + answer.reply());
            }
        }
    }

    @Test
    void theWordingClaimsNoLevelAndNoPercentOutsideAVeteransChance() {
        Pattern level = Pattern.compile("\\b(higher|lvl|level|far|past me)\\b");
        for (Map.Entry<String, List<String>> entry : ReplyBank.all().entrySet()) {
            String key = entry.getKey();
            for (String line : entry.getValue()) {
                String bare = key.split("/")[0];
                if (List.of("deflect.unseen", "deflect.unseen.item", "deflect.unseen.mob", "rough.heard", "drop.unknown",
                        "train.unknown").contains(bare)) {
                    assertFalse(level.matcher(line).find(), key + ": " + line);
                }
                if (bare.endsWith(".item") || bare.endsWith(".mob")) { // said of a thing, not a place: no "never been there"
                    assertFalse(line.contains("been"), key + ": " + line);
                }
                assertTrue(!line.contains("%") || key.startsWith("bucket.exact"), key + ": " + line);
            }
        }
        assertEquals("3%", ContentAnswers.percent(0.03));
        assertEquals("0.6%", ContentAnswers.percent(0.006));
        assertEquals("1%", ContentAnswers.percent(0.0098));
        assertEquals("0.05%", ContentAnswers.percent(0.0005));
        assertEquals("<0.01%", ContentAnswers.percent(0.00005));
    }

    @Test
    void rateNumbersOnlyFromVeterans() {
        KnownWorld plain = world(5, Past.SYNTHESIZED, false);
        KnownWorld veteran = world(6, Past.SYNTHESIZED, true);
        for (String style : STYLES) {
            ContentAnswer vague = ask("whats the exp rate", plain, 40, style);
            assertEquals(Outcome.ANSWERED, vague.outcome());
            assertFalse(vague.reply().matches(".*\\d.*"), vague.reply());
            assertFalse(vague.facts().containsKey("content.rules.rates"), vague.facts().toString());
            ContentAnswer exact = ask("whats the exp rate", veteran, 80, style);
            assertTrue(exact.reply().contains(String.valueOf(RATES.exp())), exact.reply());
            assertTrue(exact.facts().containsKey("content.rules.rates"));
        }
        assertTrue(ask("whats the exp rate", null, 40, STYLES.get(0)).reply().matches(".*\\d.*"), "the level horizon keeps its numbers");
    }

    @Test
    void anUnvisitedFieldOfAKnownRegionIsKnownByNameOnly() {
        KnownWorld citizen = world(7, Past.SYNTHESIZED, false);
        int home = k.trainingMaps(40, "victoria", 1).get(0);
        trainedAt(citizen, home, 0, 60);
        int target = 0;
        for (int map : k.trainingMaps(30, "victoria", 60)) {
            ContentAnswer named = ask("where is " + k.names().say(EntityKind.MAP, map), null, 200, STYLES.get(0));
            if (map != home && named.subject() != null && named.subject().kind() == EntityKind.MAP && named.subject().id() == map
                    && !k.isTown(map)) {
                target = map;
                break;
            }
        }
        assertTrue(target != 0);
        String line = "where is " + k.names().say(EntityKind.MAP, target);
        for (String style : STYLES) {
            ContentAnswer answer = ask(line, citizen, 40, style);
            assertEquals(Outcome.ANSWERED, answer.outcome(), answer.reply());
            assertFalse(answer.reply().contains("lvl") || answer.reply().contains("level") || answer.reply().contains("high"),
                    "no level claim: " + answer.reply());
            assertTrue(answer.facts().get("content.where").contains("never been there"), answer.facts().toString());
        }
    }

    @Test
    void personalAnswersNameOnlyWhatTheBotKnows() {
        List<String> leaks = new ArrayList<>();
        int checked = 0;
        for (PersonalProbe.Bot bot : PersonalProbe.bots(SeedOverlay::knowledge, RATES)) {
            if (bot.world() == null) {
                continue;
            }
            for (int i = 0; i < KnowledgeProbeTest.POSITIVES.size(); i++) {
                KnowledgeProbeTest.Probe probe = KnowledgeProbeTest.POSITIVES.get(i);
                ContentAnswer answer = probe(probe, bot.world(), bot.id(), bot.level(), i);
                if (answer.outcome() != Outcome.ANSWERED || answer.reply() == null) {
                    continue;
                }
                checked++;
                Set<Integer> route = routeMaps(probe, answer);
                for (NameIndex.Mention mention : k.names().mentions(answer.reply(), List.of(EntityKind.MOB, EntityKind.MAP))) {
                    Entity named = mention.candidate().entity();
                    boolean known = bot.world().past() == Past.UNKNOWN;
                    List<Entity> sameName = new ArrayList<>(named.kind() == EntityKind.MAP ? mapsSaid(named.say()) : mobsSaid(named.say()));
                    if (named.kind() == EntityKind.MAP) { // "kerning city subway" (a street) reads as the "subway" booth
                        sameName.addAll(mapsSaidIn(answer.reply(), named.say()));
                    }
                    for (Entity same : sameName) { // a name is many things ("ligators": the field ones and kpq's)
                        Grade grade = PersonalHorizon.grade(k, bot.world(), same, hub(same), false, RATES);
                        known |= same.kind() == EntityKind.MAP && route.contains(same.id()) || grade == Grade.FULL
                                || same.equals(answer.subject()) && grade == Grade.ROUGH;
                    }
                    if (!known) {
                        leaks.add(bot.label() + ": " + probe.line() + " -> " + answer.reply() + " names " + named.kind() + " " + named.say());
                    }
                    if (named.kind() == EntityKind.MAP && !hub(named)
                            && sameName.stream().noneMatch(map -> k.map(map.id()).map(MapFact::region).isPresent())) {
                        leaks.add(bot.label() + ": " + probe.line() + " -> " + answer.reply() + " names an event map " + named.say());
                    }
                }
            }
        }
        assertTrue(checked > 100, checked + " personal answers");
        assertEquals(List.of(), leaks);
    }

    // ---- helpers ------------------------------------------------------------------------------

    private ContentAnswer probe(KnowledgeProbeTest.Probe probe, KnownWorld w, int botId, int level, int index) {
        clock += 1_000_000;
        return answers.explain(new Ask(probe.line(), new Asker(7000 + index, "Tester", probe.map(), probe.level(), probe.job(), 0),
                new Responder(botId, level, "victoria", STYLES.get(index % STYLES.size()), Set.of(), true, w),
                name -> name.equalsIgnoreCase("bob"), clock));
    }

    /** A scroll's spoken name may carry its %; the rest of the reply may not. */
    private static String nameOf(ContentAnswer answer) {
        return answer.subject() == null ? "\u0000" : answer.subject().say();
    }

    /** Every map of the way from the asker to the answer's subject, and their towns (a route answer names them). */
    private Set<Integer> routeMaps(KnowledgeProbeTest.Probe probe, ContentAnswer answer) {
        Set<Integer> out = new HashSet<>();
        out.add(probe.map());
        out.add(k.townOf(probe.map()));
        if (answer.subject() != null && (answer.kind() == Kind.ROUTE || answer.kind() == Kind.WHERE_IS)) {
            List<Integer> targets = new ArrayList<>(answer.subject().ids());
            if (answer.subject().kind() == EntityKind.NPC) {
                targets = new ArrayList<>(k.npcMaps(answer.subject().id()));
            }
            for (int target : targets) {
                for (int map : k.path(probe.map(), target)) {
                    out.add(map);
                    out.add(k.townOf(map));
                }
            }
        }
        return out;
    }

    /**
     * Every map spoken with this name, or with it as the spot inside a town ("henesys department store" is said
     * "department store" in Henesys): a reply names one of them.
     */
    private List<Entity> mapsSaid(String say) {
        List<Entity> out = new ArrayList<>();
        for (MapFact map : k.maps()) {
            String name = k.names().say(EntityKind.MAP, map.id());
            if (name.equals(say) || name.endsWith(" " + say)) {
                out.add(k.names().entity(EntityKind.MAP, map.id()).orElseThrow());
            }
        }
        return out;
    }

    /** Maps whose spoken name the reply says and that share a word with {@code say}. */
    private List<Entity> mapsSaidIn(String reply, String say) {
        Set<String> words = Set.of(say.split(" "));
        List<Entity> out = new ArrayList<>();
        for (MapFact map : k.maps()) {
            String name = k.names().say(EntityKind.MAP, map.id());
            if (!name.isEmpty() && reply.contains(name) && java.util.Arrays.stream(name.split(" ")).anyMatch(words::contains)) {
                out.add(k.names().entity(EntityKind.MAP, map.id()).orElseThrow());
            }
        }
        return out;
    }

    private List<Entity> mobsSaid(String say) {
        List<Entity> out = new ArrayList<>();
        for (MobFact mob : k.mobs()) {
            if (k.names().say(EntityKind.MOB, mob.id()).equals(say)) {
                out.add(k.names().entity(EntityKind.MOB, mob.id()).orElseThrow());
            }
        }
        return out;
    }

    private boolean hub(Entity named) {
        int map = named.kind() == EntityKind.MAP ? named.id() : 0;
        int town = map == 0 ? 0 : k.townOf(map);
        return Set.of(200_000_000, 211_000_000, 220_000_000).contains(town == 0 ? map : town) || map >= 910_000_000 && map <= 910_000_022;
    }
}
