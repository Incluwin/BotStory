package server.living.roster;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterState;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;

/**
 * APQ plan A2 at Amos the Strong's gate: as Ann goes into the Entrance of the Amorian Challenge, each companion of hers
 * still on Amos' Training Ground is held off follow and goes in on its own ticket, as a player does: one with a ticket
 * talks to Amos the Strong and is sent in, one with ten keys trades them first, one with neither says so once in her
 * party and waits until she leaves the entrance. A bench call to the entrance lands at the training ground. Off while
 * the APQ desk is.
 */
class CompanionTicketGateTest {
    private static final int ISOLDE = 901;
    private static final int THANE = 902;
    private static final int MAGNA = 903;
    private static final int TRAINING_GROUND = CompanionTicketGate.TRAINING_GROUND_MAP;
    private static final int ENTRANCE = CompanionTicketGate.ENTRANCE_MAP;
    private static final int TICKET = CompanionTicketGate.TICKET;
    private static final int KEY = CompanionTicketGate.KEY;

    private final LifecycleHarness game = new LifecycleHarness();
    private final long[] now = {1_000_000L};
    private final Map<Integer, Map<Integer, Integer>> bags = new HashMap<>();
    private final List<String> talks = new ArrayList<>();
    private boolean amosRefuses;
    private CompanionTicketGate gate;

    @BeforeEach
    void annAndHerCrewOnAmosTrainingGround() {
        Persona isolde = game.companion("isolde", "Isolde", ISOLDE, RosterState.ACTIVE, TRAINING_GROUND);
        Persona thane = game.companion("thane", "Thane", THANE, RosterState.ACTIVE, TRAINING_GROUND);
        Persona magna = game.companion("magna", "Magna", MAGNA, RosterState.ACTIVE, TRAINING_GROUND);
        game.moveAnn(TRAINING_GROUND, 100, 0);
        game.roster();
        List<AgentRuntimeEntry> crew = new ArrayList<>();
        for (Persona persona : List.of(isolde, thane, magna)) {
            game.outWithAnn(persona);
            Character bot = game.bot(persona);
            crew.add(new AgentRuntimeEntry(bot, game.ann, null));
            Map<Integer, Integer> bag = bags.computeIfAbsent(bot.getId(), id -> new HashMap<>());
            when(bot.getItemQuantity(eq(TICKET), eq(false))).thenAnswer(inv -> bag.getOrDefault(TICKET, 0));
            when(bot.getItemQuantity(eq(KEY), eq(false))).thenAnswer(inv -> bag.getOrDefault(KEY, 0));
        }
        bags.get(THANE).put(TICKET, 1);
        bags.get(MAGNA).put(KEY, 10);
        game.registry.when(() -> AgentRuntimeRegistry.entriesForLeader(ANN)).thenReturn(crew);
        game.config.COMPANION_PQ_ENABLED_KEYS = "kpq,apq";
        gate = new CompanionTicketGate(game.world, () -> now[0], this::amos);
    }

    @AfterEach
    void close() {
        game.close();
    }

    /** Amos the Strong's script (9201043.js) for the companion, as a click runs it. */
    private boolean amos(AgentRuntimeEntry runtime, int selection) {
        Character bot = runtime.bot();
        talks.add(bot.getName() + (selection == CompanionTicketGate.TRADE ? " trade" : " enter"));
        if (amosRefuses) {
            return false;
        }
        Map<Integer, Integer> bag = bags.get(bot.getId());
        if (selection == CompanionTicketGate.TRADE && bag.getOrDefault(TICKET, 0) == 0 && bag.getOrDefault(KEY, 0) >= 10) {
            bag.merge(KEY, -10, Integer::sum);
            bag.put(TICKET, 1);
        } else if (selection == CompanionTicketGate.ENTER && bag.getOrDefault(TICKET, 0) > 0) {
            bag.merge(TICKET, -1, Integer::sum);
            bot.changeMap(game.map(ENTRANCE), new Point(0, 0));
        }
        return true;
    }

    /** Ann walks into the entrance: the gate starts each visit, then the visits walk up, read his menu and talk. */
    private void annGoesIn() {
        game.moveAnn(ENTRANCE, 0, 0);
        gate.ownerEntered(ANN, ENTRANCE);
        game.runDueTimers(); // there at once (no Amos on the mock map): it starts reading his menu
        now[0] += CompanionTicketGate.TALK_MS;
        game.runDueTimers();
    }

    @Test
    void eachGoesInOnItsOwnTicketAndOneWithNoneSaysSoOnceAndWaits() {
        annGoesIn();

        assertEquals(List.of("Thane enter", "Magna trade", "Magna enter"), talks);
        assertEquals(ENTRANCE, game.bot(game.personas.get("thane")).getMapId());
        assertEquals(ENTRANCE, game.bot(game.personas.get("magna")).getMapId());
        assertEquals(0, bags.get(MAGNA).getOrDefault(KEY, 0), "his script took the ten keys");
        assertFalse(gate.holds(THANE), "in: it follows again");
        assertFalse(gate.holds(MAGNA));
        assertTrue(gate.holds(ISOLDE), "no ticket, no keys: it stays at the training ground");
        assertEquals(List.of("Isolde: " + CompanionTicketGate.NO_TICKET), game.partyChat);

        game.moveAnn(670_010_200, 0, 0);
        assertFalse(gate.holds(ISOLDE), "Ann went on into the challenge: the hold ends, the instance guard keeps it out");
    }

    @Test
    void aRefusedTalkKeepsItHeldNeverFollowingInOnItsOwnersTicket() {
        amosRefuses = true;
        annGoesIn();

        assertTrue(gate.holds(THANE));
        assertEquals(TRAINING_GROUND, game.bot(game.personas.get("thane")).getMapId());
    }

    @Test
    void aBenchCallToTheEntranceLandsAtTheTrainingGround() {
        assertEquals(TRAINING_GROUND, gate.arrivalMapId(ENTRANCE));
        assertEquals(100_000_000, gate.arrivalMapId(100_000_000), "anywhere else: its owner's own map");
    }

    @Test
    void anyOtherMapHoldsNobody() {
        game.moveAnn(100_000_000, 0, 0);
        gate.ownerEntered(ANN, 100_000_000);

        assertFalse(gate.holds(ISOLDE));
        assertTrue(game.partyChat.isEmpty());
        assertTrue(talks.isEmpty());
    }

    @Test
    void theGateIsOffWithTheApqDesk() {
        game.config.COMPANION_PQ_ENABLED_KEYS = "kpq";
        game.moveAnn(ENTRANCE, 0, 0);
        gate.ownerEntered(ANN, ENTRANCE);

        assertFalse(gate.on());
        assertFalse(gate.holds(ISOLDE), "no companion APQ: it follows on its owner's, as before");
        assertEquals(ENTRANCE, gate.arrivalMapId(ENTRANCE));
        assertTrue(game.partyChat.isEmpty());
    }
}
