package server.living.roster;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * As a companion run ends, one log line gives each lineup companion's HP and MP potions as it was let in and as it
 * ends: the per-quest potion standard (agreed with the shops work) is retuned from these counts.
 */
class CompanionRunPotionLineTest {

    @Test
    void eachCompanionShowsItsHpAndMpPotionsAtTheYesAndAtTheEnd() {
        assertEquals("casse(333) hp=40>31 mp=50>22",
                CompanionRunRecorder.potionPart("casse(333)", new int[]{40, 50}, new int[]{31, 22}));
    }

    @Test
    void aCountThatWasNotReadShowsAsAQuestionMark() {
        assertEquals("rurik(831) hp=?>12 mp=?>0",
                CompanionRunRecorder.potionPart("rurik(831)", null, new int[]{12, 0}));
        assertEquals("yara(15) hp=20>? mp=25>?",
                CompanionRunRecorder.potionPart("yara(15)", new int[]{20, 25}, null));
    }

    @Test
    void theLineNamesTheRunItsQuestAndItsOutcome() {
        assertEquals("PQ potions: session=s-1 pq=lpq outcome=cleared companions=[a hp=1>0 mp=2>1, b hp=3>3 mp=4>4]",
                CompanionRunRecorder.potionLine("s-1", "lpq", "cleared",
                        List.of("a hp=1>0 mp=2>1", "b hp=3>3 mp=4>4")));
    }
}
