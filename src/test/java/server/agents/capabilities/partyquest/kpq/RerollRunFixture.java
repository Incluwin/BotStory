package server.agents.capabilities.partyquest.kpq;

import client.Character;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import server.agents.capabilities.combat.AgentCombatObjectiveTargetStateRuntime;
import server.agents.capabilities.combat.AgentCombatVariationRuntime;
import server.agents.capabilities.looting.AgentGrindLootStateRuntime;
import server.agents.capabilities.movement.AgentFarmAnchorStateRuntime;
import server.agents.capabilities.movement.AgentGroundingService;
import server.agents.capabilities.partyquest.AgentPartyQuestHandback;
import server.agents.plans.AgentScriptItemActionService;
import server.agents.runtime.AgentModeService;
import server.agents.runtime.AgentModeStateRuntime;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Stage 1 of a live KPQ run for the re-roll tests (round 2b), over {@link KpqRunFixture}: Ben, a human, leads Mira, Pip
 * and Tobi, three companions collecting their coupons, with the stage-1 levers off so only the re-roll differs from a
 * plain stage 1. The grind, stop and ground calls the coordinator's real gateway makes are stubbed as StageOnePaceTest
 * stubs them. The host's handback rule records every re-roll question it is asked to show.
 */
final class RerollRunFixture implements AutoCloseable {
    /** The yaml's defaults, written out so a test reads the same numbers whatever the deployment sets. */
    static final AgentKpqRerollPolicy POLICY = new AgentKpqRerollPolicy(true, 75_000L, 60_000L, 2, 900_000L, 25_000L);
    static final String THIRTY_FIVES_LINE = "ugh, two of us got 35s. that's a long grind, wanna bail and run it again?";
    static final String GO_AGAIN_LINE = "ok, out we go. see you at lakelis.";
    static final String GRIND_IT_OUT_LINE = "alright, let's grind it out.";

    final KpqRunFixture run = new KpqRunFixture();
    final KpqLogLines coordinatorLog = new KpqLogLines(AgentKpqCoordinator.class);
    final KpqLogLines terminationLog = new KpqLogLines(AgentKpqTerminationService.class);
    final List<AgentPartyQuestHandback.RerollAsk> asked = new CopyOnWriteArrayList<>();
    final Character ben;
    final Character mira;
    final Character pip;
    final Character tobi;
    private final MockedStatic<AgentGrindLootStateRuntime> loot = mockStatic(AgentGrindLootStateRuntime.class);
    private final MockedStatic<AgentScriptItemActionService> items = mockStatic(AgentScriptItemActionService.class);
    private final MockedStatic<AgentModeService> modes = mockStatic(AgentModeService.class);
    private final MockedStatic<AgentFarmAnchorStateRuntime> farmAnchors = mockStatic(AgentFarmAnchorStateRuntime.class);
    private final MockedStatic<AgentCombatVariationRuntime> variation = mockStatic(AgentCombatVariationRuntime.class);
    private final MockedStatic<AgentCombatObjectiveTargetStateRuntime> objective =
            mockStatic(AgentCombatObjectiveTargetStateRuntime.class);
    private final MockedStatic<AgentModeStateRuntime> modeState = mockStatic(AgentModeStateRuntime.class);
    private final MockedStatic<AgentGroundingService> grounding = mockStatic(AgentGroundingService.class);

    RerollRunFixture() {
        AgentKpqRerollStreaks.resetForTest();
        ben = at(run.human(1, "Ben"), 0, 0);
        mira = at(run.companion(2, "Mira"), -300, -400);
        pip = at(run.companion(3, "Pip"), 200, -600);
        tobi = at(run.companion(4, "Tobi"), 500, -200);
        AgentPartyQuestHandback.install(new AgentPartyQuestHandback.Rule() {
            @Override
            public AgentPartyQuestHandback.Handback handback(int agentCharacterId, String questKey) {
                return AgentPartyQuestHandback.Handback.FOLLOW_LEADER;
            }

            @Override
            public boolean askLeaderToReroll(AgentPartyQuestHandback.RerollAsk ask) {
                asked.add(ask);
                return true;
            }
        });
    }

    /** Ben's companion run in stage 1, Cloto having given Mira, Pip and Tobi these numbers. */
    AgentKpqSession companionRun(int miraTarget, int pipTarget, int tobiTarget) {
        return stageOne(true, ben, miraTarget, pipTarget, tobiTarget);
    }

    /**
     * A stage-1 session of Ben's party with these numbers: {@code companionRun} adopts it for the companions (a stock
     * run's session is not adopted), and {@code leader} leads it.
     */
    AgentKpqSession stageOne(boolean companionRun, Character leader, int miraTarget, int pipTarget, int tobiTarget) {
        List<Character> order = new ArrayList<>(List.of(ben, mira, pip, tobi));
        order.remove(leader);
        order.addFirst(leader);
        AgentKpqSession session = run.session(companionRun, AgentKpqSession.Phase.STAGE_1,
                order.toArray(Character[]::new));
        session.setStageOneLevers(AgentKpqStageOneLevers.NONE);
        session.setRerollPolicy(POLICY);
        if (leader != mira) collecting(session, mira, miraTarget);
        if (leader != pip) collecting(session, pip, pipTarget);
        if (leader != tobi) collecting(session, tobi, tobiTarget);
        if (leader != ben) collecting(session, ben, 10);
        return session;
    }

    /** Cloto gave this member its number; it holds no coupons yet. */
    static void collecting(AgentKpqSession session, Character member, int target) {
        AgentKpqMemberState state = session.member(member.getId());
        state.markQuestionRequested();
        state.setCouponTarget(target);
        state.setRole(AgentKpqMemberState.Role.COUPON_COLLECTOR);
        state.beginStageMovement(1, 0L);
        when(member.getItemQuantity(AgentKpqDefinition.COUPON_ITEM, false)).thenReturn(0);
    }

    static Character at(Character member, int x, int y) {
        when(member.getPosition()).thenReturn(new Point(x, y));
        return member;
    }

    /** Every line said on the map so far, in order, as "Speaker: line". */
    List<String> said() {
        ArgumentCaptor<Character> speakers = ArgumentCaptor.forClass(Character.class);
        ArgumentCaptor<String> lines = ArgumentCaptor.forClass(String.class);
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(speakers.capture(), lines.capture()), atLeast(0));
        List<String> said = new ArrayList<>();
        for (int index = 0; index < lines.getAllValues().size(); index++) {
            said.add(speakers.getAllValues().get(index).getName() + ": " + lines.getAllValues().get(index));
        }
        return said;
    }

    @Override
    public void close() {
        AgentKpqRerollStreaks.resetForTest();
        grounding.close();
        modeState.close();
        objective.close();
        variation.close();
        farmAnchors.close();
        modes.close();
        items.close();
        loot.close();
        terminationLog.close();
        coordinatorLog.close();
        run.close();
    }
}
