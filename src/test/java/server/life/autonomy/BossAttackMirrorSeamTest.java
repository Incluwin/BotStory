package server.life.autonomy;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.life.Monster;
import testutil.LogLines;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BossAttackMirrorSeamTest {
    private static final String FAILED = "Boss mirror listener failed in ";

    private final Character controller = mock(Character.class);
    private final Monster arm = part(8_800_003, 1_000_000_021);
    private final Recorder recorder = new Recorder();

    @AfterEach
    void tearDown() {
        BossAttackMirrorSeam.install(null);
    }

    @Test
    void attackActionsDecodeToTheZeroBasedIndexAndTheFacingBit() {
        BossAttackMirrorSeam.install(recorder);

        BossAttackMirrorSeam.acceptedMove(controller, arm, 24, 0x0003, 5);
        BossAttackMirrorSeam.acceptedMove(controller, arm, 25, 0x0000, 6);
        BossAttackMirrorSeam.acceptedMove(controller, arm, 41, (short) 0x8001, 7);

        assertEquals(List.of(
                new Attack(controller, arm, 0, false, 0x0003, 5),
                new Attack(controller, arm, 0, true, 0x0000, 6),
                new Attack(controller, arm, 8, true, 0x8001, 7)), recorder.attacks);
    }

    @Test
    void movesSkillsAndRefusedAttacksSendNoCall() {
        BossAttackMirrorSeam.install(recorder);

        for (int raw : new int[]{-1, 0, 23, 42, 59, 127}) {
            BossAttackMirrorSeam.acceptedMove(controller, arm, raw, 0x0003, 1);
        }

        assertEquals(List.of(), recorder.attacks);
    }

    @Test
    void onlyAHitFromAnAttackOrASkillIsReported() {
        BossAttackMirrorSeam.install(recorder);

        for (int damageFrom : new int[]{-4, -3, -2, -1}) {
            BossAttackMirrorSeam.reportedHit(controller, arm, damageFrom, 900);
        }
        BossAttackMirrorSeam.reportedHit(controller, arm, 0, 1_200);
        BossAttackMirrorSeam.reportedHit(controller, arm, 1, 0);

        assertEquals(List.of(
                new Hit(controller, arm, 0, 1_200),
                new Hit(controller, arm, 1, 0)), recorder.hits);
        assertEquals(List.of(), recorder.attacks);
    }

    @Test
    void withNoListenerBothCallsDoNothing() {
        BossAttackMirrorSeam.install(recorder);
        BossAttackMirrorSeam.install(null);

        assertDoesNotThrow(() -> {
            BossAttackMirrorSeam.acceptedMove(controller, arm, 24, 0, 1);
            BossAttackMirrorSeam.reportedHit(controller, arm, 0, 100);
        });
        assertEquals(List.of(), recorder.attacks);
        assertEquals(List.of(), recorder.hits);
    }

    @Test
    void aListenerFailureIsSwallowedAndLoggedOnceAMinute() {
        BossAttackMirrorSeam.install(new Recorder() {
            @Override
            public void controllerAttack(Character controller, Monster part, int attackIndex,
                                         boolean facingLeft, int pOption, int moveId) {
                throw new IllegalStateException("boom");
            }

            @Override
            public void reportedHit(Character human, Monster attacker, int damageFrom, int damage) {
                throw new IllegalStateException("boom");
            }
        });

        try (LogLines lines = new LogLines(BossAttackMirrorSeam.class)) {
            assertDoesNotThrow(() -> {
                BossAttackMirrorSeam.acceptedMove(controller, arm, 24, 0, 1);
                BossAttackMirrorSeam.reportedHit(controller, arm, 0, 100);
                BossAttackMirrorSeam.acceptedMove(controller, arm, 26, 0, 2);
            });

            assertEquals(List.of(FAILED + "controllerAttack mob=8800003 oid=1000000021 skipped=0"),
                    lines.startingWith(FAILED));
        }
    }

    @Test
    void aFailureAMinuteAfterTheLastReportIsLoggedWithTheSkippedCount() {
        BossAttackMirrorSeam.install(recorder); // starts the failure log afresh
        RuntimeException boom = new IllegalStateException("boom");

        try (LogLines lines = new LogLines(BossAttackMirrorSeam.class)) {
            BossAttackMirrorSeam.failed("controllerAttack", arm, boom, 1_000L);
            BossAttackMirrorSeam.failed("reportedHit", arm, boom, 30_000L);
            BossAttackMirrorSeam.failed("controllerAttack", arm, boom, 60_999L);
            BossAttackMirrorSeam.failed("reportedHit", arm, boom, 61_000L);

            assertEquals(List.of(
                    FAILED + "controllerAttack mob=8800003 oid=1000000021 skipped=0",
                    FAILED + "reportedHit mob=8800003 oid=1000000021 skipped=2"), lines.startingWith(FAILED));
        }
    }

    private static Monster part(int mobId, int oid) {
        Monster monster = mock(Monster.class);
        when(monster.getId()).thenReturn(mobId);
        when(monster.getObjectId()).thenReturn(oid);
        return monster;
    }

    private record Attack(Character controller, Monster part, int attackIndex, boolean facingLeft,
                          int pOption, int moveId) {
    }

    private record Hit(Character human, Monster attacker, int damageFrom, int damage) {
    }

    private static class Recorder implements BossAttackMirrorSeam.Listener {
        final List<Attack> attacks = new ArrayList<>();
        final List<Hit> hits = new ArrayList<>();

        @Override
        public void controllerAttack(Character controller, Monster part, int attackIndex, boolean facingLeft,
                                     int pOption, int moveId) {
            attacks.add(new Attack(controller, part, attackIndex, facingLeft, pOption, moveId));
        }

        @Override
        public void reportedHit(Character human, Monster attacker, int damageFrom, int damage) {
            hits.add(new Hit(human, attacker, damageFrom, damage));
        }
    }
}
