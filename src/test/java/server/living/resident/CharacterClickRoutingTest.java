package server.living.resident;

import client.Character;
import client.Client;
import net.packet.Packet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import server.integration.AgentPresence;
import server.living.LivingWorld;
import server.living.arc.ArcService;
import server.living.bond.BondService;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.quest.QuestLedger;
import server.living.roster.CompanionDirectory;
import server.living.roster.CompanionDirectory.Relation;
import server.living.roster.CompanionSlots;
import server.living.roster.RosterService;
import server.living.roster.ui.CompanionMenus;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import testutil.NpcTalks;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Double-clicking a character (docs/BOT_MANAGEMENT_PLAN.md, "ResidentDialogue.onCharacterClicked"): your own
 * companion opens its card; another player's companion, a citizen who is not your guest, a plain Agent, a human and
 * yourself keep the stock character info; a Resident nobody owns opens its menu.
 */
class CharacterClickRoutingTest {
    private static final int ANN = 10;
    private static final int OLWEN = 900;  // Ann's companion
    private static final int BRIN = 901;   // Bob's companion
    private static final int CATO = 902;   // a Resident
    private static final int MIRA = 950;   // a citizen
    private static final int PROBE = 960;  // a plain Agent

    private final LivingWorld world = mock(LivingWorld.class);
    private final CompanionDirectory directory = mock(CompanionDirectory.class);
    private final RosterService roster = mock(RosterService.class);
    private final LivingContent content = mock(LivingContent.class);
    private final Character ann = character(ANN);
    private final Client client = mock(Client.class);
    private final RosterEntry olwenRow = new RosterEntry(ANN, "olwen", RosterState.ACTIVE, Assignment.TRAIN, 0, 0, 0,
            0L, 0L, 0L, 0L, 0L, 0L, 0, null);
    private ResidentDialogue dialogue;
    private MockedConstruction<CompanionMenus> menus;

    @BeforeEach
    void wire() {
        when(ann.getClient()).thenReturn(client);
        when(client.getPlayer()).thenReturn(ann);
        when(client.canClickNPC()).thenReturn(true);
        when(world.directory()).thenReturn(directory);
        when(world.roster()).thenReturn(roster);
        when(world.content()).thenReturn(content);
        when(world.bonds()).thenReturn(mock(BondService.class));
        when(world.quests()).thenReturn(mock(QuestLedger.class));
        when(world.arcs()).thenReturn(mock(ArcService.class));
        when(world.slots()).thenReturn(mock(CompanionSlots.class)); // no citizen here is Ann's guest (HireOfferTest has one)
        Set<Integer> agents = Set.of(OLWEN, BRIN, CATO, MIRA, PROBE);
        AgentPresence.install(chr -> agents.contains(chr.getId()));
        menus = mockConstruction(CompanionMenus.class);
        dialogue = new ResidentDialogue(world);
    }

    @AfterEach
    void restore() {
        LivingTalk.forget(ann);
        menus.close();
        AgentPresence.install(null);
    }

    @Test
    void yourOwnCompanionOpensItsCard() {
        Character olwen = target(OLWEN, new Relation.OwnCompanion(olwenRow));

        assertTrue(dialogue.onCharacterClicked(ann, olwen));

        assertEquals(1, menus.constructed().size());
        verify(menus.constructed().get(0)).card(ann, olwenRow);
    }

    @Test
    void anotherPlayersCompanionKeepsTheStockCharacterInfo() {
        assertFalse(dialogue.onCharacterClicked(ann, target(BRIN, new Relation.OthersCompanion(20, "brin"))));
        assertNoWindow();
    }

    @Test
    void citizensAndPlainAgentsKeepTheStockCharacterInfo() {
        assertFalse(dialogue.onCharacterClicked(ann, target(MIRA, new Relation.Citizen(MIRA))));
        assertFalse(dialogue.onCharacterClicked(ann, target(PROBE, new Relation.PlainAgent(PROBE))));
        assertNoWindow();
    }

    @Test
    void humansAndYourselfKeepTheStockCharacterInfo() {
        assertFalse(dialogue.onCharacterClicked(ann, ann));
        assertFalse(dialogue.onCharacterClicked(ann, character(20)));
        assertFalse(dialogue.onCharacterClicked(character(OLWEN), ann)); // an Agent clicking is never routed
        verify(directory, never()).resolve(any(), any());
        assertNoWindow();
    }

    @Test
    void anUnownedResidentOpensItsMenu() {
        Persona cato = mock(Persona.class);
        when(cato.id()).thenReturn("cato");
        when(cato.name()).thenReturn("Cato");
        when(cato.title()).thenReturn("");
        when(cato.homeTown()).thenReturn("lith-harbor");
        when(content.persona("cato")).thenReturn(cato);

        assertTrue(dialogue.onCharacterClicked(ann, target(CATO, new Relation.Resident("cato"))));

        ArgumentCaptor<Packet> sent = ArgumentCaptor.forClass(Packet.class);
        verify(client).sendPacket(sent.capture());
        assertEquals(java.util.List.of("Who are you?", "Never mind."), NpcTalks.read(sent.getValue()).labels());
        assertTrue(menus.constructed().isEmpty());
    }

    @Test
    void aResidentTheRosterStillOwnsKeepsTheStockCharacterInfo() {
        when(content.persona("cato")).thenReturn(mock(Persona.class));
        when(roster.isOwned(any())).thenReturn(true);

        assertFalse(dialogue.onCharacterClicked(ann, target(CATO, new Relation.Resident("cato"))));
        assertNoWindow();
    }

    private Character target(int id, Relation relation) {
        Character target = character(id);
        when(directory.resolve(ann, target)).thenReturn(relation);
        return target;
    }

    private void assertNoWindow() {
        assertTrue(menus.constructed().isEmpty());
        verify(client, never()).sendPacket(any());
    }

    private static Character character(int id) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        return chr;
    }
}
