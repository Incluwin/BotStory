package server.living.roster;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.expedition.boss.AgentBossRunHost.Out;
import server.agents.capabilities.expedition.boss.AgentBossRunHost.Refusal;
import server.agents.capabilities.expedition.boss.AgentBossRunMember.Phase;
import server.integration.AgentPresence;
import server.life.NPC;
import server.living.roster.CompanionBossAllies.State;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static server.living.roster.BossAllyHarness.KEY;
import static server.living.roster.BossAllyHarness.SLEEPYWOOD_CAMP;
import static server.living.roster.BossAllyHarness.isLine;
import static server.living.roster.BossAllyHarness.seat;
import static server.living.roster.LifecycleHarness.HENESYS;

/**
 * Ann logs off with bench allies at Mu Young (P7 Part B step 10, CompanionBossAllies through RosterService's logout):
 * an ally still being called or on its way down is called off and lets its lease, its commitment and its trip go with
 * no line; one loaded and on her list, or walking up to her, is taken off it, and the run's WITHDRAWN refusal sends it
 * home; one in the fight sees it through for a human still in it, even one outside her party
 * (InstanceLogoutHandoff.anyHuman), the first saying so once where that human hears it, as are its later lines; with
 * no human left in the fight nothing changes (the run's no-human rule takes them out); one down outside goes home at
 * once. The old session of a channel change is no logout: nothing changes.
 */
class CompanionBossAlliesLogoutTest {
    private static final int ORRIN = 911;
    private static final int MAGNA = 912;
    private static final int GUNNAR = 913;
    private static final int BEN = 20;
    private static final int MU_YOUNG = 1061014;

    private final BossAllyHarness h = new BossAllyHarness();
    private final Character ben = mock(Character.class);

    @BeforeEach
    void annCalledHerAlliesDownToMuYoung() {
        h.benched("orrin", "Orrin", ORRIN, SLEEPYWOOD_CAMP);
        h.benched("magna", "Magna", MAGNA, SLEEPYWOOD_CAMP);
        h.benched("gunnar", "Gunnar", GUNNAR, SLEEPYWOOD_CAMP);
        h.game.roster();
        when(h.game.world.bossRuns()).thenReturn(h.bossRuns); // the logout reaches them as LivingWorld wires it
        AgentPresence.install(chr -> h.game.personaOfBot.containsKey(chr.getId()));
        when(ben.getId()).thenReturn(BEN);
        when(ben.getName()).thenReturn("Ben");
        when(ben.isLoggedin()).thenReturn(true);
        NPC muYoung = mock(NPC.class);
        when(muYoung.getPosition()).thenReturn(new Point(617, 246)); // WZ 105100100 life/0
        when(h.room.getNPCById(MU_YOUNG)).thenReturn(muYoung);
    }

    @AfterEach
    void close() {
        h.close();
    }

    // ---- before they are here ------------------------------------------------------------------

    @Test
    void aCallStillWaitingOnCompanionOpsIsDroppedAtTheLogout() {
        List<Runnable> ops = DirectExecutor.queue();
        CompanionExecutor queued = DirectExecutor.queued(ops);
        when(h.game.world.executor()).thenReturn(queued);
        h.allies.send(h.game.ann, "easy_balrog", List.of("gunnar"), h.now);
        assertEquals(State.CALLING, h.allies.trip("gunnar").state);

        logsOff(null);
        assertNull(h.allies.trip("gunnar"), "dropped at the logout itself");
        List.copyOf(ops).forEach(Runnable::run); // the call comes after it

        assertEquals(List.of(), h.open.calls, "never enlisted");
        assertEquals(0, h.game.leases.count(CompanionLeases.Kind.EXPEDITION));
        assertEquals(List.of(), h.whispers);
    }

    @Test
    void aTravellingAllyIsCalledOffAndLetGoWithNoLine() {
        h.allies.send(h.game.ann, "easy_balrog", List.of("gunnar"), h.now);
        assertEquals(State.TRAVELLING, h.allies.trip("gunnar").state);
        List<String> before = List.copyOf(h.whispers); // its ETA

        logsOff(null);

        assertHome(GUNNAR, "gunnar");
        assertTrue(h.open.calls.contains("withdraw " + GUNNAR), h.open.calls.toString());
        h.game.runDueTimers(); // its arrival is stale
        h.bossRuns.refused(seat(GUNNAR), Refusal.WITHDRAWN); // the run's next tick
        assertEquals(List.of(), h.ports.loads);
        assertEquals(before, h.whispers, "no line");
        assertEquals(List.of(), h.mapChat);
        assertEquals(List.of(), h.game.partyChat);
    }

    // ---- here, before Start ---------------------------------------------------------------------

    @Test
    void aLoadedAllyOnHerListOrWalkingUpIsTakenOffItAndTheRefusalSendsItHome() {
        h.arrived("orrin", "magna");
        h.open.phases.put(ORRIN, Phase.REGISTERED); // Magna still walks up to her
        h.bossRuns.registered(seat(ORRIN));

        logsOff(null);

        assertTrue(h.open.calls.containsAll(List.of("withdraw " + ORRIN, "withdraw " + MAGNA)),
                h.open.calls.toString());
        assertEquals(State.HERE, h.allies.trip("orrin").state, "the run takes it off her list on its next tick");
        assertEquals(2, h.game.leases.count(CompanionLeases.Kind.EXPEDITION));

        for (int id : List.of(ORRIN, MAGNA)) {
            h.open.phases.put(id, Phase.REFUSED);
            h.bossRuns.refused(seat(id), Refusal.WITHDRAWN);
        }

        assertHome(ORRIN, "orrin");
        assertHome(MAGNA, "magna");
        assertEquals(0, h.game.leases.count(CompanionLeases.Kind.EXPEDITION));
        assertEquals(List.of(), h.game.partyChat);
    }

    // ---- in the fight ---------------------------------------------------------------------------

    @Test
    void inTheFightTheyStayForAHumanOutsideHerPartyTheFirstSayingSoOnceWhereHeHearsIt() {
        EventInstanceManager fight = inTheFightWithBen(true);

        logsOff(fight);

        assertTrue(h.open.calls.containsAll(List.of("handTo " + ORRIN + " -> " + BEN,
                "handTo " + MAGNA + " -> " + BEN)), h.open.calls.toString());
        assertEquals(1, h.mapChat.size(), "once for Ann's allies: " + h.mapChat);
        assertTrue(h.mapChat.get(0).endsWith(": i'll see this through with you, Ben."), h.mapChat.toString());
        assertEquals(State.HERE, h.allies.trip("orrin").state, "they fight on");
        assertEquals(KEY, h.game.commitments.keyOf(ORRIN), "still tied to Ann's run");
        assertEquals(2, h.game.leases.count(CompanionLeases.Kind.EXPEDITION));

        Character orrin = h.game.bots.get(ORRIN); // out by the reward portal after the clear, with Ben
        orrin.changeMap(h.room, new Point(-100, 246));
        when(orrin.getEventInstance()).thenReturn(null);
        when(h.room.getCharacterById(BEN)).thenReturn(ben);
        h.mapChat.clear();
        h.open.cleared = true;
        h.bossRuns.memberOut(seat(ORRIN), Out.EXITED);

        assertEquals(1, h.mapChat.size(), "said where Ben hears it: " + h.mapChat);
        assertTrue(isLine(BossRunLines.ALLY_GOOD_FIGHT, null, h.mapChat.get(0).substring("Orrin: ".length())),
                h.mapChat.toString());
        assertHome(ORRIN, "orrin");
    }

    @Test
    void aHandoffLineHerPartyCompanionSaidIsNotSaidAgain() {
        EventInstanceManager fight = inTheFightWithBen(true);
        h.runs.get(KEY).handoffSaid = true; // Isolde said it in party chat first

        logsOff(fight);

        assertTrue(h.open.calls.contains("handTo " + ORRIN + " -> " + BEN), h.open.calls.toString());
        assertEquals(List.of(), h.mapChat);
    }

    @Test
    void inTheFightWithNoHumanLeftNothingChanges() {
        EventInstanceManager fight = inTheFightWithBen(false);
        List<String> before = List.copyOf(h.whispers);

        logsOff(fight);

        assertTrue(h.open.calls.stream().noneMatch(call -> call.startsWith("handTo")), h.open.calls.toString());
        assertEquals(List.of(), h.mapChat);
        assertEquals(before, h.whispers);
        assertEquals(State.HERE, h.allies.trip("orrin").state, "the run's no-human rule takes them out");
        assertEquals(State.HERE, h.allies.trip("magna").state);
        assertEquals(2, h.game.leases.count(CompanionLeases.Kind.EXPEDITION));
    }

    @Test
    void anAllyDownOutsideGoesHomeAtOnce() {
        EventInstanceManager fight = inTheFightWithBen(false);
        Character orrin = h.game.bots.get(ORRIN); // down, and came to outside beside Mu Young
        orrin.changeMap(h.room, new Point(0, 246));
        when(orrin.getEventInstance()).thenReturn(null);
        h.bossRuns.memberOut(seat(ORRIN), Out.DIED);
        h.open.phases.put(ORRIN, Phase.OUT);
        assertTrue(h.allies.trip("orrin").waitingOutside);

        logsOff(fight);

        assertHome(ORRIN, "orrin");
        assertEquals(State.HERE, h.allies.trip("magna").state, "Magna is still in the fight");
        assertEquals(1, h.game.leases.count(CompanionLeases.Kind.EXPEDITION));
    }

    // ---- no logout -------------------------------------------------------------------------------

    @Test
    void aChannelChangeIsNoLogoutAndLeavesThemAlone() {
        h.arrived("orrin");
        h.allies.send(h.game.ann, "easy_balrog", List.of("gunnar"), h.now);

        h.game.roster().ownerChangingChannel(h.game.ann, 2);
        h.game.roster().onOwnerLogout(h.game.ann, null); // the old session's own logout

        assertTrue(h.open.calls.stream().noneMatch(call -> call.startsWith("withdraw")), h.open.calls.toString());
        assertEquals(State.HERE, h.allies.trip("orrin").state);
        assertEquals(State.TRAVELLING, h.allies.trip("gunnar").state);
        assertEquals(2, h.game.leases.count(CompanionLeases.Kind.EXPEDITION));
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** Ann's client is gone (she is offline and in no room any more), then her logout runs on companion-ops. */
    private void logsOff(EventInstanceManager instance) {
        h.game.annOnline = false;
        h.game.moveAnn(HENESYS, 0, 0);
        h.game.roster().onOwnerLogout(h.game.ann, instance);
    }

    /**
     * Orrin and Magna signed up and went into the Easy Balrog fight with Ann; Ben, in no party of hers, is in it too
     * when {@code withBen}. The expedition is Ann's. The fight's event instance.
     */
    private EventInstanceManager inTheFightWithBen(boolean withBen) {
        h.arrived("orrin", "magna");
        for (int id : List.of(ORRIN, MAGNA)) {
            h.open.phases.put(id, Phase.REGISTERED);
            h.bossRuns.registered(seat(id));
        }
        MapleMap battle = h.game.annEntersInstance(105100300);
        EventInstanceManager fight = h.game.ann.getEventInstance();
        when(fight.getExpedition()).thenReturn(h.expedition); // Ann leads it
        Character orrin = h.game.bots.get(ORRIN);
        Character magna = h.game.bots.get(MAGNA);
        for (Character ally : List.of(orrin, magna)) {
            h.open.phases.put(ally.getId(), Phase.INSIDE);
            ally.changeMap(battle, new Point(0, 0));
            when(ally.getEventInstance()).thenReturn(fight);
            when(fight.getPlayerById(ally.getId())).thenReturn(ally);
        }
        if (withBen) {
            when(fight.getPlayerById(BEN)).thenReturn(ben);
            when(battle.getCharacterById(BEN)).thenReturn(ben);
            when(fight.getPlayers()).thenReturn(List.of(orrin, ben, magna));
        } else {
            when(fight.getPlayers()).thenReturn(List.of(orrin, magna));
        }
        h.mapChat.clear();
        return fight;
    }

    /** The ally is unloaded (or never was) and its trip, its EXPEDITION lease and its commitment are let go. */
    private void assertHome(int id, String personaId) {
        assertNull(h.allies.trip(personaId), personaId + " still on a trip");
        assertNull(h.game.leases.holder(id), personaId + "'s lease");
        assertFalse(h.game.commitments.isCommitted(id), personaId + "'s commitment");
        assertFalse(h.game.loaded.contains(id), personaId + " still loaded");
    }
}
