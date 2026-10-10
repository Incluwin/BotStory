package server.knowledge;

import org.junit.jupiter.api.Test;

class ZzCastleProbeTest {
    @Test
    void probe() throws Exception {
        try (AutoCloseable seed = KnowledgeTestKit.publishSeed()) {
            WorldKnowledge k = SeedOverlay.knowledge();
            for (int item : new int[] {4000501, 4000502, 4000503}) {
                for (int level : new int[] {20, 33, 50}) {
                    System.out.println("PROBE " + item + " L" + level + " " + FarmSites.plan(k, item, level, 106020000, false));
                }
                for (DropSource s : k.dropSources(item)) {
                    System.out.println("PROBE src " + s);
                }
            }
            System.out.println("PROBE map 103000906 " + k.map(103000906));
            MapFact m = k.map(103000906).orElseThrow(); for (int i = 0; i < m.mobs().size(); i++) { int mob = m.mobs().getInt(i);
                System.out.println("PROBE mob " + mob + " " + k.mob(mob) + " drops " + k.mobDrops(mob).size());
            }
        }
    }
}
