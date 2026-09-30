package server.living.roster;

import client.Character;
import client.Client;
import client.Job;
import net.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.partyquest.AgentPartyQuestHandback.Handback;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.KERNING;

/**
 * Decision 8b in a companion KPQ run (P6): Ann logs off inside a four-member run her friend Ben leads, and her
 * companions see it through for him, the first saying so once in party chat. They stay hers (rows ACTIVE, still
 * committed, led by nobody new), their recovery at the end only stops them, and they park once the run is over. The
 * event leader's companions are never handed over, since the run ends with its leader (whoever leads it now: the lead
 * can pass inside the run), and nobody's when fewer than three of the run would stay in it, since KerningPQ ends it
 * then.
 */
class CompanionRunDelegationTest {
    private static final int BEN = 20;

    private final CompanionPqFixture fx = new CompanionPqFixture();
    private final LifecycleHarness game = fx.game;
    private final Character ben = mock(Character.class);
    /** The run's event instance, which still holds Ann as she logs off. */
    private final EventInstanceManager instance = mock(EventInstanceManager.class);
    private Persona mira;
    private Persona tobi;
    private CompanionRun run;

    @BeforeEach
    void benAndAnnAtLakelis() {
        when(ben.getId()).thenReturn(BEN);
        when(ben.getName()).thenReturn("Ben");
        when(ben.getMap()).thenReturn(game.map(KERNING));
        when(ben.getMapId()).thenReturn(KERNING);
        when(ben.getParty()).thenReturn(game.party);
        when(ben.getEventInstance()).thenReturn(instance);
        CompanionPqFixture.stand(ben, 25, Job.PAGE);
        Client client = mock(Client.class);
        when(client.getChannel()).thenReturn(1);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(client).runOnIoThread(any());
        when(ben.getClient()).thenReturn(client);
        when(Server.getInstance().getWorld(0).getPlayerStorage().getCharacterById(BEN)).thenReturn(ben);
        game.humans.add(BEN);
        game.members.add(BEN);
        mira = fx.companion("mira", "Mira", 900, 25, Job.HUNTER);
    }

    @AfterEach
    void close() {
        fx.close();
    }

    /** Ann's Tobi comes along too: four go in. */
    private void withTobi() {
        tobi = fx.companion("tobi", "Tobi", 901, 26, Job.PAGE);
    }

    /** {@code leader} says Yes at Lakelis, and the whole lineup is in the event instance. */
    private void inTheRun(Character leader) {
        int leaderId = leader.getId();
        when(game.party.getLeaderId()).thenReturn(leaderId);
        fx.ready();
        game.roster().installInstanceDelegation(fx.lifecycle); // as LivingWorld.start does
        fx.service.start(leader, fx.service.plan(leader, "kpq"));
        run = fx.service.runForSession(fx.doors.session.sessionId());
        for (int id : run.memberIds) {
            when(instance.getPlayerById(id)).thenReturn(id == ANN ? game.ann : id == BEN ? ben : game.bots.get(id));
        }
        when(instance.getLeaderId()).thenReturn(leaderId);
    }

    /** Inside the run the party lead passes to {@code leaderId}: the instance and, a tick later, the session follow. */
    private void passTheLeadTo(int leaderId) {
        when(game.party.getLeaderId()).thenReturn(leaderId);
        when(instance.getLeaderId()).thenReturn(leaderId);
        fx.doors.session.setLeadership(leaderId, 900);
    }

    @Test
    void nonLeaderOwnerInAFourMemberRunHandsCompanionsToTheLeader() {
        withTobi();
        inTheRun(ben);
        assertEquals(Set.of(BEN, ANN, 900, 901), run.memberIds);

        game.roster().onOwnerLogout(game.ann, instance);

        assertEquals(List.of("Mira: i'll see this through with you, Ben."), game.partyChat, "once, not per companion");
        assertEquals(2, game.store.events("companion.handoff").size());
        assertEquals(RosterState.ACTIVE, game.row(mira).state(), "still Ann's");
        assertEquals(RosterState.ACTIVE, game.row(tobi).state());
        assertTrue(game.roster().finishingForFriend(900));
        assertTrue(game.roster().finishingForFriend(901));
        assertTrue(game.commitments.isCommitted(900) && game.commitments.isCommitted(901));
        assertTrue(game.members.containsAll(List.of(900, 901)), "nobody leaves the run");
    }

    @Test
    void eventLeaderLoggingOutHandsNobody() {
        withTobi();
        inTheRun(game.ann); // Ann leads Ben and her two

        game.roster().onOwnerLogout(game.ann, instance);

        assertEquals(List.of(), game.store.events("companion.handoff"));
        assertFalse(game.roster().finishingForFriend(900));
        assertEquals(RosterState.PARKED, game.row(mira).state(), "the run ends with its leader: they leave with her");
        assertFalse(game.commitments.isCommitted(900));
        assertTrue(game.partyChat.stream().noneMatch(line -> line.contains("see this through")),
                game.partyChat.toString());
    }

    @Test
    void aLeaderWhoPassedTheLeadHandsTheirCompanionsToTheNewLeader() {
        withTobi();
        inTheRun(game.ann); // Ann leads Ben and her two
        passTheLeadTo(BEN);

        game.roster().onOwnerLogout(game.ann, instance);

        assertEquals(List.of("Mira: i'll see this through with you, Ben."), game.partyChat);
        assertEquals(2, game.store.events("companion.handoff").size());
        assertTrue(game.roster().finishingForFriend(900) && game.roster().finishingForFriend(901));
        assertTrue(game.commitments.isCommitted(900), "the run goes on with Ben, Mira and Tobi");
    }

    @Test
    void theNewLeaderLoggingOutHandsNobody() {
        withTobi();
        inTheRun(ben);
        passTheLeadTo(ANN); // Ann leads now: the run ends with her

        game.roster().onOwnerLogout(game.ann, instance);

        assertEquals(List.of(), game.store.events("companion.handoff"));
        assertEquals(RosterState.PARKED, game.row(mira).state());
        assertTrue(game.partyChat.stream().noneMatch(line -> line.contains("see this through")),
                game.partyChat.toString());
    }

    @Test
    void runThatWouldDropBelowThreeHandsNobody() {
        inTheRun(ben); // Ben, Ann and Mira

        game.roster().onOwnerLogout(game.ann, instance);

        assertEquals(List.of(), game.store.events("companion.handoff"), "Ben and Mira alone: KerningPQ ends the run");
        assertEquals(RosterState.PARKED, game.row(mira).state());
        assertFalse(game.commitments.isCommitted(900));
        assertTrue(game.partyChat.stream().noneMatch(line -> line.contains("see this through")),
                game.partyChat.toString());
    }

    @Test
    void finishedCompanionParksAtReleaseKey() {
        withTobi();
        inTheRun(ben);
        game.roster().onOwnerLogout(game.ann, instance);
        game.annOnline = false;
        game.calls.clear();

        fx.sessionEnds(true);

        assertTrue(game.calls.containsAll(List.of("leave Mira", "leave Tobi")), "out of Ben's party: " + game.calls);
        assertEquals(RosterState.PARKED, game.row(mira).state());
        assertEquals(RosterState.PARKED, game.row(tobi).state());
        assertFalse(game.roster().finishingForFriend(900));
    }

    @Test
    void handbackReleasesAFinishingCompanion() {
        withTobi();
        inTheRun(ben);
        assertEquals(Handback.FOLLOW_LEADER, fx.lifecycle.handback(900, "kpq"), "Ann on: it follows her");

        game.roster().onOwnerLogout(game.ann, instance);

        assertEquals(Handback.RELEASE, fx.lifecycle.handback(900, "kpq"), "only stopped: it parks as the run ends");
    }

    @Test
    void theDelegationIsInstalledAtStartAndTakenOutAtStop() throws IOException {
        String source = Files.readString(Path.of("src/main/java/server/living/LivingWorld.java"));
        String start = source.substring(source.indexOf("public static synchronized void start()"),
                source.indexOf("public static synchronized void stop()"));
        String stop = source.substring(source.indexOf("public static synchronized void stop()"));

        assertTrue(start.contains("world.roster.installInstanceDelegation(world.partyQuestRuns);"));
        assertTrue(stop.contains("world.roster.installInstanceDelegation(null);"));
    }
}
