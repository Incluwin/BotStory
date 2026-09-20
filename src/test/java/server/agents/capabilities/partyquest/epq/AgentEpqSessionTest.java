package server.agents.capabilities.partyquest.epq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentEpqSessionTest {
    private AgentEpqSession registered;

    @AfterEach
    void cleanup() {
        AgentEpqSessionRegistry.remove(registered);
    }

    @Test
    void registryAcceptsFourToSixMembersAndKeepsIndexesPrivate() {
        registered = session(4);
        AgentEpqSessionRegistry.registerComplete(registered);
        assertSame(registered, AgentEpqSessionRegistry.forMember(4));
        assertSame(registered, AgentEpqSessionRegistry.forOperator(1));
        assertEquals(1, AgentEpqSessionRegistry.sessions().size());
        AgentEpqSessionRegistry.remove(registered);
        assertFalse(AgentEpqSessionRegistry.active(4));
    }

    @Test
    void rejectsAnIncompleteParty() {
        assertThrows(IllegalArgumentException.class,
                () -> AgentEpqSessionRegistry.registerComplete(session(3)));
    }

    @Test
    void transitionsForwardAndLeasesCoordination() {
        AgentEpqSession session = session(4);
        session.transition(AgentEpqSession.Phase.STAGE_ONE, 20L);
        session.transition(AgentEpqSession.Phase.ENTERING, 30L);
        assertEquals(AgentEpqSession.Phase.STAGE_ONE, session.phase());
        assertTrue(session.claimExecutionTick(1, 40L, 100L));
        assertFalse(session.claimExecutionTick(2, 50L, 100L));
        assertTrue(session.claimExecutionTick(2, 141L, 100L));
    }

    @Test
    void stageTwoCompletionStaysLatchedAcrossItemReactorConsumption() {
        AgentEpqSession session = session(5);

        assertFalse(session.stageTwoBottlesSecured());
        session.secureStageTwoBottles(25L);
        assertTrue(session.stageTwoBottlesSecured());
        session.secureStageTwoBottles(30L);
        assertTrue(session.stageTwoBottlesSecured());
        assertEquals(25L, session.lastProgressAtMs());
    }

    @Test
    void stageFiveHumanHandoffStaysLatched() {
        AgentEpqSession session = session(5);

        assertFalse(session.stageFiveStoneHandedOff());
        session.markStageFiveStoneHandedOff(25L);
        assertTrue(session.stageFiveStoneHandedOff());
        session.markStageFiveStoneHandedOff(30L);
        assertEquals(25L, session.lastProgressAtMs());
    }

    @Test
    void workAgentIsAgentLeaderOrFirstAgentForHumanLeader() {
        assertEquals(1, session(5).workAgentId());

        AgentEpqSession mixed = new AgentEpqSession(
                AgentEpqSession.Mode.HUMAN_ASSISTED, 7L, 10, 10L);
        mixed.addMember(10, AgentEpqMemberState.MemberType.HUMAN);
        mixed.addMember(13, AgentEpqMemberState.MemberType.AGENT);
        mixed.addMember(11, AgentEpqMemberState.MemberType.AGENT);
        mixed.addMember(12, AgentEpqMemberState.MemberType.AGENT);
        mixed.setLeadership(10, 13);

        assertEquals(11, mixed.workAgentId());
    }

    @Test
    void progressSignaturesRefreshTheStageWatchdogOnlyWhenEvidenceChanges() {
        AgentEpqSession session = session(5);
        session.observeProgressSignature(100L, 20L);
        session.observeProgressSignature(100L, 50L);
        assertEquals(20L, session.lastProgressAtMs());

        AgentEpqWatchdogRuntime.tick(session, 240_019L);
        assertFalse(session.terminal());
        AgentEpqWatchdogRuntime.tick(session, 240_020L);
        assertEquals(AgentEpqSession.Phase.FAILED, session.phase());
        assertTrue(session.failure().contains("stalled"));
    }

    @Test
    void stageFiveHasOneStoneCollectorAndStillAcceptsHumanLeadership() {
        AgentEpqSession agentsOnly = session(5);
        assertTrue(AgentEpqCoordinator.mayCollectStageFiveStone(agentsOnly, 1));
        assertFalse(AgentEpqCoordinator.mayCollectStageFiveStone(agentsOnly, 2));

        AgentEpqSession mixed = new AgentEpqSession(
                AgentEpqSession.Mode.HUMAN_ASSISTED, 7L, 10, 10L);
        mixed.addMember(10, AgentEpqMemberState.MemberType.HUMAN);
        mixed.addMember(11, AgentEpqMemberState.MemberType.AGENT);
        mixed.addMember(12, AgentEpqMemberState.MemberType.AGENT);
        mixed.addMember(13, AgentEpqMemberState.MemberType.AGENT);
        mixed.addMember(14, AgentEpqMemberState.MemberType.AGENT);
        mixed.setLeadership(10, 11);

        assertTrue(AgentEpqCoordinator.mayCollectStageFiveStone(mixed, 11));
        assertFalse(AgentEpqCoordinator.mayCollectStageFiveStone(mixed, 12));
        assertFalse(AgentEpqCoordinator.mayCollectStageFiveStone(mixed, 10));
    }

    @Test
    void exclusiveGroundItemsHaveStageSpecificCollectors() {
        registered = session(5);
        EventInstanceManager event = mock(EventInstanceManager.class);
        registered.bindEventInstance(event);
        AgentEpqSessionRegistry.registerComplete(registered);
        Character leader = member(1, event, AgentEpqDefinition.STAGE_FOUR_MAP);
        Character follower = member(2, event, AgentEpqDefinition.STAGE_FOUR_MAP);

        assertTrue(AgentEpqSessionRegistry.canLootExclusive(
                leader, AgentEpqDefinition.MONSTER_MARBLE));
        assertFalse(AgentEpqSessionRegistry.canLootExclusive(
                follower, AgentEpqDefinition.MONSTER_MARBLE));

        when(leader.getMapId()).thenReturn(AgentEpqDefinition.STAGE_FIVE_MAP);
        when(follower.getMapId()).thenReturn(AgentEpqDefinition.STAGE_FIVE_MAP);
        assertTrue(AgentEpqSessionRegistry.canLootExclusive(
                leader, AgentEpqDefinition.MAGIC_STONE));
        assertFalse(AgentEpqSessionRegistry.canLootExclusive(
                follower, AgentEpqDefinition.MAGIC_STONE));
        registered.markStageFiveStoneHandedOff(25L);
        assertFalse(AgentEpqSessionRegistry.canLootExclusive(
                leader, AgentEpqDefinition.MAGIC_STONE));

        when(leader.getMapId()).thenReturn(AgentEpqDefinition.BOSS_MAP);
        when(follower.getMapId()).thenReturn(AgentEpqDefinition.BOSS_MAP);
        assertFalse(AgentEpqSessionRegistry.canLootExclusive(
                leader, AgentEpqDefinition.MAGIC_STONE));
        assertFalse(AgentEpqSessionRegistry.canLootExclusive(
                follower, AgentEpqDefinition.MAGIC_STONE));
    }

    @Test
    void sessionAndMemberAnnouncementsAreOneShotAndLureTagsReset() {
        AgentEpqSession session = session(5);
        assertTrue(session.claimAnnouncement("stage-progress"));
        assertFalse(session.claimAnnouncement("stage-progress"));

        AgentEpqMemberState member = session.member(1);
        member.tagStageTwoObject(77);
        assertEquals(77, member.stageTwoTaggedObjectId());
        member.clearStageTwoTag();
        assertEquals(0, member.stageTwoTaggedObjectId());

        assertFalse(member.stageTwoReturningToTree());
        member.beginStageTwoTreeReturn();
        assertTrue(member.stageTwoReturningToTree());
        member.finishStageTwoTreeReturn();
        assertFalse(member.stageTwoReturningToTree());
    }

    @Test
    void stalledStageTwoLureOnlyRecoversAfterNoTreewardProgress() {
        AgentEpqMemberState member = session(5).member(1);
        member.tagStageTwoObject(77, 800, 1_000L);

        assertFalse(member.stageTwoLureStalled(6_999L, 6_000L));
        member.observeStageTwoLureProgress(760, 5_000L, 16);
        assertFalse(member.stageTwoLureStalled(10_999L, 6_000L));
        assertTrue(member.stageTwoLureStalled(11_000L, 6_000L));

        member.tagStageTwoObject(77, 760, 11_000L);
        assertFalse(member.stageTwoLureStalled(11_001L, 6_000L));
        member.clearStageTwoTag();
        assertFalse(member.stageTwoLureStalled(99_000L, 6_000L));
    }

    @Test
    void lobbyAgentsChatInOrderDuringLeaderCountdown() {
        AgentEpqSession session = session(5);

        assertEquals(10L, AgentEpqCoordinator.lobbyChatAt(session, 1));
        assertEquals(660L, AgentEpqCoordinator.lobbyChatAt(session, 2));
        assertEquals(1_310L, AgentEpqCoordinator.lobbyChatAt(session, 3));
        assertEquals("Ready for EPQ!", AgentEpqCoordinator.lobbyReadyMessage(session, 2));
        assertEquals("Ready. Let's go!", AgentEpqCoordinator.lobbyReadyMessage(session, 3));
    }

    private static AgentEpqSession session(int memberCount) {
        AgentEpqSession session = new AgentEpqSession(AgentEpqSession.Mode.TEST_OBSERVATION, 7L, 1, 10L);
        for (int id = 1; id <= memberCount; id++) {
            session.addMember(id, AgentEpqMemberState.MemberType.AGENT);
        }
        if (memberCount > 0) session.setLeadership(1, 1);
        return session;
    }

    private static Character member(int id, EventInstanceManager event, int mapId) {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(id);
        when(character.getEventInstance()).thenReturn(event);
        when(character.getMapId()).thenReturn(mapId);
        return character;
    }
}
