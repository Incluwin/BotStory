package server.knowledge;

import org.junit.jupiter.api.Test;
import server.knowledge.ContentAnswers.Rates;
import server.knowledge.KnowledgeHorizon.Grade;
import server.knowledge.KnownWorld.Past;
import server.knowledge.KnownWorld.Recall;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The personal grade table (docs/SERVER_KNOWLEDGE_PLAN.md 7.3), case by case, over the seed snapshot. */
class PersonalHorizonTest {
    private static final Rates RATES = new Rates(10, 3, 3, 3, 4f, 4);
    private static final int HENESYS = 100_000_000;
    private static final int ORBIS = 200_000_000;
    private static final int LEAFRE = 240_000_000;
    private static final int ATHENA = 1_012_100;
    private static final int RED_POTION = 2_000_000;
    private static final int MUSHMOM = 6_130_101;
    private final WorldKnowledge k = SeedOverlay.knowledge();

    /** A bot that lived one Victoria field (so it knows that region) and nothing else. */
    private KnownWorld livedIn(int field) {
        KnownWorld w = new KnownWorld(1);
        w.lived(EntityKind.MAP, field, 1, k.map(field).orElseThrow().region());
        w.configure(Past.SYNTHESIZED, Recall.NONE, false, false, map -> k.map(map).map(MapFact::region).orElse(null));
        return w;
    }

    private Grade grade(KnownWorld w, EntityKind kind, int id, boolean townKnown, boolean famous) {
        return PersonalHorizon.grade(k, w, k.names().entity(kind, id).orElseThrow(), townKnown, famous, RATES);
    }

    @Test
    void placesAreKnownWhereTheBotHasBeen() {
        List<Integer> fields = k.trainingMaps(12, "victoria", 20);
        int field = fields.get(0);
        int other = fields.stream().filter(map -> map != field).findFirst().orElseThrow();
        KnownWorld w = livedIn(field);
        assertEquals(Grade.FULL, grade(w, EntityKind.MAP, field, false, false), "a field it trained on");
        assertEquals(Grade.ROUGH, grade(w, EntityKind.MAP, other, false, false), "a field of a region it knows, never visited");
        assertEquals(Grade.FULL, grade(w, EntityKind.MAP, HENESYS, false, false), "a town of a region it knows");
        assertEquals(Grade.FULL, grade(w, EntityKind.NPC, ATHENA, false, false), "an NPC standing in such a town");
        assertEquals(Grade.NONE, grade(w, EntityKind.MAP, LEAFRE, false, false), "another continent's town");
        assertEquals(Grade.FULL, grade(w, EntityKind.MAP, ORBIS, true, false), "the hub towns stay known to everyone");
        assertNull(PersonalHorizon.grade(k, w, Entity.simple(EntityKind.QUEST, 1000, "", ""), false, false, RATES), "quests: the prior");
    }

    @Test
    void mobsAreKnownByFightingThemOrWhereTheySpawn() {
        int field = k.trainingMaps(12, "victoria", 1).get(0);
        int placed = k.map(field).orElseThrow().mobs().getInt(0);
        KnownWorld w = livedIn(field);
        assertEquals(0, w.kills(placed));
        assertEquals(Grade.FULL, grade(w, EntityKind.MOB, placed, false, false), "it spawns on a map the bot knows");
        assertEquals(Grade.ROUGH, grade(w, EntityKind.MOB, MUSHMOM, false, true), "a boss is famous");
        int leafreMob = k.map(k.trainingMaps(110, "leafre", 1).get(0)).orElseThrow().mobs().getInt(0);
        assertEquals(Grade.NONE, grade(w, EntityKind.MOB, leafreMob, false, false));
        w.lived(EntityKind.MOB, leafreMob, 1, null);
        assertEquals(Grade.FULL, grade(w, EntityKind.MOB, leafreMob, false, false), "killed one");
    }

    @Test
    void itemsAreKnownByHavingThemSeeingThemDropOrTheirShop() {
        DropSource drop = k.mobDrops(100101).stream()
                .filter(d -> d.chance() >= 1_000 && d.chance() <= 10_000 && d.questId() == 0
                        && k.shopsSelling(d.itemId()).isEmpty() && k.dropSources(d.itemId()).stream().noneMatch(DropSource::global))
                .findFirst().orElseThrow();
        KnownWorld many = new KnownWorld(1);
        many.lived(EntityKind.MOB, 100101, 500, null);
        many.configure(Past.SYNTHESIZED, Recall.NONE, false, false, map -> null);
        KnownWorld few = new KnownWorld(2);
        few.lived(EntityKind.MOB, 100101, 10, null);
        few.configure(Past.SYNTHESIZED, Recall.NONE, false, false, map -> null);
        assertTrue(500 * drop.effectiveChance(3, 3) >= 1 && 10 * drop.effectiveChance(3, 3) < 1, "fixture chance " + drop.chance());
        assertEquals(Grade.FULL, grade(many, EntityKind.ITEM, drop.itemId(), false, false), "500 kills: seen it drop");
        assertEquals(Grade.NONE, grade(few, EntityKind.ITEM, drop.itemId(), false, false), "10 kills: never saw one");
        few.heard(EntityKind.ITEM, drop.itemId(), null);
        assertEquals(Grade.FULL, grade(few, EntityKind.ITEM, drop.itemId(), false, false), "heard of it");
        KnownWorld nowhere = new KnownWorld(3);
        nowhere.configure(Past.SYNTHESIZED, Recall.NONE, false, false, map -> null);
        assertEquals(Grade.NONE, grade(nowhere, EntityKind.ITEM, RED_POTION, false, false), "no town it knows sells it");
        KnownWorld local = livedIn(k.trainingMaps(12, "victoria", 1).get(0));
        assertEquals(Grade.FULL, grade(local, EntityKind.ITEM, RED_POTION, false, false), "a shop in a town of its region");
    }

    @Test
    void aPartyQuestIsFamousButOnlyItsLobbyMakesItKnown() {
        Entity kpq = k.names().resolveSlot("kpq", List.of(EntityKind.PQ)).best().orElseThrow().entity();
        KnownWorld stranger = new KnownWorld(1);
        stranger.lived(EntityKind.MAP, ORBIS, 1, "orbis");
        stranger.configure(Past.SYNTHESIZED, Recall.NONE, false, false, map -> null);
        assertEquals(Grade.ROUGH, PersonalHorizon.grade(k, stranger, kpq, false, true, RATES));
        KnownWorld local = livedIn(k.trainingMaps(12, "victoria", 1).get(0));
        assertEquals(Grade.FULL, PersonalHorizon.grade(k, local, kpq, false, true, RATES), "its lobby town is in a known region");
    }
}
