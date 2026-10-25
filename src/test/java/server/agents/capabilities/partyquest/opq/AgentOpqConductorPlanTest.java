package server.agents.capabilities.partyquest.opq;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-18, the rooms of a human-led OPQ run (AgentOpqConductorPlan): the leader holds no room (Eak answers only him,
 * in five of the six rooms), five companions take the walkway, the storage room, the lobby, the way up and the lounge,
 * the sealed room waits for three free companions, extras help in the lounge once the sealed room is staffed, and a
 * companion keeps its room until it is complete.
 */
class AgentOpqConductorPlanTest {
    private static final AgentOpqDefinition.Room WALKWAY = AgentOpqDefinition.Room.WALKWAY;
    private static final AgentOpqDefinition.Room STORAGE = AgentOpqDefinition.Room.STORAGE;
    private static final AgentOpqDefinition.Room LOBBY = AgentOpqDefinition.Room.LOBBY;
    private static final AgentOpqDefinition.Room SEALED = AgentOpqDefinition.Room.SEALED;
    private static final AgentOpqDefinition.Room LOUNGE = AgentOpqDefinition.Room.LOUNGE;
    private static final AgentOpqDefinition.Room WAY_UP = AgentOpqDefinition.Room.ON_WAY_UP;

    private static AgentOpqConductorPlan.Companion fresh(int id) {
        return new AgentOpqConductorPlan.Companion(id, null, false);
    }

    private static AgentOpqConductorPlan.Companion working(int id, AgentOpqDefinition.Room room) {
        return new AgentOpqConductorPlan.Companion(id, room, false);
    }

    /** A companion whose jump with its Haste does not reach the lounge's ledges. */
    private static AgentOpqConductorPlan.Companion grounded(int id, AgentOpqDefinition.Room room) {
        return new AgentOpqConductorPlan.Companion(id, room, false, false);
    }

    private static AgentOpqConductorPlan.Assignment room(AgentOpqDefinition.Room room) {
        return new AgentOpqConductorPlan.Assignment(AgentOpqConductorPlan.Duty.ROOM, room);
    }

    private static Map<Integer, AgentOpqConductorPlan.Assignment> plan(Set<AgentOpqDefinition.Room> complete,
                                                                        AgentOpqConductorPlan.Companion... companions) {
        return AgentOpqConductorPlan.plan(List.of(companions), complete);
    }

    @Test
    void fiveCompanionsTakeTheFiveRoomsThatNeedOneEachAndTheSealedRoomWaits() {
        Map<Integer, AgentOpqConductorPlan.Assignment> plan = plan(Set.of(),
                fresh(16), fresh(12), fresh(14), fresh(13), fresh(15));

        assertEquals(List.of(12, 13, 14, 15, 16), new ArrayList<>(plan.keySet()), "id order");
        assertEquals(room(WALKWAY), plan.get(12));
        assertEquals(room(STORAGE), plan.get(13));
        assertEquals(room(LOBBY), plan.get(14));
        assertEquals(room(WAY_UP), plan.get(15));
        assertEquals(room(LOUNGE), plan.get(16));
        assertTrue(plan.values().stream().noneMatch(assignment -> assignment.room() == SEALED));
    }

    @Test
    void theFirstPassAppliedAgainKeepsEveryoneInTheirRoom() {
        Map<Integer, AgentOpqConductorPlan.Assignment> plan = plan(Set.of(), working(12, WALKWAY),
                working(13, STORAGE), working(14, LOBBY), working(15, WAY_UP), working(16, LOUNGE));

        assertEquals(room(WALKWAY), plan.get(12));
        assertEquals(room(STORAGE), plan.get(13));
        assertEquals(room(LOBBY), plan.get(14));
        assertEquals(room(WAY_UP), plan.get(15));
        assertEquals(room(LOUNGE), plan.get(16));
    }

    @Test
    void aFreeCompanionWaitsUntilThreeAreFreeForTheSealedPlatforms() {
        Set<AgentOpqDefinition.Room> complete = EnumSet.of(STORAGE);
        Map<Integer, AgentOpqConductorPlan.Assignment> one = plan(complete, working(12, WALKWAY),
                working(13, STORAGE), working(14, LOBBY), working(15, WAY_UP), working(16, LOUNGE));
        assertEquals(AgentOpqConductorPlan.Assignment.WAIT, one.get(13), "storage done first: it waits in the centre");

        complete.addAll(List.of(LOBBY, WALKWAY));
        Map<Integer, AgentOpqConductorPlan.Assignment> three = plan(complete, working(12, WALKWAY),
                fresh(13), working(14, LOBBY), working(15, WAY_UP), working(16, LOUNGE));
        assertEquals(room(SEALED), three.get(12));
        assertEquals(room(SEALED), three.get(13));
        assertEquals(room(SEALED), three.get(14));
        assertEquals(room(WAY_UP), three.get(15));
        assertEquals(room(LOUNGE), three.get(16));
    }

    @Test
    void aCompanionWithAStatuePieceTakesItToTheStatueFirst() {
        Map<Integer, AgentOpqConductorPlan.Assignment> plan = plan(EnumSet.of(STORAGE, LOBBY, WALKWAY),
                working(12, WALKWAY), new AgentOpqConductorPlan.Companion(13, STORAGE, true), working(14, LOBBY),
                working(15, WAY_UP), working(16, LOUNGE));

        assertEquals(AgentOpqConductorPlan.Assignment.CARRY, plan.get(13));
        assertEquals(AgentOpqConductorPlan.Assignment.WAIT, plan.get(12), "two free are not enough for the platforms");
        assertEquals(AgentOpqConductorPlan.Assignment.WAIT, plan.get(14));
    }

    @Test
    void withFourCompanionsTheLoungeGoesToTheFirstOneFreed() {
        Map<Integer, AgentOpqConductorPlan.Assignment> first = plan(Set.of(),
                fresh(12), fresh(13), fresh(14), fresh(15));
        assertEquals(room(WALKWAY), first.get(12));
        assertEquals(room(STORAGE), first.get(13));
        assertEquals(room(LOBBY), first.get(14));
        assertEquals(room(WAY_UP), first.get(15));

        Map<Integer, AgentOpqConductorPlan.Assignment> freed = plan(EnumSet.of(STORAGE), working(12, WALKWAY),
                working(13, STORAGE), working(14, LOBBY), working(15, WAY_UP));
        assertEquals(room(LOUNGE), freed.get(13));
    }

    @Test
    void extrasHelpInTheLoungeOnceTheSealedRoomIsStaffedOrDone() {
        Map<Integer, AgentOpqConductorPlan.Assignment> staffed = plan(EnumSet.of(STORAGE, LOBBY, WALKWAY, WAY_UP),
                working(12, SEALED), working(13, SEALED), working(14, SEALED), fresh(15), working(16, LOUNGE));
        assertEquals(room(LOUNGE), staffed.get(15));

        Map<Integer, AgentOpqConductorPlan.Assignment> done = plan(EnumSet.of(STORAGE, LOBBY, WALKWAY, SEALED),
                fresh(12), fresh(13), fresh(14), working(15, WAY_UP), working(16, LOUNGE));
        assertEquals(room(LOUNGE), done.get(12));
        assertEquals(room(LOUNGE), done.get(13));
        assertEquals(room(LOUNGE), done.get(14));
    }

    @Test
    void aRoomWhoseWorkerDepartedGoesToAFreeCompanion() {
        // Hal (way up) departed: the run no longer counts him, and the first free companion takes his room.
        Map<Integer, AgentOpqConductorPlan.Assignment> plan = plan(EnumSet.of(STORAGE), working(12, WALKWAY),
                working(13, STORAGE), working(14, LOBBY), working(16, LOUNGE));

        assertEquals(room(WAY_UP), plan.get(13));
    }

    @Test
    void aSealedHolderThatDepartedIsReplacedOnlyWhenAFreeCompanionCanStand() {
        Set<AgentOpqDefinition.Room> complete = EnumSet.of(STORAGE, LOBBY, WALKWAY);
        Map<Integer, AgentOpqConductorPlan.Assignment> short1 = plan(complete, working(12, SEALED),
                working(13, SEALED), working(15, WAY_UP), working(16, LOUNGE));
        assertEquals(room(SEALED), short1.get(12), "the two left keep the room");
        assertEquals(room(SEALED), short1.get(13));

        complete.add(WAY_UP);
        Map<Integer, AgentOpqConductorPlan.Assignment> refilled = plan(complete, working(12, SEALED),
                working(13, SEALED), fresh(15), working(16, LOUNGE));
        assertEquals(room(SEALED), refilled.get(15));
    }

    @Test
    void theClimberTakesTheLoungeAndTheCompanionThatCannotClimbTakesTheClimbersRoom() {
        // 2026-10-20 live: Sadie (a Fighter, jump 100) had the lounge and stood at its door for 43 minutes while Jinx,
        // the one companion with Haste, worked the storage room.
        Map<Integer, AgentOpqConductorPlan.Assignment> plan = plan(Set.of(), grounded(12, null), fresh(13),
                grounded(14, null), grounded(15, null), grounded(16, null));

        assertEquals(room(WALKWAY), plan.get(12));
        assertEquals(room(LOUNGE), plan.get(13));
        assertEquals(room(LOBBY), plan.get(14));
        assertEquals(room(WAY_UP), plan.get(15));
        assertEquals(room(STORAGE), plan.get(16));
    }

    @Test
    void aWorkingNonClimberKeepsTheLoungeAndTheFirstFreeClimberJoinsIt() {
        Map<Integer, AgentOpqConductorPlan.Assignment> plan = plan(EnumSet.of(STORAGE), grounded(12, WALKWAY),
                fresh(13), grounded(14, LOBBY), grounded(15, WAY_UP), grounded(16, LOUNGE));

        assertEquals(room(LOUNGE), plan.get(16), "a companion keeps its room until it is complete");
        assertEquals(room(LOUNGE), plan.get(13));
        assertEquals(room(WALKWAY), plan.get(12));
    }

    @Test
    void aClimberAlreadyWorkingAnotherRoomKeepsIt() {
        Map<Integer, AgentOpqConductorPlan.Assignment> plan = plan(Set.of(), grounded(12, WALKWAY),
                working(13, STORAGE), grounded(14, LOBBY), grounded(15, WAY_UP), grounded(16, LOUNGE));

        assertEquals(room(STORAGE), plan.get(13));
        assertEquals(room(LOUNGE), plan.get(16));
    }

    @Test
    void onADayTheLobbyWantsAClimberALoneClimberTakesTheLobbyFirst() {
        // 2026-10-24 live (run 4, a Sunday on the server): Sunday's LP box sits up a 100 px ledge; Marrow (jump 100) stood
        // in Monday's cell for the whole run. With one climber the lobby goes first and the lounge keeps its low room.
        Map<Integer, AgentOpqConductorPlan.Assignment> plan = AgentOpqConductorPlan.plan(List.of(grounded(12, null),
                fresh(13), grounded(14, null), grounded(15, null), grounded(16, null)), Set.of(),
                EnumSet.of(LOBBY, LOUNGE));

        assertEquals(room(WALKWAY), plan.get(12));
        assertEquals(room(LOBBY), plan.get(13));
        assertEquals(room(STORAGE), plan.get(14));
        assertEquals(room(WAY_UP), plan.get(15));
        assertEquals(room(LOUNGE), plan.get(16));
    }

    @Test
    void theLobbysClimberJoinsTheLoungeOnceTheLobbyIsDone() {
        Map<Integer, AgentOpqConductorPlan.Assignment> plan = AgentOpqConductorPlan.plan(List.of(
                grounded(12, WALKWAY), fresh(13), grounded(14, STORAGE), grounded(15, WAY_UP),
                grounded(16, LOUNGE)), EnumSet.of(LOBBY), EnumSet.of(LOBBY, LOUNGE));

        assertEquals(room(LOUNGE), plan.get(13));
        assertEquals(room(LOUNGE), plan.get(16));
    }

    @Test
    void withNoClimberTheRoomsGoInIdOrderAsBefore() {
        Map<Integer, AgentOpqConductorPlan.Assignment> plan = plan(Set.of(), grounded(12, null), grounded(13, null),
                grounded(14, null), grounded(15, null), grounded(16, null));

        assertEquals(room(STORAGE), plan.get(13));
        assertEquals(room(LOUNGE), plan.get(16));
    }

    @Test
    void everyRoomCompleteLeavesEveryoneWaitingAndTheRolesAreTheStockOnes() {
        Map<Integer, AgentOpqConductorPlan.Assignment> plan = plan(EnumSet.allOf(AgentOpqDefinition.Room.class),
                working(12, WALKWAY), fresh(13), fresh(14));
        plan.values().forEach(assignment -> assertEquals(AgentOpqConductorPlan.Assignment.WAIT, assignment));

        assertEquals(AgentOpqMemberState.Role.WALKWAY_COLLECTOR, AgentOpqConductorPlan.role(WALKWAY));
        assertEquals(AgentOpqMemberState.Role.SEALED_PLATFORM, AgentOpqConductorPlan.role(SEALED));
        assertEquals(AgentOpqMemberState.Role.WAY_UP_RUNNER, AgentOpqConductorPlan.role(WAY_UP));
    }
}
