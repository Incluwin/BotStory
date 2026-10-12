package server.living.roster;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.expedition.zakum.AgentZakumDefinition;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterState;

import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;

/**
 * P12 G11, companion boss scope case 3: a companion on the recruit or gate map of a boss the switch lists, beside the
 * owner it follows, is barred from summoning there (the Agent side checks the map's SUMMON bit). Off the switch, on
 * another map, or with the owner gone, nothing changes.
 */
class CompanionBossRunsSummonGateTest {
    private static final int ISOLDE = 901;
    private static final int ROOM = AgentZakumDefinition.RECRUIT_MAP_ID;

    private final LifecycleHarness game = new LifecycleHarness();
    private CompanionBossRuns runs;
    private Persona persona;
    private Character isolde;

    @BeforeEach
    void annStandsInAdobissRoomWithIsolde() {
        persona = game.companion("isolde", "Isolde", ISOLDE, RosterState.ACTIVE, ROOM);
        game.moveAnn(ROOM, 100, 0);
        game.roster();
        game.outWithAnn(persona);
        isolde = game.bot(persona);
        when(game.map(ROOM).getCharacterById(ANN)).thenReturn(game.ann);
        game.config.COMPANION_BOSS_ENABLED_TYPES = "zakum";
        ConcurrentHashMap<String, CompanionBossRun> parts = new ConcurrentHashMap<>();
        CompanionBossRuns.OpenRuns open = mock(CompanionBossRuns.OpenRuns.class);
        runs = new CompanionBossRuns(game.world, open, id -> null, CompanionBossRuns.Supplies.LIVE, parts,
                new CompanionBossAllies(game.world, open, parts, new CompanionBossQuota(open)));
    }

    @AfterEach
    void close() {
        game.close();
    }

    @Test
    void aCompanionBesideItsOwnerInTheRecruitRoomOfAListedBossIsInScope() {
        assertTrue(runs.besideOwnerAtBossGate(isolde));
    }

    @Test
    void offTheSwitchOrWithTheOwnerGoneItIsNot() {
        game.config.COMPANION_BOSS_ENABLED_TYPES = "easy_balrog";
        assertFalse(runs.besideOwnerAtBossGate(isolde), "the switch leaves Zakum out");

        game.config.COMPANION_BOSS_ENABLED_TYPES = "zakum";
        when(game.map(ROOM).getCharacterById(ANN)).thenReturn(null);
        assertFalse(runs.besideOwnerAtBossGate(isolde), "Ann left the room");
    }

    @Test
    void onAMapNoBossRecruitsOnItIsNot() {
        game.moveAnn(100_000_000, 100, 0);
        game.placeWithAnn(persona);
        when(game.map(100_000_000).getCharacterById(ANN)).thenReturn(game.ann);
        assertFalse(runs.besideOwnerAtBossGate(isolde), "Henesys");
        assertFalse(runs.besideOwnerAtBossGate(null));
    }
}
