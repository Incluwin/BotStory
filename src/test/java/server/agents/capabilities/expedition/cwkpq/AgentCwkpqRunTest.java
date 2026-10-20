package server.agents.capabilities.expedition.cwkpq;

import client.Character;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.expedition.boss.AgentBossRules;
import server.agents.capabilities.expedition.boss.AgentBossRunCatalog;
import server.agents.capabilities.expedition.boss.AgentBossRunDefinition;
import server.agents.capabilities.expedition.cwkpq.AgentCwkpqRun.Stage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The CWKPQ run hands each tick's members to the play of the stage map they stand on, and leaves a stage with no play
 * yet standing. Its stage maps are the boss rules' cwkpq stageMaps, so the boss track adopts and ticks members on every
 * one of them, and the Council Hall is the run's battle map.
 */
class AgentCwkpqRunTest {
    private final EventInstanceManager event = mock(EventInstanceManager.class);

    @Test
    void eachStagesMembersGoToThatStagesPlayAndAStageWithNoPlayStands() {
        List<String> played = new ArrayList<>();
        AgentCwkpqRun run = new AgentCwkpqRun(Map.of(Stage.STORAGE, (members, on, self, nowMs) ->
                members.forEach(member -> played.add(member.getName() + "@" + nowMs))));
        Character orrin = member("Orrin", Stage.STORAGE.mapId());
        Character magna = member("Magna", Stage.STORAGE.mapId());
        Character fenna = member("Fenna", Stage.HALLWAY.mapId());
        Character lost = member("Lost", 100_000_000);

        run.tick(List.of(orrin, fenna, magna, lost), event, 5L);
        run.tick(List.of(fenna), event, 6L);

        assertEquals(List.of("Orrin@5", "Magna@5"), played, "the hallway has no play yet: Fenna stands");
    }

    @Test
    void theStageMapsAreTheBossRulesStageMapsAndTheCouncilIsTheBattleMap() {
        assertEquals(AgentBossRules.stageMaps("cwkpq"), AgentCwkpqRun.stageMapsBeforeCouncil());
        AgentBossRunDefinition cwkpq = AgentBossRunCatalog.of("cwkpq");
        assertEquals(AgentCwkpqRun.COUNCIL_HALL_MAP_ID, cwkpq.battleMapId());
        for (Stage stage : Stage.values()) {
            assertTrue(cwkpq.fightMap(stage.mapId()), stage + " is a map of the run");
            assertSame(stage, Stage.of(stage.mapId()));
        }
        assertNull(Stage.of(610_030_700), "the Secret Chamber comes after the clear");
        assertInstanceOf(AgentCwkpqRun.class, cwkpq.fight().get());
        assertEquals(AgentBossRunDefinition.DeathMode.HOLD_UNTIL_CLEAR, cwkpq.deathMode());
    }

    @Test
    void adoptionGivesEachMemberItsSlotAndTheEndForgetsThem() {
        AgentCwkpqRun run = AgentCwkpqRun.forCompanions();
        run.adopt(10, 0);
        run.adopt(11, 1);
        assertEquals(1, run.ordinal(11));

        run.end(event);
        assertEquals(-1, run.ordinal(11));
    }

    private static Character member(String name, int mapId) {
        Character member = mock(Character.class);
        when(member.getName()).thenReturn(name);
        when(member.getMapId()).thenReturn(mapId);
        return member;
    }
}
