package server.living.resident;

import client.Character;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import server.agents.capabilities.dialogue.AgentChatUtilityFlow;
import server.agents.commands.AgentCommandTargetResolver;
import server.agents.commands.AgentTargetedCommandMatch;
import server.agents.runtime.AgentRuntimeEntry;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * "tamsin sell junk" (live 2026-10-10, docs/LIVING_RPG_PLAN.md 12.2): to a companion out with its owner it is the
 * Agent's own sell-now command ("Supplies and loot"), so the roster hands the line on and leaves the bench job alone;
 * to a benched or away companion it stays the bench job SELL, and "sell the haul" is the bench job either way.
 */
class ResidentDialogueSellNowTest {
    private static final int OWNER = 7;

    private final LivingWorld world = mock(LivingWorld.class);
    private final RosterService roster = mock(RosterService.class);
    private final CompanionChatVerbs verbs = mock(CompanionChatVerbs.class);
    private final Character owner = mock(Character.class);
    private final Persona tamsin = new Persona("tamsin", "Tamsin", "", "THIEF", new Content.LevelBand(30, 40),
            Content.Strength.ROOKIE, null, "relaxed-v1", null, "henesys", List.of(), false, null, List.of(), null,
            List.of(), List.of(), Map.of(), -1, -1, -1, List.of(), 0, Look.NONE, "test");
    private ResidentDialogue dialogue;

    @BeforeEach
    void wire() {
        LivingContent content = mock(LivingContent.class);
        when(world.content()).thenReturn(content);
        when(world.roster()).thenReturn(roster);
        when(world.chatVerbs()).thenReturn(verbs);
        when(verbs.logoutTurn(any(), anyString())).thenReturn(CompanionChatVerbs.LogoutTurn.NONE);
        when(content.personaByName("Tamsin")).thenReturn(tamsin);
        when(content.personaByName("tamsin")).thenReturn(tamsin);
        when(owner.getId()).thenReturn(OWNER);
        when(owner.getName()).thenReturn("Bossfriend");
        when(roster.ownerOf("tamsin")).thenReturn(OWNER);
        when(roster.assign(any(), any(), any(), anyInt(), anyInt())).thenReturn(new RosterResult.Ok("ok"));
        dialogue = new ResidentDialogue(world);
    }

    @Test
    void sellJunkToACompanionOutWithItsOwnerSellsNowAndKeepsTheBenchJob() {
        state(RosterState.ACTIVE);

        assertFalse(dialogue.onMapChat(owner, "Tamsin sell junk"), "the line goes on to the Agent routes");
        assertFalse(dialogue.onWhisper(owner, "Tamsin", "sell junk"), "the whisper goes on to the Agent bridge");
        assertFalse(dialogue.onWhisper(owner, "Tamsin", "sell my junk"));
        assertFalse(dialogue.onWhisper(owner, "Tamsin", "sell trash"));

        verify(roster, never()).assign(any(), any(), any(), anyInt(), anyInt());
        verify(verbs, never()).say(any(), anyString(), any());
        assertEquals(List.of("sellTrash"), agentRouteCalls("Tamsin sell junk"));
    }

    @Test
    void sellJunkToABenchedOrAwayCompanionIsStillItsBenchJob() {
        for (RosterState state : new RosterState[] {RosterState.BENCHED, RosterState.AWAY}) {
            state(state);

            assertTrue(dialogue.onMapChat(owner, "Tamsin sell junk"), state.name());
        }
        run("roster.assign");
        verify(roster, times(2)).assign(owner, tamsin, Assignment.SELL, 0, 0);
    }

    @Test
    void sellTheHaulIsTheBenchJobOutOrBenched() {
        for (RosterState state : new RosterState[] {RosterState.ACTIVE, RosterState.BENCHED}) {
            state(state);

            assertTrue(dialogue.onMapChat(owner, "Tamsin sell the haul"), state.name());
            assertTrue(dialogue.onWhisper(owner, "Tamsin", "sell and come back"), state.name());
        }
        run("roster.assign");
        verify(roster, times(4)).assign(owner, tamsin, Assignment.SELL, 0, 0);
    }

    private void state(RosterState state) {
        when(roster.entry(OWNER, "tamsin")).thenReturn(new RosterEntry(OWNER, "tamsin", state, Assignment.TRAIN, 0, 0,
                100000000, 0L, 0L, 0L, 1_000L, 1_000L, 1_000L, 0, null));
    }

    /** What the Agent route does with the owner's line once the roster hands it on: the named Agent's own flow. */
    private static List<String> agentRouteCalls(String line) {
        Character bot = mock(Character.class);
        when(bot.getName()).thenReturn("Tamsin");
        AgentRuntimeEntry entry = new AgentRuntimeEntry(bot, null, null);
        AgentTargetedCommandMatch<AgentRuntimeEntry> match =
                AgentCommandTargetResolver.resolveTargetedAgent(List.of(entry), line);
        assertEquals(entry, match.entry());
        List<String> calls = new ArrayList<>();
        AgentChatUtilityFlow.handle(match.commandText(), new AgentChatUtilityFlow.UtilityCallbacks() {
            @Override
            public void tradeInvite() {
                calls.add("tradeInvite");
            }

            @Override
            public void sellTrash() {
                calls.add("sellTrash");
            }

            @Override
            public void makeCrystals() {
                calls.add("makeCrystals");
            }

            @Override
            public void disassembleTrash() {
                calls.add("disassembleTrash");
            }
        });
        return calls;
    }

    /** Runs the verbs the dialogue queued on companion-ops. */
    @SuppressWarnings("unchecked")
    private void run(String name) {
        ArgumentCaptor<Supplier<String>> verb = ArgumentCaptor.forClass(Supplier.class);
        verify(verbs, atLeastOnce()).say(eq(owner), eq(name), verb.capture());
        verb.getAllValues().forEach(Supplier::get);
    }
}
