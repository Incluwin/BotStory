package server.mods;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StockClientAuditTest {

    @Test
    void stockNpcsAreTheServersNpcWzWithoutTheRepackOnes() {
        assertTrue(StockClientAudit.stockNpc(9010000), "Maple Administrator is in every v83 Npc.wz");
        assertFalse(StockClientAudit.stockNpc(2142112), "Cobie came with the Community Repack import");
        assertFalse(StockClientAudit.stockNpc(9970999), "the quest-marker hitbox NPC exists only in generated client files");
    }
}
