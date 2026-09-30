package server.agents.capabilities.partyquest.kpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/** A companion run with coordination chat on gets the delayed nudges test runs had (P6 WP2). */
class GuidanceModeTest {
    private static final long DELAYED_PROMPT_AFTER_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqHumanDialogueRuntime.DELAYED_PROMPT_AFTER_MS");
    private static final String TASK = "talk to Cloto to begin Stage 2";

    private KpqRunFixture run;
    private Character ben;
    private Character mira;
    private Character pip;
    private AgentKpqSession session;

    @BeforeEach
    void setUp() {
        run = new KpqRunFixture();
        ben = run.human(1, "Ben");
        mira = run.companion(2, "Mira");
        pip = run.companion(3, "Pip");
    }

    @AfterEach
    void tearDown() {
        run.close();
    }

    @Test
    void productionWithoutTheFlagStaysSilent() {
        session = run.session(false, AgentKpqSession.Phase.STAGE_2, ben, mira, pip);

        waitOnBen();

        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(any(), anyString()), never());
    }

    @Test
    void anAdoptedRunWithCoordinationOnPromptsOnceAfterTheDelay() {
        session = run.session(true, AgentKpqSession.Phase.STAGE_2, ben, mira, pip);

        AgentKpqHumanDialogueRuntime.promptDelayed(session, ben, TASK, 2_000L);
        AgentKpqHumanDialogueRuntime.promptDelayed(session, ben, TASK, 2_000L + DELAYED_PROMPT_AFTER_MS - 1L);
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(any(), anyString()), never());

        AgentKpqHumanDialogueRuntime.promptDelayed(session, ben, TASK, 2_000L + DELAYED_PROMPT_AFTER_MS);

        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(any(), anyString()), times(1));
    }

    @Test
    void anAdoptedRunWithCoordinationOffStaysSilent() {
        session = run.session(true, AgentKpqSession.Phase.STAGE_2, ben, mira, pip);
        session.setMemberCoordinationChatEnabled(false);

        waitOnBen();

        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(any(), anyString()), never());
    }

    @Test
    void testObservationStillPrompts() {
        session = observationSession();

        waitOnBen();

        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(eq(mira), anyString()), times(1));
    }

    @Test
    void aDownedCompanionLeavesTheNudgeToOneStillInTheEvent() {
        // Mira would say this turn's nudge, but she is down.
        session = observationSession();
        when(mira.getHp()).thenReturn(0);

        waitOnBen();

        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(eq(pip), anyString()), times(1));
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(eq(mira), anyString()), never());
    }

    private AgentKpqSession observationSession() {
        AgentKpqSession observed = new AgentKpqSession(AgentKpqSession.Mode.TEST_OBSERVATION, 7L, 1, 3, 1_000L);
        observed.addMember(1, AgentKpqMemberState.MemberType.HUMAN);
        observed.addMember(2, AgentKpqMemberState.MemberType.AGENT);
        observed.addMember(3, AgentKpqMemberState.MemberType.AGENT);
        observed.setMemberCoordinationChatEnabled(false);
        observed.bindEventInstance(run.event);
        return observed;
    }

    private void waitOnBen() {
        AgentKpqHumanDialogueRuntime.promptDelayed(session, ben, TASK, 2_000L);
        AgentKpqHumanDialogueRuntime.promptDelayed(session, ben, TASK, 2_000L + DELAYED_PROMPT_AFTER_MS);
    }
}
