package server.living.resident;

import client.Character;
import config.LivingWorldConfig;
import org.junit.jupiter.api.Test;
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
 * Found in the standing live checks (2026-10-31): in Henesys, "thanks FrostDemon" to a citizen was answered by Posy, the
 * Resident nearest the player ("oh, it's nothing. it's hats."), and "hi QuietPig" by another, so the citizen never heard
 * the greeting or the thanks its warmth (and so its standing) grows by. A line naming someone else on the map is theirs.
 */
class ResidentDialogueNamedCitizenTest {
    private static final int OWNER = 7;
    private static final int TOWN = 100000000;

    private final LivingWorld world = mock(LivingWorld.class);
    private final ResidentRuntime residents = mock(ResidentRuntime.class);
    private final Character player = mock(Character.class);
    private final MapleMap map = mock(MapleMap.class);
    private final Persona fenna = new Persona("fenna", "Fenna", "", "BOWMAN", new Content.LevelBand(20, 25),
            Content.Strength.ROOKIE, null, "relaxed-v1", null, "henesys", List.of(), false, null, List.of(), null,
            List.of(), List.of(), Map.of(), -1, -1, -1, List.of(), 0, Content.Look.NONE, "test");

    private ResidentDialogue dialogue() {
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
        when(map.getId()).thenReturn(TOWN);
        when(player.getId()).thenReturn(OWNER);
        when(player.getName()).thenReturn("Crewfriend");
        when(player.getMap()).thenReturn(map);
        when(player.getMapId()).thenReturn(TOWN);
        when(player.getPosition()).thenReturn(new Point(0, 0));
        Character fennaBot = mock(Character.class);
        when(fennaBot.getPosition()).thenReturn(new Point(60, 0)); // the Resident right beside the player
        ResidentRuntime.Session session = new ResidentRuntime.Session(fenna, 900);
        session.entry = new server.agents.runtime.AgentRuntimeEntry(fennaBot, null, null);
        when(residents.residentsIn(TOWN)).thenReturn(List.of(session));
        Character frostDemon = mock(Character.class);
        when(frostDemon.getId()).thenReturn(1017);
        when(map.getCharacterByName("frostdemon")).thenReturn(frostDemon); // a citizen a little further off
        when(map.getCharacterByName("crewfriend")).thenReturn(player);
        return new ResidentDialogue(world);
    }

    @Test
    void aGreetingOrThanksNamingACitizenHereIsLeftToTheCitizen() {
        ResidentDialogue dialogue = dialogue();

        assertFalse(dialogue.onMapChat(player, "thanks FrostDemon"), "on to the citizens");
        assertFalse(dialogue.onMapChat(player, "hi FrostDemon"));
        verify(residents, never()).reply(any(), any(), anyString());
    }

    @Test
    void aPlainThanksOrOneNamingTheSpeakerStillGoesToTheResidentBesideThem() {
        ResidentDialogue dialogue = dialogue();

        assertTrue(dialogue.onMapChat(player, "thanks"));
        assertTrue(dialogue.onMapChat(player, "hi crewfriend")); // their own name is no one else's
    }
}
