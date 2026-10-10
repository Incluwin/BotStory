package server.living.resident;

import client.Character;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import server.living.LivingWorld;
import server.living.config.Content;
import server.living.config.Content.Look;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.roster.CompanionChatVerbs;
import server.living.roster.RosterResult;
import server.living.roster.RosterService;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Deploy review 2026-10-10: a hired citizen (hire:&lt;id&gt;) on a live bench slice answered its owner's whispered "come
 * back" with "call me back first": the whisper looked names up among the authored personas only, so the call never
 * reached the roster. A whisper now finds the owner's hire by its own name, as map chat does.
 */
class ResidentDialogueHireWhisperTest {
    private static final int OWNER = 7;
    private static final String HIRE_ID = "hire:543";

    private final LivingWorld world = mock(LivingWorld.class);
    private final LivingContent content = mock(LivingContent.class);
    private final RosterService roster = mock(RosterService.class);
    private final CompanionChatVerbs verbs = mock(CompanionChatVerbs.class);
    private final Character owner = mock(Character.class);
    private final Character stranger = mock(Character.class);
    private final Persona wren = new Persona(HIRE_ID, "Wren", "", "WARRIOR", new Content.LevelBand(29, 29),
            Content.Strength.ROOKIE, null, "relaxed-v1", null, "henesys", List.of(), false, null, List.of(), null,
            List.of(), List.of(), Map.of(), -1, -1, -1, List.of(), 0, Look.NONE, "test");
    private ResidentDialogue dialogue;

    @BeforeEach
    void wire() {
        when(world.content()).thenReturn(content);
        when(world.roster()).thenReturn(roster);
        when(world.chatVerbs()).thenReturn(verbs);
        when(content.persona(HIRE_ID)).thenReturn(wren); // a hire is no authored persona: personaByName knows nothing
        when(owner.getId()).thenReturn(OWNER);
        when(owner.getName()).thenReturn("Ohboy");
        when(stranger.getId()).thenReturn(30);
        when(stranger.getName()).thenReturn("Bob");
        RosterEntry row = new RosterEntry(OWNER, HIRE_ID, RosterState.BENCHED, Assignment.FARM_MESO, 0, 0, 100000000, 0L,
                0L, 0L, 1_000L, 1_000L, 1_000L, 0, null);
        when(roster.hasCompanions(owner)).thenReturn(true);
        when(roster.roster(owner)).thenReturn(List.of(row));
        when(roster.ownerOf(HIRE_ID)).thenReturn(OWNER);
        when(roster.entry(OWNER, HIRE_ID)).thenReturn(row);
        when(roster.call(owner, wren)).thenReturn(new RosterResult.Ok("Wren is on the way."));
        dialogue = new ResidentDialogue(world);
    }

    @Test
    void anOwnersWhisperedComeBackCallsItsHireOffTheBench() {
        assertTrue(dialogue.onWhisper(owner, "Wren", "come back"), "the roster takes it: no refusal from the bridge");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Supplier<String>> verb = ArgumentCaptor.forClass(Supplier.class);
        verify(verbs).say(eq(owner), eq("roster.call"), verb.capture());
        verb.getValue().get();
        verify(roster).call(owner, wren);
    }

    @Test
    void anythingElseStillGoesOnToTheAgentBridgeAndAStrangersWhisperFindsNoHire() {
        assertFalse(dialogue.onWhisper(owner, "Wren", "how was ur day"), "the companion's Agent answers it");
        assertFalse(dialogue.onWhisper(stranger, "Wren", "come back"));

        verify(verbs, never()).say(any(), anyString(), any());
        verify(roster, never()).call(any(), any());
    }
}
