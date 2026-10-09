package server.agents.capabilities.partyquest.opq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P6h OPQ base fix OB2: a release takes only the Agents out of the party, in every mode. A human in a stock
 * HUMAN_ASSISTED run (a director lobby's human) keeps his own party; he used to be taken out of it with the Agents.
 * A companion run keeps the whole party as before (keepPartyAfterRelease).
 */
class AgentOpqReleaseKeepsHumansTest {
    private final OpqRunFixture run = new OpqRunFixture();
    private final Character ben = run.human(1, "Ben");
    private final Character mira = run.companion(2, "Mira");
    private final Character kes = run.companion(3, "Kes");
    private final Character pel = run.companion(4, "Pel");
    private final Character hal = run.companion(5, "Hal");
    private final Character gus = run.companion(6, "Gus");
    private final Character[] agents = {mira, kes, pel, hal, gus};

    @AfterEach
    void tearDown() {
        run.close();
    }

    @Test
    void aStockFailureTakesOnlyTheAgentsOutOfTheParty() {
        AgentOpqSession session = run.session(AgentOpqSession.Mode.HUMAN_ASSISTED, AgentOpqSession.Phase.SPLIT_ROOMS,
                ben, mira, kes, pel, hal, gus);

        AgentOpqTerminationService.fail(session, "No legal OPQ progress before timeout", 5_000L);

        for (Character agent : agents) verify(run.party).leaveCurrentParty(agent);
        verify(run.party, never()).leaveCurrentParty(ben);
    }

    @Test
    void aStockCompletionTakesOnlyTheAgentsOutOfTheParty() {
        AgentOpqSession session = run.session(AgentOpqSession.Mode.HUMAN_ASSISTED,
                AgentOpqSession.Phase.CLAIMING_REWARD, ben, mira, kes, pel, hal, gus);
        for (Character member : new Character[]{ben, mira, kes, pel, hal, gus}) {
            when(member.getMapId()).thenReturn(AgentOpqDefinition.RECRUIT_MAP);
        }

        AgentOpqCoordinator.tickSession(session, 10_000L);

        for (Character agent : agents) verify(run.party).leaveCurrentParty(agent);
        verify(run.party, never()).leaveCurrentParty(ben);
    }

    @Test
    void aHumanLedForceStopKeepsTheHumansParty() {
        AgentOpqSession session = run.session(AgentOpqSession.Mode.HUMAN_ASSISTED, AgentOpqSession.Phase.GARDEN,
                ben, mira, kes, pel, hal, gus);

        AgentOpqRuntime.forceStop(mira.getId(), "Agent runtime removed", 5_000L);

        verify(run.party).leaveCurrentParty(mira);
        verify(run.party, never()).leaveCurrentParty(ben);
    }
}
