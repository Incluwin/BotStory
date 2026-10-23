package server.living.roster;

import client.Character;
import client.Job;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.npc.NPCConversationManager;
import server.expeditions.Expedition;
import server.expeditions.ExpeditionType;
import server.integration.AgentPresence;
import server.living.LivingWorld;
import server.living.LivingWorldHooks;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterState;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * CWKPQ plan C3, decision 5 at Jack: with companions on his list, he starts the run only once every archetype's sigil
 * can be turned by someone on it (its job and one of its sigil skills); an expedition of humans alone starts as stock.
 * While the list is open, a party companion of the owner warns once each: nobody can turn a sigil, only one member can,
 * or fewer than eight are on the list.
 */
class CompanionBossRunsCwkpqTest {
    private static final int ISOLDE = 901;
    private static final int THANE = 902;
    private static final int HALL = 610_030_020;

    private final LifecycleHarness game = new LifecycleHarness();
    private final Expedition expedition = mock(Expedition.class);
    private final List<Character> listed = new ArrayList<>();
    private CompanionBossRuns runs;
    private Character isolde;
    private Character thane;

    @BeforeEach
    void annLeadsTheKeepsExpeditionWithTwoCompanions() {
        game.config.COMPANION_BOSS_ENABLED_TYPES = "cwkpq";
        Persona isoldePersona = game.companion("isolde", "Isolde", ISOLDE, RosterState.ACTIVE, HALL);
        Persona thanePersona = game.companion("thane", "Thane", THANE, RosterState.ACTIVE, HALL);
        game.moveAnn(HALL, 976, 94);
        game.roster();
        game.outWithAnn(isoldePersona);
        game.outWithAnn(thanePersona);
        isolde = game.bot(isoldePersona);
        thane = game.bot(thanePersona);
        when(game.ann.getClient().getChannelServer().getExpedition(ExpeditionType.CWKPQ)).thenReturn(expedition);
        when(expedition.isRegistering()).thenReturn(true);
        when(expedition.getLeader()).thenReturn(game.ann);
        when(expedition.getActiveMembers()).thenAnswer(inv -> List.copyOf(listed));
        runs = new CompanionBossRuns(game.world);
        when(game.world.bossRuns()).thenReturn(runs);
        AgentPresence.install(chr -> chr.getId() == ISOLDE || chr.getId() == THANE);
        turns(game.ann, Job.HERO, 0);
        turns(isolde, Job.BISHOP, 2_321_003);
        turns(thane, Job.HERMIT, 4_111_005);
    }

    @AfterEach
    void close() {
        AgentPresence.install(null);
        game.close();
    }

    @Test
    void anExpeditionOfHumansAloneStartsAsStock() {
        listed.add(game.ann);
        assertNull(runs.cwkpqStartRefusal(game.ann), "no companion on the list: Jack's own rules only");
    }

    @Test
    void withCompanionsOnTheListJackNamesEverySigilNobodyCanTurn() {
        listed.addAll(List.of(game.ann, isolde, thane));

        String refusal = runs.cwkpqStartRefusal(game.ann);

        assertEquals("Your expedition cannot open the way through the keep: nobody on your list can turn the"
                        + " #bWarrior#k sigil (Shout, Charged Blow, Dragon Fury: Spear or Dragon Fury: Pole Arm),"
                        + " the #bBowman#k sigil (Silver Hawk, Strafe, Golden Eagle or Piercing Arrow) or the #bPirate#k"
                        + " sigil (Flash Fist, Transformation, Dragon Strike, Gaviota or Rapid Fire). Bring someone who"
                        + " can, then talk to me again.",
                refusal, "Ann is a Hero with no sigil skill yet: she counts for none");
    }

    @Test
    void everySigilCoveredStartsAndTheHookAnswersTheSame() {
        listed.addAll(List.of(game.ann, isolde, thane, member(Job.BOWMASTER, 3_111_005),
                member(Job.CORSAIR, 5_211_002)));
        turns(game.ann, Job.HERO, 1_111_008);

        assertNull(runs.cwkpqStartRefusal(game.ann));
        try (var hooks = mockStatic(LivingWorld.class)) {
            LivingWorld world = game.world;
            hooks.when(LivingWorld::get).thenReturn(world);
            NPCConversationManager cm = mock(NPCConversationManager.class);
            when(cm.getPlayer()).thenReturn(game.ann);
            assertNull(LivingWorldHooks.cwkpqStartRefusal(cm));
            listed.removeLast();
            assertTrue(LivingWorldHooks.cwkpqStartRefusal(cm).contains("#bPirate#k"));
        }
    }

    @Test
    void aPartyCompanionWarnsOnceForEachKindWhileTheListIsOpen() {
        listed.addAll(List.of(game.ann, isolde, thane));
        CompanionBossRun run = new CompanionBossRun("cwkpq", 1L, LifecycleHarness.ANN);
        run.signedUp.addAll(Set.of(ISOLDE, THANE));

        runs.warnSigils(run, null);
        runs.warnSigils(run, null);
        assertEquals(1, game.partyChat.size(), game.partyChat.toString());
        assertTrue(said(BossRunLines.SIGIL_MISSING, "warrior"), game.partyChat.toString());

        turns(game.ann, Job.HERO, 1_111_008);
        listed.addAll(List.of(member(Job.BOWMASTER, 3_111_005), member(Job.CORSAIR, 5_211_002)));
        runs.warnSigils(run, null);
        assertEquals(2, game.partyChat.size());
        assertTrue(said(BossRunLines.SMALL_LIST, null), "every sigil covered, five on the list");

        runs.warnSigils(run, null);
        assertEquals(3, game.partyChat.size());
        assertTrue(said(BossRunLines.SIGIL_THIN, "warrior"), "then the first archetype only one can turn");

        runs.warnSigils(run, null);
        listed.addAll(List.of(member(Job.PALADIN, 1_211_002), member(Job.IL_ARCHMAGE, 2_211_002),
                member(Job.MARKSMAN, 3_211_005), member(Job.SHADOWER, 4_211_001), member(Job.BUCCANEER, 5_121_001)));
        runs.warnSigils(run, null);
        assertEquals(3, game.partyChat.size(), "each warning once a run, and ten with two of each need none");
    }

    @Test
    void twoOfEachOnAListOfTenSaysNothing() {
        listed.addAll(List.of(game.ann, isolde, thane, member(Job.PALADIN, 1_211_002),
                member(Job.BOWMASTER, 3_111_005), member(Job.MARKSMAN, 3_211_005), member(Job.SHADOWER, 4_211_001),
                member(Job.CORSAIR, 5_211_002), member(Job.BUCCANEER, 5_121_001), member(Job.IL_ARCHMAGE, 2_211_002)));
        turns(game.ann, Job.HERO, 1_111_008);
        CompanionBossRun run = new CompanionBossRun("cwkpq", 1L, LifecycleHarness.ANN);
        run.signedUp.addAll(Set.of(ISOLDE, THANE));

        runs.warnSigils(run, null);
        assertTrue(game.partyChat.isEmpty(), game.partyChat.toString());
    }

    @Test
    void anotherBossesRunNeverWarnsOfSigils() {
        listed.addAll(List.of(game.ann, isolde, thane));
        CompanionBossRun run = new CompanionBossRun("horntail", 1L, LifecycleHarness.ANN);
        run.signedUp.addAll(Set.of(ISOLDE, THANE));

        runs.warnSigils(run, null);
        assertTrue(game.partyChat.isEmpty());
    }

    /** True when the last party line is one of {@code intent}'s cwkpq wordings, "{name}" read as {@code name}. */
    private boolean said(String intent, String name) {
        String chat = game.partyChat.getLast();
        String line = chat.substring(chat.indexOf(": ") + 2);
        return BossRunLines.BY_BOSS.get("cwkpq").get(intent).stream()
                .anyMatch(wording -> wording.replace("{name}", name == null ? "" : name).equals(line));
    }

    /** C9 live 2026-10-22: three of the thieves' room's eyes need Flash Jump; Jack says so when no thief holds it. */
    @Test
    void jackCautionsAListWhoseThievesCannotFlashJump() {
        Character shadower = member(Job.SHADOWER, 4_211_001);
        Character nightLord = member(Job.NIGHTLORD, 4_111_005);
        when(nightLord.getSkillLevel(4_111_006)).thenReturn(20); // Flash Jump
        Character hero = member(Job.HERO, 1_111_008);

        assertEquals(JackLines.NO_FLASH_JUMP, CompanionBossRuns.cwkpqThiefReach(List.of(hero, shadower)));
        assertNull(CompanionBossRuns.cwkpqThiefReach(List.of(hero, shadower, nightLord)));
        assertNull(CompanionBossRuns.cwkpqThiefReach(List.of(hero)), "no thief: the start check refuses that list");
    }

    private static void turns(Character member, Job job, int skillId) {
        when(member.getJob()).thenReturn(job);
        when(member.getSkillLevel(anyInt())).thenReturn(0);
        if (skillId != 0) {
            when(member.getSkillLevel(skillId)).thenReturn(1);
        }
    }

    private static Character member(Job job, int skillId) {
        Character member = mock(Character.class);
        turns(member, job, skillId);
        return member;
    }
}
