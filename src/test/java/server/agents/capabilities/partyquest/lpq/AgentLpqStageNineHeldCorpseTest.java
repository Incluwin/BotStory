package server.agents.capabilities.partyquest.lpq;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LPQ base fix 1 can keep a dead member on the boss map, still registered, for as long as the event cannot spare it.
 * In every stage-9 phase such a corpse gets no order and holds no claim: it must not reserve a cleanup drop the living
 * members then skip (a drop left on the floor blocks the key hand-in), loot within LOOT_RADIUS (Character.pickupItem
 * has no alive check), or count as a ranged member firing at the Black Ratz (which lets melee members cross early).
 */
class AgentLpqStageNineHeldCorpseTest {
    private static String boss() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/server/agents/capabilities/partyquest/lpq/AgentLpqCoordinator.java"))
                .replace("\r\n", "\n");
        return AgentLpqStageNineStationTest.method(source, "    private static void boss(");
    }

    @Test
    void aDeadMemberGetsNoStageNineOrderInAnyPhase() throws Exception {
        String boss = boss();
        String loop = AgentLpqStageNineStationTest.block(boss,
                boss.indexOf("for (AgentLpqMemberState member : session.members()) {"));
        int mapCheck = loop.indexOf("|| agent.getMapId() != AgentLpqDefinition.stage(9).mapId()) continue;");
        int hp = loop.indexOf("if (agent.getHp() <= 0) continue;");
        assertTrue(mapCheck > 0 && hp > mapCheck, "the HP check follows the map check");
        String beforeHp = loop.substring(0, hp);
        for (String order : List.of("collectNearestDrop(", "ACTIONS.", "member.assign(", "lootClaims",
                "engageBossTriggerRatz(", "prepareStageNineWaitingAgent(", "fightAlishar(", "claimIntent")) {
            assertFalse(beforeHp.contains(order), "a corpse reaches no " + order);
        }
        assertEquals(1, loop.split("agent\\.getHp\\(\\) <= 0", -1).length - 1, "one HP check covers every phase");
    }

    @Test
    void aDeadShooterIsNotFiringAtTheBlackRatz() throws Exception {
        String boss = boss();
        int start = boss.indexOf("boolean rangedTriggerEngaged = ");
        String engaged = boss.substring(start, boss.indexOf(';', start)).replaceAll("\\s+", "");
        int alive = engaged.indexOf(".filter(agent->agent.getHp()>0)");
        assertTrue(alive > 0 && alive < engaged.indexOf(".anyMatch("), engaged);
    }
}
