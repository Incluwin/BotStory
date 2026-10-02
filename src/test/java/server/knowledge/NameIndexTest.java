package server.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Name resolution against the real knowledge file, aliases.yaml and the seed overlay
 * (docs/SERVER_KNOWLEDGE_PLAN.md 5.2 / 5.6): slang, the scroll grammar, typos, canonical preference,
 * kind-typed slots, numbered series, ties and free-text mentions.
 */
class NameIndexTest {
    private static final List<EntityKind> ITEM = List.of(EntityKind.ITEM);
    private static final List<EntityKind> MOB = List.of(EntityKind.MOB);
    private static final List<EntityKind> PLACE = List.of(EntityKind.MAP, EntityKind.REGION, EntityKind.PQ);
    private static final List<EntityKind> WHERE = List.of(EntityKind.NPC, EntityKind.MAP, EntityKind.REGION,
            EntityKind.PQ, EntityKind.ROLE, EntityKind.MOB);

    private final NameIndex names = SeedOverlay.knowledge().names();

    @Test
    void normalizesNamesAndChatTheSameWay() {
        assertEquals("pig head", NameIndex.normalize("Pig's Head"));
        assertEquals("steely throwing knife", NameIndex.normalize("Steely Throwing-Knives"));
        assertEquals("jr wraith", NameIndex.normalize("Jr. Wraiths"));
        assertEquals("glove att 60%", NameIndex.normalize("glove att 60 %"));
        assertEquals("glove att 60%", NameIndex.normalize("glove att 60 percent"));
        assertEquals("60% g att", NameIndex.normalize("60p g att"));
        assertEquals("soo many steely", NameIndex.normalize("sooooo many steelies"));
        assertEquals("octopus", NameIndex.singular("octopi"));
        assertEquals("boss", NameIndex.singular("boss"));
        assertEquals("glass", NameIndex.singular("glasses"));
        assertEquals("horse", NameIndex.singular("horses"));
        assertEquals("shoe", NameIndex.singular("shoes"));
        assertEquals("mushroom", NameIndex.singular("shrooms"));
    }

    @Test
    void slangResolvesToTheRightIds() {
        assertBest(2070005, EntityKind.ITEM, names.resolve("steelies", ITEM));
        assertBest(2070005, EntityKind.ITEM, names.resolve("steelys", ITEM));
        assertBest(1002357, EntityKind.ITEM, names.resolve("zhelm", ITEM));
        assertBest(1002357, EntityKind.ITEM, names.resolve("zakum helm", ITEM));
        assertBest(2000006, EntityKind.ITEM, names.resolve("manas", ITEM));
        assertBest(4000017, EntityKind.ITEM, names.resolve("pig heads", ITEM));
        assertBest(4230101, EntityKind.MOB, names.resolve("zlupins", MOB));
        assertBest(3230101, EntityKind.MOB, names.resolve("jr wraiths", MOB));
        assertBest(2130103, EntityKind.MOB, names.resolve("jr neckis", MOB));
        assertEquals("steelies", names.resolve("steelies", ITEM).best().orElseThrow().entity().say());
        assertEquals("jr wraiths", names.resolve("Jr. Wraith", MOB).best().orElseThrow().entity().say());
    }

    @Test
    void scrollGrammarReadsThePercentFromTheDesc() {
        assertBest(2040804, EntityKind.ITEM, names.resolve("glove att 60", ITEM));
        assertBest(2040804, EntityKind.ITEM, names.resolve("60% g att", ITEM));
        assertBest(2040804, EntityKind.ITEM, names.resolve("g att 60%", ITEM));
        assertBest(2040804, EntityKind.ITEM, names.resolve("gatt 60", ITEM));
        assertBest(2040805, EntityKind.ITEM, names.resolve("glove att 10", ITEM));
        assertBest(2040002, EntityKind.ITEM, names.resolve("helm def 10", ITEM));
        assertBest(2044701, EntityKind.ITEM, names.resolve("claw att 60", ITEM));
        assertEquals("glove att 60", names.say(EntityKind.ITEM, 2040804));
        NameIndex.Resolution noPercent = names.resolve("glove att", ITEM);
        assertTrue(noPercent.tie(), "no % given: ask which one");
        assertTrue(noPercent.candidates().stream().anyMatch(c -> c.entity().id() == 2040804));
    }

    @Test
    void placesTyposAndSeries() {
        assertBest(104010001, EntityKind.MAP, names.resolve("pig beach", PLACE));
        assertBest(104010001, EntityKind.MAP, names.resolve("the pig beach", PLACE));
        assertBest(100000000, EntityKind.MAP, names.resolve("henesis", PLACE));
        assertBest(102000000, EntityKind.MAP, names.resolve("perrion", PLACE));
        assertEquals("fuzzy", names.resolve("perrion", PLACE).best().orElseThrow().how().substring(0, 5));
        Entity ants = names.resolve("ant tunnel", PLACE).best().orElseThrow().entity();
        assertEquals(EntityKind.REGION, ants.kind());
        assertTrue(ants.ids().contains(105050000));
        Entity tower = names.resolve("orbis tower", PLACE).best().orElseThrow().entity();
        assertEquals(EntityKind.REGION, tower.kind(), "numbered series collapse into a region");
        assertTrue(tower.ids().contains(200082100));
        assertEquals("orbis tower", tower.say());
        assertEquals("kerning square", names.say(EntityKind.MAP, 103040101), "1st Floor 2nd Floor Area B is said as its street");
        assertEquals("kerning square", names.say(EntityKind.MAP, 103040202));
        Entity kpq = names.resolve("kpq", PLACE).best().orElseThrow().entity();
        assertEquals(EntityKind.PQ, kpq.kind());
        assertEquals(9020000, kpq.npc());
        assertEquals(21, kpq.minLevel());
    }

    @Test
    void snailIsTheMobNeverTheCashPet() {
        assertBest(100100, EntityKind.MOB, names.resolve("snail", MOB));
        assertBest(100100, EntityKind.MOB, names.resolve("snails", List.of(EntityKind.ITEM, EntityKind.MOB)));
        assertBest(100100, EntityKind.MOB, names.resolve("snail", WHERE));
    }

    @Test
    void kindTypedSlotsPickTheirKind() {
        assertBest(8810018, EntityKind.MOB, names.resolve("ht", MOB));
        assertBest(1122000, EntityKind.ITEM, names.resolve("ht", ITEM));
        Entity potions = names.resolve("potion shop", WHERE).best().orElseThrow().entity();
        assertEquals(EntityKind.ROLE, potions.kind());
        assertTrue(potions.ids().contains(1011100), "Luna the Henesys grocer");
        assertEquals(EntityKind.ROLE, names.resolve("pots", WHERE).best().orElseThrow().entity().kind());
        assertBest(1012100, EntityKind.NPC, names.resolve("athena", WHERE));
        assertBest(1012100, EntityKind.NPC, names.resolve("athena pierce", WHERE));
        assertFalse(names.resolve("the", WHERE).resolved());
        assertFalse(names.resolve("it", ITEM).resolved());
        assertFalse(names.resolve("everyone", WHERE).resolved());
    }

    @Test
    void canonicalIdsBeatEventCopies() {
        assertBest(1210100, EntityKind.MOB, names.resolve("pigs", MOB));
        assertBest(6130101, EntityKind.MOB, names.resolve("mushmom", MOB));
        assertBest(8130100, EntityKind.MOB, names.resolve("jr balrog", MOB));
        assertBest(2000004, EntityKind.ITEM, names.resolve("elixir", ITEM));
    }

    @Test
    void closeCandidatesAreATie() {
        NameIndex.Resolution caps = names.resolve("mushroom caps", ITEM);
        assertTrue(caps.tie(), caps.toString());
        assertTrue(caps.candidates().size() >= 2);
        assertFalse(names.resolve("helm", ITEM).resolved(), "one common word is too vague to pick from");
    }

    @Test
    void freeTextFindsAliasesAndMultiWordNames() {
        List<NameIndex.Mention> found = names.mentions("lf> party for pig beach, also need a zhelm lol", WHERE_AND_ITEMS);
        assertEquals(List.of(104010001, 1002357), found.stream().map(m -> m.candidate().entity().id()).toList());
        assertTrue(names.mentions("red is my favourite colour and i lost my key", WHERE_AND_ITEMS).isEmpty(),
                "one-word chat names never resolve in free text");
    }

    private static final List<EntityKind> WHERE_AND_ITEMS = List.of(EntityKind.ITEM, EntityKind.MOB, EntityKind.MAP,
            EntityKind.REGION, EntityKind.NPC, EntityKind.PQ);

    private static void assertBest(int id, EntityKind kind, NameIndex.Resolution resolution) {
        assertTrue(resolution.resolved(), resolution.toString());
        Entity best = resolution.best().orElseThrow().entity();
        assertEquals(kind, best.kind(), resolution.toString());
        assertEquals(id, best.id(), resolution.toString());
    }
}
