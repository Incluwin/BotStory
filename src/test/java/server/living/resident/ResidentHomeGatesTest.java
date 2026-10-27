package server.living.resident;

import client.Character;
import client.QuestStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import server.living.config.ContentLoader;
import server.living.config.LivingContent;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mushroom Castle's door (scripts/portal/TD_MC_first.js) checks quest 2260: a Resident who lives behind it counts it as
 * done, so the route graph can take a Mushroom Castle companion home (docs/REGIONAL_RESIDENTS_PLAN.md section 11.6).
 */
class ResidentHomeGatesTest {
    private static final LivingContent CONTENT = ContentLoader.load(Path.of("scripts/config/living-world"));

    @Test
    void aMushroomCastleResidentHasBeenThroughTheDoor() {
        Character percival = mock(Character.class);
        when(percival.getQuestStatus(2260)).thenReturn((byte) 0);

        assertTrue(ResidentHomeGates.open(percival, CONTENT.persona("percival")));

        ArgumentCaptor<QuestStatus> status = ArgumentCaptor.forClass(QuestStatus.class);
        verify(percival).updateQuestStatus(status.capture());
        assertEquals(2260, status.getValue().getQuestID());
        assertEquals(QuestStatus.Status.COMPLETED, status.getValue().getStatus());
    }

    @Test
    void onceDoneOrLivingElsewhereNothingChanges() {
        Character fern = mock(Character.class);
        when(fern.getQuestStatus(2260)).thenReturn((byte) QuestStatus.Status.COMPLETED.getId());
        assertFalse(ResidentHomeGates.open(fern, CONTENT.persona("fern")));
        verify(fern, never()).updateQuestStatus(any());

        Character nerys = mock(Character.class);
        assertFalse(ResidentHomeGates.open(nerys, CONTENT.persona("nerys")), "Florina Beach has no gated door");
        verify(nerys, never()).updateQuestStatus(any());
    }
}
