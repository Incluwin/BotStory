package server.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The live overlay over the real knowledge file, from fixture rows shaped like the DB after the changesets
 * (no DB): the KPQ coupon at 800000, shop 1338 without the Teleport Rock (changeset 200), Perfect Pitch as a
 * flat global drop, Inkwell's pitch prices, boss drop rate, script-only drop sources and DB-placed NPCs.
 */
class KnowledgeOverlayTest {
    private static final int KPQ_COUPON = 4001007;
    private static final int LIGATOR_PC = 9300001;
    private static final int TELEPORT_ROCK = 5041000;
    private static final int FAIRY_WING = 4003003;
    private static final int PERFECT_PITCH = 4310000;

    private final KnowledgeOverlay overlay = KnowledgeOverlay.builder()
            .drop(LIGATOR_PC, KPQ_COUPON, 800000, 1, 1, 0)
            .drop(100100, 4000019, 600000, 1, 1, 0)
            .drop(2220000, 4000019, 100000, 1, 1, 0)
            .drop(9500317, FAIRY_WING, 40000, 1, 1, 0)
            .drop(9500319, FAIRY_WING, 40000, 1, 1, 0)
            .drop(8150000, 4000226, 50000, 1, 1, 0)
            .globalDrop(-1, PERFECT_PITCH, 2000, 1, 1, 0)
            .globalDrop(1, 4031865, 3000, 1, 1, 0)
            .shopItem(1338, 9090000, 2010003, 100, 0)
            .shopItem(1338, 9090000, 2001002, 4000, 0)
            .shopItem(1001, 1011100, 2001002, 3200, 0)
            .shopItem(9000069, 9000069, 2001002, 0, 5)
            .life("n", 9090000, 910000000)
            .life("x", 1, 2)
            .build();
    private final WorldKnowledge knowledge = KnowledgeFixtures.with(overlay);

    @Test
    void kpqCouponComesFromTheLiveChance() {
        List<DropSource> sources = knowledge.dropSources(KPQ_COUPON);
        assertEquals(1, sources.size());
        DropSource coupon = sources.get(0);
        assertEquals(LIGATOR_PC, coupon.mobId());
        assertEquals(800000, coupon.chance());
        assertEquals(1.0, coupon.effectiveChance(3, 3), "capped at one per kill");
        assertEquals(0.8, coupon.effectiveChance(1, 1), 1e-9);
        assertEquals(knowledge.spawnMaps(LIGATOR_PC), coupon.maps());
        assertSame(coupon, knowledge.mobDrops(LIGATOR_PC).get(0));
    }

    @Test
    void shop1338NoLongerSellsTheTeleportRock() {
        assertTrue(knowledge.shopsSelling(TELEPORT_ROCK).isEmpty());
        List<ShopSource> manaElixir = knowledge.shopsSelling(2001002);
        assertEquals(List.of(1001, 1338, 9000069), manaElixir.stream().map(ShopSource::shopId).toList(),
                "cheapest meso price first, Perfect Pitch trades last");
        ShopSource muMu = manaElixir.get(1);
        assertEquals(9090000, muMu.npcId());
        assertEquals(List.of(910000000), muMu.npcMaps(), "Mu Mu has no WZ placement; plife puts him in the FM");
        assertEquals(5, manaElixir.get(2).pitch());
        assertEquals(0, manaElixir.get(2).price());
    }

    @Test
    void globalDropsAreFlatAndKeepTheirContinent() {
        DropSource pitch = knowledge.dropSources(PERFECT_PITCH).get(0);
        assertTrue(pitch.global());
        assertEquals(0, pitch.mobId());
        assertEquals(-1, pitch.continent());
        assertEquals(0.002, pitch.effectiveChance(3, 3), 1e-9, "global drops ignore the drop rate");
        assertEquals(1, knowledge.dropSources(4031865).get(0).continent());
        assertEquals(2, knowledge.globalDrops().size());
        assertTrue(knowledge.mobDrops(100100).stream().noneMatch(DropSource::global));
    }

    @Test
    void bossesUseTheBossDropRate() {
        List<DropSource> snailShell = knowledge.dropSources(4000019);
        assertEquals(List.of(100100, 2220000), snailShell.stream().map(DropSource::mobId).toList(), "highest chance first");
        DropSource mano = snailShell.get(1);
        assertTrue(mano.boss());
        assertEquals(0.2, mano.effectiveChance(3, 2), 1e-9);
        assertFalse(snailShell.get(0).boss());
        assertEquals(0.6, snailShell.get(0).effectiveChance(1, 5), 1e-9);
    }

    @Test
    void scriptOnlyMobsNameTheNpcAndTheirMap() {
        List<DropSource> wing = knowledge.dropSources(FAIRY_WING);
        assertEquals(2, wing.size());
        for (DropSource source : wing) {
            assertTrue(source.scriptOnly(), "mob " + source.mobId());
            assertEquals(9220004, source.viaNpc());
            assertEquals(List.of(209080000), source.maps());
        }
        assertFalse(knowledge.dropSources(4000019).get(0).scriptOnly(), "snails are placed on maps");
        DropSource balrog = knowledge.dropSources(4000226).get(0);
        assertTrue(balrog.scriptOnly(), "Crimson Balrog only comes from event/Boats.js");
        assertTrue(balrog.maps().isEmpty(), "Boats.js names nine maps, so the spawn map is not guessed");
    }

    @Test
    void overlayRebuildKeepsTheFileAndRoutes() {
        WorldKnowledge next = knowledge.withOverlay(KnowledgeOverlay.EMPTY, 2);
        assertTrue(next.isFrom(KnowledgeFixtures.file()));
        assertTrue(next.dropSources(KPQ_COUPON).isEmpty());
        assertEquals(knowledge.route(100000000, 220000000), next.route(100000000, 220000000));
        assertTrue(next.dataVersion().endsWith("#2"));
        assertTrue(next.npcMaps(9090000).isEmpty(), "plife came from the old overlay");
    }

    @Test
    void currentIsAnEmptySnapshotUntilTheLoaderPublishes() {
        WorldKnowledge current = WorldKnowledge.current();
        assertFalse(current.ready(), "tests never start KnowledgeLoader");
        assertEquals("not-ready", current.dataVersion());
        assertTrue(current.dropSources(KPQ_COUPON).isEmpty());
        assertTrue(current.map(100000000).isEmpty());
        assertTrue(current.route(100000000, 101000000).isEmpty());
        assertTrue(knowledge.ready());
    }
}
