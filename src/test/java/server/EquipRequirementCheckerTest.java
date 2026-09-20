package server;

import client.Job;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EquipRequirementCheckerTest {
    @Test
    void shouldIgnoreJobRequirementPerFriendServerPolicy() {
        assertTrue(EquipRequirementChecker.meetsRequirements(
                Job.WARRIOR, stats(0x2, 30, 0, 0, 0, 0, 0),
                30, 30, 200, 200, 200, 200, 0));
    }

    @Test
    void shouldEnforceOnlyPrimaryStatForClass() {
        // Warrior: STR gate applies, DEX/INT/LUK requirements are ignored.
        assertFalse(EquipRequirementChecker.meetsRequirements(
                Job.WARRIOR, stats(0, 30, 0, 95, 0, 0, 0), 30, 30, 4, 50, 4, 4, 0));
        assertTrue(EquipRequirementChecker.meetsRequirements(
                Job.WARRIOR, stats(0, 30, 80, 50, 80, 80, 0), 30, 30, 4, 60, 4, 4, 0));
        // Pirates have no stat gate at all.
        assertTrue(EquipRequirementChecker.meetsRequirements(
                Job.PIRATE, stats(0, 30, 90, 90, 90, 90, 0), 30, 30, 4, 4, 4, 4, 0));
    }

    @Test
    void shouldAllowMatchingBaseAndDerivedJobFamilies() {
        Map<String, Integer> warriorStats = stats(0x1, 30, 0, 95, 0, 0, 0);

        assertTrue(EquipRequirementChecker.meetsRequirements(
                Job.WARRIOR, warriorStats, 30, 30, 50, 200, 4, 4, 0));
        assertTrue(EquipRequirementChecker.meetsRequirements(
                Job.DAWNWARRIOR1, warriorStats, 30, 30, 50, 200, 4, 4, 0));
        assertTrue(EquipRequirementChecker.meetsRequirements(
                Job.ARAN1, warriorStats, 30, 30, 50, 200, 4, 4, 0));
    }

    @Test
    void shouldTreatEvanAsMagicianForJobMaskChecks() {
        assertTrue(EquipRequirementChecker.meetsRequirements(
                Job.EVAN1, stats(0x2, 30, 0, 0, 50, 0, 0),
                30, 30, 4, 4, 200, 4, 0));
    }

    @Test
    void shouldAllowUnrestrictedItemsForBeginnerJobs() {
        assertTrue(EquipRequirementChecker.meetsRequirements(
                Job.BEGINNER, stats(0, 10, 0, 0, 0, 0, 0),
                10, 10, 4, 12, 4, 4, 0));
    }

    private Map<String, Integer> stats(int reqJob, int reqLevel, int reqDex, int reqStr, int reqInt, int reqLuk, int reqPop) {
        return Map.of(
                "reqJob", reqJob,
                "reqLevel", reqLevel,
                "reqDEX", reqDex,
                "reqSTR", reqStr,
                "reqINT", reqInt,
                "reqLUK", reqLuk,
                "reqPOP", reqPop
        );
    }
}
