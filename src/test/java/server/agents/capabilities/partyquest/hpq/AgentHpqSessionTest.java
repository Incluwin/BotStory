package server.agents.capabilities.partyquest.hpq;

import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.partyquest.AgentCompanionRunSession;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.partyquest.kpq.AgentKpqSession;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestCandidateScope;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestLobbyRuntime;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestLobbySession;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentHpqSessionTest {
    @Test
    void watchdogCanClaimOnlyAnExpiredExecutionLease() {
        AgentHpqSession session = session();

        assertTrue(session.claimExecutionTick(101, 1_000L, 3_000L));
        assertFalse(session.claimExpiredExecutionTick(102, 3_999L, 3_000L));
        assertTrue(session.claimExpiredExecutionTick(102, 4_000L, 3_000L));
        assertEquals(102, session.executionAgentId());
    }

    @Test
    void phaseEntryAndBonusChoiceRemainSessionLocal() {
        AgentHpqSession session = session();
        session.setBonusMode(AgentHpqSession.BonusMode.ENTER);
        session.transition(AgentHpqSession.Phase.BONUS_FARMING, 5_000L);

        assertEquals(AgentHpqSession.BonusMode.ENTER, session.bonusMode());
        assertEquals(5_000L, session.phaseEnteredAtMs());
    }

    @Test
    void lobbyPreparationDeadlineRemainsSessionLocal() {
        AgentHpqSession first = session();
        AgentHpqSession second = session();

        first.setReadyAtMs(6_000L);

        assertEquals(6_000L, first.readyAtMs());
        assertEquals(0L, second.readyAtMs());
    }

    @Test
    void preparationCountdownStartsOnceAndResetsWhenThePartyDisperses() {
        assertEquals(6_000L, AgentHpqCoordinator.preparationReadyAtMs(
                true, 0L, 1_000L, 5_000L));
        assertEquals(6_000L, AgentHpqCoordinator.preparationReadyAtMs(
                true, 6_000L, 2_000L, 5_000L));
        assertEquals(0L, AgentHpqCoordinator.preparationReadyAtMs(
                false, 6_000L, 2_000L, 5_000L));
    }

    @Test
    void eachNewInvasionWaveIsDetectedOnlyOnItsRisingEdge() {
        AgentHpqSession session = session();

        assertFalse(session.observeDefenseHostiles(false));
        assertTrue(session.observeDefenseHostiles(true));
        assertFalse(session.observeDefenseHostiles(true));
        assertFalse(session.observeDefenseHostiles(false));
        assertTrue(session.observeDefenseHostiles(true));
        assertEquals(2, session.defenseWaveOrdinal());
    }

    @Test
    void rewardClaimsAreFrozenAtomicAndResolvedPerRegisteredMember() {
        AgentHpqSession session = session();

        assertFalse(session.beginRewardClaim(101));
        session.freezeRewardEligibility();
        assertTrue(session.beginRewardClaim(101));
        assertFalse(session.beginRewardClaim(101));
        session.cancelRewardClaim(101);
        assertTrue(session.beginRewardClaim(101));
        assertTrue(session.completeRewardClaim(101));
        assertFalse(session.beginRewardClaim(999));
        session.forfeitReward(102);
        session.forfeitReward(103);

        assertTrue(session.member(101).rewardClaimed());
        assertTrue(session.allRewardsResolved());
    }

    @Test
    void watchdogDisposesAnOrphanedHpqEvent() {
        EventInstanceManager event = mock(EventInstanceManager.class);
        when(event.getPlayers()).thenReturn(List.of());
        when(event.getName()).thenReturn("Henesys0");
        AgentHpqSession session = new AgentHpqSession(
                AgentHpqSession.Mode.BACKGROUND_POPULATION, 1L, 99, 3, 1_000L);
        session.bindEventInstance(event);
        session.transition(AgentHpqSession.Phase.COLLECTING_SEEDS, 1_000L);

        AgentHpqWatchdogRuntime.tick(session, 5_000L);

        verify(event).dispose();
        assertEquals(AgentHpqSession.Phase.FAILED, session.phase());
    }

    @Test
    void removeMemberDropsOnlyThatMember() {
        AgentHpqSession session = session();

        session.removeMember(102);

        assertNull(session.member(102));
        assertEquals(2, session.memberCount());
        assertEquals(List.of(101, 103), session.members().stream()
                .map(AgentHpqMemberState::characterId).toList());
        assertEquals(101, session.eventLeaderId());
        session.removeMember(999); // not a member: nothing changes
        assertEquals(2, session.memberCount());
    }

    @Test
    void aSessionIsNoCompanionRunAndHasNoCoordinationChatUntilItsHostSaysSo() {
        AgentHpqSession session = session();

        assertFalse(session.companionAdopted());
        assertFalse(session.memberCoordinationChatEnabled());
        session.setMemberCoordinationChatEnabled(true);
        assertTrue(session.memberCoordinationChatEnabled());
        session.setCompanionAdopted(true);
        assertTrue(session.companionAdopted());
    }

    @Test
    void bothCompanionRunSessionsShareTheHostsHandle() {
        AgentCompanionRunSession hpq = session();
        AgentCompanionRunSession kpq = new AgentKpqSession(AgentKpqSession.Mode.PRODUCTION, 7L, 101, 3, 1_000L);

        assertInstanceOf(AgentHpqSession.class, hpq);
        hpq.setPaceCalibration(null); // HPQ ignores a calibration
        kpq.setMemberCoordinationChatEnabled(true);
        assertTrue(((AgentKpqSession) kpq).memberCoordinationChatEnabled());
        assertFalse(kpq.companionAdopted());
        assertFalse(hpq.sessionId().isBlank());
    }

    @Test
    void aPackageLobbyAdmitLeavesTheSessionAStockRun() {
        try (HpqAdmitFixture fixture = new HpqAdmitFixture()) {
            AgentPartyQuestEngagement engagement = new AgentPartyQuestEngagement(
                    "hpq", AgentPartyQuestEngagement.Mode.PRODUCTION, 7L, HpqAdmitFixture.BEN, 3, 1_000L);
            AgentPartyQuestEngagementRegistry.register(engagement);
            AgentPartyQuestLobbySession lobby = new AgentPartyQuestLobbySession(
                    engagement.engagementId(), AgentHpqLobbyProfile.profile(), 7L, HpqAdmitFixture.BEN, 3,
                    AgentPartyQuestCandidateScope.ANY_ELIGIBLE_HUMAN, 1_000L);
            for (var member : fixture.party()) {
                boolean agent = member.getId() != HpqAdmitFixture.BEN;
                AgentPartyQuestEngagementRegistry.addAndIndexMember(engagement, member.getId(), agent
                        ? AgentPartyQuestEngagement.MemberType.AGENT
                        : AgentPartyQuestEngagement.MemberType.HUMAN, 1_000L);
                lobby.addMember(member.getId(), agent
                        ? AgentPartyQuestLobbySession.MemberType.AGENT
                        : AgentPartyQuestLobbySession.MemberType.HUMAN,
                        AgentPartyQuestLobbySession.MemberRole.JOINED_MEMBER, 1_000L);
            }
            lobby.setCoordinatorAgentId(HpqAdmitFixture.MIRA);
            lobby.markReady(1_000L);
            engagement.beginLobby(lobby.lobbyId(), 1_000L);
            engagement.lobbyReady(1_000L);
            AgentPartyQuestLobbyRuntime.register(lobby, 1_000L);

            AgentHpqAdmissionService.AdmissionResult admitted = fixture.keep(AgentHpqAdmissionService.admitFromLobby(
                    engagement, lobby, fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L,
                    AgentHpqSession.Mode.PRODUCTION, false));

            assertTrue(admitted.success(), admitted.message());
            assertFalse(admitted.session().companionAdopted());
        }
    }

    /** The background lobby and the !hpqtest flows admit through admitFromLobby, never as a companion run. */
    @Test
    void everyPackageCallerOfTheLobbyAdmitPassesFalse() throws Exception {
        Pattern call = Pattern.compile("AgentHpqAdmissionService\\.admitFromLobby\\(([^;]*)\\);");
        List<String> lastArguments = new ArrayList<>();
        try (Stream<Path> files = Files.list(Path.of("src/main/java/server/agents/capabilities/partyquest/hpq"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = call.matcher(Files.readString(file));
                while (matcher.find()) {
                    String[] arguments = matcher.group(1).split(",");
                    lastArguments.add(file.getFileName() + ":" + arguments[arguments.length - 1].trim());
                }
            }
        }
        assertEquals(List.of("AgentHpqLobbyAdmissionRuntime.java:false", "AgentHpqTestService.java:false"),
                lastArguments.stream().sorted().toList());
    }

    private static AgentHpqSession session() {
        AgentHpqSession session = new AgentHpqSession(
                AgentHpqSession.Mode.TEST_OBSERVATION, 7L, 101, 3, 1_000L);
        session.addMember(101, AgentHpqMemberState.MemberType.AGENT);
        session.addMember(102, AgentHpqMemberState.MemberType.AGENT);
        session.addMember(103, AgentHpqMemberState.MemberType.HUMAN);
        session.setLeadership(101, 101);
        return session;
    }
}
