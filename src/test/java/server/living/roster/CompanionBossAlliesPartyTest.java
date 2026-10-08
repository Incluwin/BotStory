package server.living.roster;

import net.server.world.PartyEvents;
import net.server.world.PartyOperation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.expedition.boss.AgentBossRunHost.Out;
import server.agents.capabilities.expedition.boss.AgentBossRunHost.Refusal;
import server.agents.capabilities.expedition.boss.AgentBossRunMember.Phase;
import server.living.roster.CompanionBossAllies.State;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static server.living.roster.BossAllyHarness.SLEEPYWOOD_CAMP;
import static server.living.roster.BossAllyHarness.seat;

/**
 * An owner's overflow party (P7 Part B step 8, CompanionBossAllies.formParty): once none of the owner's allies still
 * waits to sign up (or is still being called), those on Mu Young's list or in the fight form one party of bots, led by
 * the highest level (the lower ordinal on a tie); a party already formed keeps its lead, so a late registrant joins it.
 * One ally forms none, and a party holds six. A refusal (a LATE one at Start too) or an ally leaving forms it again.
 * Every join and leave carries the "companion.boss" cause, so the party sync takes it for companion code's own echo.
 */
class CompanionBossAlliesPartyTest {
    private static final int ORRIN = 911;
    private static final int MAGNA = 912;
    private static final int GUNNAR = 913;

    private final BossAllyHarness h = new BossAllyHarness();

    @BeforeEach
    void annHasThreeAlliesOnTheBench() {
        h.benched("orrin", "Orrin", ORRIN, SLEEPYWOOD_CAMP);
        h.benched("magna", "Magna", MAGNA, SLEEPYWOOD_CAMP);
        h.benched("gunnar", "Gunnar", GUNNAR, SLEEPYWOOD_CAMP);
    }

    @AfterEach
    void close() {
        h.close();
    }

    @Test
    void itFormsOnlyOnceNoAllyOfTheOwnerWaitsToSignUp() {
        h.game.roster();
        h.arrived("orrin", "magna", "gunnar");

        signUp(ORRIN);
        signUp(MAGNA);
        assertEquals(List.of(), h.ports.party, "Gunnar still walks up");

        signUp(GUNNAR);

        assertEquals(List.of("join Orrin <- Magna (companion.boss)", "join Orrin <- Gunnar (companion.boss)"),
                h.ports.party);
    }

    @Test
    void anAllyStillBeingCalledHoldsThePartyBack() {
        h.game.roster();
        h.arrived("orrin", "magna");
        List<Runnable> ops = DirectExecutor.queue();
        CompanionExecutor queued = DirectExecutor.queued(ops);
        when(h.game.world.executor()).thenReturn(queued);
        h.allies.send(h.game.ann, "easy_balrog", List.of("gunnar"), h.now);
        assertEquals(State.CALLING, h.allies.trip("gunnar").state);

        h.open.phases.put(ORRIN, Phase.REGISTERED);
        h.open.phases.put(MAGNA, Phase.REGISTERED);
        h.allies.registered(seat(MAGNA));

        assertEquals(List.of(), h.ports.party);
    }

    @Test
    void theHighestLevelLeadsAndATieGoesToTheLowerOrdinal() {
        level(ORRIN, 70);
        level(MAGNA, 72);
        level(GUNNAR, 72);
        h.game.roster();
        h.arrived("orrin", "magna", "gunnar");

        signUp(ORRIN, MAGNA, GUNNAR);

        assertEquals(List.of("join Magna <- Orrin (companion.boss)", "join Magna <- Gunnar (companion.boss)"),
                h.ports.party);
    }

    @Test
    void oneAllyFormsNoParty() {
        h.game.roster();
        h.arrived("orrin");

        signUp(ORRIN);
        h.open.phases.put(ORRIN, Phase.INSIDE);
        h.allies.registered(seat(ORRIN));

        assertEquals(List.of(), h.ports.party);
    }

    @Test
    void aPartyHoldsSixAtMost() {
        h.game.config.ROSTER_MAX = 12; // seven allies for one owner
        for (int i = 0; i < 4; i++) {
            h.benched("extra" + i, "Extra" + i, 920 + i, SLEEPYWOOD_CAMP);
        }
        h.game.roster();
        h.arrived("orrin", "magna", "gunnar", "extra0", "extra1", "extra2", "extra3");

        signUp(ORRIN, MAGNA, GUNNAR, 920, 921, 922, 923);

        assertEquals(5, h.ports.party.size(), "the lead and five: " + h.ports.party);
        assertTrue(h.ports.party.stream().allMatch(join -> join.startsWith("join Orrin <- ")),
                h.ports.party.toString());
        assertTrue(h.ports.party.stream().noneMatch(join -> join.contains("Extra3")), h.ports.party.toString());
    }

    @Test
    void aLateRegistrantJoinsThePartyAlreadyFormedWhateverItsLevel() {
        h.game.roster();
        h.arrived("orrin", "magna");
        signUp(ORRIN, MAGNA);
        assertEquals(List.of("join Orrin <- Magna (companion.boss)"), h.ports.party);

        level(GUNNAR, 90);
        h.arrived("gunnar");
        signUp(GUNNAR);

        assertEquals(List.of("join Orrin <- Magna (companion.boss)", "join Orrin <- Gunnar (companion.boss)"),
                h.ports.party);
    }

    @Test
    void aLateRefusalAtStartFormsTheParty() {
        h.game.roster();
        h.arrived("orrin", "magna");
        h.allies.send(h.game.ann, "easy_balrog", List.of("gunnar"), h.now); // on its way, not here yet
        signUp(ORRIN, MAGNA);
        assertEquals(List.of(), h.ports.party, "Gunnar is still on its way");

        h.bossRuns.refused(seat(GUNNAR), Refusal.LATE); // the leader pressed Start first

        assertEquals(List.of("join Orrin <- Magna (companion.boss)"), h.ports.party);
    }

    @Test
    void aLeadThatLeavesTheRunHandsThePartyToTheNext() {
        h.game.roster();
        h.arrived("orrin", "magna", "gunnar");
        signUp(ORRIN, MAGNA, GUNNAR);
        h.ports.party.clear();

        h.bossRuns.memberOut(seat(ORRIN), Out.MISSED_START);

        assertEquals(List.of("leave Orrin (companion.boss)", "join Magna <- Gunnar (companion.boss)"), h.ports.party);
    }

    @Test
    void everyJoinCarriesTheBossCauseSoThePartySyncTakesItForAnEcho() {
        h.game.roster();
        h.arrived("orrin", "magna");
        signUp(ORRIN, MAGNA);
        String join = h.ports.party.get(0);
        String cause = join.substring(join.indexOf('(') + 1, join.indexOf(')'));
        assertEquals("companion.boss", cause);

        CompanionPartySync.Crew crew = mock(CompanionPartySync.Crew.class);
        new CompanionPartySync(crew, () -> h.now).onPartyChanged(new PartyEvents.PartyChangeEvent(901,
                PartyOperation.JOIN, MAGNA, 0, new PartyEvents.Snapshot(List.of(ORRIN), ORRIN),
                new PartyEvents.Snapshot(List.of(ORRIN, MAGNA), ORRIN), cause, true));

        verifyNoInteractions(crew);
    }

    private void signUp(int... ids) {
        for (int id : ids) {
            h.open.phases.put(id, Phase.REGISTERED);
            h.bossRuns.registered(seat(id));
        }
    }

    private void level(int id, int level) {
        when(h.game.bots.get(id).getLevel()).thenReturn(level);
    }
}
