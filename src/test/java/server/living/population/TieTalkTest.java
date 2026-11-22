package server.living.population;

import config.PopulationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.MockedStatic;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import org.junit.jupiter.api.Test;
import server.living.crew.TieKind;
import server.living.population.CitizenTies.Tie;
import server.living.population.TieTalk.Catalog;
import server.living.population.TieTalk.Exchange;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tie small talk (docs/WORLD_SYSTEMS_PLAN.md N2.2): the shipped chatter/ties.yaml parses into friend and rival
 * exchanges and a Resident line for every tie kind; an exchange is said only between two tied citizens on one town map,
 * at most once a pair in half an hour; Resident lines need the switch.
 */
class TieTalkTest {
    private static final Path FILE = Path.of("scripts/config/population").resolve(TieTalk.FILE);
    private static final int TOWN = 100_000_000;

    private final Population population = mock(Population.class);
    private final PopulationConfig config = new PopulationConfig();
    private final CitizenTies ties = mock(CitizenTies.class);
    private final FieldCatalog fields = mock(FieldCatalog.class);
    private final PresenceScheduler presence = mock(PresenceScheduler.class);
    private final ChatterEngine chatter = mock(ChatterEngine.class);
    private final VoicePacks voices = mock(VoicePacks.class);
    private CitizenSession ann;
    private CitizenSession bo;
    private MockedStatic<AgentRuntimeIdentityRuntime> identity;

    @BeforeEach
    void twoTiedCitizensInHenesys() {
        config.CITIZEN_TIES_ENABLED = true;
        config.CONTENT_DIR = "scripts/config/population";
        when(population.config()).thenReturn(config);
        when(population.ties()).thenReturn(ties);
        when(population.fields()).thenReturn(fields);
        when(population.presence()).thenReturn(presence);
        when(population.chatter()).thenReturn(chatter);
        when(population.voices()).thenReturn(voices);
        when(voices.render(any(), anyString(), anyLong())).thenAnswer(call -> call.getArgument(1));
        when(voices.delayMs(any(), anyString())).thenReturn(1000L);
        when(fields.isTown(TOWN)).thenReturn(true);
        when(chatter.mayReply(any(), anyLong())).thenReturn(true);
        ann = CitizenTiesTest.session(1);
        bo = CitizenTiesTest.session(2);
        ann.mapId = TOWN;
        bo.mapId = TOWN;
        when(presence.session(2)).thenReturn(bo);
        bo.entry = mock(AgentRuntimeEntry.class);
        identity = mockStatic(AgentRuntimeIdentityRuntime.class);
        identity.when(() -> AgentRuntimeIdentityRuntime.bot(any())).thenReturn(mock(client.Character.class));
    }

    @AfterEach
    void close() {
        identity.close();
    }

    @Test
    void theShippedCatalogHasBothKindsAndALineForEveryResidentTie() {
        Catalog catalog = TieTalk.load(FILE);
        assertTrue(catalog.exchanges().stream().anyMatch(e -> e.ties().contains(TieKind.FRIEND)));
        assertTrue(catalog.exchanges().stream().anyMatch(e -> e.ties().contains(TieKind.RIVAL)));
        for (String section : List.of("idle", "about")) {
            for (TieKind kind : TieKind.values()) {
                if (kind != TieKind.CREWMATE) {
                    assertFalse(catalog.resident(section, kind).isEmpty(), section + " " + kind);
                }
            }
        }
        for (Exchange exchange : catalog.exchanges()) {
            for (String line : concat(exchange.call(), exchange.answer())) {
                assertTrue(line.chars().allMatch(c -> c >= 0x20 && c <= 0x7e), line);
                assertTrue(line.chars().noneMatch(Character::isDigit), "no digits: " + line);
                assertTrue(line.replace("{caller}", "").replace("{answerer}", "").indexOf('{') < 0, "only known slots: " + line);
            }
        }
    }

    private static List<String> concat(List<String> a, List<String> b) {
        return java.util.stream.Stream.concat(a.stream(), b.stream()).toList();
    }

    @Test
    void anExchangeNeedsItsKindAndItsStrength() {
        TieTalk talk = new TieTalk(population);
        List<Exchange> exchanges = List.of(new Exchange("f", List.of(TieKind.FRIEND), 10, 10, List.of("c"), List.of("a")),
                new Exchange("close", List.of(TieKind.FRIEND), 40, 10, List.of("c"), List.of("a")));
        for (int i = 0; i < 30; i++) {
            assertEquals("f", talk.pick(exchanges, TieKind.FRIEND, 20).id());
        }
        assertNull(talk.pick(exchanges, TieKind.RIVAL, 90));
        assertNull(talk.pick(exchanges, TieKind.FRIEND, 5));
    }

    private void tied(double strength) {
        when(ties.of(1)).thenReturn(List.of(new Tie(1, 2, CitizenTies.Kind.FRIEND, (int) strength, System.currentTimeMillis())));
    }

    private boolean talkUntilItSpeaks(TieTalk talk, long now) {
        for (int i = 0; i < 200; i++) {
            if (talk.talk(ann, now)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void twoFriendsInTownTradeACallAndAnAnswerOnceAPairAHalfHour() {
        tied(30);
        TieTalk talk = new TieTalk(population);
        long now = System.currentTimeMillis();
        assertTrue(talkUntilItSpeaks(talk, now));
        verify(chatter).answer(eq(ann), anyString(), eq(1000L), eq(null));
        verify(chatter).answer(eq(bo), anyString(), eq(1000L + TieTalk.ANSWER_PAUSE_MS + 1000L), eq(null));
        assertFalse(talkUntilItSpeaks(talk, now + 60_000L), "the same pair inside half an hour");
        assertTrue(talkUntilItSpeaks(talk, now + TieTalk.PAIR_GAP_MS));
    }

    @Test
    void notOnAFieldNotWhenTheOtherIsElsewhereNotForAWeakTieAndNotWhenOff() {
        tied(30);
        TieTalk talk = new TieTalk(population);
        long now = System.currentTimeMillis();
        bo.mapId = TOWN + 1;
        assertFalse(talkUntilItSpeaks(talk, now), "on another map");
        bo.mapId = TOWN;
        ann.mapId = 104_000_100;
        bo.mapId = 104_000_100;
        assertFalse(talkUntilItSpeaks(talk, now), "on a field");
        ann.mapId = TOWN;
        bo.mapId = TOWN;
        tied(5);
        assertFalse(talkUntilItSpeaks(talk, now), "a tie under KNOWN");
        tied(30);
        config.CITIZEN_TIES_ENABLED = false;
        assertFalse(talkUntilItSpeaks(talk, now), "off");
        verify(chatter, never()).answer(any(), anyString(), anyLong(), any());
    }

    @Test
    void residentLinesNameTheOtherAndNeedTheSwitch() {
        TieTalk talk = new TieTalk(population);
        String line = talk.residentLine("idle", TieKind.OWED, "Brand");
        assertNotNull(line);
        assertTrue(line.contains("Brand"), line);
        assertNull(talk.residentLine("idle", TieKind.CREWMATE, "Brand"));
        config.CITIZEN_TIES_ENABLED = false;
        assertNull(talk.residentLine("idle", TieKind.OWED, "Brand"));
    }
}
