package server.living.roster;

import config.LivingWorldConfig;
import org.junit.jupiter.api.Test;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static server.living.roster.LifecycleHarness.HENESYS;
import static server.living.roster.LifecycleHarness.HENESYS_FIELD;
import static server.living.roster.LifecycleHarness.LITH;

/**
 * Decision 7 (docs/BOT_MANAGEMENT_PLAN.md, "Decisions"): a recall on the same continent arrives within
 * {@code RECALL_MAX_ETA_MS} (60 s by default); an off-island one keeps its 180 s; there is no whistle by default.
 */
class RecallCapTest {
    private static final int FLORINA_FIELD = 110000100;
    private static final int ORBIS = 200000000;

    @Test
    void theSameContinentIsCappedAndOffIslandIsNot() {
        assertEquals(160_000L, RecallTravel.etaMs(FLORINA_FIELD, 103000100), "three roads to a Kerning field, uncapped");
        assertEquals(60_000L, RecallTravel.etaMs(FLORINA_FIELD, 103000100, 60_000L));
        assertEquals(40_000L, RecallTravel.etaMs(LITH, 104000100, 60_000L), "a shorter trip keeps its time");
        assertEquals(0L, RecallTravel.etaMs(LITH, LITH, 60_000L));
        assertEquals(180_000L, RecallTravel.etaMs(LITH, ORBIS, 60_000L), "off-island keeps its 180 s");
        assertEquals(180_000L, RecallTravel.etaMs(ORBIS, HENESYS, 60_000L));
        assertEquals(0L, RecallTravel.etaMs(LITH, HENESYS, 0L), "a cap of 0 makes every island trip instant");
    }

    @Test
    void theValleysAndTheSwampAreOnTheIslandSoTheCapApplies() {
        // seen live: Ilse camped at Dangerous Valley II (106000002, offered by the camp picker) took the 180 s off-island time
        assertEquals(60_000L, RecallTravel.etaMs(106000002, 103010000, 60_000L), "Dangerous Valley II to a Kerning field");
        assertEquals(60_000L, RecallTravel.etaMs(107000000, 104000000, 60_000L), "the Swamp of Despair to Lith Harbor");
        assertEquals(60_000L, RecallTravel.etaMs(106000000, 102000000), "one road from Deep Valley to Perion");
    }

    @Test
    void theFreeMarketIsOnTheIslandThroughEachTownsPortal() {
        // found live 2026-09-29: an owner in FM room 4 waited 180 s for a companion called from Henesys
        assertEquals(60_000L, RecallTravel.etaMs(100000000, 910000004), "one portal from Henesys to an FM room");
        assertEquals(60_000L, RecallTravel.etaMs(106000101, 910000004, 60_000L), "the Burnt Land to an FM room, capped");
        assertEquals(80_000L, RecallTravel.etaMs(910000004, 101020004), "an FM room to an Ellinia field, uncapped");
    }

    @Test
    void theDefaultsAreDecision7() throws IOException {
        LivingWorldConfig config = new LivingWorldConfig();
        assertEquals(60_000, config.RECALL_MAX_ETA_MS);
        assertEquals(0, config.RECALL_WHISTLE_ITEM_ID, "whistle off");
        String yaml = Files.readString(Path.of("config.yaml"), StandardCharsets.US_ASCII);
        assertTrue(yaml.contains("\n    RECALL_MAX_ETA_MS: 60000\n"), "config.yaml living_world carries the key");
        for (char c : yaml.toCharArray()) {
            assertTrue(c < 128, "config.yaml stays US-ASCII");
        }
    }

    @Test
    void aNegativeCapIsRejected() {
        LivingWorldConfig config = new LivingWorldConfig();
        config.RECALL_MAX_ETA_MS = -1;
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    void aCallUsesTheConfiguredCap() {
        try (LifecycleHarness game = new LifecycleHarness()) {
            game.config.RECALL_MAX_ETA_MS = 30_000;
            Persona olwen = game.companion("olwen", "Olwen", 900, RosterState.BENCHED, LITH);

            assertEquals(new RosterResult.Travelling(30_000L, LITH), game.roster().call(game.ann, olwen));
            assertEquals(HENESYS_FIELD, game.ann.getMapId());
        }
    }
}
