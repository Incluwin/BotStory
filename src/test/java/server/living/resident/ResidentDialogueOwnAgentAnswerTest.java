package server.living.resident;

import client.Character;
import config.LivingWorldConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.dialogue.AgentChatPendingAction;
import server.agents.capabilities.dialogue.AgentPendingActionStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.LivingWorld;
import server.living.arc.ArcService;
import server.living.bond.BondService;
import server.living.bond.ContractService;
import server.living.config.Content;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.quest.QuestLedger;
import server.living.roster.CompanionChatVerbs;
import server.living.roster.RosterService;
import server.living.store.LivingStore;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Found 2026-09-23 ("did you mean X?" then "yes" did nothing): map chat reaches the living world before the Agent
 * routes, and in town the Resident nearest the owner took a plain "yes" as accepting its quest ("nothing to accept
 * right now."), so the Agent waiting on that answer never saw it. A plain yes or no while one of the speaker's own Agents
 * waits on their answer is now left to it; with none waiting, the Resident still takes it.
 */
class ResidentDialogueOwnAgentAnswerTest {
    private static final int OWNER = 7;
    private static final int TOWN = 100000000;

    private final LivingWorld world = mock(LivingWorld.class);
    private final ResidentRuntime residents = mock(ResidentRuntime.class);
    private final Character owner = mock(Character.class);
    private final Persona fenna = new Persona("fenna", "Fenna", "", "BOWMAN", new Content.LevelBand(20, 25),
            Content.Strength.ROOKIE, null, "relaxed-v1", null, "henesys", List.of(), false, null, List.of(), null,
            List.of(), List.of(), Map.of(), -1, -1, -1, List.of(), 0, Content.Look.NONE, "test");
    private AgentRuntimeEntry companion;
    private ResidentDialogue dialogue;

    @BeforeEach
    void wire() {
        LivingContent content = mock(LivingContent.class);
        CompanionChatVerbs verbs = mock(CompanionChatVerbs.class);
        when(verbs.logoutTurn(any(), anyString())).thenReturn(CompanionChatVerbs.LogoutTurn.NONE);
        when(world.content()).thenReturn(content);
        when(world.roster()).thenReturn(mock(RosterService.class));
        when(world.chatVerbs()).thenReturn(verbs);
        when(world.store()).thenReturn(mock(LivingStore.class));
        when(world.config()).thenReturn(new LivingWorldConfig());
        when(world.residents()).thenReturn(residents);
        when(world.quests()).thenReturn(mock(QuestLedger.class));
        when(world.contracts()).thenReturn(mock(ContractService.class));
        when(world.bonds()).thenReturn(mock(BondService.class));
        when(world.arcs()).thenReturn(mock(ArcService.class));
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(TOWN);
        when(owner.getId()).thenReturn(OWNER);
        when(owner.getName()).thenReturn("Bossfriend");
        when(owner.getMap()).thenReturn(map);
        when(owner.getMapId()).thenReturn(TOWN);
        when(owner.getPosition()).thenReturn(new Point(0, 0));
        Character fennaBot = mock(Character.class);
        when(fennaBot.getPosition()).thenReturn(new Point(60, 0)); // right beside the owner
        ResidentRuntime.Session session = new ResidentRuntime.Session(fenna, 900);
        session.entry = new AgentRuntimeEntry(fennaBot, null, null);
        when(residents.residentsIn(TOWN)).thenReturn(List.of(session));
        Character tamsin = mock(Character.class);
        when(tamsin.getId()).thenReturn(901);
        companion = new AgentRuntimeEntry(tamsin, owner, null);
        AgentRuntimeRegistry.clear();
        AgentRuntimeRegistry.registerEntry(OWNER, companion);
        dialogue = new ResidentDialogue(world);
    }

    @AfterEach
    void unwire() {
        AgentRuntimeRegistry.clear();
    }

    @Test
    void aYesTheOwnersAgentWaitsOnIsLeftToIt() {
        AgentPendingActionStateRuntime.setPendingAction(companion, AgentChatPendingAction.LOGOUT); // "u want me to log off?"

        assertFalse(dialogue.onMapChat(owner, "yes"), "the line goes on to the Agent routes");
        assertFalse(dialogue.onMapChat(owner, "sure"));
        verify(residents, never()).reply(any(), any(), anyString());
    }

    @Test
    void withNoAgentWaitingTheResidentBesideThemStillTakesIt() {
        assertTrue(dialogue.onMapChat(owner, "yes"));
        verify(residents).reply(any(), any(), anyString());
    }
}
