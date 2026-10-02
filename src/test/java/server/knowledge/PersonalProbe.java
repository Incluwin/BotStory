package server.knowledge;

import config.KnowledgeConfig;
import server.knowledge.ContentAnswers.Rates;
import server.knowledge.ContentAnswers.Responder;
import server.knowledge.KnownWorld.Past;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The four bots of the step-4 probe (docs/SERVER_KNOWLEDGE_PLAN.md 7.9 item 8), opened through an in-memory
 * {@link PersonalKnowledge} over a snapshot: A, a level-35 citizen who grew up in Victoria; B, a level-35 citizen whose home
 * is Ludibrium; V, a level-80 El Nath veteran; K, a level-35 companion (unknown past) with one visit to Kerning City.
 * KnowledgeProbeTest (seed rows) and KnowledgeProbeMain (live rows) both ask them the probe lines.
 */
final class PersonalProbe {
    /** population/regions.yaml victoria towns. */
    static final IntList VICTORIA_TOWNS = IntList.of(100000000, 101000000, 102000000, 103000000, 104000000, 105040300, 120000000);
    static final int LEGACY_LEVEL = 35;

    /** One probe bot; {@code world} null for the legacy (level horizon) peer. */
    record Bot(String label, int id, int level, String homeRegion, KnownWorld world) {
        Responder responder(String style) {
            return new Responder(id, level, homeRegion, style, Set.of(), true, world);
        }
    }

    private PersonalProbe() {
    }

    /** legacy (lv 35, victoria, no known world), A, B, V, K. */
    static List<Bot> bots(Supplier<WorldKnowledge> knowledge, Rates rates) {
        PersonalKnowledge registry = PersonalKnowledge.inMemory(knowledge, world -> rates, new KnowledgeConfig());
        registry.open(9101, new PersonalKnowledge.Spec(Past.SYNTHESIZED, 9101, 35, band -> band < 10 ? "maple-island" : "victoria",
                VICTORIA_TOWNS, List.of(), false, false));
        registry.open(9102, new PersonalKnowledge.Spec(Past.SYNTHESIZED, 9102, 35,
                band -> band < 10 ? "maple-island" : band < 35 ? "victoria" : "ludibrium", IntList.of(220000000), List.of(), false, false));
        registry.open(9103, new PersonalKnowledge.Spec(Past.SYNTHESIZED, 9103, 80,
                band -> band < 10 ? "maple-island" : band < 45 ? "victoria" : "el-nath", IntList.of(211000000), List.of(), true, false));
        registry.visited(9104, 103000000);
        KnownWorld companion = registry.companion(9104, 35);
        return List.of(new Bot("legacy", 9100, LEGACY_LEVEL, "victoria", null),
                new Bot("A", 9101, 35, "victoria", registry.known(9101)),
                new Bot("B", 9102, 35, "ludibrium", registry.known(9102)),
                new Bot("V", 9103, 80, "el-nath", registry.known(9103)),
                new Bot("K", 9104, 35, "victoria", companion));
    }
}
