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

import java.util.ArrayList;
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
    private static final int PIANUS_CAVE = 230_040_420;
    private static final int PIANUS = 8_510_000;
    private static final int BLOODY_BOOM = 8_510_100;
    private static final int SERUF = 4_220_000;

    private final LifecycleHarness game = new LifecycleHarness();
    private CompanionBossRuns runs;
    private Character isolde;
    private MapleMap field;
    private Monster jrBalrog;
    private boolean annHitIt;
    private final List<Monster> hitByAnn = new ArrayList<>();

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

    /**
     * P16 at Pianus (field_pianus): a Bloody Boom, the group's add, is fair game once Ann has hit Pianus on its map,
     * though nobody has hit that very Boom. Seruf, one of the group's own bosses, still waits for her first hit.
     */
    @Test
    void aGroupsAddIsFairGameOnceAPlayerHitOneOfItsBossesThere() {
        game.config.COMPANION_BOSS_ENABLED_TYPES = "field_pianus";
        MapleMap cave = game.map(PIANUS_CAVE);
        Monster pianus = fieldMob(PIANUS, cave);
        Monster boom = fieldMob(BLOODY_BOOM, cave);
        Monster seruf = fieldMob(SERUF, cave);
        when(cave.getAllMonsters()).thenReturn(List.of(pianus, boom, seruf));

        assertFalse(runs.mayEngageFieldBoss(isolde, boom), "nobody of Ann's party has hit Pianus yet");
        hitByAnn.add(pianus);
        assertTrue(runs.mayEngageFieldBoss(isolde, boom), "Ann fights Pianus: its Booms are fair game");
        assertFalse(runs.mayEngageFieldBoss(isolde, seruf), "Seruf is a boss of its own: a player hits first");
        when(pianus.isAlive()).thenReturn(false);
        assertFalse(runs.mayEngageFieldBoss(isolde, boom), "Pianus fell: a Boom left waits for a player's hit");
    }

    private Monster fieldMob(int mobId, MapleMap on) {
        Monster mob = mock(Monster.class);
        when(mob.getId()).thenReturn(mobId);
        when(mob.isAlive()).thenReturn(true);
        when(mob.getMap()).thenReturn(on);
        when(mob.highestDamagerIdWhere(any())).thenAnswer(invocation ->
                hitByAnn.contains(mob) && ((IntPredicate) invocation.getArgument(0)).test(ANN) ? ANN : 0);
        return mob;
    }

    /**
     * Snack Bar: a player's hits land on the stand (Unknown Snack Bar 8220008), which revives as the boss 8220009 with
     * an empty damage record. The fight goes on: the companions may hit the boss, since Ann hit the form it revived
     * from. A boss that revived from a form nobody of her party hit still waits for her first hit.
     */
    @Test
    void aBossThatRevivedFromAFormAPlayerHitIsFairGame() {
        Monster snackBar = fieldMob(8_220_009, field);
        assertFalse(runs.mayEngageFieldBoss(isolde, snackBar), "nobody of Ann's party hit it or its stand");
        when(snackBar.hitBeforeReviveBy(any())).thenAnswer(invocation ->
                ((IntPredicate) invocation.getArgument(0)).test(ANN));
        assertTrue(runs.mayEngageFieldBoss(isolde, snackBar), "Ann broke its stand");
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
