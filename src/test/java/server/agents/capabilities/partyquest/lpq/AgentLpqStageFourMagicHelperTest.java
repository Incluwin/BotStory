package server.agents.capabilities.partyquest.lpq;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LPQ base fix 7: the stage-4 magic helper reaches room 403. assignStageFourMagicHelper sends a free magic attacker
 * to a magic room a non-magic member owns, but two owner-only checks on the main map cancelled the helper every pass
 * before it could enter (Step 0: Lioren and Casse free, neither entered 403). The helper now enters (without a second
 * door marker), and the existing in-room takeover gives it the room.
 */
class AgentLpqStageFourMagicHelperTest {
    private static final int ROOM_403 = AgentLpqDefinition.STAGE_4_WEAK_MAGIC_ROOM;
    private static final int PELL = 762_603;
    private static final int LIOREN = 762_604;

    private static boolean helper(int stage, int room, int assigned, boolean agentMagic, Integer owner, int agent,
                                  boolean ownerMagic) {
        return AgentLpqCoordinator.stageFourMagicHelper(stage, room, assigned, agentMagic, owner, agent, ownerMagic);
    }

    @Test
    void aFreeMageSentToAPhysicalOwnersRoomEntersIt() {
        boolean magicHelper = helper(4, ROOM_403, ROOM_403, true, PELL, LIOREN, false);

        assertTrue(magicHelper);
        assertEquals(AgentLpqCoordinator.MainMapRoomStep.ENTER,
                AgentLpqCoordinator.mainMapRoomStep(false, PELL, LIOREN, magicHelper));
    }

    @Test
    void theOtherStepsAreUnchanged() {
        assertEquals(AgentLpqCoordinator.MainMapRoomStep.RELEASE_NOT_OWNER,
                AgentLpqCoordinator.mainMapRoomStep(false, PELL, LIOREN, false), "not the owner, not a helper");
        assertEquals(AgentLpqCoordinator.MainMapRoomStep.RELEASE_NOT_OWNER,
                AgentLpqCoordinator.mainMapRoomStep(false, null, LIOREN, false), "a room nobody holds");
        assertEquals(AgentLpqCoordinator.MainMapRoomStep.ENTER,
                AgentLpqCoordinator.mainMapRoomStep(false, PELL, PELL, false), "the owner");
        assertEquals(AgentLpqCoordinator.MainMapRoomStep.RELEASE_COMPLETED,
                AgentLpqCoordinator.mainMapRoomStep(true, PELL, PELL, false), "a completed room");
        assertEquals(AgentLpqCoordinator.MainMapRoomStep.RELEASE_COMPLETED,
                AgentLpqCoordinator.mainMapRoomStep(true, PELL, LIOREN, true), "even for a helper");
    }

    @Test
    void onlyTheHelperCaseIsAHelper() {
        assertFalse(helper(4, ROOM_403, ROOM_403, true, PELL, LIOREN, true), "the owner is a mage already");
        assertFalse(helper(4, ROOM_403, ROOM_403, false, PELL, LIOREN, false), "the member is no mage");
        assertFalse(helper(4, 922_010_404, 922_010_404, true, PELL, LIOREN, false), "a physical room");
        assertFalse(helper(4, ROOM_403, 922_010_401, true, PELL, LIOREN, false), "assigned elsewhere");
        assertFalse(helper(4, ROOM_403, ROOM_403, true, null, LIOREN, false), "nobody owns the room");
        assertFalse(helper(4, ROOM_403, ROOM_403, true, LIOREN, LIOREN, false), "the owner itself");
        assertFalse(helper(5, ROOM_403, ROOM_403, true, PELL, LIOREN, false), "not stage 4");
    }

    @Test
    void theOwnerAndTheHelperMayEnter() {
        assertTrue(AgentLpqCoordinator.mayEnterRoom(PELL, PELL, false));
        assertTrue(AgentLpqCoordinator.mayEnterRoom(PELL, LIOREN, true));
        assertFalse(AgentLpqCoordinator.mayEnterRoom(PELL, LIOREN, false));
        assertFalse(AgentLpqCoordinator.mayEnterRoom(null, LIOREN, false));
    }

    @Test
    void bothMainMapSitesKnowTheHelperAndAHelperDropsNoMarker() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/server/agents/capabilities/partyquest/lpq/AgentLpqCoordinator.java"))
                .replace("\r\n", "\n");
        String splitRooms = AgentLpqStageNineStationTest.method(source, "    private static void splitRooms(");
        int branchAt = splitRooms.indexOf("if (agent.getMapId() == mainMap && assigned != 0) {");
        String branch = AgentLpqStageNineStationTest.block(splitRooms, branchAt).replaceAll("\\s+", " ");
        assertTrue(branch.contains("switch (mainMapRoomStep(completed, session.rooms().owner(assigned), "
                + "agent.getId(), !completed && isStageFourMagicHelper(session, stage, member, agent, assigned))) {"),
                branch);
        assertFalse(branch.contains("Objects.equals(session.rooms().owner(assigned), agent.getId())"),
                "no bare owner test remains in the main-map branch");

        String enter = AgentLpqStageNineStationTest.method(source, "    private static boolean markRoomAndEnter(")
                .replaceAll("\\s+", " ");
        assertTrue(enter.contains("if (!mayEnterRoom(session.rooms().owner(roomMapId), agent.getId(), magicHelper)) "
                + "return false;"), enter);
        assertFalse(enter.contains("Objects.equals(session.rooms().owner(roomMapId), agent.getId())"));
        int helperAt = enter.indexOf("boolean magicHelper = isStageFourMagicHelper(session, stage(session.phase()), "
                + "member, agent, roomMapId);");
        int marker = enter.indexOf("if (!magicHelper && !member.roomMarkerDroppedFor(roomMapId)) {");
        assertTrue(helperAt > 0 && marker > helperAt, "the door marker is the owner's alone");
    }
}
