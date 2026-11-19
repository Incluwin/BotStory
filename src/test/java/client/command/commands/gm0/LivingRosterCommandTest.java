package client.command.commands.gm0;

import org.junit.jupiter.api.Test;
import server.living.config.Content.BondStage;
import server.living.store.LivingStore.ContractState;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LivingRosterCommandTest {
    @Test
    void theBondLineUsesTheCompanionCardsWords() {
        assertEquals("Lia: Trusted (35 points), hired",
                LivingRosterCommand.bondLine("Lia", BondStage.TRUSTED, 35, ContractState.ACTIVE));
        assertEquals("Lia: Met (2 points), no contract",
                LivingRosterCommand.bondLine("Lia", BondStage.MET, 2, ContractState.NONE));
        assertEquals("Lia: none yet (0 points), contract offered",
                LivingRosterCommand.bondLine("Lia", BondStage.NONE, 0, ContractState.OFFERED));
        assertEquals("Lia: Sworn (90 points), contract ended",
                LivingRosterCommand.bondLine("Lia", BondStage.SWORN, 90, ContractState.ENDED));
    }
}
