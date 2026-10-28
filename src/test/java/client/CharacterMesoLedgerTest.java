package client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.TimerManager;
import server.economy.ledger.MesoAccount;
import server.economy.ledger.MesoFlows;
import server.economy.ledger.MesoHolder;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * The purse hook (docs/ECONOMY_PLAN.md E0): every change of a character's meso is booked, and what is booked is what
 * the purse really moved: an income clamped at the meso cap books only the part that fitted, a refused fee nothing.
 */
class CharacterMesoLedgerTest {
    private MockedStatic<TimerManager> timers;

    @BeforeEach
    void setUp() {
        timers = mockStatic(TimerManager.class);
        timers.when(TimerManager::getInstance).thenReturn(mock(TimerManager.class));
        MesoFlows.installClassifier(chr -> MesoHolder.HUMAN);
        MesoFlows.drain();
    }

    @AfterEach
    void tearDown() {
        MesoFlows.installClassifier(null);
        MesoFlows.drain();
        timers.close();
    }

    @Test
    void gainsAndFeesBookToTheOpenAccount() {
        Character chr = Character.getDefault(new BotClient(0, 1));
        try (var scope = MesoFlows.as(MesoAccount.QUEST)) {
            chr.gainMeso(5_000, false);
        }
        try (var scope = MesoFlows.as(MesoAccount.SERVICE)) {
            chr.tryPayMeso(1_200, false);
            assertFalse(chr.tryPayMeso(1_000_000, false), "more than it has: nothing taken");
        }

        Map<MesoAccount, Map<MesoHolder, MesoFlows.Sum>> sums = MesoFlows.drain();
        assertEquals(new MesoFlows.Sum(5_000, 0, 1), sums.get(MesoAccount.QUEST).get(MesoHolder.HUMAN));
        assertEquals(new MesoFlows.Sum(0, 1_200, 1), sums.get(MesoAccount.SERVICE).get(MesoHolder.HUMAN));
        assertEquals(3_800, chr.getMeso());
    }

    @Test
    void anIncomePastTheCapBooksOnlyWhatFitted() {
        Character chr = Character.getDefault(new BotClient(0, 1));
        chr.gainMeso(Integer.MAX_VALUE - 100, false);
        MesoFlows.drain();

        try (var scope = MesoFlows.as(MesoAccount.MOB_DROP)) {
            chr.gainMeso(1_000, false);
        }
        assertEquals(100L, MesoFlows.drain().get(MesoAccount.MOB_DROP).get(MesoHolder.HUMAN).net());
        assertEquals(Integer.MAX_VALUE, chr.getMeso());
    }
}
