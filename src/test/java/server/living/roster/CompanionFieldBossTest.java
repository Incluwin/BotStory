package server.living.roster;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.expedition.boss.AgentBossRunHost;
import server.life.Monster;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;

/**
 * P13 G14 at Jr. Balrog (105090900, field_victoria): a companion joins only once a player of its party has hit that very
 * boss (decision 17), a citizen never hits it (decision 19), and a companion that died beside it rests in town before
 * it follows again (decision 18), until it is back to full or the rest's time limit.
 */
class CompanionFieldBossTest {
    private static final int ISOLDE = 901;
    private static final int CITIZEN = 950;
    private static final int JR_BALROG_MAP = 105_090_900;
    private static final int JR_BALROG = 8_130_100;

    private final LifecycleHarness game = new LifecycleHarness();
    private CompanionBossRuns runs;
    private Character isolde;
    private MapleMap field;
    private Monster jrBalrog;
    private boolean annHitIt;

    @BeforeEach
    void annAndIsoldeAtJrBalrog() {
        Persona persona = game.companion("isolde", "Isolde", ISOLDE, RosterState.ACTIVE, JR_BALROG_MAP);
        game.moveAnn(JR_BALROG_MAP, 100, 0);
        game.roster();
        game.outWithAnn(persona);
        isolde = game.bot(persona);
        game.config.COMPANION_BOSS_ENABLED_TYPES = "field_victoria";
        field = game.map(JR_BALROG_MAP);
        jrBalrog = mock(Monster.class);
        when(jrBalrog.getId()).thenReturn(JR_BALROG);
        when(jrBalrog.isAlive()).thenReturn(true);
        when(jrBalrog.getMap()).thenReturn(field);
        when(jrBalrog.highestDamagerIdWhere(any())).thenAnswer(invocation ->
                annHitIt && ((IntPredicate) invocation.getArgument(0)).test(ANN) ? ANN : 0);
        when(field.getAllMonsters()).thenReturn(List.of(jrBalrog));
        ConcurrentHashMap<String, CompanionBossRun> parts = new ConcurrentHashMap<>();
        CompanionBossRuns.OpenRuns open = mock(CompanionBossRuns.OpenRuns.class);
        runs = new CompanionBossRuns(game.world, open, id -> null, CompanionBossRuns.Supplies.LIVE, parts,
                new CompanionBossAllies(game.world, open, parts, new CompanionBossQuota(open)));
        AgentBossRunHost.install(runs);
    }

    @AfterEach
    void close() {
        AgentBossRunHost.install(null);
        game.close();
    }

    @Test
    void aCompanionJoinsOnlyOnceAPlayerOfItsPartyHasHitThatVeryBoss() {
        assertFalse(runs.mayEngageFieldBoss(isolde, jrBalrog), "nobody of Ann's party has hit it: a fresh spawn");
        annHitIt = true;
        assertTrue(runs.mayEngageFieldBoss(isolde, jrBalrog));
    }

    @Test
    void aCitizenNeverHitsIt() {
        annHitIt = true;
        Character citizen = game.bot(CITIZEN, "Brindle");
        game.standsBesideAnn(citizen);
        assertFalse(runs.mayEngageFieldBoss(citizen, jrBalrog), "decision 19");
        assertFalse(runs.mayEngageFieldBoss(null, jrBalrog));
    }

    @Test
    void aCompanionThatDiedBesideItRestsUntilFullThenFollows() {
        when(isolde.isAlive()).thenReturn(false);
        runs.died(isolde);
        assertTrue(runs.restsAfterFieldBoss(ISOLDE), "dead");

        when(isolde.isAlive()).thenReturn(true);
        when(isolde.getHp()).thenReturn(50);
        when(isolde.getCurrentMaxHp()).thenReturn(4000);
        when(isolde.getMp()).thenReturn(800);
        when(isolde.getCurrentMaxMp()).thenReturn(800);
        assertTrue(runs.restsAfterFieldBoss(ISOLDE), "revived in town at 50 HP");
        when(game.world.bossRuns()).thenReturn(runs);
        assertTrue(new CompanionFollowGuard(game.world).holdsOffFollow(ISOLDE), "the follow guard keeps it in town");

        when(isolde.getHp()).thenReturn(4000);
        assertFalse(runs.restsAfterFieldBoss(ISOLDE), "back to full: it follows");
        assertFalse(runs.restsAfterFieldBoss(ISOLDE), "the rest is over");
    }

    @Test
    void theRestEndsAtItsTimeLimitAndOnlyADeathBesideACoveredBossStartsOne() {
        game.config.COMPANION_FIELD_BOSS_REST_MAX_MS = 0;
        runs.died(isolde);
        assertFalse(runs.restsAfterFieldBoss(ISOLDE), "a limit of 0 ends it at once");

        game.config.COMPANION_FIELD_BOSS_REST_MAX_MS = 120_000;
        game.config.COMPANION_BOSS_ENABLED_TYPES = "easy_balrog";
        runs.died(isolde);
        assertFalse(runs.restsAfterFieldBoss(ISOLDE), "field_victoria is off");

        game.config.COMPANION_BOSS_ENABLED_TYPES = "field_victoria";
        Character citizen = game.bot(CITIZEN, "Brindle");
        game.standsBesideAnn(citizen);
        runs.died(citizen);
        assertFalse(runs.restsAfterFieldBoss(CITIZEN), "a citizen is no companion");
    }
}
