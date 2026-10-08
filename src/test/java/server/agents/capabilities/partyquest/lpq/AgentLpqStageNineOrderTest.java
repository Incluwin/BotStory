package server.agents.capabilities.partyquest.lpq;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.lpq.AgentLpqStageNineBossPolicy.Floor;
import server.agents.capabilities.partyquest.lpq.AgentLpqStageNineBossPolicy.Group;
import server.agents.capabilities.partyquest.lpq.AgentLpqStageNineBossPolicy.MemberState;
import server.agents.capabilities.partyquest.lpq.AgentLpqStageNineBossPolicy.Move;
import server.agents.capabilities.partyquest.lpq.AgentLpqStageNineBossPolicy.Order;
import server.agents.capabilities.partyquest.lpq.AgentLpqStageNineBossPolicy.Pass;
import server.agents.capabilities.partyquest.lpq.AgentLpqStageNineBossPolicy.Side;
import server.agents.capabilities.partyquest.lpq.AgentLpqStageNineBossPolicy.Targets;

import java.awt.Point;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LPQ base fixes 5 and 6 as one member's stage-9 order per pass (AgentLpqStageNineBossPolicy.order), carried from
 * pass to pass through the member's own stored state, and the coordinator's wiring of it: the two-group split, the
 * station hysteresis, the rest and resume thresholds, and the move each order becomes.
 */
class AgentLpqStageNineOrderTest {
    private static final Floor FLOOR = AgentLpqCoordinator.stageNineFloor();
    private static final Set<Integer> ALISHAR = Set.of(AgentLpqDefinition.ALISHAR);
    private static final int POT_STOP = server.agents.runtime.AgentRuntimeConfig.cfg.POT_STOP;
    private static final int MAX_HP = 2_000;

    private static Point at(int x) {
        return new Point(x, 184);
    }

    private static Pass shooter(int rank, boolean groupsUnset, int x, int bossX, int potions, int hp) {
        return new Pass(true, rank, groupsUnset, at(x), at(bossX), List.of(), potions, POT_STOP, hp, MAX_HP);
    }

    /** One pass for a member: the order from its stored state, stored back as the coordinator does. */
    private static Order pass(AgentLpqMemberState member, Pass pass) {
        Order order = AgentLpqStageNineBossPolicy.order(member.stageNineState(), pass, FLOOR);
        member.applyStageNine(order.state());
        return order;
    }

    private static AgentLpqMemberState agent(int id) {
        return new AgentLpqMemberState(id, AgentLpqMemberState.MemberType.AGENT);
    }

    @Test
    void twoShootersThatBothStartLeftTakeOppositeSides() {
        AgentLpqMemberState first = agent(762_301);
        AgentLpqMemberState second = agent(762_302);

        Order a = pass(first, shooter(0, true, 100, 600, 50, MAX_HP));
        Order b = pass(second, shooter(1, true, 150, 600, 50, MAX_HP));

        assertEquals(Group.A, first.stageNineState().group());
        assertEquals(Group.B, second.stageNineState().group());
        assertEquals(Side.LEFT, first.stageNineState().homeSide());
        assertEquals(Side.LEFT, second.stageNineState().homeSide(), "both started left of Alishar");
        assertEquals(Side.LEFT, a.state().side());
        assertEquals(Side.RIGHT, b.state().side(), "group B goes round to the other side");
        assertEquals(Move.FIGHT_FROM_STATION, a.move());
        assertEquals(at(233), a.point());
        assertEquals(new Targets(ALISHAR, AgentLpqDefinition.ALISHAR_SUMMONS), a.targets());
        assertEquals(Move.WALK_TO_STATION, b.move(), "it walks across him without attacking");
        assertEquals(at(967), b.point());

        AgentLpqMemberState late = agent(762_303);
        pass(late, shooter(1, false, 120, 600, 50, MAX_HP));
        assertEquals(Group.A, late.stageNineState().group(), "a shooter that joins later is group A");
    }

    @Test
    void aShooterWalksOutOfAlisharsReachBeforeItShoots() {
        AgentLpqMemberState member = agent(762_351);
        Order crossing = pass(member, shooter(1, true, 300, 600, 50, MAX_HP));
        assertEquals(Move.WALK_TO_STATION, crossing.move(), "group B crosses to the right");
        assertEquals(at(967), crossing.point());
        for (int x : new int[]{601, 700, 800, 920}) {
            Order next = pass(member, shooter(1, false, x, 600, 50, MAX_HP));
            assertEquals(Move.WALK_TO_STATION, next.move(), (x - 600) + " px past him: still inside his 320-px reach");
            assertEquals(at(967), next.point());
            assertNull(next.targets(), "it does not stop to attack at his side");
        }
        Order clear = pass(member, shooter(1, false, 921, 600, 50, MAX_HP));
        assertEquals(Move.FIGHT_FROM_STATION, clear.move(), "321 px: out of his reach, it fights");
        assertEquals(at(967), clear.point());

        Order lunge = pass(member, shooter(1, false, 921, 700, 50, MAX_HP));
        assertEquals(Move.WALK_TO_STATION, lunge.move(), "Alishar steps to 221 px of it: it steps back out first");
        assertEquals(at(1_067), lunge.point());
    }

    @Test
    void aStoredStationSurvivesAlisharsSmallSteps() {
        AgentLpqMemberState member = agent(762_311);
        Order first = pass(member, shooter(0, true, 233, 600, 50, MAX_HP));
        assertEquals(at(233), first.point());

        for (int bossX : new int[]{610, 590, 620, 596, 610}) {
            Order next = pass(member, shooter(0, false, 233, bossX, 50, MAX_HP));
            assertEquals(Move.FIGHT_FROM_STATION, next.move(), "boss " + bossX);
            assertEquals(at(233), next.point(), "boss " + bossX + ": the farm keeps its anchor");
            assertEquals(at(233), member.stageNineState().station());
        }
        Order moved = pass(member, shooter(0, false, 233, 630, 50, MAX_HP));
        assertEquals(at(263), moved.point(), "397 px is past the band: a new station");
    }

    @Test
    void restingStartsBelowFortyPercentAndHoldsUntilSeventy() {
        AgentLpqMemberState member = agent(762_321);
        Order fighting = pass(member, shooter(0, true, 233, 600, 0, 900));
        assertEquals(Move.FIGHT_FROM_STATION, fighting.move(), "45 %: still fighting");
        assertFalse(member.stageNineState().resting());

        Order start = pass(member, shooter(0, false, 233, 600, 0, 780));
        assertEquals(Move.WALK_TO_REST, start.move(), "39 % and no potions: it backs off");
        assertEquals(at(-250), start.point(), "the floor end on its side, 850 px from him");
        assertTrue(member.stageNineState().resting());
        assertNull(start.targets(), "a member walking to rest does not fight");

        for (int hp : new int[]{1_000, 1_200, 1_399}) {
            Order resting = pass(member, shooter(0, false, -250, 600, 0, hp));
            assertEquals(Move.REST, resting.move(), hp + " HP: still resting");
            assertEquals(at(-250), resting.point());
        }

        Order back = pass(member, shooter(0, false, -250, 600, 0, 1_400));
        assertFalse(member.stageNineState().resting(), "70 %: back to the fight");
        assertNull(member.stageNineState().restPoint());
        assertEquals(Move.FIGHT_FROM_STATION, back.move());
        assertEquals(at(233), back.point(), "its group, side and station waited for it");
        assertEquals(Group.A, member.stageNineState().group());

        AgentLpqMemberState potted = agent(762_322);
        pass(potted, shooter(0, true, 233, 600, 0, 700));
        assertTrue(potted.stageNineState().resting());
        Order rebought = pass(potted, shooter(0, false, -250, 600, POT_STOP, 900));
        assertFalse(potted.stageNineState().resting(), "POT_STOP potions again: back to the fight at 45 %");
        assertEquals(Move.FIGHT_FROM_STATION, rebought.move());
    }

    @Test
    void meleeMembersTakeTheSummonsFirstAndGetNoGroup() {
        AgentLpqMemberState member = agent(762_331);
        Order order = pass(member, new Pass(false, -1, true, at(500), at(600), List.of(), 50, POT_STOP, MAX_HP,
                MAX_HP));
        assertEquals(Move.MELEE, order.move());
        assertNull(order.point());
        assertEquals(new Targets(AgentLpqDefinition.ALISHAR_SUMMONS, ALISHAR), order.targets());
        assertEquals(MemberState.NONE, member.stageNineState());
    }

    @Test
    void aSummonOnAShooterIsFoughtFirst() {
        Order order = AgentLpqStageNineBossPolicy.order(null, new Pass(true, 0, true, at(233), at(600),
                List.of(at(280)), 50, POT_STOP, MAX_HP, MAX_HP), FLOOR);
        assertEquals(new Targets(AgentLpqDefinition.ALISHAR_SUMMONS, ALISHAR), order.targets());
    }

    @Test
    void theMemberKeepsItsStateUntilTheNextStage() {
        AgentLpqMemberState member = agent(762_341);
        pass(member, shooter(1, true, 100, 600, 50, MAX_HP));
        MemberState stored = member.stageNineState();
        assertEquals(new MemberState(Group.B, Side.LEFT, Side.RIGHT, at(967), false, null), stored);
        stored.station().translate(5, 0);
        assertEquals(at(967), member.stageNineState().station(), "a caller cannot move a stored station");

        member.resetForStage(AgentLpqMemberState.Role.BOSS_ATTACKER);
        assertEquals(MemberState.NONE, member.stageNineState());
    }

    // ---- wiring (source scan) ----

    @Test
    void theCoordinatorAppliesEachOrderAsIs() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/server/agents/capabilities/partyquest/lpq/AgentLpqCoordinator.java"))
                .replace("\r\n", "\n");
        String boss = AgentLpqStageNineStationTest.method(source, "    private static void boss(");
        String flatBoss = boss.replaceAll("\\s+", "");
        assertTrue(flatBoss.contains("List<Integer>stageNineShooters=alisharAlive?stageNineShooterIds(session)"
                + ":List.of();"));
        assertTrue(flatBoss.contains("booleanstageNineGroupsUnset=alisharAlive&&session.members().stream()"
                + ".allMatch(member->member.stageNineGroup()==null);"));
        assertTrue(flatBoss.contains("fightAlishar(session,member,entry,agent,alishar.getPosition(),"
                + "stageNineShooters,stageNineGroupsUnset,stageNineSummons);"));

        String fight = AgentLpqStageNineStationTest.method(source, "    private static void fightAlishar(");
        String flat = fight.replaceAll("\\s+", "");
        assertTrue(flat.contains("AgentLpqStageNineBossPolicy.MemberStatebefore=member.stageNineState();"), flat);
        assertTrue(flat.contains("intpotions=ACTIONS.hpPotionCount(agent);"), flat);
        assertTrue(flat.contains("AgentLpqStageNineBossPolicy.Orderorder=AgentLpqStageNineBossPolicy.order(before,"
                + "newAgentLpqStageNineBossPolicy.Pass(stageNineShooter(agent,session.companionAdopted()),"
                + "shooterIds.indexOf(agent.getId()),"
                + "groupsUnset,agent.getPosition(),boss,summons,potions,"
                + "server.agents.runtime.AgentRuntimeConfig.cfg.POT_STOP,agent.getHp(),agent.getMaxHp()),"
                + "stageNineFloor());"), flat);
        assertTrue(flat.contains("member.applyStageNine(order.state());"), flat);
        assertTrue(flat.contains("switch(order.move()){"
                + "caseMELEE->ACTIONS.grind(entry,order.targets().preferred(),order.targets().fallback());"
                + "caseWALK_TO_STATION,WALK_TO_REST->ACTIONS.navigate(entry,order.point(),true);"
                + "caseFIGHT_FROM_STATION,FIGHT_AT_REST->ACTIONS.grindFromAnchor(entry,order.point(),"
                + "order.targets().preferred(),order.targets().fallback());"
                + "caseREST->ACTIONS.stop(entry);}"), flat);
        assertEquals(5, fight.split("ACTIONS\\.", -1).length - 1, "the potion count and one action per move");
        assertEquals(1, fight.split("applyStageNine\\(", -1).length - 1);
        for (String rescue : AgentLpqStageNineStationTest.RESCUE_CALLS) {
            assertFalse(fight.contains(rescue), "no rescue: " + rescue);
        }
        assertEquals(Set.of(Move.values()), Set.of(Move.MELEE, Move.WALK_TO_STATION, Move.FIGHT_FROM_STATION,
                Move.WALK_TO_REST, Move.REST, Move.FIGHT_AT_REST), "every move is wired");
    }
}
