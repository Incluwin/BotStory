package server.agents.capabilities.partyquest.epq;

import client.Character;
import client.Job;
import client.inventory.InventoryType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * memberProblem answers, in the stock admission's own words and order, why one party member cannot enter EPQ from
 * Ellin: the entrance, then the level band, then the adventurer rule, then the bag (four ETC and one USE slot).
 */
class AgentEpqMemberProblemTest {
    private final Character tobi = EpqAdmitFixture.member(EpqAdmitFixture.TOBI, "Tobi", Job.FIGHTER);

    @Test
    void anEligibleMemberHasNoProblem() {
        assertEquals("", AgentEpqAdmissionService.memberProblem(tobi));
    }

    @Test
    void eachProblemInTheAdmissionsOwnWords() {
        Character away = EpqAdmitFixture.member(1, "Away", Job.FIGHTER);
        when(away.getMapId()).thenReturn(AgentEpqDefinition.STAGE_ONE_MAP);
        assertEquals("Away is not at EPQ", AgentEpqAdmissionService.memberProblem(away));

        Character low = EpqAdmitFixture.member(2, "Low", Job.FIGHTER);
        when(low.getLevel()).thenReturn(43);
        assertEquals("Low must be level 44-55", AgentEpqAdmissionService.memberProblem(low));
        Character high = EpqAdmitFixture.member(3, "High", Job.FIGHTER);
        when(high.getLevel()).thenReturn(56);
        assertEquals("High must be level 44-55", AgentEpqAdmissionService.memberProblem(high));

        Character knight = EpqAdmitFixture.member(4, "Knight", Job.DAWNWARRIOR1);
        assertEquals("Knight must be an Adventurer", AgentEpqAdmissionService.memberProblem(knight));
        Character nobody = EpqAdmitFixture.member(5, "Nobody", null);
        assertEquals("Nobody must be an Adventurer", AgentEpqAdmissionService.memberProblem(nobody));

        Character fullEtc = EpqAdmitFixture.member(6, "FullEtc", Job.FIGHTER);
        when(fullEtc.getInventory(InventoryType.ETC).getNumFreeSlot()).thenReturn((short) 3);
        assertEquals("FullEtc needs four ETC slots and one USE slot", AgentEpqAdmissionService.memberProblem(fullEtc));
        Character fullUse = EpqAdmitFixture.member(7, "FullUse", Job.FIGHTER);
        when(fullUse.getInventory(InventoryType.USE).getNumFreeSlot()).thenReturn((short) 0);
        assertEquals("FullUse needs four ETC slots and one USE slot", AgentEpqAdmissionService.memberProblem(fullUse));
    }

    @Test
    void theStockOrderDecidesWhichProblemIsNamed() {
        when(tobi.getInventory(InventoryType.ETC).getNumFreeSlot()).thenReturn((short) 0);
        assertEquals("Tobi needs four ETC slots and one USE slot", AgentEpqAdmissionService.memberProblem(tobi));
        when(tobi.getJob()).thenReturn(Job.NOBLESSE);
        assertEquals("Tobi must be an Adventurer", AgentEpqAdmissionService.memberProblem(tobi));
        when(tobi.getLevel()).thenReturn(30);
        assertEquals("Tobi must be level 44-55", AgentEpqAdmissionService.memberProblem(tobi));
        when(tobi.getMapId()).thenReturn(AgentEpqDefinition.ENTRANCE_MAP);
        assertEquals("Tobi is not at EPQ", AgentEpqAdmissionService.memberProblem(tobi));
    }

    @Test
    void stockValidationStillNamesTheAgentCoverageFirst() {
        Character ben = EpqAdmitFixture.member(EpqAdmitFixture.BEN, "Ben", Job.BEGINNER);
        Character mira = EpqAdmitFixture.member(EpqAdmitFixture.MIRA, "Mira", Job.ASSASSIN);
        Character una = EpqAdmitFixture.member(EpqAdmitFixture.UNA, "Una", Job.FP_WIZARD);
        when(tobi.getLevel()).thenReturn(30); // a member problem too: coverage is still named first

        assertEquals("Missing EPQ coverage: exactly five members, bowman, pirate",
                AgentEpqAdmissionService.validate(ben, ben, List.of(ben, mira, tobi, una)).message());
    }
}
