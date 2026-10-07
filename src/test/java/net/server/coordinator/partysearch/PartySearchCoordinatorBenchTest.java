package net.server.coordinator.partysearch;

import client.BotClient;
import client.Character;
import client.Client;
import client.Job;
import config.YamlConfig;
import net.packet.Packet;
import net.server.coordinator.world.InviteCoordinator;
import net.server.coordinator.world.InviteCoordinator.InviteType;
import net.server.world.Party;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.living.LivingWorldHooks;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The stock Party Search and the bench (P9 flow 11; decisions 2026-10-06): bots never enter the search's pool (nothing
 * answers its invite to one); a round no human answered asks the leader's bench through the living world, and a round
 * the bench answered spends none of the search's re-entries, so the search goes on while the party has room.
 */
class PartySearchCoordinatorBenchTest {
    /** A map with no MapNeighbors entry, so "nearby" is the same map range. */
    private static final int MAP = 109000000;
    /** The search window's bit for Fighter (instantiateJobTable). */
    private static final int FIGHTER_BIT = 1 << 4;
    private static final int LEADER_ID = 10;

    private final PartySearchCoordinator coordinator = new PartySearchCoordinator();
    private final List<Integer> invited = new ArrayList<>();

    @AfterEach
    void forgetInvites() {
        for (int id : invited) {
            InviteCoordinator.removeInvite(InviteType.PARTY, id);
        }
    }

    private Character leader() {
        Character leader = character(LEADER_ID, mock(Client.class), 30, Job.FIGHTER);
        Party party = mock(Party.class);
        when(party.getId()).thenReturn(77);
        when(party.getMembers()).thenReturn(List.of());
        when(leader.getParty()).thenReturn(party);
        when(leader.getPartyId()).thenReturn(77);
        return leader;
    }

    private Character character(int id, Client client, int level, Job job) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getName()).thenReturn("C" + id);
        when(chr.getClient()).thenReturn(client);
        when(chr.getLevel()).thenReturn(level);
        when(chr.getJob()).thenReturn(job);
        when(chr.getMapId()).thenReturn(MAP);
        when(chr.isLoggedinWorld()).thenReturn(true);
        invited.add(id);
        return chr;
    }

    @Test
    void aBotIsNeverOfferedTheSearchsInviteButAHumanIs() {
        Character bot = character(901, mock(BotClient.class), 31, Job.FIGHTER);
        Character human = character(902, mock(Client.class), 31, Job.FIGHTER);
        Character leader = leader();
        try (MockedStatic<LivingWorldHooks> hooks = mockStatic(LivingWorldHooks.class)) {
            coordinator.attachPlayer(bot);
            coordinator.updatePartySearchStorage();
            coordinator.registerPartyLeader(leader, 25, 40, FIGHTER_BIT);
            coordinator.runPartySearch();

            verify(bot, never()).sendPacket(any(Packet.class));
            hooks.verify(() -> LivingWorldHooks.partySearchUnanswered(eq(leader),
                    eq(new PartySearchWant(25, 40, Set.of(Job.FIGHTER)))));

            coordinator.attachPlayer(human);
            coordinator.updatePartySearchStorage();
            coordinator.runPartySearch();

            verify(human).sendPacket(any(Packet.class));
        }
    }

    @Test
    void aRoundTheBenchAnsweredSpendsNoReentry() {
        int rounds = YamlConfig.config.server.PARTY_SEARCH_REENTRY_LIMIT + 5;
        assertEquals(rounds, roundsAsked(true, rounds), "the bench answers every round: the search goes on");
        assertEquals(YamlConfig.config.server.PARTY_SEARCH_REENTRY_LIMIT + 1, roundsAsked(false, rounds),
                "nobody answers: the search runs out of re-entries as before");
    }

    /** How many of {@code rounds} rounds asked the bench, which answers with {@code answers}. */
    private int roundsAsked(boolean answers, int rounds) {
        PartySearchCoordinator search = new PartySearchCoordinator();
        Character leader = leader();
        int[] asked = {0};
        try (MockedStatic<LivingWorldHooks> hooks = mockStatic(LivingWorldHooks.class)) {
            hooks.when(() -> LivingWorldHooks.partySearchUnanswered(any(), any())).thenAnswer(inv -> {
                asked[0]++;
                return answers;
            });
            search.registerPartyLeader(leader, 25, 40, FIGHTER_BIT);
            for (int i = 0; i < rounds; i++) {
                search.runPartySearch();
            }
        }
        assertTrue(asked[0] > 0);
        return asked[0];
    }

    @Test
    void theWantMatchesLevelRangeAndTheSearchsJobGroups() {
        PartySearchWant fighters = new PartySearchWant(25, 40, Set.of(Job.FIGHTER));

        assertTrue(fighters.wants(25, Job.FIGHTER));
        assertTrue(fighters.wants(40, Job.HERO), "a 4th-job Hero is filed under Fighter");
        assertTrue(fighters.wants(33, Job.CRUSADER));
        assertEquals(false, fighters.wants(24, Job.FIGHTER));
        assertEquals(false, fighters.wants(41, Job.FIGHTER));
        assertEquals(false, fighters.wants(30, Job.WARRIOR), "a first-job Warrior is its own group");
        assertEquals(false, fighters.wants(30, Job.PAGE));
        assertEquals(false, fighters.wants(30, null));
    }
}
