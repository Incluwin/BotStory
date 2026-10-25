package server.living.roster;

import client.Character;
import config.LivingWorldConfig;
import net.server.coordinator.matchchecker.MatchCheckerCoordinator;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import net.server.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.integration.PartyGateway;
import server.living.LivingWorld;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GPQ plan decision 9: a player founds a guild with the companions of the party they lead. A guildless companion of
 * theirs in that party counts as a cofounder, one in a guild already says so and does not; a party of the founder's own
 * companions is kept; and each companion asked answers yes a moment later and says so. Off with the kill switch.
 */
class CompanionGuildFoundingTest {
    private static final int ANN = 10;
    private static final int PARTY = 77;

    private final LivingWorld world = mock(LivingWorld.class);
    private final LivingWorldConfig config = new LivingWorldConfig();
    private final CompanionDirectory directory = mock(CompanionDirectory.class);
    private final RosterService roster = mock(RosterService.class);
    private final MatchCheckerCoordinator matches = mock(MatchCheckerCoordinator.class);
    private final MapleMap hq = mock(MapleMap.class);
    private final Party party = mock(Party.class);
    private final List<Runnable> later = new ArrayList<>();
    private final List<String> chat = new ArrayList<>();
    private final Character ann = person(ANN, PARTY);
    private final Character olwen = person(901, PARTY);
    private final Character brin = person(902, PARTY);
    private final Character stranger = person(950, PARTY);
    private MockedStatic<AgentPartyGatewayRuntime> gateway;
    private CompanionGuildFounding founding;

    @BeforeEach
    void annLeadsHerCrewAtTheGuildHeadquarters() {
        when(world.config()).thenReturn(config);
        when(world.directory()).thenReturn(directory);
        when(world.roster()).thenReturn(roster);
        when(directory.ownerOfCharacter(901)).thenReturn(ANN);
        when(directory.ownerOfCharacter(902)).thenReturn(ANN);
        when(ann.isPartyLeader()).thenReturn(true);
        when(ann.getParty()).thenReturn(party);
        when(party.getLeaderId()).thenReturn(ANN);
        when(hq.getAllPlayers()).thenReturn(List.of(ann, olwen, brin, stranger));
        World worldServer = mock(World.class);
        when(ann.getWorldServer()).thenReturn(worldServer);
        when(worldServer.getMatchCheckerCoordinator()).thenReturn(matches);
        when(matches.answerMatchConfirmation(anyInt(), anyBoolean())).thenReturn(true);
        doAnswer(inv -> later.add(inv.getArgument(1))).when(roster).later(anyString(), any(Runnable.class), anyLong());
        gateway = mockStatic(AgentPartyGatewayRuntime.class);
        PartyGateway partyGateway = mock(PartyGateway.class);
        gateway.when(AgentPartyGatewayRuntime::party).thenReturn(partyGateway);
        when(partyGateway.sendPartyChat(any(), anyString())).thenAnswer(inv ->
                chat.add(((Character) inv.getArgument(0)).getName() + ": " + inv.getArgument(1)));
        founding = new CompanionGuildFounding(world);
    }

    @AfterEach
    void close() {
        gateway.close();
    }

    @Test
    void aGuildlessCompanionInTheFoundersPartyCofoundsAndOneInAGuildSaysSo() {
        assertTrue(founding.cofounder(ann, olwen));
        assertFalse(founding.cofounder(ann, stranger), "nobody's companion: the stock rule (no party) stands");
        assertFalse(founding.cofounder(ann, ann));

        when(brin.getGuildId()).thenReturn(31);
        assertFalse(founding.cofounder(ann, brin));
        assertEquals(List.of("c902: " + CompanionGuildFounding.IN_A_GUILD), chat);
        founding.cofounder(ann, brin);
        assertEquals(1, chat.size(), "once, not on every count");
    }

    @Test
    void aCompanionInAnotherPartyOrOfAFounderWhoDoesNotLeadIsNoCofounder() {
        when(olwen.getPartyId()).thenReturn(78);
        assertFalse(founding.cofounder(ann, olwen));
        when(olwen.getPartyId()).thenReturn(PARTY);
        when(ann.isPartyLeader()).thenReturn(false);
        assertFalse(founding.cofounder(ann, olwen));
    }

    @Test
    void aPartyOfTheFoundersOwnCompanionsIsKeptAndOneWithAnotherPersonIsNot() {
        members(ANN, 901, 902);
        assertTrue(founding.keepsParty(ann));

        members(ANN, 901, 950);
        assertFalse(founding.keepsParty(ann), "another player in it: the stock founding makes its own party");
    }

    @Test
    void eachCompanionAskedAnswersYesAMomentLaterAndSaysSo() {
        founding.asked(ann, Set.of(ANN, 901, 902, 950));

        verify(matches, never()).answerMatchConfirmation(anyInt(), anyBoolean());
        assertEquals(2, later.size(), "the two companions, nobody else");
        later.forEach(Runnable::run);
        verify(matches).answerMatchConfirmation(901, true);
        verify(matches).answerMatchConfirmation(902, true);
        verify(matches, never()).answerMatchConfirmation(950, true);
        assertEquals(2, chat.size(), chat.toString());
    }

    @Test
    void theKillSwitchLeavesTheStockFounding() {
        config.COMPANION_GUILD_COFOUNDERS_ENABLED = false;
        members(ANN, 901, 902);

        assertFalse(founding.cofounder(ann, olwen));
        assertFalse(founding.keepsParty(ann));
        founding.asked(ann, Set.of(901, 902));
        assertTrue(later.isEmpty());
    }

    private void members(int... ids) {
        List<PartyCharacter> list = new ArrayList<>();
        for (int id : ids) {
            PartyCharacter member = mock(PartyCharacter.class);
            when(member.getId()).thenReturn(id);
            list.add(member);
        }
        when(party.getMembers()).thenReturn(list);
    }

    private Character person(int id, int partyId) {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(id);
        when(character.getName()).thenReturn("c" + id);
        when(character.getPartyId()).thenReturn(partyId);
        when(character.getMap()).thenAnswer(inv -> hq);
        return character;
    }
}
