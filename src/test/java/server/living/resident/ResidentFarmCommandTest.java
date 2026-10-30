package server.living.resident;

import client.Character;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import server.knowledge.KnowledgeTestKit;
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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * "lisa farm steelies x200" (docs/SERVER_KNOWLEDGE_PLAN.md 8.3): with server knowledge a farm order resolves players'
 * names and slang to the item, asks back on a tie and places no order then; an unknown name keeps the old line.
 */
class ResidentFarmCommandTest {
    private static final int OWNER = 7;
    private static final int STEELY = 2070005;
    private static final int RED_POTION = 2000000;

    private final LivingWorld world = mock(LivingWorld.class);
    private final RosterService roster = mock(RosterService.class);
    private final CompanionChatVerbs verbs = mock(CompanionChatVerbs.class);
    private final Character owner = mock(Character.class);
    private final Persona lisa = new Persona("lisa", "Lisa", "", "THIEF", new Content.LevelBand(30, 40), Content.Strength.ROOKIE,
            null, "relaxed-v1", null, "kerning-city", List.of(), false, null, List.of(), null, List.of(), List.of(), Map.of(),
            -1, -1, -1, List.of(), 0, Look.NONE, "test");
    private ResidentDialogue dialogue;

    @BeforeEach
    void wire() {
        LivingContent content = mock(LivingContent.class);
        when(world.content()).thenReturn(content);
        when(world.roster()).thenReturn(roster);
        when(world.chatVerbs()).thenReturn(verbs);
        when(content.personaByName("Lisa")).thenReturn(lisa);
        when(owner.getId()).thenReturn(OWNER);
        when(owner.getName()).thenReturn("Ann");
        when(roster.entry(OWNER, "lisa")).thenReturn(new RosterEntry(OWNER, "lisa", RosterState.BENCHED, Assignment.TRAIN, 0, 0,
                103000000, 0L, 0L, 0L, 1_000L, 1_000L, 1_000L, 0, null));
        when(roster.ownerOf("lisa")).thenReturn(OWNER);
        when(roster.cachedOwnerOf("lisa")).thenReturn(OWNER); // whose a whisper is, from memory
        when(roster.assign(any(), any(), any(), anyInt(), anyInt())).thenReturn(new RosterResult.Ok("ok"));
        when(roster.delegate(any(), any(), any(), anyInt(), anyInt(), anyInt())).thenReturn(new RosterResult.Ok("ok"));
        dialogue = new ResidentDialogue(world);
    }

    @Test
    void slangNamesTheItemAndTheCountStays() throws Exception {
        try (AutoCloseable seed = KnowledgeTestKit.publishSeed()) {
            assertTrue(dialogue.onWhisper(owner, "Lisa", "farm steelies x200"));
            run("roster.assign");
            verify(roster).assign(owner, lisa, Assignment.FARM_ITEM, STEELY, 200);
        }
    }

    @Test
    void wzNamesResolveAsPlayersTypeThem() throws Exception {
        try (AutoCloseable seed = KnowledgeTestKit.publishSeed()) {
            assertTrue(dialogue.onWhisper(owner, "Lisa", "farm 50 pig heads"));
            assertTrue(dialogue.onWhisper(owner, "Lisa", "farm snail shells"));
            run("roster.assign");
            verify(roster).assign(owner, lisa, Assignment.FARM_ITEM, 4000017, 50);
            verify(roster).assign(owner, lisa, Assignment.FARM_ITEM, 4000019, 0);
        }
    }

    @Test
    void goFarmDelegatesTheResolvedItem() throws Exception {
        try (AutoCloseable seed = KnowledgeTestKit.publishSeed()) {
            assertTrue(dialogue.onWhisper(owner, "Lisa", "go farm red pots"));
            run("roster.delegate");
            verify(roster).delegate(owner, lisa, Assignment.FARM_ITEM, RED_POTION, 0, 0);
        }
    }

    @Test
    void aTieAsksBackAndPlacesNoOrder() throws Exception {
        try (AutoCloseable seed = KnowledgeTestKit.publishSeed()) {
            assertTrue(dialogue.onWhisper(owner, "Lisa", "farm glove att"));
            ArgumentCaptor<String> said = ArgumentCaptor.forClass(String.class);
            verify(owner).dropMessage(eq(5), said.capture());
            assertTrue(said.getValue().startsWith("Lisa: glove att ") && said.getValue().endsWith("?"), said.getValue());
            assertTrue(said.getValue().contains(" or "), said.getValue());
            verify(verbs, never()).say(any(), anyString(), any());
        }
    }

    @Test
    void anUnknownNameKeepsTheOldLine() throws Exception {
        try (AutoCloseable seed = KnowledgeTestKit.publishSeed()) {
            assertTrue(dialogue.onWhisper(owner, "Lisa", "farm qwzx"));
            verify(owner).dropMessage(5, "Lisa: i don't know what 'qwzx' is.");
            verify(verbs, never()).say(any(), anyString(), any());
        }
    }

    /** Runs the verbs the dialogue queued on companion-ops. */
    @SuppressWarnings("unchecked")
    private void run(String name) {
        ArgumentCaptor<Supplier<String>> verb = ArgumentCaptor.forClass(Supplier.class);
        verify(verbs, atLeastOnce()).say(eq(owner), eq(name), verb.capture());
        verb.getAllValues().forEach(Supplier::get);
    }
}
