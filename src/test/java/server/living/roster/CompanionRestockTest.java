package server.living.roster;

import client.Character;
import config.LivingWorldConfig;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.contracts.AgentResourceCategory;
import server.agents.capabilities.supplies.AgentSupplyProcurementState;
import server.agents.capabilities.supplies.AgentSupplyReadiness;
import server.agents.integration.AgentRelationshipRuntime;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.LivingWorld;
import server.living.arc.ArcService;
import server.living.config.Content.Persona;
import server.living.resident.ResidentRuntime;
import server.living.store.LivingStore;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The living world's readiness rule (CompanionRestock): a standing duty for every companion its owner carries, out
 * with them and following them, or held outside a run for them (even while the run counts it as one of its extras),
 * and none for one told to wait or sent somewhere, on its way, away, on a bench slice, in a run or with the kill switch
 * off. Its callbacks only hand lines to companion-ops: the go and back lines for an ACTIVE companion, a held
 * one quiet (it walks back to the spot it waits at), and the broke line at most once per COMPANION_RESTOCK_ASK_EVERY_MS.
 * It never reads the store: the store here throws on any call.
 */
class CompanionRestockTest {
    private static final int ANN = 10;
    private static final int FENNA = 901;
    private static final int KERNING = 103_000_000;
    private static final int PAPULATUS_GATE = 220_080_000;

    private final LivingWorld world = mock(LivingWorld.class);
    private final LivingWorldConfig config = new LivingWorldConfig();
    private final RosterService roster = mock(RosterService.class);
    private final CompanionSettingsApplier settings = mock(CompanionSettingsApplier.class);
    private final Commitments commitments = new Commitments();
    private final CompanionSlots slots = new CompanionSlots(world);
    private final Character fenna = mock(Character.class);
    private final Character ann = mock(Character.class);
    private AgentRuntimeEntry entry;
    private final Persona persona = mock(Persona.class);
    /** Submitted companion-ops commands, by name, and the commands themselves until drained. */
    private final List<String> submitted = new ArrayList<>();
    private final List<Runnable> queue = new ArrayList<>();
    private final List<String> said = new ArrayList<>();
    private final List<String> walks = new ArrayList<>();
    private final Set<String> offFollow = new HashSet<>();
    private final Map<Integer, Integer> leaders = new HashMap<>();
    private final Set<Integer> engaged = new HashSet<>();
    private long now = 1_000_000L;
    private MockedStatic<AgentRuntimeRegistry> registry;
    private MockedStatic<AgentPartyQuestEngagementRegistry> engagements;
    private MockedStatic<AgentMovementCommandRuntime> movement;
    private CompanionRestock restock;

    @BeforeEach
    void fennaIsOutWithAnn() {
        when(world.config()).thenReturn(config);
        when(world.store()).thenReturn(mock(LivingStore.class, inv -> {
            throw new AssertionError("the restock rule touched the store: " + inv.getMethod().getName());
        }));
        when(world.roster()).thenReturn(roster);
        when(world.commitments()).thenReturn(commitments);
        when(world.slots()).thenReturn(slots);
        ArcService arcs = mock(ArcService.class);
        when(arcs.lines(any(), anyString())).thenReturn(List.of());
        when(world.arcs()).thenReturn(arcs);
        ResidentRuntime residents = mock(ResidentRuntime.class);
        when(residents.personaOfCharacterId(FENNA)).thenReturn(persona);
        when(world.residents()).thenReturn(residents);
        CompanionExecutor ops = mock(CompanionExecutor.class);
        when(ops.submit(anyString(), any(Runnable.class))).thenAnswer(inv -> {
            submitted.add(inv.getArgument(0));
            queue.add(inv.getArgument(1));
            return CompletableFuture.completedFuture(null);
        });
        when(world.executor()).thenReturn(ops);
        when(persona.id()).thenReturn("fenna");
        when(fenna.getId()).thenReturn(FENNA);
        when(fenna.getMapId()).thenReturn(KERNING);
        when(roster.settings()).thenReturn(settings);
        when(settings.boundTo(FENNA)).thenReturn(new CompanionSettingsApplier.Bound(ANN, "fenna"));
        when(roster.keepsOffFollow(anyString())).thenAnswer(inv -> offFollow.contains(inv.getArgument(0)));
        when(roster.sayInParty(any(), anyString())).thenAnswer(inv -> said.add(inv.getArgument(1)));
        leaders.put(FENNA, ANN);
        when(ann.getId()).thenReturn(ANN);
        entry = new AgentRuntimeEntry(fenna, null, null);
        AgentRelationshipRuntime.setInteractionTarget(entry, ann);
        AgentRelationshipRuntime.setFollowTarget(entry, ann);
        AgentModeStateRuntime.setFollowing(entry, true); // out with Ann, following her
        registry = mockStatic(AgentRuntimeRegistry.class);
        registry.when(() -> AgentRuntimeRegistry.leaderIdForAgentCharacter(anyInt()))
                .thenAnswer(inv -> leaders.getOrDefault((int) inv.getArgument(0), 0));
        registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(FENNA)).thenReturn(entry);
        engagements = mockStatic(AgentPartyQuestEngagementRegistry.class);
        engagements.when(() -> AgentPartyQuestEngagementRegistry.forMember(anyInt()))
                .thenAnswer(inv -> engaged.contains((int) inv.getArgument(0)) ? mock(AgentPartyQuestEngagement.class)
                        : null);
        movement = mockStatic(AgentMovementCommandRuntime.class);
        movement.when(() -> AgentMovementCommandRuntime.moveTo(any(), any(), anyBoolean())).thenAnswer(inv -> {
            Point to = inv.getArgument(1);
            return walks.add(((AgentRuntimeEntry) inv.getArgument(0)).bot().getId() + " to " + to.x + "," + to.y);
        });
        restock = new CompanionRestock(world, () -> now);
    }

    @AfterEach
    void close() {
        movement.close();
        engagements.close();
        registry.close();
    }

    @Test
    void aCompanionOutWithItsOwnerOrHeldForThemKeepsThePartyQuestStandard() {
        assertEquals(AgentSupplyReadiness.Activity.PARTY_QUEST, restock.standing(FENNA), "ACTIVE, following Ann");

        hold();
        assertEquals(AgentSupplyReadiness.Activity.PARTY_QUEST, restock.standing(FENNA), "waiting outside Ann's run");
    }

    @Test
    void onItsWayAwayOnABenchSliceInARunUnboundOrSwitchedOffItHasNoDuty() {
        offFollow.add("fenna"); // TRAVELLING: off follow, no seat held
        assertNull(restock.standing(FENNA), "on its way");
        slots.reserve(ANN, "fenna", CompanionSlots.Kind.AWAY, Long.MAX_VALUE);
        assertNull(restock.standing(FENNA), "away on an errand");
        slots.release("fenna");
        offFollow.clear();

        leaders.put(FENNA, FENNA);
        assertNull(restock.standing(FENNA), "a live bench slice leads itself");
        leaders.put(FENNA, ANN);

        commitments.commit("kpq:1", ANN, FENNA);
        assertNull(restock.standing(FENNA), "committed to a run");
        commitments.releaseMember(FENNA);

        engaged.add(FENNA);
        assertNull(restock.standing(FENNA), "engaged by a party quest");
        engaged.clear();

        assertNull(restock.standing(902), "not a companion");
        config.COMPANION_RESTOCK_ENABLED = false;
        assertNull(restock.standing(FENNA), "the kill switch");
        config.COMPANION_RESTOCK_ENABLED = true;
        assertEquals(AgentSupplyReadiness.Activity.PARTY_QUEST, restock.standing(FENNA));
    }

    @Test
    void atABossGateItHasNoDutySoItNeverWalksFromThereToAShop() {
        when(fenna.getMapId()).thenReturn(PAPULATUS_GATE); // Toly's shop is six hops back down the Path of Time
        assertNull(restock.standing(FENNA), "a boss gate keeps its no-shop line: restocking before bosses is P7's");

        when(fenna.getMapId()).thenReturn(KERNING);
        assertEquals(AgentSupplyReadiness.Activity.PARTY_QUEST, restock.standing(FENNA));
    }

    @Test
    void atABossGateACompanionShortOfPotionsStillSaysThereIsNoShop() {
        when(fenna.getMapId()).thenReturn(PAPULATUS_GATE); // it carries no potions at all
        PartyCharacter member = mock(PartyCharacter.class);
        when(member.getId()).thenReturn(FENNA);
        when(member.isBot()).thenReturn(true);
        Party party = mock(Party.class);
        when(party.getMembers()).thenReturn(List.of(member));
        when(ann.getParty()).thenReturn(party);
        when(roster.onlineCharacter(ANN)).thenReturn(ann);

        restock.bossGate(ANN, PAPULATUS_GATE);

        assertEquals(1, said.size(), "the no-shop line, once");
    }

    @Test
    void aHeldExtraOfARunningRunKeepsItsDuty() {
        hold();
        commitments.commit("pq:kpq:1", ANN, FENNA); // the run commits its extras too, until it ends

        assertEquals(AgentSupplyReadiness.Activity.PARTY_QUEST, restock.standing(FENNA),
                "waiting outside Ann's run, it restocks meanwhile");
        engaged.add(FENNA);
        assertNull(restock.standing(FENNA), "but not once a run engages it");
    }

    @Test
    void anActiveCompanionItsOwnerToldToWaitOrSentSomewhereHasNoDuty() {
        AgentModeStateRuntime.setFollowing(entry, false); // "Wait here", or sent to a spot
        assertNull(restock.standing(FENNA), "its owner's order stands: it does not walk off to a shop");

        Character stranger = mock(Character.class);
        when(stranger.getId()).thenReturn(11);
        AgentModeStateRuntime.setFollowing(entry, true);
        AgentRelationshipRuntime.setFollowTarget(entry, stranger);
        assertNull(restock.standing(FENNA), "following someone else");
        AgentRelationshipRuntime.setFollowTarget(entry, ann);
        assertEquals(AgentSupplyReadiness.Activity.PARTY_QUEST, restock.standing(FENNA));

        AgentSupplyProcurementState trip = entry.capabilityStates().require(AgentSupplyProcurementState.STATE_KEY);
        trip.start("readiness:HP_POTION:1", "maintenance:resupply:readiness:HP_POTION:1",
                AgentResourceCategory.HP_POTION, 103_000_002, 1_051_002, KERNING,
                AgentSupplyProcurementState.Phase.SHOPPING);
        trip.rememberForegroundMode(true, false);
        AgentModeStateRuntime.setFollowing(entry, false); // travel stopped its follow mode
        assertEquals(AgentSupplyReadiness.Activity.PARTY_QUEST, restock.standing(FENNA),
                "on its trip, which ends by following Ann: the shop still buys to its target");
    }

    @Test
    void theDeskAndTheCompanionShareOneMemoryOfItsBrokeLine() {
        assertTrue(restock.mayAsk(FENNA, CompanionRestockLines.BROKE, 300_000L), "Lakelis's desk said it");

        restock.refused(FENNA, AgentSupplyReadiness.Activity.PARTY_QUEST, AgentSupplyReadiness.Refusal.CANNOT_AFFORD,
                new AgentSupplyReadiness.Shortfall(19, 0), 795);
        drain();
        assertEquals(List.of(), said, "a tick later its own refusal does not say it again");

        now += 300_000L;
        assertTrue(restock.mayAsk(FENNA, CompanionRestockLines.BROKE, 300_000L), "the desk's own interval");
        assertTrue(!restock.mayAsk(FENNA, CompanionRestockLines.BROKE, 300_000L), "but not twice at once");
    }

    @Test
    void theCallbacksOnlyHandTheirLinesToCompanionOps() {
        restock.started(FENNA, AgentSupplyReadiness.Activity.PARTY_QUEST, 50_000L);

        assertEquals(List.of("companion.restock.go " + FENNA), submitted);
        assertEquals(List.of(), said, "nothing said on the Agent tick");
        drain();
        assertEquals(1, said.size());
        assertTrue(CompanionRestockLines.GENERIC.get(CompanionRestockLines.GO).contains(said.getFirst()), said.toString());
    }

    @Test
    void anActiveCompanionSaysItIsBackAndAHeldOneWalksBackToItsSpotQuietly() {
        restock.finished(FENNA, AgentSupplyReadiness.Activity.PARTY_QUEST, AgentSupplyReadiness.Outcome.READY);
        drain();
        assertEquals(1, said.size());
        assertTrue(CompanionRestockLines.GENERIC.get(CompanionRestockLines.BACK).contains(said.getFirst()));

        said.clear();
        hold();
        when(roster.holdSpot("fenna")).thenReturn(new RosterService.HoldSpot(KERNING, new Point(1440, 120)));
        restock.started(FENNA, AgentSupplyReadiness.Activity.PARTY_QUEST, 50_000L);
        restock.finished(FENNA, AgentSupplyReadiness.Activity.PARTY_QUEST, AgentSupplyReadiness.Outcome.READY);
        drain();
        assertEquals(List.of(), said, "held: no go line, no back line");
        assertEquals(List.of(FENNA + " to 1440,120"), walks, "back beside Lakelis");
        assertTrue(submitted.contains("companion.restock.after " + FENNA));
    }

    @Test
    void aHeldCompanionThatIsNotBackOnItsMapIsNotWalked() {
        hold();
        when(roster.holdSpot("fenna")).thenReturn(new RosterService.HoldSpot(KERNING, new Point(1440, 120)));
        when(fenna.getMapId()).thenReturn(103_000_002); // still in the shop

        restock.finished(FENNA, AgentSupplyReadiness.Activity.PARTY_QUEST, AgentSupplyReadiness.Outcome.INTERRUPTED);
        drain();

        assertEquals(List.of(), walks);
    }

    @Test
    void theBrokeLineIsLimitedAndInterruptedShortSpaceAndTheOtherRefusalsAreSilent() {
        restock.finished(FENNA, AgentSupplyReadiness.Activity.PARTY_QUEST, AgentSupplyReadiness.Outcome.SHORT_MESOS);
        restock.refused(FENNA, AgentSupplyReadiness.Activity.PARTY_QUEST, AgentSupplyReadiness.Refusal.CANNOT_AFFORD,
                new AgentSupplyReadiness.Shortfall(19, 0), 795);
        drain();
        assertEquals(1, said.size(), "once per ask interval: " + said);
        assertTrue(CompanionRestockLines.GENERIC.get(CompanionRestockLines.BROKE).contains(said.getFirst()));

        restock.finished(FENNA, AgentSupplyReadiness.Activity.PARTY_QUEST, AgentSupplyReadiness.Outcome.INTERRUPTED);
        restock.finished(FENNA, AgentSupplyReadiness.Activity.PARTY_QUEST, AgentSupplyReadiness.Outcome.SHORT_SPACE);
        restock.refused(FENNA, AgentSupplyReadiness.Activity.PARTY_QUEST, AgentSupplyReadiness.Refusal.NO_SPACE,
                new AgentSupplyReadiness.Shortfall(19, 0), 0);
        restock.refused(FENNA, AgentSupplyReadiness.Activity.PARTY_QUEST, AgentSupplyReadiness.Refusal.NO_SUPPLIER,
                new AgentSupplyReadiness.Shortfall(19, 0), 0);
        now += config.COMPANION_RESTOCK_ASK_EVERY_MS - 1;
        restock.refused(FENNA, AgentSupplyReadiness.Activity.PARTY_QUEST, AgentSupplyReadiness.Refusal.CANNOT_AFFORD,
                new AgentSupplyReadiness.Shortfall(19, 0), 795);
        drain();
        assertEquals(1, said.size(), "silent, and still within the interval: " + said);

        now += 1;
        restock.refused(FENNA, AgentSupplyReadiness.Activity.PARTY_QUEST, AgentSupplyReadiness.Refusal.CANNOT_AFFORD,
                new AgentSupplyReadiness.Shortfall(19, 0), 795);
        drain();
        assertEquals(2, said.size(), "half an hour later it asks again");
    }

    @Test
    void theLivingWorldInstallsTheRuleAtStartAndTakesItOutAtStop() throws java.io.IOException {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/server/living/LivingWorld.java"));
        String start = source.substring(source.indexOf("public static synchronized void start()"),
                source.indexOf("public static synchronized void stop()"));
        String stop = source.substring(source.indexOf("public static synchronized void stop()"));

        int installed = start.indexOf("world.restock.install();");
        assertTrue(installed >= 0 && installed < start.indexOf("instance = world;"), "before the world goes live");
        assertTrue(stop.contains("server.living.roster.CompanionRestock.uninstall();"));
    }

    /** Fenna waits outside Ann's run: off follow on her HOLD seat. */
    private void hold() {
        offFollow.add("fenna");
        slots.reserve(ANN, "fenna", CompanionSlots.Kind.HOLD, Long.MAX_VALUE);
    }

    private void drain() {
        while (!queue.isEmpty()) {
            queue.removeFirst().run();
        }
    }
}
