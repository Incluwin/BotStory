package server.agents.diagnostics.arena;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArenaTooltipCheckTest {
    @Test
    void reportsWhatPlayersAreToldAgainstWhatTheServerComputes() {
        // FriendServer's Corkscrew Blow: String.wz h20 vs Skill.wz level 20.
        assertEquals(List.of("damage 420% (server 180%)", "3 monsters (server 5)"),
                ArenaTooltipCheck.differences("MP -36, damage 420%, attacks up to 3 monsters", 180, 5, 36));
    }

    @Test
    void agreementAndUncheckableTooltipsReportNothing() {
        assertTrue(ArenaTooltipCheck.differences("MP -30, damage 240%, attacks up to 3 monsters", 240, 3, 30).isEmpty());
        assertTrue(ArenaTooltipCheck.differences("Increases weapon attack by 20", 100, 1, 10).isEmpty());
        assertTrue(ArenaTooltipCheck.differences(null, 100, 1, 10).isEmpty());
    }
}
