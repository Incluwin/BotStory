package server.economy.ledger;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.economy.EconomyOperationKind;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The economy ledger's counting (docs/ECONOMY_PLAN.md E0): a purse change books to the innermost open account, or
 * UNATTRIBUTED; a sale's tax moves from the transfer account to TAX without a second change; a drain starts the sums
 * from zero; and every economy operation has an account, so a rolled-back one nets out where it was booked.
 */
class MesoFlowsTest {
    private final Character player = mock(Character.class);

    @BeforeEach
    void start() {
        MesoFlows.installClassifier(chr -> MesoHolder.HUMAN);
        MesoFlows.setEnabled(true);
        MesoFlows.drain();
    }

    @AfterEach
    void restore() {
        MesoFlows.installClassifier(null);
        MesoFlows.setEnabled(true);
        MesoFlows.drain();
    }

    @Test
    void aPurseChangeBooksToTheInnermostOpenAccount() {
        try (var outer = MesoFlows.as(MesoAccount.MOB_DROP)) {
            MesoFlows.purse(player, 300);
            try (var inner = MesoFlows.as(MesoAccount.HAUL)) {
                MesoFlows.purse(player, -100); // a bench slice moves its pickup into the haul inside the kill's scope
            }
            MesoFlows.purse(player, 50);
        }
        MesoFlows.purse(player, 7);

        Map<MesoAccount, Map<MesoHolder, MesoFlows.Sum>> sums = MesoFlows.drain();
        assertEquals(new MesoFlows.Sum(350, 0, 2), sums.get(MesoAccount.MOB_DROP).get(MesoHolder.HUMAN));
        assertEquals(new MesoFlows.Sum(0, 100, 1), sums.get(MesoAccount.HAUL).get(MesoHolder.HUMAN));
        assertEquals(new MesoFlows.Sum(7, 0, 1), sums.get(MesoAccount.UNATTRIBUTED).get(MesoHolder.HUMAN),
                "no scope open: it is listed so it gets a name");
    }

    @Test
    void aSalesTaxMovesFromTheTransferToTaxAndTheTransferNetsToZero() {
        try (var shop = MesoFlows.as(MesoAccount.PLAYER_SHOP)) {
            MesoFlows.purse(player, -10_000); // the buyer pays the price
            MesoFlows.purse(player, 9_920); // the seller gets it less 0.8 %
        }
        MesoFlows.reclassify(MesoHolder.HUMAN, MesoAccount.PLAYER_SHOP, MesoAccount.TAX, 80);

        Map<MesoAccount, Map<MesoHolder, MesoFlows.Sum>> sums = MesoFlows.drain();
        assertEquals(0L, sums.get(MesoAccount.PLAYER_SHOP).get(MesoHolder.HUMAN).net(), "a transfer creates nothing");
        assertEquals(-80L, sums.get(MesoAccount.TAX).get(MesoHolder.HUMAN).net(), "the tax is the meso that left");
        assertEquals(2L, sums.get(MesoAccount.PLAYER_SHOP).get(MesoHolder.HUMAN).events(), "the reclass is no change");
    }

    @Test
    void aDrainStartsTheSumsFromZeroAndAnOffLedgerCountsNothing() {
        MesoFlows.record(MesoAccount.BENCH_SIM, MesoHolder.BOT, 1_000);
        assertFalse(MesoFlows.drain().isEmpty());
        assertTrue(MesoFlows.drain().isEmpty(), "drained");

        MesoFlows.setEnabled(false);
        MesoFlows.record(MesoAccount.BENCH_SIM, MesoHolder.BOT, 1_000);
        MesoFlows.purse(player, 1_000);
        assertTrue(MesoFlows.drain().isEmpty());
    }

    @Test
    void aClassifierThatFailsCountsAPlayer() {
        MesoFlows.installClassifier(chr -> {
            throw new IllegalStateException("population stopping");
        });
        MesoFlows.purse(player, 5);
        Map<MesoHolder, MesoFlows.Sum> unattributed = MesoFlows.drain().get(MesoAccount.UNATTRIBUTED);
        assertEquals(5L, unattributed.get(MesoHolder.HUMAN).net());
        assertNull(unattributed.get(MesoHolder.BOT));
    }

    @Test
    void everyEconomyOperationHasAnAccountAndOnlyTransfersAreInternal() {
        for (EconomyOperationKind kind : EconomyOperationKind.values()) {
            MesoAccount account = MesoAccount.forOperation(kind);
            boolean transfer = kind == EconomyOperationKind.PLAYER_SHOP_LIST || kind == EconomyOperationKind.PLAYER_SHOP_SALE
                    || kind == EconomyOperationKind.PLAYER_TRADE || kind == EconomyOperationKind.COMPANION_GIFT
                    || kind == EconomyOperationKind.NEED_FULFILLED;
            assertEquals(!transfer, account.external(), kind + " -> " + account);
        }
    }
}
