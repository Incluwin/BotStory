package server.quest.requirements;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DayByDayRequirementTest {
    private static final ZoneId ZONE = ZoneId.of("UTC");

    private static long at(int day, int hour, int minute) {
        return ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, ZONE).toInstant().toEpochMilli();
    }

    @Test
    void aDailyQuestOpensAgainTheNextCalendarDay() {
        assertFalse(DayByDayRequirement.availableAgain(at(1, 9, 0), at(1, 23, 59), ZONE), "same day");
        assertTrue(DayByDayRequirement.availableAgain(at(1, 23, 59), at(2, 0, 1), ZONE), "after midnight");
    }
}
