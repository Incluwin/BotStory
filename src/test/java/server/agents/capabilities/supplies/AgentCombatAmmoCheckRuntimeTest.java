package server.agents.capabilities.supplies;

import server.agents.runtime.AgentRuntimeEntry;

import client.Character;
import client.inventory.WeaponType;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.combat.AgentAttackExecutionProvider;
import server.agents.capabilities.combat.AgentCombatAmmoCounter;
import server.agents.capabilities.combat.AgentCombatAttackExecutionPolicy;
import server.agents.capabilities.combat.AgentCombatRuntime;
import server.agents.capabilities.dialogue.AgentDialogueCatalog;
import server.agents.capabilities.expedition.boss.AgentBossRunRegistry;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.capabilities.partyquest.AgentPartyQuestRuntime;
import server.agents.runtime.AgentModeStateRuntime;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class AgentCombatAmmoCheckRuntimeTest {
    @Test
    void warnsOnceWhenProjectileAmmoIsLow() {
        Character bot = mock(Character.class);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(bot, null, null);

        try (MockedStatic<AgentAttackExecutionProvider> attacks = mockStatic(AgentAttackExecutionProvider.class);
             MockedStatic<AgentCombatAmmoCounter> ammo = mockStatic(AgentCombatAmmoCounter.class);
             MockedStatic<AgentCombatRuntime> runtime = mockStatic(AgentCombatRuntime.class)) {
            attacks.when(() -> AgentAttackExecutionProvider.getEquippedWeaponType(bot)).thenReturn(WeaponType.BOW);
            ammo.when(() -> AgentCombatAmmoCounter.isRangedAmmoWeapon(WeaponType.BOW)).thenReturn(true);
            ammo.when(() -> AgentCombatAmmoCounter.countAmmo(bot, WeaponType.BOW)).thenReturn(10);

            AgentCombatAmmoCheckRuntime.tickAmmoCheck(entry, bot, 100, 5);

            assertTrue(AgentAmmoStateRuntime.ammoWarnSent(entry));
            assertFalse(AgentAmmoStateRuntime.noAmmo(entry));
            runtime.verify(() -> AgentCombatRuntime.sayMapNow(eq(bot), anyString()));
        }
    }

    @Test
    void clearsWarningStateForWeaponsWithoutAmmoNeed() {
        Character bot = mock(Character.class);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(bot, null, null);
        AgentAmmoStateRuntime.setAmmoWarnSent(entry, true);
        AgentAmmoStateRuntime.setNoAmmo(entry, true);

        try (MockedStatic<AgentAttackExecutionProvider> attacks = mockStatic(AgentAttackExecutionProvider.class);
             MockedStatic<AgentCombatAmmoCounter> ammo = mockStatic(AgentCombatAmmoCounter.class)) {
            attacks.when(() -> AgentAttackExecutionProvider.getEquippedWeaponType(bot)).thenReturn(WeaponType.SWORD1H);
            ammo.when(() -> AgentCombatAmmoCounter.isRangedAmmoWeapon(WeaponType.SWORD1H)).thenReturn(false);

            AgentCombatAmmoCheckRuntime.tickAmmoCheck(entry, bot, 100, 5);

            assertFalse(AgentAmmoStateRuntime.ammoWarnSent(entry));
            assertFalse(AgentAmmoStateRuntime.noAmmo(entry));
        }
    }

    // ---- running dry inside a companion run (2026-10-07: Kestrel at LPQ stage 7 said nothing) -------------

    @Test
    void aCompanionRunMemberThatRunsDrySaysOneInRunLineAndKeepsItsPost() {
        try (Scene scene = new Scene(WeaponType.CLAW, 0)) {
            scene.companionRun = true;
            AgentModeStateRuntime.setGrinding(scene.entry, true);

            scene.tick();
            scene.tick();

            scene.movement.verify(() -> AgentMovementCommandRuntime.followConfiguredTarget(any()), never());
            assertEquals(1, scene.said.size(), scene.said.toString());
            assertTrue(inRunLines("stars").contains(scene.said.getFirst()), scene.said.toString());
            assertTrue(AgentAmmoStateRuntime.noAmmo(scene.entry));
            assertTrue(AgentAmmoStateRuntime.dryLineSaid(scene.entry));
        }
    }

    @Test
    void aMemberThatEntersACompanionRunAlreadyDrySaysTheLineOnce() {
        try (Scene scene = new Scene(WeaponType.CLAW, 0)) {
            AgentAmmoStateRuntime.setNoAmmo(scene.entry, true); // ran dry in the run before (EPQ, then LPQ)
            AgentAmmoStateRuntime.setAmmoWarnSent(scene.entry, true);
            scene.companionRun = true;

            scene.tick();
            scene.tick();

            assertEquals(1, scene.said.size(), scene.said.toString());
            assertTrue(inRunLines("stars").contains(scene.said.getFirst()));
        }
    }

    @Test
    void aCompanionRunMemberHandedStarsAfterRunningDryCanShootAgain() {
        try (Scene scene = new Scene(WeaponType.CLAW, 300)) {
            AgentAmmoStateRuntime.setNoAmmo(scene.entry, true);
            AgentAmmoStateRuntime.setAmmoWarnSent(scene.entry, true);
            AgentAmmoStateRuntime.setDryLineSaid(scene.entry, true);
            scene.companionRun = true;

            scene.tick();

            assertFalse(AgentAmmoStateRuntime.noAmmo(scene.entry), "a loot or a share lets it throw again at once");
            assertTrue(AgentAmmoStateRuntime.ammoWarnSent(scene.entry));
            assertTrue(AgentAmmoStateRuntime.dryLineSaid(scene.entry), "the dry news keeps its 500 hysteresis");
            assertEquals(List.of(), scene.said);
            assertEquals(AgentCombatAttackExecutionPolicy.AttackExecutionReadiness.READY,
                    AgentCombatAttackExecutionPolicy.attackExecutionReadiness(false,
                            AgentAmmoStateRuntime.noAmmo(scene.entry), 0, () -> true, () -> true),
                    "a RANGED plan is no longer deferred");
        }
    }

    @Test
    void aStockMemberHandedStarsAfterRunningDryStaysBlockedTillFiveHundred() {
        try (Scene scene = new Scene(WeaponType.CLAW, 300)) {
            AgentAmmoStateRuntime.setNoAmmo(scene.entry, true);
            AgentAmmoStateRuntime.setAmmoWarnSent(scene.entry, true);

            scene.tick();

            assertTrue(AgentAmmoStateRuntime.noAmmo(scene.entry), "stock runs and grinding keep today's threshold");
        }
    }

    @Test
    void aStockMemberThatRunsDryStillHeadsBack() {
        try (Scene scene = new Scene(WeaponType.CLAW, 0)) {
            AgentModeStateRuntime.setGrinding(scene.entry, true);

            scene.tick();

            scene.movement.verify(() -> AgentMovementCommandRuntime.followConfiguredTarget(scene.entry));
            assertEquals(1, scene.said.size());
            assertTrue(AgentDialogueCatalog.combatAmmoOutReplies().contains(scene.said.getFirst()), scene.said.toString());
            assertFalse(AgentAmmoStateRuntime.dryLineSaid(scene.entry));
        }
    }

    @Test
    void aBossRunMemberThatRunsDrySaysTheInRunLine() {
        try (Scene scene = new Scene(WeaponType.BOW, 0)) {
            scene.bossRun = true;
            AgentModeStateRuntime.setGrinding(scene.entry, true);

            scene.tick();

            scene.movement.verify(() -> AgentMovementCommandRuntime.followConfiguredTarget(any()), never());
            assertEquals(1, scene.said.size());
            assertTrue(inRunLines("arrows").contains(scene.said.getFirst()), scene.said.toString());
        }
    }

    @Test
    void aMemberWhoseRunOwnsTheDryNewsSaysNothingHere() {
        try (Scene scene = new Scene(WeaponType.CLAW, 0)) {
            scene.companionRun = true;
            scene.ownsDryLine = true;

            scene.tick();

            assertEquals(List.of(), scene.said, "LPQ stage 7 says it with who takes the ratz");
            assertFalse(AgentAmmoStateRuntime.dryLineSaid(scene.entry));
            assertTrue(AgentAmmoStateRuntime.noAmmo(scene.entry));
        }
    }

    @Test
    void soulArrowDoesNotMakeAnEmptyQuiverNewsAgain() {
        // review: Soul Arrow counts as endless ammo, which cleared the flag, so each expiry repeated the line
        try (Scene scene = new Scene(WeaponType.BOW, 0)) {
            scene.companionRun = true;
            scene.tick();
            scene.count = Integer.MAX_VALUE; // Soul Arrow up; the quiver is still empty
            scene.tick();
            scene.count = 0; // it expires
            scene.tick();

            assertEquals(1, scene.said.size(), scene.said.toString());
        }
    }

    @Test
    void aBagBackAtTheWarningMakesTheNextDryEpisodeNews() {
        try (Scene scene = new Scene(WeaponType.CLAW, 0)) {
            scene.companionRun = true;
            scene.tick();
            scene.count = 300; // a share: it throws again, but the dry news keeps its hysteresis
            scene.carried = 300;
            scene.tick();
            scene.count = 0;
            scene.carried = 0;
            scene.tick();
            assertEquals(1, dryLines(scene), scene.said.toString());

            scene.count = 600; // refilled past AMMO_LOW_WARN
            scene.carried = 600;
            scene.tick();
            scene.count = 0;
            scene.carried = 0;
            scene.tick();
            assertEquals(2, dryLines(scene), scene.said.toString());
        }
    }

    /** The in-run dry lines it said (its "running low" warning aside). */
    private static long dryLines(Scene scene) {
        return scene.said.stream().filter(inRunLines("stars")::contains).count();
    }

    @Test
    void aMemberThatEntersItsNextRunStillDrySaysItThere() {
        // review: the prod-lane harness skips the desks, so a companion can go from one run to the next unrestocked
        try (Scene scene = new Scene(WeaponType.CLAW, 0)) {
            scene.companionRun = true;
            scene.tick();
            scene.companionRun = false; // the run is over
            scene.tick();
            scene.companionRun = true; // the next one
            scene.tick();

            assertEquals(2, scene.said.size(), scene.said.toString());
        }
    }

    private static List<String> inRunLines(String word) {
        return AgentDialogueCatalog.inRunAmmoOutReplies().stream().map(line -> String.format(line, word)).toList();
    }

    /** One Agent with a ranged weapon and {@code count} projectiles; its run and its words are recorded. */
    private static final class Scene implements AutoCloseable {
        final Character bot = mock(Character.class);
        final AgentRuntimeEntry entry = new AgentRuntimeEntry(bot, null, null);
        final List<String> said = new ArrayList<>();
        boolean companionRun;
        boolean bossRun;
        boolean ownsDryLine;
        /** What it counts (Integer.MAX_VALUE under Soul Arrow) and what its bag holds. */
        int count;
        int carried;
        final MockedStatic<AgentAttackExecutionProvider> attacks = mockStatic(AgentAttackExecutionProvider.class);
        final MockedStatic<AgentCombatAmmoCounter> ammo =
                mockStatic(AgentCombatAmmoCounter.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        final MockedStatic<AgentCombatRuntime> runtime = mockStatic(AgentCombatRuntime.class);
        final MockedStatic<AgentPartyQuestRuntime> runs = mockStatic(AgentPartyQuestRuntime.class);
        final MockedStatic<AgentBossRunRegistry> bosses = mockStatic(AgentBossRunRegistry.class);
        final MockedStatic<AgentMovementCommandRuntime> movement = mockStatic(AgentMovementCommandRuntime.class);

        Scene(WeaponType weapon, int count) {
            when(bot.getId()).thenReturn(77);
            when(bot.getMapId()).thenReturn(922_010_700);
            attacks.when(() -> AgentAttackExecutionProvider.getEquippedWeaponType(bot)).thenReturn(weapon);
            this.count = count;
            this.carried = count;
            ammo.when(() -> AgentCombatAmmoCounter.countAmmo(bot, weapon)).thenAnswer(inv -> this.count);
            ammo.when(() -> AgentCombatAmmoCounter.countCarried(bot, weapon)).thenAnswer(inv -> this.carried);
            runtime.when(() -> AgentCombatRuntime.sayMapNow(eq(bot), anyString()))
                    .thenAnswer(inv -> said.add(inv.getArgument(1)));
            runs.when(() -> AgentPartyQuestRuntime.companionRun(anyInt())).thenAnswer(inv -> companionRun);
            runs.when(() -> AgentPartyQuestRuntime.ownsDryLine(anyInt(), anyInt())).thenAnswer(inv -> ownsDryLine);
            bosses.when(() -> AgentBossRunRegistry.inside(anyInt())).thenAnswer(inv -> bossRun);
        }

        void tick() {
            AgentCombatAmmoCheckRuntime.tickAmmoCheck(entry, bot, 500, 5);
        }

        @Override
        public void close() {
            movement.close();
            bosses.close();
            runs.close();
            runtime.close();
            ammo.close();
            attacks.close();
        }
    }
}
