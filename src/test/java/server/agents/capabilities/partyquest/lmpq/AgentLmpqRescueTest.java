package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The 'LMPQ rescue:' line (P6h LMPQ step 1, C8): its format (LPQ's, so pqkit parses both), a site without its session
 * finding it by the member, a note that never throws, and the coupon vacuum noting only a pickup that happened.
 */
class AgentLmpqRescueTest {
    private final LmpqRunFixture run = new LmpqRunFixture();
    private final Character ben = run.human(1, "Ben");
    private final Character mira = run.companion(2, "Mira");
    private final Character kes = run.companion(3, "Kes");
    private final Character pel = run.companion(4, "Pel");

    @AfterEach
    void tearDown() {
        run.close();
    }

    @Test
    void theLineNamesTheRunTheSiteAndTheMember() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, true,
                AgentLmpqSession.Phase.FARMING, ben, mira, kes, pel);
        try (LmpqLogLines lines = new LmpqLogLines(AgentLmpqRescue.class)) {
            AgentLmpqRescue.note(session, AgentLmpqRescue.Kind.VACUUM, "farm-loot", mira, "coupons=3");
            AgentLmpqRescue.note(null, AgentLmpqRescue.Kind.VACUUM, "leader-regroup", kes, "");
            AgentLmpqRescue.note(null, AgentLmpqRescue.Kind.VACUUM, "farm-sweep", null, null);

            String id = session.sessionId();
            assertEquals(List.of(
                    "LMPQ rescue: session=" + id + " kind=VACUUM site=farm-loot phase=FARMING member=Mira(2)"
                            + " applied=true companionRun=true detail=coupons=3",
                    "LMPQ rescue: session=" + id + " kind=VACUUM site=leader-regroup phase=FARMING member=Kes(3)"
                            + " applied=true companionRun=true detail=-",
                    "LMPQ rescue: session=- kind=VACUUM site=farm-sweep phase=- member=-(-) applied=true"
                            + " companionRun=false detail=-"),
                    lines.startingWith("LMPQ rescue:"));
        }
    }

    @Test
    void aNoteNeverThrows() {
        Character broken = mock(Character.class);
        when(broken.getId()).thenReturn(9);
        when(broken.getName()).thenThrow(new IllegalStateException("character unloaded"));
        try (LmpqLogLines lines = new LmpqLogLines(AgentLmpqRescue.class)) {
            AgentLmpqRescue.note(null, AgentLmpqRescue.Kind.VACUUM, "farm-loot", broken, "");
            assertEquals(List.of("LMPQ rescue note failed: kind=VACUUM site=farm-loot"),
                    lines.startingWith("LMPQ rescue"));
        }
    }

    @Test
    void theVacuumIsNotedOnlyWhenItPickedSomethingUp() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, AgentLmpqSession.Phase.FARMING,
                ben, mira, kes, pel);
        when(ben.getMapId()).thenReturn(AgentLmpqDefinition.mapForRoom(1));
        when(kes.getMapId()).thenReturn(AgentLmpqDefinition.mapForRoom(2));
        when(pel.getMapId()).thenReturn(AgentLmpqDefinition.mapForRoom(4));
        session.member(mira.getId()).assignTargetRoom(3);
        session.rooms().reserve(3, mira.getId(), 5_000L);
        when(mira.getItemQuantity(AgentLmpqDefinition.COUPON, false)).thenReturn(7);
        try (LmpqLogLines lines = new LmpqLogLines(AgentLmpqRescue.class)) {
            when(run.partyQuests.lootNearby(mira, Set.of(AgentLmpqDefinition.COUPON))).thenReturn(false);
            AgentLmpqCoordinator.tickMember(session, run.entry(mira), mira, 6_000L);
            assertTrue(lines.startingWith("LMPQ rescue:").isEmpty(), "nothing was picked up");

            session.rooms().reserve(5, mira.getId(), 7_000L);
            session.member(mira.getId()).assignTargetRoom(5);
            when(mira.getMapId()).thenReturn(AgentLmpqDefinition.mapForRoom(5));
            when(run.partyQuests.lootNearby(mira, Set.of(AgentLmpqDefinition.COUPON))).thenReturn(true);
            AgentLmpqCoordinator.tickMember(session, run.entry(mira), mira, 8_000L);

            assertEquals(List.of("LMPQ rescue: session=" + session.sessionId() + " kind=VACUUM site=farm-loot"
                            + " phase=FARMING member=Mira(2) applied=true companionRun=false detail=coupons=7"),
                    lines.startingWith("LMPQ rescue:"));
        }
    }
}
