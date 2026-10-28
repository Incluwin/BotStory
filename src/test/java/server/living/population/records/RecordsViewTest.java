package server.living.population.records;

import client.Character;
import config.PopulationConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.living.population.Population;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The records page (docs/BOT_CONCEPT_PLAN.md F7.1, decision D13): a player's clear shows as the quest's first and
 * fastest; a faster party (citizens here) takes the fastest and the first stays; each viewer sees their own best; and
 * {@code @records} finds a quest by what players type for it.
 */
class RecordsViewTest {
    private static final String KPQ = "KerningPQ";
    private static final String BALROG = "BalrogBattle_Easy";
    private static final long NOW = 1_800_000_000_000L; // 2027-01-15 08:00 UTC
    private static final long MINUTE = 60_000L;

    private final Population population = mock(Population.class);
    private final ClearLogTest.FakePorts ports = new ClearLogTest.FakePorts();
    private final Character ann = mock(Character.class);
    private final Character bo = mock(Character.class);
    private ClearLog log;
    private RecordsView view;

    @BeforeEach
    void setUp() {
        when(population.config()).thenReturn(new PopulationConfig());
        log = new ClearLog(population, new ClearLogTest.MemoryStore(), ports, new QuestLabels(List.of(
                new QuestLabels.Quest(KPQ, "Kerning PQ", false, List.of("kpq")),
                new QuestLabels.Quest(BALROG, "Easy Balrog", true, List.of("balrog", "ebalrog")))));
        view = new RecordsView(log);
        when(ann.getId()).thenReturn(10);
        when(ann.getName()).thenReturn("Ann");
        when(bo.getId()).thenReturn(11);
        when(bo.getName()).thenReturn("Bo");
        ports.kinds.put(10, Clear.AS_PLAYER);
        ports.kinds.put(11, Clear.AS_PLAYER);
    }

    private Character citizen(int id, String name) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getName()).thenReturn(name);
        ports.kinds.put(id, Clear.AS_CITIZEN);
        return chr;
    }

    @Test
    void aPlayersBalrogClearIsTheFirstAndTheFastest() {
        log.cleared(List.of(ann, bo), BALROG, "b1", NOW - (16 * MINUTE + 5_000L), NOW);

        List<String> lines = view.details(ann, BALROG);

        assertEquals("#eEasy Balrog#n", lines.get(0));
        assertEquals("First clear: Ann, Bo (Jan 15)", lines.get(1));
        assertEquals("Fastest: #b16:05#k by Ann, Bo (Jan 15)", lines.get(2));
        assertEquals("Your best: 16:05", lines.get(3));
    }

    @Test
    void aFasterPartyTakesTheFastestTheFirstStays() {
        log.cleared(List.of(ann), BALROG, "b1", NOW - 16 * MINUTE, NOW);
        log.cleared(List.of(citizen(5001, "Kim"), citizen(5002, "Lu"), citizen(5003, "Mo")), BALROG, "b2",
                NOW + 3_600_000L, NOW + 3_600_000L + 14 * MINUTE + 20_000L);

        List<String> mine = view.details(ann, BALROG);
        List<String> his = view.details(bo, BALROG);

        assertEquals("First clear: Ann (Jan 15)", mine.get(1));
        assertEquals("Fastest: #b14:20#k by Kim, Lu, Mo (Jan 15)", mine.get(2));
        assertEquals("Your best: 16:00", mine.get(3));
        assertEquals("You have no time here yet.", his.get(3));
    }

    @Test
    void recordsInChatListEveryQuestOrOneByWhatPlayersType() {
        log.cleared(List.of(ann), KPQ, "k1", NOW - 9 * MINUTE, NOW);
        log.cleared(List.of(bo), BALROG, "b1", NOW - 15 * MINUTE, NOW);

        List<String> all = view.chat(ann, null);
        List<String> balrog = view.chat(ann, "Balrog");
        List<String> unknown = view.chat(ann, "nope");

        assertEquals(List.of("Kerning PQ: first Ann, fastest 9:00 Ann, yours 9:00",
                "Easy Balrog: first Bo, fastest 15:00 Bo"), all);
        assertEquals("Easy Balrog", balrog.get(0));
        assertTrue(balrog.get(2).startsWith("Fastest: 15:00 by Bo"));
        assertTrue(unknown.get(0).startsWith("No such quest or boss: nope"));
    }

    @Test
    void aQuestIsFoundByScriptLabelOrAlias() {
        QuestLabels labels = log.labels();

        assertEquals(BALROG, labels.find("ebalrog"));
        assertEquals(BALROG, labels.find("easy balrog"));
        assertEquals(KPQ, labels.find("KerningPQ"));
        assertEquals(null, labels.find("zakum"));
        assertEquals("ZakumBattle", labels.label("ZakumBattle"), "an unlisted script reads as itself");
    }
}
