package client.command.commands.gm6;

import client.Character;
import client.Client;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.life.Monster;
import server.life.MonsterStats;
import server.life.autonomy.balrog.EasyBalrogBodyBehavior;
import server.maps.MapleMap;
import testutil.LogLines;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BalrogProbeCommandTest {
    private static final int BODY = EasyBalrogBodyBehavior.MOB_ID;
    private static final int CLAW = 8_830_008;
    private static final int ORDINARY = 100_100;

    private final Client client = mock(Client.class);
    private final Character gm = mock(Character.class);
    private final MapleMap map = mock(MapleMap.class);

    BalrogProbeCommandTest() {
        when(client.getPlayer()).thenReturn(gm);
        when(gm.getMap()).thenReturn(map);
        when(gm.getName()).thenReturn("Bossfriend");
        when(gm.getMapId()).thenReturn(180_000_000);
    }

    @AfterEach
    void restoreTheDefaultSwitch() {
        EasyBalrogBodyBehavior.setTelegraphSkillActions(true);
    }

    /** T-T1: the body at 49% is exactly the HP that ServerMobAutonomyServiceTest's 145 cast tests use. */
    @Test
    void hpLowersOnlyTheNamedMobToThePercentOfItsMaxHp() {
        Monster body = monster(BODY, 3_210_000);
        Monster claw = monster(CLAW, 2_059_200);
        onMap(body, claw);

        run("hp", "49", Integer.toString(BODY));

        assertEquals(1_572_900L, body.getHp());
        assertEquals(2_059_200L, claw.getHp(), "a mob with another id must not change");
    }

    /** T-T1: the target is floored (1,999 x 49% = 979.51). */
    @Test
    void hpFloorsTheTarget() {
        Monster mob = monster(ORDINARY, 1_999);
        onMap(mob);

        run("hp", "49");

        assertEquals(979L, mob.getHp());
    }

    /** T-T2: never raise HP. */
    @Test
    void hpLeavesAMobAtOrBelowThePercentUnchanged() {
        Monster wounded = monster(ORDINARY, 1_000);
        wounded.applyAndGetHpDamage(600, false);
        Monster full = monster(BODY, 3_210_000);
        onMap(wounded, full);

        run("hp", "49", Integer.toString(ORDINARY));
        run("hp", "100");

        assertEquals(400L, wounded.getHp());
        assertEquals(3_210_000L, full.getHp());
    }

    /** T-T3: the sealed body is fake until it activates; the tool must not touch it. */
    @Test
    void hpSkipsFakeMobs() {
        Monster sealedBody = monster(BODY, 3_210_000);
        sealedBody.setFake(true);
        Monster claw = monster(CLAW, 2_059_200);
        onMap(sealedBody, claw);

        run("hp", "1");

        assertEquals(3_210_000L, sealedBody.getHp());
        assertEquals(20_592L, claw.getHp());
    }

    /** The tool never kills: a companion hit that lands between the read and the write still leaves 1 HP. */
    @Test
    void hpZeroNeverKillsEvenWhenAHitLandsAfterTheRead() {
        Monster mob = new Monster(ORDINARY, stats(1_000)) {
            private boolean hitPending = true;

            @Override
            public long getHp() {
                long read = super.getHp();
                if (hitPending) {
                    hitPending = false;
                    applyAndGetHpDamage(400, false);
                }
                return read;
            }
        };
        onMap(mob);

        run("hp", "0");

        assertTrue(mob.isAlive(), "the probe must never kill a mob");
        assertEquals(1L, mob.getHp());
    }

    @Test
    void telegraphSetsTheBodySwitchPrintsItAndLogsTheUse() {
        try (LogLines lines = new LogLines(BalrogProbeCommand.class)) {
            run("telegraph", "action");
            assertTrue(EasyBalrogBodyBehavior.telegraphSkillActions());
            verify(gm).yellowMessage(argThat(line -> line.contains("telegraph = action")));

            run("telegraph", "none");
            assertFalse(EasyBalrogBodyBehavior.telegraphSkillActions());
            verify(gm).yellowMessage(argThat(line -> line.contains("telegraph = none")));

            assertEquals(2, lines.startingWith("gm balrogprobe telegraph:").size());
        }
    }

    @Test
    void badArgumentsChangeNothingAndShowTheSyntax() {
        Monster body = monster(BODY, 3_210_000);
        onMap(body);

        run("telegraph", "maybe");
        run("hp", "-5", Integer.toString(BODY));
        run("hp", "half");

        assertTrue(EasyBalrogBodyBehavior.telegraphSkillActions());
        assertEquals(3_210_000L, body.getHp());
        verify(gm, times(3)).yellowMessage(BalrogProbeCommand.USAGE);
    }

    private void run(String... params) {
        new BalrogProbeCommand().execute(client, params);
    }

    private void onMap(Monster... monsters) {
        when(map.getAllMonsters()).thenReturn(List.of(monsters));
    }

    private static Monster monster(int id, long maxHp) {
        return new Monster(id, stats(maxHp));
    }

    private static MonsterStats stats(long maxHp) {
        MonsterStats stats = new MonsterStats();
        stats.hp = maxHp;
        stats.mp = 100;
        return stats;
    }
}
