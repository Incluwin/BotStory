package server.life;

import client.Character;
import config.LivingWorldConfig;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.integration.AgentPresence;
import server.life.PartyExpSplit.Basis;
import server.living.LivingWorld;
import server.living.LivingWorldHooks;
import server.living.roster.CompanionDirectory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * COMPANION_EXP_SHARE (docs/BOT_MANAGEMENT_PLAN.md decision 17): roster companions in a party earn their own cut of a
 * kill, measured against the whole party as before, while the humans split as if the companions were absent.
 */
class ExpShareTest {
    private static final float COMMON = 0.8f;
    private static final float MVP = 0.2f;
    private static final float KILL = 1_000f;

    private final Map<String, Integer> levels = new LinkedHashMap<>();

    @Test
    void withoutCompanionsTheSplitIsTheStockOne() {
        levels.put("ann", 30);
        levels.put("ben", 20);
        Map<String, Basis<String>> bases = split(Set.of(), Map.of("ann", 50L, "ben", 80L));

        assertEquals(new Basis<>(50, 2, "ben"), bases.get("ann"));
        assertEquals(bases.get("ann"), bases.get("ben"));
        assertEquals(0.1f, bases.get("ann").bonusMod(), 1e-6);
    }

    @Test
    void aFullCrewNeverSlowsItsOwnerDown() {
        levels.put("ann", 30);
        Map<String, Basis<String>> alone = split(Set.of(), Map.of("ann", 100L));
        for (String companion : List.of("c1", "c2", "c3", "c4", "c5")) {
            levels.put(companion, 30);
        }
        Map<String, Long> damage = Map.of("ann", 100L, "c1", 40L, "c2", 40L);
        Map<String, Basis<String>> crewed = split(Set.of("c1", "c2", "c3", "c4", "c5"), damage);

        assertEquals(alone.get("ann"), crewed.get("ann"));
        assertEquals(exp("ann", alone), exp("ann", crewed), 1e-3);
        assertEquals(KILL, exp("ann", crewed), 1e-3); // her level is the whole total and she is the MVP
    }

    @Test
    void companionsEarnTheirOwnCutAgainstTheWholeParty() {
        levels.put("ann", 30);
        levels.put("c1", 40);
        levels.put("c2", 20);
        Map<String, Basis<String>> bases = split(Set.of("c1", "c2"), Map.of("ann", 10L, "c1", 90L));

        assertEquals(new Basis<>(90, 3, "c1"), bases.get("c1"));
        assertEquals(bases.get("c1"), bases.get("c2"));
        // the humans' MVP is the top human damager, not the companion that out-damaged them
        assertEquals(new Basis<>(30, 1, "ann"), bases.get("ann"));
        assertTrue(exp("c1", bases) > 0f);
    }

    @Test
    void twoHumansSplitBetweenThemselvesOnly() {
        levels.put("ann", 30);
        levels.put("ben", 20);
        levels.put("hadrik", 31);
        levels.put("brin", 25);
        Map<String, Basis<String>> bases = split(Set.of("hadrik", "brin"), Map.of("ben", 70L, "hadrik", 200L));

        assertEquals(new Basis<>(50, 2, "ben"), bases.get("ann"));
        assertEquals(bases.get("ann"), bases.get("ben"));
        assertEquals(new Basis<>(106, 4, "hadrik"), bases.get("brin"));
        assertEquals(0.1f, bases.get("ann").bonusMod(), 1e-6);
        assertEquals(0.2f, bases.get("brin").bonusMod(), 1e-6);
    }

    @Test
    void theShareFormulaIsTheStockOne() {
        // (COMMON * level) / total, plus the MVP part, times the participation EXP
        assertEquals((COMMON * 30 / 90) * KILL, PartyExpSplit.share(30, 90, false, KILL, COMMON, MVP), 1e-3);
        assertEquals((COMMON * 30 / 90 + MVP) * KILL, PartyExpSplit.share(30, 90, true, KILL, COMMON, MVP), 1e-3);
    }

    @Test
    void theHookNamesOnlyRosterCompanionsAndHonoursTheSwitch() {
        LivingWorld world = mock(LivingWorld.class);
        CompanionDirectory directory = mock(CompanionDirectory.class);
        LivingWorldConfig config = new LivingWorldConfig();
        when(world.config()).thenReturn(config);
        when(world.directory()).thenReturn(directory);
        Character hadrik = character(900);
        Character plainAgent = character(960);
        Character ann = character(10);
        when(directory.ownerOfCharacter(900)).thenReturn(10);

        try (MockedStatic<LivingWorld> living = mockStatic(LivingWorld.class);
             MockedStatic<AgentPresence> presence = mockStatic(AgentPresence.class)) {
            living.when(LivingWorld::get).thenReturn(world);
            presence.when(() -> AgentPresence.isAgent(hadrik)).thenReturn(true);
            presence.when(() -> AgentPresence.isAgent(plainAgent)).thenReturn(true);

            assertTrue(config.COMPANION_EXP_SHARE); // decision 17's default
            assertTrue(LivingWorldHooks.expShareCompanion(hadrik));
            assertFalse(LivingWorldHooks.expShareCompanion(plainAgent));
            assertFalse(LivingWorldHooks.expShareCompanion(ann));

            config.COMPANION_EXP_SHARE = false;
            assertFalse(LivingWorldHooks.expShareCompanion(hadrik));

            living.when(LivingWorld::get).thenReturn(null);
            config.COMPANION_EXP_SHARE = true;
            assertFalse(LivingWorldHooks.expShareCompanion(hadrik));
        }
    }

    private Map<String, Basis<String>> split(Set<String> companions, Map<String, Long> damage) {
        return PartyExpSplit.bases(List.copyOf(levels.keySet()), levels::get, companions::contains, damage);
    }

    /** One member's cut of a kill, with its party bonus, the way Monster hands it out. */
    private float exp(String member, Map<String, Basis<String>> bases) {
        Basis<String> basis = bases.get(member);
        float share = PartyExpSplit.share(levels.get(member), basis.totalLevel(), member.equals(basis.mvp()), KILL, COMMON, MVP);
        return share + basis.bonusMod() * share;
    }

    private static Character character(int id) {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(id);
        return character;
    }
}
