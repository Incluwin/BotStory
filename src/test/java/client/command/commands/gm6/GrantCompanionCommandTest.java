package client.command.commands.gm6;

import client.Character;
import client.Client;
import client.command.commands.gm6.GrantCompanionCommand.Request;
import net.server.PlayerStorage;
import net.server.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.integration.AgentPresence;
import server.living.LivingWorld;
import server.living.config.Content;
import server.living.config.Content.Look;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.population.Citizen;
import server.living.population.Population;
import server.living.roster.CompanionExecutor;
import server.living.roster.DirectExecutor;
import server.living.roster.RosterService;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GrantCompanionCommandTest {
    private static final Persona OLWEN = persona("olwen", "Olwen");

    private final Client client = mock(Client.class);
    private final Character gm = character(1, "Jevelopers");
    private final Character friend = character(2, "Reluctant");
    private final PlayerStorage players = mock(PlayerStorage.class);
    private final LivingWorld world = mock(LivingWorld.class);
    private final LivingContent content = mock(LivingContent.class);
    private final RosterService roster = mock(RosterService.class);
    private final Population population = mock(Population.class);

    private MockedStatic<LivingWorld> living;
    private MockedStatic<Population> populations;
    private MockedStatic<AgentPresence> presence;

    @BeforeEach
    void wire() {
        World channelWorld = mock(World.class);
        when(client.getPlayer()).thenReturn(gm);
        when(client.getWorldServer()).thenReturn(channelWorld);
        when(channelWorld.getPlayerStorage()).thenReturn(players);
        when(players.getCharacterByName("Reluctant")).thenReturn(friend);
        when(world.content()).thenReturn(content);
        when(world.roster()).thenReturn(roster);
        CompanionExecutor ops = DirectExecutor.inline(); // the roster is written on companion-ops
        when(world.executor()).thenReturn(ops);
        when(content.persona("olwen")).thenReturn(OLWEN);
        when(roster.grant(any(), any())).thenReturn("granted");
        when(roster.release(any(), any(), anyString())).thenReturn("released");
        living = mockStatic(LivingWorld.class);
        living.when(LivingWorld::get).thenReturn(world);
        populations = mockStatic(Population.class);
        populations.when(Population::get).thenReturn(population);
        presence = mockStatic(AgentPresence.class);
    }

    @AfterEach
    void close() {
        presence.close();
        populations.close();
        living.close();
    }

    // ---- parsing -------------------------------------------------------------------------

    @Test
    void parsesAGrantToYourself() {
        assertEquals(new Request(false, "olwen", null), GrantCompanionCommand.parse(new String[]{"olwen"}));
    }

    @Test
    void parsesAGrantToANamedPlayer() {
        assertEquals(new Request(false, "olwen", "Reluctant"),
                GrantCompanionCommand.parse(new String[]{"olwen", "Reluctant"}));
    }

    @Test
    void parsesARelease() {
        assertEquals(new Request(true, "olwen", null), GrantCompanionCommand.parse(new String[]{"release", "olwen"}));
        assertEquals(new Request(true, "olwen", null), GrantCompanionCommand.parse(new String[]{"RELEASE", "olwen"}));
    }

    @Test
    void anythingElseIsAUsageError() {
        assertNull(GrantCompanionCommand.parse(new String[]{}));
        assertNull(GrantCompanionCommand.parse(new String[]{"release"}));
        assertNull(GrantCompanionCommand.parse(new String[]{"release", "olwen", "Reluctant"}));
        assertNull(GrantCompanionCommand.parse(new String[]{"olwen", "Reluctant", "extra"}));
    }

    // ---- execution -----------------------------------------------------------------------

    @Test
    void usageErrorShowsTheSyntax() {
        run();

        verify(gm).yellowMessage(GrantCompanionCommand.USAGE);
        living.verifyNoInteractions();
    }

    @Test
    void grantsToTheGmByDefault() {
        run("olwen");

        verify(roster).grant(gm, OLWEN);
        verify(gm).yellowMessage("granted");
    }

    @Test
    void grantsToANamedOnlinePlayer() {
        run("olwen", "Reluctant");

        verify(roster).grant(friend, OLWEN);
        verify(gm).yellowMessage("granted");
    }

    @Test
    void refusesAnOfflinePlayer() {
        run("olwen", "Nobody");

        verify(roster, never()).grant(any(), any());
        verify(gm).yellowMessage("'Nobody' must be online.");
    }

    @Test
    void refusesABotAsOwner() {
        presence.when(() -> AgentPresence.isAgent(friend)).thenReturn(true);

        run("olwen", "Reluctant");

        verify(roster, never()).grant(any(), any());
        verify(gm).yellowMessage("'Reluctant' is a bot; companions belong to players.");
    }

    @Test
    void refusesHiredCitizenIdsUntilP4() {
        when(content.persona("hire:123")).thenReturn(OLWEN); // even once HiredHands resolves them

        run("hire:123");

        verify(roster, never()).grant(any(), any());
        verify(gm).yellowMessage("Hired citizens cannot be granted yet (P4).");
    }

    @Test
    void refusesCitizensUntilP4() {
        when(population.citizenByName("Mirabel")).thenReturn(mock(Citizen.class));

        run("Mirabel");

        verify(roster, never()).grant(any(), any());
        verify(gm).yellowMessage("'Mirabel' is a citizen; citizens cannot be granted yet (P4).");
    }

    @Test
    void refusesAnUnknownPersona() {
        run("nobody");

        verify(roster, never()).grant(any(), any());
        verify(gm).yellowMessage("No persona with id 'nobody'.");
    }

    @Test
    void releaseGoesThroughTheRosterReleaseForTheOnlineOwner() {
        when(roster.ownerOf("olwen")).thenReturn(2);
        when(roster.onlineCharacter(2)).thenReturn(friend);

        run("release", "olwen");

        verify(roster).release(friend, OLWEN, "gm release");
        verify(gm).yellowMessage("Reluctant: released");
    }

    @Test
    void releaseOfAnUnownedPersonaDoesNothing() {
        run("release", "olwen");

        verify(roster, never()).release(any(), any(), anyString());
        verify(gm).yellowMessage("Olwen is on nobody's roster.");
    }

    @Test
    void releaseNeedsTheOwnerOnline() {
        when(roster.ownerOf("olwen")).thenReturn(2);

        run("release", "olwen");

        verify(roster, never()).release(any(), any(), anyString());
        verify(gm).yellowMessage("Olwen's owner (character 2) must be online.");
    }

    @Test
    void disabledLivingWorldIsReported() {
        living.when(LivingWorld::get).thenReturn(null);

        run("olwen");

        verify(gm).yellowMessage("Living world is disabled (living_world.ENABLED in config.yaml).");
    }

    private void run(String... params) {
        new GrantCompanionCommand().execute(client, params);
    }

    private static Character character(int id, String name) {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(id);
        when(character.getName()).thenReturn(name);
        return character;
    }

    private static Persona persona(String id, String name) {
        return new Persona(id, name, "", "BEGINNER", new Content.LevelBand(1, 1), Content.Strength.ROOKIE, null,
                "relaxed-v1", null, "lith-harbor", List.of(), false, null, List.of(), null, List.of(), List.of(), Map.of(),
                -1, -1, -1, List.of(), 0, Look.NONE, "test");
    }
}
