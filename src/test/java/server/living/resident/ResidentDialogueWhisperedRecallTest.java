package server.living.resident;

import client.Character;
import config.LivingWorldConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import server.agents.capabilities.dialogue.jev.AgentChatIntentRuntime;
import server.living.LivingWorld;
import server.living.config.Content;
import server.living.config.Content.Look;
import server.living.config.Content.Persona;
import server.living.config.ContentLoader;
import server.living.config.LivingContent;
import server.living.phrases.PhraseMatcher;
import server.living.roster.CompanionChatVerbs;
import server.living.roster.RosterResult;
import server.living.roster.RosterService;
import server.living.store.LivingStore;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Fourth deploy review 2026-10-10: the owner whispered Tamsin, on a live bench slice, "tamsin come back", "come back
 * to me", "call tamsin" or "get over here", and she whispered back "can't from out here, call me back first.": the
 * whisper reached the roster's recall only in its nine exact words, with no name taken off (map chat takes a leading
 * name off), so the line went on to the whisper bridge, which a live slice refuses. A whispered call, its name first
 * or last, and a phrase-pack "follow me" or "come here" to a companion that is not out with its owner now call it;
 * the same lines to one out with its owner stay the Agent's.
 */
class ResidentDialogueWhisperedRecallTest {
    private static final int OWNER = 7;

    private final LivingWorld world = mock(LivingWorld.class);
    private final LivingContent content = mock(LivingContent.class);
    private final RosterService roster = mock(RosterService.class);
    private final CompanionChatVerbs verbs = mock(CompanionChatVerbs.class);
    private final Character owner = mock(Character.class);
    private final Persona tamsin = new Persona("tamsin", "Tamsin", "", "THIEF", new Content.LevelBand(30, 40),
            Content.Strength.ROOKIE, null, "relaxed-v1", null, "henesys", List.of(), false, null, List.of(), null,
            List.of(), List.of(), Map.of(), -1, -1, -1, List.of(), 0, Look.NONE, "test");
    private ResidentDialogue dialogue;

    @BeforeEach
    void wire() {
        when(world.content()).thenReturn(content);
        when(world.roster()).thenReturn(roster);
        when(world.chatVerbs()).thenReturn(verbs);
        when(world.store()).thenReturn(mock(LivingStore.class));
        when(world.config()).thenReturn(new LivingWorldConfig());
        when(content.phrases()).thenReturn(ContentLoader.load(Path.of("scripts/config/living-world")).phrases());
        PhraseMatcher phrases = new PhraseMatcher(world);
        phrases.reload();
        when(world.phrases()).thenReturn(phrases);
        when(content.personaByName("Tamsin")).thenReturn(tamsin);
        when(content.personaByName("tamsin")).thenReturn(tamsin);
        when(owner.getId()).thenReturn(OWNER);
        when(owner.getName()).thenReturn("Bossfriend");
        when(roster.ownerOf("tamsin")).thenReturn(OWNER);
        when(roster.cachedOwnerOf("tamsin")).thenReturn(OWNER);
        when(roster.call(owner, tamsin)).thenReturn(new RosterResult.Ok("Tamsin is on the way."));
        dialogue = new ResidentDialogue(world);
    }

    @AfterEach
    void unwire() {
        AgentChatIntentRuntime.installPrejudge(null);
        AgentChatIntentRuntime.installLearner(null);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"tamsin come back", "Tamsin, come back", "come back tamsin", "come back to me",
            "come back here", "come here tamsin", "call tamsin", "get over here", "follow me", "lets go", "come to me",
            "come on tamsin", "come back"})
    void aWhisperedCallToACompanionOnItsBenchSliceCallsIt(String said) {
        for (RosterState state : new RosterState[] {RosterState.BENCHED, RosterState.AWAY}) {
            state(state);

            assertTrue(dialogue.onWhisper(owner, "Tamsin", said), state + ": the roster takes it, no refusal");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Supplier<String>> verb = ArgumentCaptor.forClass(Supplier.class);
            verify(verbs).say(eq(owner), eq("roster.call"), verb.capture());
            verb.getValue().get();
            verify(roster).call(owner, tamsin);
            clearInvocations(verbs, roster);
        }
    }

    /** Out with its owner, "follow me" and "lets go" are its Agent's own (it follows); a call still calls. */
    @Test
    void theSameLinesToACompanionOutWithItsOwnerStayTheAgents() {
        state(RosterState.ACTIVE);

        assertFalse(dialogue.onWhisper(owner, "Tamsin", "follow me"));
        assertFalse(dialogue.onWhisper(owner, "Tamsin", "lets go"));
        assertFalse(dialogue.onWhisper(owner, "Tamsin", "get over here"));

        verify(verbs, never()).say(any(), anyString(), any());
        verify(roster, never()).call(any(), any());
    }

    /** Anything else still goes on to the bridge (which tells the owner why it cannot), and is no call. */
    @Test
    void anotherWhisperIsNoRecall() {
        state(RosterState.BENCHED);

        assertFalse(dialogue.onWhisper(owner, "Tamsin", "how was ur day"));
        assertFalse(dialogue.onWhisper(owner, "Tamsin", "stats"));
        assertFalse(dialogue.onWhisper(owner, "Tamsin", "where are you"));
        assertFalse(dialogue.onWhisper(owner, "Tamsin", "stop"));

        verify(verbs, never()).say(any(), anyString(), any());
        verify(roster, never()).call(any(), any());
    }

    /**
     * Whisper review P1 (2026-10-10): every whisper to a persona read the roster, once for its roster commands and
     * again for its recall, another player's whisper too. The owner's now reads its row once; another player's none.
     */
    @Test
    void theOwnersWhisperReadsItsRowOnceAndAnotherPlayersNone() {
        state(RosterState.BENCHED);

        assertTrue(dialogue.onWhisper(owner, "Tamsin", "come back to me"));
        verify(roster).entry(OWNER, "tamsin");

        clearInvocations(roster);
        Character stranger = mock(Character.class);
        when(stranger.getId()).thenReturn(OWNER + 1);
        when(stranger.getName()).thenReturn("Passerby");
        when(world.quests()).thenReturn(mock(server.living.quest.QuestLedger.class));
        when(world.residents()).thenReturn(mock(ResidentRuntime.class));
        when(world.arcs()).thenReturn(mock(server.living.arc.ArcService.class));
        when(world.bonds()).thenReturn(mock(server.living.bond.BondService.class));
        assertTrue(dialogue.onWhisper(stranger, "Tamsin", "hi"), "Tamsin answers a stranger's whisper");
        verify(roster, never()).entry(anyInt(), anyString());
        verify(roster, never()).ownerOf(anyString());
    }

    private void state(RosterState state) {
        when(roster.entry(OWNER, "tamsin")).thenReturn(new RosterEntry(OWNER, "tamsin", state, Assignment.FARM_MESO, 0,
                0, 100000000, 0L, 0L, 0L, 1_000L, 1_000L, 1_000L, 0, null));
    }
}
