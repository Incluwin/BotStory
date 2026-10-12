package server.living.roster;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;

/**
 * P15 G8 at the Papulatus gate (220080000): as Ann comes onto it, a companion of hers with no Papulatus entry left
 * today stays on the map before with one quota line, until she leaves the gate; one with an entry left follows on.
 * The stock party check then never names the capped one. The read asks the boss log by its capital name.
 */
class CompanionBossGateCapTest {
    private static final int ISOLDE = 901;
    private static final int THANE = 902;
    private static final int PATH_OF_TIME = 220_070_400;
    private static final int GATE = 220_080_000;

    private final LifecycleHarness game = new LifecycleHarness();
    private final Set<Integer> capped = new HashSet<>();
    private final List<String> asked = new ArrayList<>();
    private CompanionBossGateCap cap;
    private Character isolde;
    private Character thane;

    @BeforeEach
    void annAndHerCrewBeforeTheGate() {
        Persona isoldePersona = game.companion("isolde", "Isolde", ISOLDE, RosterState.ACTIVE, PATH_OF_TIME);
        Persona thanePersona = game.companion("thane", "Thane", THANE, RosterState.ACTIVE, PATH_OF_TIME);
        game.moveAnn(PATH_OF_TIME, 100, 0);
        game.roster();
        game.outWithAnn(isoldePersona);
        game.outWithAnn(thanePersona);
        isolde = game.bot(isoldePersona);
        thane = game.bot(thanePersona);
        List<AgentRuntimeEntry> crew = List.of(new AgentRuntimeEntry(isolde, game.ann, null),
                new AgentRuntimeEntry(thane, game.ann, null));
        game.registry.when(() -> AgentRuntimeRegistry.entriesForLeader(ANN)).thenReturn(crew);
        cap = new CompanionBossGateCap(game.world, (id, name) -> {
            asked.add(id + " " + name);
            return !capped.contains(id);
        });
    }

    @AfterEach
    void close() {
        game.close();
    }

    @Test
    void aCappedCompanionStaysOffTheGateWithOneLineUntilItsOwnerLeaves() {
        capped.add(ISOLDE);
        game.moveAnn(GATE, 0, 0);
        cap.ownerEntered(ANN, GATE, "papulatus");

        assertTrue(cap.holds(ISOLDE), "no Papulatus entry left: it stays before the gate");
        assertFalse(cap.holds(THANE), "an entry left: it follows on");
        assertEquals(List.of(ISOLDE + " PAPULATUS", THANE + " PAPULATUS"), asked);
        assertEquals(1, game.partyChat.size(), game.partyChat.toString());
        String chat = game.partyChat.get(0);
        assertTrue(BossRunLines.GENERIC.get(BossRunLines.QUOTA).contains(chat.substring(chat.indexOf(": ") + 2)), chat);

        game.moveAnn(220_080_001, 0, 0);
        assertFalse(cap.holds(ISOLDE), "Ann went in: the hold ends, and the instance guard keeps it out");
        assertFalse(cap.holds(ISOLDE));
    }

    @Test
    void aPartyBossGateHoldsOnlyWhileTheSwitchListsItsBoss() {
        CompanionBossRuns runs = new CompanionBossRuns(game.world);
        when(game.world.bossRuns()).thenReturn(runs);
        game.config.COMPANION_BOSS_ENABLED_TYPES = "easy_balrog";
        assertNull(runs.partyGateKeyAt(GATE), "papulatus off: no hold at its gate");

        game.config.COMPANION_BOSS_ENABLED_TYPES = "easy_balrog,papulatus";
        assertEquals("papulatus", runs.partyGateKeyAt(GATE));
        assertNull(runs.partyGateKeyAt(PATH_OF_TIME));
        assertEquals("latanica", runsWithLatanica().partyGateKeyAt(541_010_060), "every party boss gate");
    }

    private CompanionBossRuns runsWithLatanica() {
        game.config.COMPANION_BOSS_ENABLED_TYPES = "latanica";
        return new CompanionBossRuns(game.world);
    }
}
