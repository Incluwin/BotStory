package server.living.imperfections;

import client.Character;
import config.LivingWorldConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.behavior.AgentImperfections;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F8 gate guards (docs/BOT_CONCEPT_PLAN.md F8): the kill switch, only citizens and companions, never in party content,
 * never under the HP guard or with a human of its party in danger, the hourly budget and each kind's cap, one answer
 * per drop, a typo only in town, and a companion AFK only while its owner idles in town, at most 90 s, once an hour.
 */
class ImperfectionsTest {
    private final LivingWorldConfig config = new LivingWorldConfig();
    private final Set<Integer> citizens = new HashSet<>(Set.of(1, 2, 3));
    private final Set<Integer> companions = new HashSet<>(Set.of(20));
    private final Set<Integer> inParty = new HashSet<>();
    private final Set<Integer> cheap = new HashSet<>(Set.of(4000000));
    private final List<String> said = new ArrayList<>();
    private final java.util.ArrayDeque<Double> draws = new java.util.ArrayDeque<>();
    private double draw = 0d;
    private long now = 1_000_000L;

    private final Imperfections.Host host = new Imperfections.Host() {
        @Override public boolean citizen(int characterId) { return citizens.contains(characterId); }
        @Override public boolean companion(int characterId) { return companions.contains(characterId); }
        @Override public boolean partyContent(int characterId) { return inParty.contains(characterId); }
        @Override public boolean cheap(int itemId) { return cheap.contains(itemId); }
        @Override public void say(Character bot, String moment) { said.add(bot.getId() + ":" + moment); }
    };

    private Imperfections gate() {
        config.IMPERFECTIONS_ENABLED = true;
        return new Imperfections(config, host, () -> draws.isEmpty() ? draw : draws.poll(), () -> now);
    }

    @AfterEach
    void off() {
        Imperfections.uninstall();
    }

    private static Character bot(int id, int hp, boolean town) {
        Character bot = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        when(map.isTown()).thenReturn(town);
        when(map.getAllMonsters()).thenReturn(List.of());
        when(bot.getId()).thenReturn(id);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(100000000);
        when(bot.getHp()).thenReturn(hp);
        when(bot.getCurrentMaxHp()).thenReturn(100);
        when(bot.isAlive()).thenReturn(hp > 0);
        when(bot.getPosition()).thenReturn(new Point(0, 0));
        return bot;
    }

    @Test
    void theKillSwitchLeavesEveryAgentAsItWas() {
        assertFalse(new LivingWorldConfig().IMPERFECTIONS_ENABLED);
        Imperfections.install(config, host);
        assertNull(Imperfections.active());
        assertFalse(AgentImperfections.on());
        config.IMPERFECTIONS_ENABLED = true;
        Imperfections.install(config, host);
        assertNotNull(Imperfections.active());
        assertTrue(AgentImperfections.on());
    }

    @Test
    void onlyCitizensAndCompanionsOutsidePartyContent() {
        Imperfections gate = gate();
        assertTrue(gate.calibrated(bot(1, 100, false)));
        assertTrue(gate.calibrated(bot(20, 100, false)));
        assertFalse(gate.calibrated(bot(99, 100, false)), "a Resident, a cohort Agent: never");
        assertFalse(gate.slip(bot(99, 100, false), Imperfections.Kind.LATE_POTION));
        inParty.add(1);
        assertFalse(gate.calibrated(bot(1, 100, false)));
        assertFalse(gate.slip(bot(1, 100, false), Imperfections.Kind.LOOT_SLIP));
        assertTrue(gate.counts().get("blocked.party-content") >= 2);
    }

    @Test
    void noSlipUnderTheHpGuardOrWithAHumanOfItsPartyInDanger() {
        Imperfections gate = gate();
        assertFalse(gate.slip(bot(1, 59, false), Imperfections.Kind.LATE_POTION));
        assertTrue(gate.slip(bot(1, 0, false), Imperfections.Kind.REZ_WAIT), "a dead bot may still wait for a rez");
        Character human = mock(Character.class);
        when(human.getId()).thenReturn(500);
        when(human.isAlive()).thenReturn(true);
        when(human.getHp()).thenReturn(40);
        when(human.getCurrentMaxHp()).thenReturn(100);
        Character bot = bot(2, 100, false);
        when(bot.getPartyMembersOnSameMap()).thenReturn(List.of(bot, human));
        assertFalse(gate.slip(bot, Imperfections.Kind.BUFF_LAPSE));
        assertEquals(1L, gate.counts().get("blocked.owner-danger"));
        assertEquals(1L, gate.counts().get("blocked.hp"));
    }

    @Test
    void theChanceDecidesAndTheHourlyBudgetAndEachKindsCapHold() {
        Imperfections gate = gate();
        Character bot = bot(3, 100, false);
        draw = 0.99d;
        assertFalse(gate.slip(bot, Imperfections.Kind.LATE_POTION), "the chance did not come up");
        draw = 0d;
        for (int i = 0; i < Imperfections.Kind.LATE_POTION.perHour; i++) {
            assertTrue(gate.slip(bot, Imperfections.Kind.LATE_POTION));
        }
        assertFalse(gate.slip(bot, Imperfections.Kind.LATE_POTION), "the kind's own cap");
        assertTrue(gate.slip(bot, Imperfections.Kind.LOOT_SLIP));
        assertTrue(gate.slip(bot, Imperfections.Kind.LOOT_SLIP));
        assertTrue(gate.slip(bot, Imperfections.Kind.BUFF_LAPSE));
        assertTrue(gate.slip(bot, Imperfections.Kind.BUFF_LAPSE));
        assertFalse(gate.slip(bot, Imperfections.Kind.ROPE_MISS), "10 an hour in all");
        now += Imperfections.HOUR_MS;
        assertTrue(gate.slip(bot, Imperfections.Kind.ROPE_MISS), "a sliding hour");
        assertEquals(Imperfections.Kind.LATE_POTION.perHour, gate.counts().get("fired.late_potion"));
    }

    @Test
    void aDropGetsOneAnswerAndOnlyACheapOneIsLeft() {
        Imperfections gate = gate();
        Character bot = bot(1, 100, false);
        assertFalse(gate.leavesDrop(bot, 4000019, 9), "not cheap by the index");
        assertTrue(gate.leavesDrop(bot, 4000000, 10));
        draw = 0.99d;
        assertTrue(gate.leavesDrop(bot, 4000000, 10), "the same drop, the same answer");
        assertFalse(gate.leavesDrop(bot, 4000000, 11));
        assertEquals(1L, gate.counts().get("fired.loot_slip"));
        assertTrue(gate.leftBySlip(10), "owner habits do not learn from it");
        assertFalse(gate.leftBySlip(11));
    }

    @Test
    void aTypoSwapsTwoLettersOfOneWordAndTheFixNamesIt() {
        String[] typo = Imperfections.typo("anyone selling steely", new Random(3));
        assertNotNull(typo);
        assertTrue(typo[1].equals("*anyone") || typo[1].equals("*selling") || typo[1].equals("*steely"), typo[1]);
        assertFalse(typo[0].equals("anyone selling steely"));
        assertEquals("anyone selling steely".length(), typo[0].length());
        assertNull(Imperfections.typo("hi lf kpq", new Random(3)), "no word of four letters");
        assertNull(Imperfections.typo("aaaa", new Random(3)), "nothing to swap");
    }

    @Test
    void aTypoOnlyInTown() {
        Imperfections gate = gate();
        assertNull(gate.typoFix(bot(1, 100, false), "anyone selling steely", "field"));
        assertNull(gate.typoFix(bot(1, 100, false), "anyone selling steely", "town"), "a map that is no town");
        String[] fix = gate.typoFix(bot(1, 100, true), "anyone selling steely", "town");
        assertNotNull(fix);
        assertTrue(fix[1].startsWith("*"));
    }

    @Test
    void aCompanionGoesAfkOnlyWhileItsOwnerIdlesInTownAtMostNinetySecondsOnceAnHour() {
        Imperfections gate = gate();
        Character owner = bot(500, 100, true);
        Character companion = bot(20, 100, true);
        when(companion.getPosition()).thenReturn(new Point(60, 0));
        assertNull(gate.companionAfk(companion, owner), "the owner just stopped");
        now += 59_000L;
        assertNull(gate.companionAfk(companion, owner));
        now += 1_000L;
        draw = 0.999d;
        assertNull(gate.companionAfk(companion, owner), "its one roll for this idle spell missed");
        draw = 0d;
        assertNull(gate.companionAfk(bot(20, 100, true), owner), "and it is not rolled again this spell");

        when(owner.getPosition()).thenReturn(new Point(10, 0)); // 10 px: still the same idle spell
        now += 1L;
        assertNull(gate.companionAfk(companion, owner));

        Character moved = bot(500, 100, true);
        when(moved.getPosition()).thenReturn(new Point(300, 0));
        gate.companionAfk(companion, moved); // the owner moved: a new idle spell from here
        now += 60_000L;
        draw = 0d;
        Point spot = gate.companionAfk(companion, moved);
        assertEquals(new Point(60, 0), spot, "AFK where it stands");
        assertTrue(said.contains("20:afk.brb"));
        now += 29_999L;
        assertEquals(spot, gate.companionAfk(companion, moved), "30 s at the draw of 0");
        now += 2L;
        assertNull(gate.companionAfk(companion, moved), "over");
        assertTrue(said.contains("20:afk.back"));

        now += 120_000L;
        assertNull(gate.companionAfk(companion, moved), "at most once an hour");
    }

    @Test
    void aCompanionAfkNeverRunsPastNinetySecondsAndEndsWhenItsOwnerWalksOff() {
        config.IMPERFECTIONS_COMPANION_AFK_MAX_MS = 90_000;
        Imperfections gate = gate();
        Character owner = bot(500, 100, true);
        Character companion = bot(20, 100, true);
        gate.companionAfk(companion, owner);
        now += 60_000L;
        draws.add(0d);      // the chance comes up
        draws.add(0.9999d); // the longest AFK the config allows
        draws.add(0.9d);    // it says nothing
        long start = now;
        assertNotNull(gate.companionAfk(companion, owner));
        now = start + 89_000L;
        assertNotNull(gate.companionAfk(companion, owner));
        now = start + Imperfections.COMPANION_AFK_HARD_MAX_MS;
        assertNull(gate.companionAfk(companion, owner), "never past 90 s");
        assertFalse(said.contains("20:afk.back"), "it said nothing going, so nothing coming back");

        Character third = bot(22, 100, true);
        companions.add(22);
        assertNotNull(gate.companionAfk(third, owner), "the owner still idles: another companion's roll");
        Character away = bot(500, 100, true);
        when(away.getPosition()).thenReturn(new Point(250, 0));
        assertNull(gate.companionAfk(third, away), "the owner walked off: it follows at once");
    }

    @Test
    void noCompanionAfkOutsideTown() {
        Imperfections gate = gate();
        Character owner = bot(500, 100, false);
        Character companion = bot(20, 100, false);
        gate.companionAfk(companion, owner);
        now += 61_000L;
        assertNull(gate.companionAfk(companion, owner));
    }

    @Test
    void theRezWordsComeFromTheHost() {
        Imperfections gate = gate();
        gate.waitingForRez(bot(1, 0, false));
        gate.revived(bot(1, 100, false));
        assertArrayEquals(new String[]{"1:rez.ask.self", "1:rez.thanks.self"}, said.toArray(new String[0]));
    }
}
