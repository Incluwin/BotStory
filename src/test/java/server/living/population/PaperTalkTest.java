package server.living.population;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.integration.AgentPresence;
import server.living.population.paper.PaperDesk;
import server.living.population.paper.PaperIssue;
import server.living.population.paper.PaperTalk;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Citizens in town on issue day (docs/WORLD_SYSTEMS_PLAN.md N1.3, live check P4): the top three headlines, each at most
 * once per town map per hour, only with a player there to hear; a known player named in the issue hears it once.
 */
class PaperTalkTest {
    private static final int HENESYS = 100000000;
    private static final int FIELD = 104000100;
    private static final long HOUR = 3_600_000L;

    private final PaperDesk desk = mock(PaperDesk.class);
    private final PersonMemories memories = mock(PersonMemories.class);
    private final List<ChatterEngine.Event> said = new ArrayList<>();
    private final List<Character> onMap = new ArrayList<>();
    private boolean speaks = true;
    private CitizenSession lia;
    private Character ann;
    private MockedStatic<AgentPresence> agents;
    private PaperTalk talk;

    private static PaperIssue.Story story(String gist, int weight, String... names) {
        return new PaperIssue.Story("town.x", weight, "H " + gist, "B", gist, List.of(names));
    }

    @BeforeEach
    void setUp() {
        PaperIssue issue = new PaperIssue(20000, 0L, List.of(
                new PaperIssue.Section("arena", "Arena", List.of(story("ann won", 100, "Ann", "Ben"))),
                new PaperIssue.Section("town", "Town", List.of(story("cole wed", 70, "Cole"), story("dee hit 70", 35),
                        story("eli hit 70", 20)))));
        when(desk.on()).thenReturn(true);
        when(desk.fresh(anyLong())).thenReturn(Optional.of(issue));
        Citizen citizen = new Citizen(1, "Lia", "evening", 0, 0, "victoria", "hunter", "relaxed-v1", "default", 1.0d, 0, 1,
                new LinkedHashSet<>(), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
        lia = new CitizenSession(citizen, 0L, 24 * HOUR, false);
        lia.mapId = HENESYS;
        ann = mock(Character.class);
        when(ann.getName()).thenReturn("Ann");
        when(ann.getId()).thenReturn(7);
        when(ann.getJob()).thenReturn(client.Job.FIGHTER);
        agents = mockStatic(AgentPresence.class);
        talk = new PaperTalk(desk, mapId -> mapId == HENESYS, session -> onMap, event -> {
            if (speaks) {
                said.add(event);
            }
            return speaks;
        }, memories);
    }

    @AfterEach
    void tearDown() {
        agents.close();
    }

    private List<String> gists() {
        return said.stream().map(event -> event.slots().getOrDefault("gist", event.trigger())).toList();
    }

    @Test
    void theTopThreeHeadlinesOncePerTownMapPerHour() {
        Character stranger = mock(Character.class);
        when(stranger.getName()).thenReturn("Zed");
        onMap.add(stranger);
        long now = 10 * HOUR;
        assertTrue(talk.talk(lia, now));
        assertTrue(talk.talk(lia, now + 20_000L));
        assertTrue(talk.talk(lia, now + 40_000L));
        assertFalse(talk.talk(lia, now + 60_000L), "all three said this hour");
        assertEquals(List.of("ann won", "cole wed", "dee hit 70"), gists(), "heaviest first; the fourth never");
        assertEquals("paper.headline", said.get(0).trigger());
        assertTrue(talk.talk(lia, now + HOUR));
        assertEquals("ann won", gists().get(3), "an hour on, again");
    }

    @Test
    void notOutOfTownNorWithNobodyToHearNorAfterTheFirstDay() {
        assertFalse(talk.talk(lia, HOUR), "no player on the map");
        onMap.add(mock(Character.class));
        lia.mapId = FIELD;
        assertFalse(talk.talk(lia, HOUR));
        lia.mapId = HENESYS;
        when(desk.fresh(anyLong())).thenReturn(Optional.empty());
        assertFalse(talk.talk(lia, HOUR));
        assertTrue(said.isEmpty());
    }

    @Test
    void aBotOnTheMapIsNoAudience() {
        Character bot = mock(Character.class);
        agents.when(() -> AgentPresence.isAgent(bot)).thenReturn(true);
        onMap.add(bot);
        assertFalse(talk.talk(lia, HOUR));
    }

    @Test
    void aKnownPlayerInThePaperHearsItOnce() {
        onMap.add(ann);
        when(memories.on()).thenReturn(true);
        when(memories.peek(1, 7)).thenReturn(mock(PersonMemory.class));
        assertTrue(talk.talk(lia, HOUR));
        assertEquals("paper.named", said.get(0).trigger());
        assertTrue(talk.talk(lia, HOUR + 20_000L));
        assertEquals("paper.headline", said.get(1).trigger(), "then the headlines, not her name again");
    }

    @Test
    void aStrangerInThePaperIsNotGreeted() {
        onMap.add(ann);
        when(memories.on()).thenReturn(true);
        assertTrue(talk.talk(lia, HOUR));
        assertEquals("paper.headline", said.get(0).trigger());
    }

    @Test
    void aLineHeldBackByCooldownsIsTriedAgain() {
        onMap.add(mock(Character.class));
        speaks = false;
        assertFalse(talk.talk(lia, HOUR));
        speaks = true;
        assertTrue(talk.talk(lia, HOUR + 20_000L));
        assertEquals(List.of("ann won"), gists());
    }
}
