package server.agents.capabilities.partyquest.apq;

import client.Character;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * '!apqtest' (APQ plan A3, A5b): "married" pairs the party's unmarried members a man with a woman while both are left,
 * and a checkpoint leaves the event as the stock stages before it would: their "<n>stageclear" marks, and for the
 * gates the clock Amos sets when the passes are handed in.
 */
class AgentApqTestServiceTest {

    @Test
    void theUnmarriedArePairedManWithWomanFirstThenWhoeverIsLeft() {
        Character ben = person("Ben", 0, false);
        Character cal = person("Cal", 0, false);
        Character dan = person("Dan", 0, false);
        Character una = person("Una", 1, false);
        Character vee = person("Vee", 0, true);
        Character wyn = person("Wyn", 1, false);

        List<Character[]> couples = AgentApqTestService.couples(List.of(ben, cal, dan, una, vee, wyn));

        assertEquals(List.of("Ben+Una", "Cal+Wyn"), names(couples).subList(0, 2));
        assertEquals(2, couples.size(), "Dan is left without a partner; Vee is married already");
    }

    @Test
    void twoOfTheSameGenderLeftArePairedTooForAnAllCouplesParty() {
        List<Character[]> couples = AgentApqTestService.couples(List.of(person("Ben", 0, false),
                person("Cal", 0, false), person("Una", 1, false), person("Dan", 0, false)));

        assertEquals(List.of("Ben+Una", "Cal+Dan"), names(couples));
    }

    @Test
    void aCheckpointMarksEveryEarlierStageClearAsItsNpcsWould() {
        EventInstanceManager event = mock(EventInstanceManager.class);
        when(event.getTimeLeft()).thenReturn(60L * 60_000L);

        AgentApqTestService.prepare(event, AgentApqTestService.Checkpoint.BOSS);

        for (int stage = 1; stage <= 5; stage++) {
            verify(event).setProperty(stage + "stageclear", "true");
        }
        verify(event, never()).setProperty("6stageclear", "true");
        verify(event, never()).restartEventTimer(anyLong());
    }

    @Test
    void theGatesCheckpointGivesFourMinutesAndKeepsTheRestAsHandingInThePassesDoes() {
        EventInstanceManager event = mock(EventInstanceManager.class);
        when(event.getTimeLeft()).thenReturn(40L * 60_000L);

        AgentApqTestService.prepare(event, AgentApqTestService.Checkpoint.GATES);

        verify(event).setProperty("timeLeft", Long.toString(40L * 60_000L));
        verify(event).restartEventTimer(AgentApqTestService.GATES_TIMER_MS);

        EventInstanceManager late = mock(EventInstanceManager.class);
        when(late.getTimeLeft()).thenReturn(4L * 60_000L);
        AgentApqTestService.prepare(late, AgentApqTestService.Checkpoint.GATES);
        verify(late, never()).restartEventTimer(anyLong());
        verify(late, never()).setProperty(eq("timeLeft"), anyString());
    }

    @Test
    void checkpointsAreNamedByTheirStage() {
        assertSame(AgentApqTestService.Checkpoint.ROPES, AgentApqTestService.Checkpoint.of("Ropes"));
        assertEquals(AgentApqDefinition.ROPE_MAPS.get(0), AgentApqTestService.Checkpoint.ROPES.mapId);
        assertEquals(AgentApqDefinition.BOSS_MAP, AgentApqTestService.Checkpoint.of("boss").mapId);
        assertNull(AgentApqTestService.Checkpoint.of("mirror"), "the Mirror is where every run starts");
    }

    private static Character person(String name, int gender, boolean married) {
        Character character = mock(Character.class);
        when(character.getName()).thenReturn(name);
        when(character.getGender()).thenReturn(gender);
        when(character.isMarried()).thenReturn(married);
        when(character.getPartnerId()).thenReturn(married ? 99 : -1);
        return character;
    }

    private static List<String> names(List<Character[]> couples) {
        List<String> names = new ArrayList<>();
        for (Character[] couple : couples) {
            names.add(couple[0].getName() + "+" + couple[1].getName());
        }
        return names;
    }
}
