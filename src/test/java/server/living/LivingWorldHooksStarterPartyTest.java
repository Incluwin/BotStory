package server.living;

import client.Character;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.agents.auth.AgentClaimGuard;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Level 10's stock "you must leave your starter party" sent a mentor home (live 2026-11-03: TinyWolf passed level 10 on
 * Southperry and left her newcomer's party "on its own"): a party with a companion in it is not a starter party, for
 * the companion and for the player alike.
 */
class LivingWorldHooksStarterPartyTest {
    private static final int NEWCOMER = 10;
    private static final int FRIEND = 11;
    private static final int MENTOR = 20;

    @BeforeEach
    void rule() {
        AgentClaimGuard.install(new AgentClaimGuard.Rule() {
            @Override
            public boolean mayControl(Character actor, int agentCharacterId) {
                return false;
            }

            @Override
            public boolean isClaimed(int agentCharacterId) {
                return true;
            }

            @Override
            public boolean isCompanion(int agentCharacterId) {
                return agentCharacterId == MENTOR;
            }
        });
    }

    @AfterEach
    void noRule() {
        AgentClaimGuard.install(null);
    }

    @Test
    void aMentorAndItsNewcomerBothStayInTheirParty() {
        assertTrue(LivingWorldHooks.keepsStarterParty(inParty(MENTOR, NEWCOMER, MENTOR)));
        assertTrue(LivingWorldHooks.keepsStarterParty(inParty(NEWCOMER, NEWCOMER, MENTOR)));
    }

    @Test
    void aPartyOfPlayersStillLeavesAsStock() {
        assertFalse(LivingWorldHooks.keepsStarterParty(inParty(NEWCOMER, NEWCOMER, FRIEND)));
    }

    @Test
    void nobodyToKeepWithoutAParty() {
        assertFalse(LivingWorldHooks.keepsStarterParty(mock(Character.class)));
        assertFalse(LivingWorldHooks.keepsStarterParty(null));
    }

    @Test
    void withoutTheLivingWorldsRuleTheStockRuleStands() {
        AgentClaimGuard.install(null);
        assertFalse(LivingWorldHooks.keepsStarterParty(inParty(MENTOR, NEWCOMER, MENTOR)));
    }

    private static Character inParty(int id, int... members) {
        List<PartyCharacter> list = Arrays.stream(members).mapToObj(member -> {
            PartyCharacter row = mock(PartyCharacter.class);
            when(row.getId()).thenReturn(member);
            return row;
        }).toList();
        Party party = mock(Party.class);
        when(party.getMembers()).thenReturn(list);
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getParty()).thenReturn(party);
        return chr;
    }
}
