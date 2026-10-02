package server.knowledge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The minimal immersion guard (docs/SERVER_KNOWLEDGE_PLAN.md 6.5): level + slack, home region, towns, own refs, grades. */
class KnowledgeHorizonTest {
    private final KnowledgeHorizon horizon = new KnowledgeHorizon(10);
    private final KnowledgeHorizon.Knower level20 = new KnowledgeHorizon.Knower(20, "victoria");

    @Test
    void levelPlusSlack() {
        assertTrue(horizon.knows(level20, 30, null, false, false), "lv 30 is within 20 + 10");
        assertFalse(horizon.knows(level20, 31, null, false, false));
        assertFalse(horizon.knows(level20, 50, null, false, false), "a level-20 companion does not know the Zakum helmet");
        assertTrue(horizon.knows(new KnowledgeHorizon.Knower(90, null), 50, null, false, false));
        assertTrue(horizon.knows(level20, 0, null, false, false), "no level: always within reach");
    }

    @Test
    void homeRegionTownsAndOwnReferencesAreAlwaysKnown() {
        assertTrue(horizon.knows(level20, 80, "victoria", false, false), "home region places");
        assertFalse(horizon.knows(level20, 80, "leafre", false, false));
        assertTrue(horizon.knows(level20, 80, "leafre", true, false), "hub towns and their interiors");
        assertTrue(horizon.knows(level20, 120, null, false, true), "a resident's own quest targets");
        assertTrue(horizon.knows(new KnowledgeHorizon.Knower(5, null), 15, null, false, false));
    }

    @Test
    void knowledgeFadesInGradesNotAtOneCutoff() {
        assertEquals(KnowledgeHorizon.Grade.FULL, horizon.grade(level20, 30, null, false, false, false));
        assertEquals(KnowledgeHorizon.Grade.ROUGH, horizon.grade(level20, 31, null, false, false, false), "a little past: name and place");
        assertEquals(KnowledgeHorizon.Grade.ROUGH, horizon.grade(level20, 45, null, false, false, false));
        assertEquals(KnowledgeHorizon.Grade.NONE, horizon.grade(level20, 46, null, false, false, false));
        assertEquals(KnowledgeHorizon.Grade.ROUGH, horizon.grade(level20, 120, null, false, false, true), "a famous boss: by reputation");
        assertEquals(KnowledgeHorizon.Grade.FULL, horizon.grade(level20, 120, "victoria", false, false, true), "home beats fame");
        assertEquals(KnowledgeHorizon.Grade.FULL, horizon.grade(new KnowledgeHorizon.Knower(120, null), 5, null, false, false, false),
                "knowledge downward is unlimited");
    }

    @Test
    void slackIsConfigurable() {
        KnowledgeHorizon strict = new KnowledgeHorizon(0);
        assertFalse(strict.withinReach(level20, 21));
        assertTrue(strict.withinReach(level20, 20));
        assertTrue(new KnowledgeHorizon(-3).withinReach(level20, 20), "negative slack is clamped to 0");
    }
}
