package server.living.roster;

import client.Character;
import net.server.Server;
import net.server.world.PartyCharacter;
import net.server.world.PartyOperation;
import net.server.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.follow.AgentFollowGuard;
import server.agents.capabilities.follow.AgentFollowMapSyncService;
import server.agents.runtime.AgentModeService;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.living.bond.BondService;
import server.living.bond.ContractService;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterState;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.HENESYS_FIELD;
import static server.living.roster.LifecycleHarness.KERNING;
import static server.living.roster.LifecycleHarness.LITH;
import static server.living.roster.LifecycleHarness.PARTY_ID;
import static server.living.roster.LifecycleHarness.portalX;

/**
 * Where the P2b tracks meet on the same logout, login and arrival paths (docs/BOT_MANAGEMENT_PLAN.md P2b): the follow
 * guards with channel follow (a trip that changes channel stays off follow until it arrives) and with the logout (a
 * companion waiting outside a run parks, and walks in following at the login); the deletion cleanup with the instance
 * handoff (a companion finishing a run for a friend stays in it, and is released once it is over, not parked back) and
 * with a party of offline members
 * (they leave it); and a contract walk-out waiting for a run with the logout that ends the run for it (it goes home).
 */
class TrackCombinationTest {
    private static final int BEN = 20;
    private static final String RUN = "kpq:1";

    private final LifecycleHarness game = new LifecycleHarness();
    private final CompanionFollowGuard guard = new CompanionFollowGuard(game.world);
    private final ContractService contracts = new ContractService(game.world);
    private final List<String> sync = new ArrayList<>();
    /** Party members the roster expelled (a companion parked in its offline owner's party leaving it). */
    private final List<Integer> expelled = new ArrayList<>();

    @BeforeEach
    void install() {
        when(game.world.contracts()).thenReturn(contracts);
        when(game.world.bonds()).thenReturn(mock(BondService.class));
        World world = Server.getInstance().getWorld(game.config.WORLD);
        when(world.getParty(PARTY_ID)).thenReturn(game.party);
        doAnswer(inv -> {
            PartyCharacter member = inv.getArgument(2);
            expelled.add(member.getId());
            game.members.remove((Integer) member.getId());
            return null;
        }).when(world).updateParty(eq(PARTY_ID), eq(PartyOperation.EXPEL), any());
        AgentFollowGuard.install(guard);
    }

    @AfterEach
    void close() {
        AgentFollowGuard.install(null);
        game.close();
    }

    @Test
    void aTripThatChangesChannelWithItsOwnerStaysOffFollowUntilItArrives() {
        Persona vex = game.companion("vex", "Vexa", 903, RosterState.BENCHED, LITH);
        game.companionOnChannel(vex, 1);
        game.roster().call(game.ann, vex); // join-then-travel from Lith Harbor on channel 1
        List<LifecycleHarness.Timer> trip = List.copyOf(game.timers);
        game.timers.clear();
        game.calls.clear();
        assertTrue(guard.holdsOffFollow(903), "on its way");

        changeChannel(2);
        game.timers.stream().filter(timer -> timer.delayMs() < CompanionChannelFollow.FALLBACK_MS).toList()
                .forEach(timer -> timer.task().run());

        assertEquals(2, game.companionChannel(vex), "it logged in on her new channel");
        assertEquals(RosterState.TRAVELLING, game.row(vex).state());
        assertTrue(guard.holdsOffFollow(903), "still on its way there");
        AgentRuntimeEntry runtime = new AgentRuntimeEntry(game.bot(vex), game.ann, null);
        assertFalse(AgentModeService.mayStartFollow(runtime), "no follow command starts it");
        AgentModeStateRuntime.setFollowing(runtime, true); // were anything to put it on follow anyway
        assertTrue(syncFollow(runtime));
        assertEquals(List.of("hold"), sync, "follow never warps it after her");
        assertFalse(game.calls.contains("follow Vexa"));

        trip.forEach(timer -> timer.task().run()); // its ETA ends

        assertEquals(List.of("warp Vexa to " + HENESYS_FIELD + " at " + portalX(HENESYS_FIELD) + ",200", "follow Vexa"),
                game.calls.subList(game.calls.size() - 2, game.calls.size()), "it arrives by the portal, then follows");
        assertEquals(RosterState.ACTIVE, game.row(vex).state());
        assertFalse(guard.holdsOffFollow(903));
    }

    @Test
    void aCompanionHeldOutsideARunParksAtTheLogoutAndWalksInFollowingAtTheLogin() {
        Persona hadrik = game.companion("hadrik", "Hadrik", 901, RosterState.ACTIVE, LITH);
        game.outWithAnn(hadrik);
        game.roster().hold(game.ann, hadrik, HENESYS_FIELD, null);
        assertTrue(guard.holdsOffFollow(901), "waiting outside the run");
        game.calls.clear();

        game.roster().onOwnerLogout(game.ann, null);

        assertEquals(RosterState.PARKED, game.row(hadrik).state());
        assertFalse(game.roster().keepsAnyOffFollow(), "a parked row holds nothing off follow");
        assertNull(game.slots.reservation("hadrik"), "its HOLD seat went back");
        assertEquals(List.of("remove Hadrik"), game.calls);

        game.moveAnn(KERNING, 1300, 90);
        game.roster().onOwnerLogin(game.ann);
        game.runTimers();

        String portal = KERNING + " " + portalX(KERNING) + ",200";
        List<String> login = game.calls.subList(1, game.calls.size());
        assertEquals("spawn Hadrik at " + portal, login.get(0), "it walks in from the portal nearest her");
        assertEquals("follow Hadrik", login.get(login.size() - 1), "and follows once seated");
        assertEquals(RosterState.ACTIVE, game.row(hadrik).state());
        assertTrue(game.members.contains(901));
        assertFalse(guard.holdsOffFollow(901));
    }

    @Test
    void deletingAnOwnerWhoseCompanionFinishesARunForAFriendReleasesItOnceTheRunIsOver() {
        Persona hadrik = game.companion("hadrik", "Hadrik", 901, RosterState.ACTIVE, LITH);
        game.outWithAnn(hadrik);
        game.humans.add(BEN);
        game.members.add(BEN);
        EventInstanceManager instance = mock(EventInstanceManager.class);
        Character ben = mock(Character.class);
        when(ben.getId()).thenReturn(BEN);
        when(ben.getName()).thenReturn("Ben");
        when(instance.getPlayerById(BEN)).thenReturn(ben);
        Character hadrikBot = game.bot(hadrik);
        when(instance.getPlayerById(901)).thenReturn(hadrikBot);
        game.commitments.commit(RUN, ANN, 901);
        game.roster().installInstanceDelegation((companion, human, key) -> true);
        game.roster().onOwnerLogout(game.ann, instance); // Hadrik finishes the run for Ben
        game.annOnline = false;
        assertTrue(game.roster().inFlight(ANN, "hadrik"));
        game.calls.clear();

        game.roster().ownerDeleted(ANN);

        assertEquals(RosterState.ACTIVE, game.row(hadrik).state(), "deleting one friend never touches another's run");
        assertTrue(game.commitments.isCommitted(901));
        assertTrue(game.members.contains(901), "still in Ben's party");
        assertEquals(List.of(), game.calls);

        game.commitments.releaseKey(RUN); // the run is over

        assertNull(game.row(hadrik), "released, not parked back");
        assertFalse(game.commitments.isCommitted(901), "its commitment went back");
        assertFalse(game.members.contains(901), "it left Ben's party");
        assertEquals(List.of("leave Hadrik", "remove Hadrik"), game.calls, "unloaded once");
        assertEquals(0, game.leases.count(CompanionLeases.Kind.COMPANION));
        assertFalse(game.roster().inFlight(ANN, "hadrik"));
        assertEquals(1, game.store.events("resident.released").size());
        assertTrue(game.store.events("roster.illegal_transition").isEmpty(), String.valueOf(game.store.events("roster.illegal_transition")));
    }

    @Test
    void deletingAnOwnerTakesTheCompanionsParkedWithThemOutOfTheirParty() {
        Persona hadrik = game.companion("hadrik", "Hadrik", 901, RosterState.ACTIVE, LITH);
        Persona jinx = game.companion("jinx", "Jinx", 902, RosterState.ACTIVE, LITH);
        game.outWithAnn(hadrik);
        game.outWithAnn(jinx);
        game.roster().onOwnerLogout(game.ann, null); // her own party: they stay in it, offline
        game.offline.addAll(List.of(901, 902));
        game.annOnline = false;
        assertEquals(List.of(ANN, 901, 902), game.members);

        game.roster().ownerDeleted(ANN);

        assertEquals(List.of(901, 902), expelled, "each leaves the party it stayed in");
        assertEquals(List.of(ANN), game.members);
        assertNull(game.row(hadrik));
        assertNull(game.row(jinx));
    }

    @Test
    void aWalkOutWaitingForTheRunGoesHomeWhenTheOwnersLogoutEndsTheRunForIt() {
        Persona hadrik = game.companion("hadrik", "Hadrik", 901, RosterState.ACTIVE, LITH);
        game.outWithAnn(hadrik);
        game.roster();
        List<Runnable> companionOps = new ArrayList<>(); // LivingWorld.start hands the walk-out to companion-ops
        game.commitments.addListener(ended -> companionOps.add(() -> contracts.commitmentEnded(ended)));
        game.annEntersInstance(HENESYS_FIELD);
        game.commitments.commit(RUN, ANN, 901);
        assertEquals("Hadrik heads home once this run is over.", contracts.dismiss(game.ann, hadrik));

        game.roster().onOwnerLogout(game.ann, game.ann.getEventInstance()); // nobody of hers left in the run
        game.offline.add(901);
        game.annOnline = false;
        assertEquals(RosterState.PARKED, game.row(hadrik).state(), "it logged off with her");

        companionOps.forEach(Runnable::run);

        assertNull(game.row(hadrik), "its walk-out went, with her away");
        assertEquals(List.of(901), expelled, "it left the party it stayed in as an offline member");
        assertEquals(1, game.store.events("contract.after_the_run").size());
        assertEquals(1, game.store.events("roster.release").size());
    }

    /** Client.changeChannel's hook, the old session's logout, then the login on the new channel. */
    private void changeChannel(int channel) {
        game.roster().ownerChangingChannel(game.ann, channel);
        game.roster().onOwnerLogout(game.ann, null);
        game.annOnChannel(channel);
        game.roster().onOwnerLogin(game.ann);
    }

    private boolean syncFollow(AgentRuntimeEntry runtime) {
        return AgentFollowMapSyncService.syncFollowMap(runtime, runtime.bot(), game.ann,
                new AgentFollowMapSyncService.FollowMapSyncHooks(
                        (map, point) -> point,
                        (entry, agent) -> { },
                        (agent, map, point) -> sync.add("warp " + map.getId()),
                        entry -> { },
                        (entry, agent) -> sync.add("hold")));
    }
}
