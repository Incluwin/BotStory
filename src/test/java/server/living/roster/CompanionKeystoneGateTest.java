package server.living.roster;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.config.Content.Persona;
import server.living.store.LivingStore;
import server.living.store.LivingStore.RosterState;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;

/**
 * CWKPQ plan C3 at the keystone gate: as Ann comes into the Hall to Inner Sanctum, a following companion of hers with
 * no Crimsonwood Keystone stays in the Hall of Mastery with one line in her party, until she leaves the hall; one with
 * a keystone follows on. Her benched companions are read for the ally picker (bag when loaded, saved inventory when
 * not), and the gate is off with the keystone errands.
 */
class CompanionKeystoneGateTest {
    private static final int ISOLDE = 901;
    private static final int THANE = 902;
    private static final int MAGNA = 903;
    private static final int HALL_OF_MASTERY = 610_030_010;
    private static final int HALL = CompanionKeystoneGate.HALL_MAP_ID;
    private static final int KEYSTONE = CompanionKeystoneGate.KEYSTONE_ITEM_ID;

    private final LifecycleHarness game = new LifecycleHarness();
    private CompanionKeystoneGate gate;
    private Character isolde;
    private Character thane;
    private Persona magna;

    @BeforeEach
    void annAndHerCrewInTheHallOfMastery() {
        Persona isoldePersona = game.companion("isolde", "Isolde", ISOLDE, RosterState.ACTIVE, HALL_OF_MASTERY);
        Persona thanePersona = game.companion("thane", "Thane", THANE, RosterState.ACTIVE, HALL_OF_MASTERY);
        magna = game.companion("magna", "Magna", MAGNA, RosterState.BENCHED, 100_000_000);
        game.moveAnn(HALL_OF_MASTERY, 100, 0);
        game.roster();
        game.outWithAnn(isoldePersona);
        game.outWithAnn(thanePersona);
        isolde = game.bot(isoldePersona);
        thane = game.bot(thanePersona);
        when(thane.haveItem(KEYSTONE)).thenReturn(true);
        List<AgentRuntimeEntry> crew = List.of(new AgentRuntimeEntry(isolde, game.ann, null),
                new AgentRuntimeEntry(thane, game.ann, null));
        game.registry.when(() -> AgentRuntimeRegistry.entriesForLeader(ANN)).thenReturn(crew);
        game.registry.when(() -> AgentRuntimeRegistry.leaderIdForAgentCharacter(ISOLDE)).thenReturn(ANN);
        game.registry.when(() -> AgentRuntimeRegistry.leaderIdForAgentCharacter(THANE)).thenReturn(ANN);
        game.config.COMPANION_KEYSTONE_ERRANDS_ENABLED = true;
        gate = new CompanionKeystoneGate(game.world);
    }

    @AfterEach
    void close() {
        game.close();
    }

    @Test
    void aCompanionWithNoKeystoneWaitsInTheHallOfMasteryWithOneLineUntilItsOwnerLeaves() {
        game.moveAnn(HALL, 0, 0);
        gate.ownerEntered(ANN, HALL);

        assertTrue(gate.holds(ISOLDE), "no keystone: it stays where it stood");
        assertFalse(gate.holds(THANE), "a keystone: it follows on");
        assertEquals(1, game.partyChat.size(), game.partyChat.toString());
        String chat = game.partyChat.get(0);
        assertTrue(chat.startsWith("Isolde: "), chat);
        assertTrue(BossRunLines.BY_BOSS.get("cwkpq").get(BossRunLines.KEYSTONE)
                .contains(chat.substring(chat.indexOf(": ") + 2)), chat);

        game.moveAnn(610_030_100, 0, 0);
        assertFalse(gate.holds(ISOLDE), "Ann went in: the hold ends, and the instance guard keeps it out");
    }

    @Test
    void anyOtherMapHoldsNobodyAndReadsNothing() {
        game.moveAnn(HALL_OF_MASTERY, 0, 0);
        gate.ownerEntered(ANN, HALL_OF_MASTERY);

        assertFalse(gate.holds(ISOLDE));
        assertTrue(game.partyChat.isEmpty());
        verify(game.store.store, never()).holdsItem(MAGNA, KEYSTONE);
    }

    @Test
    void theGateIsOffWithTheKeystoneErrands() {
        game.config.COMPANION_KEYSTONE_ERRANDS_ENABLED = false;
        game.moveAnn(HALL, 0, 0);
        gate.ownerEntered(ANN, HALL);

        assertFalse(gate.holds(ISOLDE), "no errand to earn one: it follows on its owner's, as before");
        assertTrue(game.partyChat.isEmpty());
        assertFalse(gate.on());
    }

    @Test
    void comingIntoTheHallReadsWhichBenchedCompanionsHoldAKeystoneForThePicker() {
        assertFalse(gate.lacks(magna.id()), "never read: the picker offers it, and the call reads");
        when(game.store.store.holdsItem(MAGNA, KEYSTONE)).thenReturn(false);
        game.moveAnn(HALL, 0, 0);
        gate.ownerEntered(ANN, HALL);
        assertTrue(gate.lacks(magna.id()), "its saved inventory holds none");

        when(game.store.store.holdsItem(MAGNA, KEYSTONE)).thenReturn(true);
        assertTrue(gate.holdsNow(magna.id(), MAGNA), "the call reads again");
        assertFalse(gate.lacks(magna.id()));
    }

    @Test
    void aLoadedCompanionIsReadFromItsBagAndAFailedReadLetsItCome() {
        assertTrue(gate.holdsNow("thane", THANE), "loaded: its bag");
        assertFalse(gate.holdsNow("isolde", ISOLDE));
        verify(game.store.store, never()).holdsItem(THANE, KEYSTONE);

        when(game.store.store.holdsItem(MAGNA, KEYSTONE)).thenThrow(new LivingStore.ReadFailed("inventoryitems",
                new java.sql.SQLException("down")));
        assertTrue(gate.holdsNow(magna.id(), MAGNA), "the stock gate is not ours to enforce harder than the picker");
    }
}
