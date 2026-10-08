package server.agents.capabilities.partyquest.epq;

import client.BuffStat;
import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.WeaponType;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.combat.AgentAttackExecutionProvider;
import testutil.Items;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * EPQ stage 2: the two weakest non-carriers lure the bugs, and lurers attack only with skill plans. In a companion run
 * a ranged member with nothing to shoot would walk after its bug forever, so the next-weakest lures instead; a stock
 * run keeps every non-carrier.
 */
class AgentEpqStageTwoLurerTest {
    private final Map<Character, WeaponType> weapons = new java.util.HashMap<>();

    @Test
    void aDryRangedMemberIsNoLurerInACompanionRun() {
        Character kestrel = member(501, 10, WeaponType.CLAW, 0); // the weakest, no stars
        Character pell = member(502, 20, WeaponType.BOW, 2_000);
        Character halvard = member(503, 30, WeaponType.SPEAR_STAB, 0);
        try (MockedStatic<AgentAttackExecutionProvider> attacks = equipped()) {
            assertEquals(List.of(502, 503), AgentEpqCoordinator.stageTwoLurerIds(List.of(kestrel, pell, halvard),
                    candidate -> AgentEpqCoordinator.stageTwoLurerCandidate(candidate, true)));
        }
    }

    @Test
    void aStockRunKeepsEveryNonCarrier() {
        Character kestrel = member(501, 10, WeaponType.CLAW, 0);
        Character pell = member(502, 20, WeaponType.BOW, 2_000);
        Character halvard = member(503, 30, WeaponType.SPEAR_STAB, 0);
        try (MockedStatic<AgentAttackExecutionProvider> attacks = equipped()) {
            assertEquals(List.of(501, 502), AgentEpqCoordinator.stageTwoLurerIds(List.of(kestrel, pell, halvard),
                    candidate -> AgentEpqCoordinator.stageTwoLurerCandidate(candidate, false)));
        }
    }

    @Test
    void theRunsOwnAdoptionDecidesWhetherADryMemberLures() {
        // review: replacing session.companionAdopted() with false in the session wiring left every test green
        Character carrier = member(500, 50, WeaponType.SPEAR_STAB, 0); // the lowest id: the work agent
        Character kestrel = member(501, 10, WeaponType.CLAW, 0);
        Character pell = member(502, 20, WeaponType.BOW, 2_000);
        Character halvard = member(503, 30, WeaponType.SPEAR_STAB, 0);
        Map<Integer, Character> online = Map.of(500, carrier, 501, kestrel, 502, pell, 503, halvard);
        AgentEpqSession run = new AgentEpqSession(AgentEpqSession.Mode.HUMAN_ASSISTED, 1L, 499, 0L);
        run.addMember(499, AgentEpqMemberState.MemberType.HUMAN);
        for (int id : List.of(500, 501, 502, 503)) {
            run.addMember(id, AgentEpqMemberState.MemberType.AGENT);
        }
        run.setLeadership(499, 500);
        server.agents.integration.CharacterGateway gateway = mock(server.agents.integration.CharacterGateway.class);
        when(gateway.findOnlineCharacterById(org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(inv -> online.get((int) inv.getArgument(0)));
        try (MockedStatic<AgentAttackExecutionProvider> attacks = equipped();
             MockedStatic<server.agents.integration.AgentCharacterGatewayRuntime> characters =
                     mockStatic(server.agents.integration.AgentCharacterGatewayRuntime.class)) {
            characters.when(server.agents.integration.AgentCharacterGatewayRuntime::characters).thenReturn(gateway);

            assertEquals(List.of(501, 502), AgentEpqCoordinator.stageTwoLurerIds(run), "a stock run");
            run.setCompanionAdopted(true);
            assertEquals(List.of(502, 503), AgentEpqCoordinator.stageTwoLurerIds(run), "an adopted run");
        }
    }

    /** A living member hitting for {@code damage}, holding {@code weapon} with {@code projectiles} for it. */
    private Character member(int id, int damage, WeaponType weapon, int projectiles) {
        Character member = mock(Character.class);
        when(member.getId()).thenReturn(id);
        when(member.getHp()).thenReturn(1_000);
        when(member.getTotalWatk()).thenReturn(damage);
        when(member.calculateMaxBaseDamage(damage)).thenReturn(damage);
        when(member.getBuffedValue(any(BuffStat.class))).thenReturn(null);
        Inventory use = new Inventory(member, InventoryType.USE, (byte) 24);
        if (projectiles > 0) {
            use.addItem(Items.itemWithQuantity(weapon == WeaponType.BOW ? 2_060_000 : 2_070_000, projectiles));
        }
        when(member.getInventory(InventoryType.USE)).thenReturn(use);
        weapons.put(member, weapon);
        return member;
    }

    private MockedStatic<AgentAttackExecutionProvider> equipped() {
        MockedStatic<AgentAttackExecutionProvider> attacks = mockStatic(AgentAttackExecutionProvider.class);
        attacks.when(() -> AgentAttackExecutionProvider.getEquippedWeaponType(any()))
                .thenAnswer(inv -> weapons.get((Character) inv.getArgument(0)));
        return attacks;
    }
}
