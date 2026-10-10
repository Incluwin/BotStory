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
import server.agents.capabilities.supplies.AgentAmmoService;
import server.agents.capabilities.supplies.AgentProjectileKind;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    private static final int HENESYS = 100_000_000;
    private static final int HENESYS_PARK = 100_000_200;
    /** Adobis's room behind the Door to Zakum: no shop in reach, and no route leads back into it (P8). */
    private static final int ADOBIS_ROOM = 211_042_400;
    private static final int MU_YOUNGS_ROOM = 105_100_100;
    /** Ice Valley II, Glibber's field on the way to the door. */
    private static final int ICE_VALLEY_II = 211_040_200;
    /** Kerning City is KPQ's desk: a companion there keeps KPQ's standard. */
    private static final AgentSupplyReadiness.Activity KPQ = AgentSupplyReadiness.Activity.partyQuest("kpq");
    private static final AgentSupplyReadiness.Activity LPQ = AgentSupplyReadiness.Activity.partyQuest("lpq");

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
        assertEquals(KPQ, restock.standing(FENNA), "ACTIVE, following Ann");

        hold();
        assertEquals(KPQ, restock.standing(FENNA), "waiting outside Ann's run");
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
        assertEquals(KPQ, restock.standing(FENNA));
    }

    @Test
    void atABossGateItKeepsItsDutyButNeverSetsOffForAShopFromThere() {
        when(fenna.getMapId()).thenReturn(PAPULATUS_GATE); // Toly's shop is six hops back down the Path of Time
        assertEquals(AgentSupplyReadiness.Activity.PARTY_QUEST, restock.standing(FENNA),
                "the duty (and the critical trip's affordability check) stays");
        assertFalse(restock.restocksFrom(FENNA, PAPULATUS_GATE),
                "a boss gate keeps its no-shop line: restocking before bosses is P7's");
        assertTrue(restock.restocksFrom(FENNA, KERNING));
    }

    @Test
    void adobissRoomHoldsATripOnlyWhileTheSwitchListsZakum() {
        when(world.bossRuns()).thenReturn(new CompanionBossRuns(world));
        config.COMPANION_BOSS_ENABLED_TYPES = "easy_balrog,zakum";
        assertFalse(restock.restocksFrom(FENNA, ADOBIS_ROOM),
                "no shop in reach and no way back in: even a critical trip stays (it only asks the party)");
        assertTrue(restock.restocksFrom(FENNA, MU_YOUNGS_ROOM), "Mu Young's room is unchanged");
        assertTrue(restock.restocksFrom(FENNA, ICE_VALLEY_II), "Glibber's field is the way there, not the room");
        assertTrue(restock.restocksFrom(FENNA, KERNING));
        assertFalse(restock.restocksFrom(FENNA, PAPULATUS_GATE), "a boss gate stays a boss gate");

        config.COMPANION_BOSS_ENABLED_TYPES = "easy_balrog";
        assertTrue(restock.restocksFrom(FENNA, ADOBIS_ROOM), "the switch off: as before P8");
        assertTrue(restock.restocksFrom(FENNA, MU_YOUNGS_ROOM));
        config.COMPANION_BOSS_ENABLED_TYPES = "";
        assertTrue(restock.restocksFrom(FENNA, ADOBIS_ROOM));

        config.COMPANION_BOSS_ENABLED_TYPES = "easy_balrog,zakum";
        when(world.bossRuns()).thenReturn(null);
        assertTrue(restock.restocksFrom(FENNA, ADOBIS_ROOM), "no boss runs");
        assertFalse(restock.restocksFrom(FENNA, PAPULATUS_GATE));
    }

    @Test
    void inARoomThatHoldsTripsACompanionShortOfTheBossStandardSaysThereIsNoShop() {
        // P8: the hold keeps it from setting off, so its own NO_SUPPLIER refusal (and its line) never comes
        AgentSupplyReadiness.Activity zakum = AgentSupplyReadiness.Activity.partyQuest("zakum");
        when(fenna.getMapId()).thenReturn(ADOBIS_ROOM);
        PartyCharacter member = mock(PartyCharacter.class);
        when(member.getId()).thenReturn(FENNA);
        when(member.isBot()).thenReturn(true);
        Party party = mock(Party.class);
        when(party.getMembers()).thenReturn(List.of(member));
        when(ann.getParty()).thenReturn(party);
        when(roster.onlineCharacter(ANN)).thenReturn(ann);
        try (MockedStatic<AgentSupplyReadiness> readiness = mockStatic(AgentSupplyReadiness.class,
                org.mockito.Mockito.CALLS_REAL_METHODS)) {
            // 300 HP / 300 MP potions: plenty for a party quest, short of Zakum's 500/400
            readiness.when(() -> AgentSupplyReadiness.shortfall(any(Character.class),
                    any(AgentSupplyReadiness.Activity.class))).thenAnswer(inv -> zakum.equals(inv.getArgument(1))
                    ? new AgentSupplyReadiness.Shortfall(200, 100) : AgentSupplyReadiness.Shortfall.NONE);
            restock.bossGate(ANN, ADOBIS_ROOM);
            assertEquals(List.of(), said, "measured against the party quest standard: nothing short");

            restock.bossGate(ANN, ADOBIS_ROOM, zakum);
            restock.bossGate(ANN, ADOBIS_ROOM, zakum);
        }
        assertEquals(1, said.size(), "the no-shop line, once in the window: " + said);
        assertTrue(CompanionRestockLines.GENERIC.get(CompanionRestockLines.NO_SHOP).contains(said.getFirst()),
                said.toString());
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
    void theStandardItKeepsIsTheOneOfTheDeskItStandsAt() {
        assertEquals(KPQ, restock.standing(FENNA), "Kerning City: Lakelis's desk");

        when(fenna.getMapId()).thenReturn(HENESYS_PARK);
        assertEquals(AgentSupplyReadiness.Activity.partyQuest("hpq"), restock.standing(FENNA), "Tory's desk");

        when(fenna.getMapId()).thenReturn(HENESYS);
        assertEquals(AgentSupplyReadiness.Activity.PARTY_QUEST, restock.standing(FENNA), "no desk: the default");

        when(fenna.getMapId()).thenReturn(HENESYS_PARK);
        hold();
        assertEquals(AgentSupplyReadiness.Activity.partyQuest("hpq"), restock.standing(FENNA),
                "held at the desk while its owner runs it");
    }

    @Test
    void aHeldExtraOfARunningRunKeepsItsDuty() {
        hold();
        commitments.commit("pq:kpq:1", ANN, FENNA); // the run commits its extras too, until it ends

        assertEquals(KPQ, restock.standing(FENNA),
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
        assertEquals(KPQ, restock.standing(FENNA));

        AgentSupplyProcurementState trip = entry.capabilityStates().require(AgentSupplyProcurementState.STATE_KEY);
        trip.start("readiness:HP_POTION:1", "maintenance:resupply:readiness:HP_POTION:1",
                AgentResourceCategory.HP_POTION, 103_000_002, 1_051_002, KERNING,
                AgentSupplyProcurementState.Phase.SHOPPING);
        trip.rememberForegroundMode(true, false);
        AgentModeStateRuntime.setFollowing(entry, false); // travel stopped its follow mode
        assertEquals(KPQ, restock.standing(FENNA),
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
    void aRefusalTellsTheAgentWhetherItsLineWasSaid() {
        // third review: the Agent forgets a gift held back by its spacing only when the refusal was really said
        AgentSupplyReadiness.Shortfall short19 = new AgentSupplyReadiness.Shortfall(19, 0);
        assertTrue(restock.refused(FENNA, AgentSupplyReadiness.Activity.PARTY_QUEST,
                AgentSupplyReadiness.Refusal.CANNOT_AFFORD, short19, 795), "its broke line");
        assertFalse(restock.refused(FENNA, AgentSupplyReadiness.Activity.PARTY_QUEST,
                AgentSupplyReadiness.Refusal.CANNOT_AFFORD, short19, 795), "said too recently: kept quiet");
        assertFalse(restock.refused(FENNA, AgentSupplyReadiness.Activity.PARTY_QUEST,
                AgentSupplyReadiness.Refusal.NO_SPACE, short19, 0), "no line for it");
        assertFalse(restock.refused(FENNA, AgentSupplyReadiness.Activity.PARTY_QUEST,
                AgentSupplyReadiness.Refusal.NO_SUPPLIER, short19, 0), "no shop line away from a boss");
        now += config.COMPANION_RESTOCK_ASK_EVERY_MS;
        assertTrue(restock.refused(FENNA, AgentSupplyReadiness.Activity.PARTY_QUEST,
                AgentSupplyReadiness.Refusal.CANNOT_AFFORD, short19, 795), "half an hour later");
        drain();
        assertEquals(2, said.size(), said.toString());
    }

    @Test
    void stillShortNewsTellsTheAgentWhetherItsLineWasSaid() {
        // fifth review: the Agent spaces still-short news from a line that was said, not from one kept quiet
        AgentSupplyReadiness.Shortfall short19 = new AgentSupplyReadiness.Shortfall(19, 0);
        assertFalse(restock.stillShort(FENNA, KPQ, short19, 300), "no broke line yet: a gift it never asked for");
        restock.refused(FENNA, KPQ, AgentSupplyReadiness.Refusal.CANNOT_AFFORD, short19, 795);
        assertTrue(restock.stillShort(FENNA, KPQ, short19, 300), "after its broke line");
        now += config.COMPANION_RESTOCK_ASK_EVERY_MS;
        assertFalse(restock.stillShort(FENNA, KPQ, short19, 300), "its broke line is half an hour old");
    }

    @Test
    void theBrokeLineIsLimitedAndInterruptedShortSpaceAndTheOtherRefusalsAreSilent() {
        restock.finished(FENNA, AgentSupplyReadiness.Activity.PARTY_QUEST, AgentSupplyReadiness.Outcome.SHORT_MESOS);
        restock.refused(FENNA, AgentSupplyReadiness.Activity.PARTY_QUEST, AgentSupplyReadiness.Refusal.CANNOT_AFFORD,
                new AgentSupplyReadiness.Shortfall(19, 0), 795);
        drain();
        assertEquals(1, said.size(), "once per ask interval: " + said);
        assertTrue(filled(CompanionRestockLines.BROKE, "potions").contains(said.getFirst()), said.toString());

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

    // ---- asking its owner, and taking the help (user decision 2026-10-09) ------------------------

    @Test
    void itAsksForWhatItLacksOrMesosManaPotionsWhenOnlyManaIsShort() {
        restock.refused(FENNA, KPQ, AgentSupplyReadiness.Refusal.CANNOT_AFFORD,
                new AgentSupplyReadiness.Shortfall(0, 25), 795);
        drain();

        assertEquals(1, said.size(), said.toString());
        assertTrue(filled(CompanionRestockLines.BROKE, "mana potions").contains(said.getFirst()),
                "it has its HP potions: " + said);
    }

    @Test
    void givenPartOfWhatItLackedItSaysWhatItStillLacks() {
        restock.refused(FENNA, KPQ, AgentSupplyReadiness.Refusal.CANNOT_AFFORD,
                new AgentSupplyReadiness.Shortfall(0, 25), 795);
        drain();
        said.clear();

        restock.stillShort(FENNA, KPQ, new AgentSupplyReadiness.Shortfall(0, 25), 300);
        assertEquals(List.of(), said, "nothing said on the Agent tick");
        assertEquals("companion.restock.still_short " + FENNA, submitted.getLast());
        drain();
        assertEquals(1, said.size(), said.toString());
        assertTrue(filled(CompanionRestockLines.STILL_SHORT, "mana potions").contains(said.getFirst()),
                said.toString());

        hold(); // waiting outside its owner's run, it still answers the help it asked for
        restock.stillShort(FENNA, KPQ, new AgentSupplyReadiness.Shortfall(0, 25), 100);
        drain();
        assertEquals(2, said.size(), "each report is a gift of its own; the Agent spaces them: " + said);
    }

    @Test
    void helpItNeverAskedForOrAskedForLongAgoGetsNoLine() {
        restock.stillShort(FENNA, KPQ, new AgentSupplyReadiness.Shortfall(19, 0), 300);
        drain();
        assertEquals(List.of(), said, "a party member's pot share it never asked for");

        restock.refused(FENNA, KPQ, AgentSupplyReadiness.Refusal.CANNOT_AFFORD,
                new AgentSupplyReadiness.Shortfall(19, 0), 795);
        now += config.COMPANION_RESTOCK_ASK_EVERY_MS;
        restock.stillShort(FENNA, KPQ, new AgentSupplyReadiness.Shortfall(19, 0), 300);
        drain();
        assertEquals(1, said.size(), "the broke line only: its ask is past: " + said);
    }

    @Test
    void anAmmoOnlyCompanionStillShortSaysWhatItShoots() {
        try (MockedStatic<AgentAmmoService> ammo = shoots(AgentProjectileKind.STARS)) {
            restock.refused(FENNA, KPQ, AgentSupplyReadiness.Refusal.CANNOT_AFFORD,
                    new AgentSupplyReadiness.Shortfall(0, 0, 500), 300);
            restock.stillShort(FENNA, KPQ, new AgentSupplyReadiness.Shortfall(0, 0, 500), 120);
            drain();
        }
        assertEquals(2, said.size(), said.toString());
        assertTrue(filled(CompanionRestockLines.STILL_SHORT, "stars").contains(said.get(1)), said.toString());
    }

    /** The generic {@code intent} lines with {need} and {ammo} read as {@code word}. */
    private static List<String> filled(String intent, String word) {
        return CompanionRestockLines.GENERIC.get(intent).stream()
                .map(line -> CompanionRestockLines.filled(line, word)).toList();
    }

    // ---- trips for projectiles alone (2026-10-07) ---------------------------------------------

    @Test
    void anAmmoOnlyTripSaysItsAmmoLines() {
        try (MockedStatic<AgentSupplyReadiness> readiness = shortOf(new AgentSupplyReadiness.Shortfall(0, 0, 500));
             MockedStatic<AgentAmmoService> ammo = shoots(AgentProjectileKind.STARS)) {
            restock.started(FENNA, KPQ, 50_000L);
            drain();
            assertEquals(1, said.size(), said.toString());
            assertTrue(ammoLines(CompanionRestockLines.GO_AMMO, "stars").contains(said.getFirst()), said.toString());

            restock.finished(FENNA, KPQ, AgentSupplyReadiness.Outcome.READY);
            drain();
            assertEquals(2, said.size(), said.toString());
            assertTrue(ammoLines(CompanionRestockLines.BACK_AMMO, "stars").contains(said.get(1)), said.toString());
        }
    }

    @Test
    void anAmmoOnlyTripThatCannotAffordItSaysTheAmmoBrokeLine() {
        try (MockedStatic<AgentSupplyReadiness> readiness = shortOf(new AgentSupplyReadiness.Shortfall(0, 0, 500));
             MockedStatic<AgentAmmoService> ammo = shoots(AgentProjectileKind.ARROWS)) {
            restock.refused(FENNA, KPQ, AgentSupplyReadiness.Refusal.CANNOT_AFFORD,
                    new AgentSupplyReadiness.Shortfall(0, 0, 500), 300);
            drain();
            assertEquals(1, said.size(), said.toString());
            assertTrue(ammoLines(CompanionRestockLines.BROKE_AMMO, "arrows").contains(said.getFirst()),
                    said.toString());

            restock.started(FENNA, KPQ, 50_000L);
            restock.finished(FENNA, KPQ, AgentSupplyReadiness.Outcome.SHORT_MESOS);
            restock.refused(FENNA, KPQ, AgentSupplyReadiness.Refusal.CANNOT_AFFORD,
                    new AgentSupplyReadiness.Shortfall(19, 0), 795);
            drain();
            assertEquals(2, said.size(), "one broke line per ask interval, potions or ammo: " + said);
            assertTrue(ammoLines(CompanionRestockLines.GO_AMMO, "arrows").contains(said.get(1)), said.toString());
        }
    }

    @Test
    void aTripShortOfPotionsAndAmmoSaysThePotionLines() {
        try (MockedStatic<AgentSupplyReadiness> readiness = shortOf(new AgentSupplyReadiness.Shortfall(5, 0, 500));
             MockedStatic<AgentAmmoService> ammo = shoots(AgentProjectileKind.STARS)) {
            restock.started(FENNA, KPQ, 50_000L);
            restock.finished(FENNA, KPQ, AgentSupplyReadiness.Outcome.READY);
            drain();
            assertEquals(2, said.size(), said.toString());
            assertTrue(CompanionRestockLines.GENERIC.get(CompanionRestockLines.GO).contains(said.getFirst()));
            assertTrue(CompanionRestockLines.GENERIC.get(CompanionRestockLines.BACK).contains(said.get(1)));
        }
    }

    @Test
    void aMixedTripThatBoughtThePotionsButNotTheStarsSaysTheAmmoBrokeLine() {
        // review: it set off short of both, the potions took the purse, and the stars were left unaffordable
        try (MockedStatic<AgentSupplyReadiness> readiness = shortOf(new AgentSupplyReadiness.Shortfall(5, 10, 1_600));
             MockedStatic<AgentAmmoService> ammo = shoots(AgentProjectileKind.STARS)) {
            restock.started(FENNA, LPQ, 50_000L);
            drain();
            assertTrue(CompanionRestockLines.GENERIC.get(CompanionRestockLines.GO).contains(said.getFirst()));

            readiness.when(() -> AgentSupplyReadiness.shortfall(any(Character.class),
                    any(AgentSupplyReadiness.Activity.class))).thenReturn(new AgentSupplyReadiness.Shortfall(0, 0, 1_600));
            restock.finished(FENNA, LPQ, AgentSupplyReadiness.Outcome.SHORT_MESOS);
            drain();
            assertEquals(2, said.size(), said.toString());
            assertTrue(ammoLines(CompanionRestockLines.BROKE_AMMO, "stars").contains(said.get(1)),
                    "not \"can't afford potions\" with a full potion standard: " + said);
        }
    }

    @Test
    void anAmmoTripDismissedMidwayLeavesNoStaleWord() {
        try (MockedStatic<AgentSupplyReadiness> readiness = shortOf(new AgentSupplyReadiness.Shortfall(0, 0, 500));
             MockedStatic<AgentAmmoService> ammo = shoots(AgentProjectileKind.STARS)) {
            restock.started(FENNA, KPQ, 50_000L);
            when(settings.boundTo(FENNA)).thenReturn(null); // dismissed mid-trip
            restock.finished(FENNA, KPQ, AgentSupplyReadiness.Outcome.INTERRUPTED);
            when(settings.boundTo(FENNA)).thenReturn(new CompanionSettingsApplier.Bound(ANN, "fenna"));
            drain();
            said.clear();

            readiness.when(() -> AgentSupplyReadiness.shortfall(any(Character.class),
                    any(AgentSupplyReadiness.Activity.class))).thenReturn(new AgentSupplyReadiness.Shortfall(5, 0, 0));
            restock.finished(FENNA, KPQ, AgentSupplyReadiness.Outcome.READY);
            drain();
            assertEquals(1, said.size(), said.toString());
            assertTrue(CompanionRestockLines.GENERIC.get(CompanionRestockLines.BACK).contains(said.getFirst()),
                    "the potion back line, not the stars': " + said);
        }
    }

    @Test
    void atABossGateACompanionShortOnlyOfAmmoSaysTheAmmoNoShopLine() {
        when(fenna.getMapId()).thenReturn(PAPULATUS_GATE);
        PartyCharacter member = mock(PartyCharacter.class);
        when(member.getId()).thenReturn(FENNA);
        when(member.isBot()).thenReturn(true);
        Party party = mock(Party.class);
        when(party.getMembers()).thenReturn(List.of(member));
        when(ann.getParty()).thenReturn(party);
        when(roster.onlineCharacter(ANN)).thenReturn(ann);
        try (MockedStatic<AgentSupplyReadiness> readiness = shortOf(new AgentSupplyReadiness.Shortfall(0, 0, 500));
             MockedStatic<AgentAmmoService> ammo = shoots(AgentProjectileKind.STARS)) {
            restock.bossGate(ANN, PAPULATUS_GATE);
            restock.bossGate(ANN, PAPULATUS_GATE);
        }
        assertEquals(1, said.size(), "once in the window: " + said);
        assertTrue(ammoLines(CompanionRestockLines.NO_SHOP_AMMO, "stars").contains(said.getFirst()), said.toString());
    }

    /** Fenna's own count lacks {@code shortfall} (AgentSupplyReadiness.shortfall), whatever her bag holds. */
    private static MockedStatic<AgentSupplyReadiness> shortOf(AgentSupplyReadiness.Shortfall shortfall) {
        MockedStatic<AgentSupplyReadiness> readiness = mockStatic(AgentSupplyReadiness.class,
                org.mockito.Mockito.CALLS_REAL_METHODS);
        readiness.when(() -> AgentSupplyReadiness.shortfall(any(Character.class),
                any(AgentSupplyReadiness.Activity.class))).thenReturn(shortfall);
        return readiness;
    }

    /** Fenna's weapon shoots {@code kind}. */
    private static MockedStatic<AgentAmmoService> shoots(AgentProjectileKind kind) {
        MockedStatic<AgentAmmoService> ammo = mockStatic(AgentAmmoService.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        ammo.when(() -> AgentAmmoService.projectileKind(any())).thenReturn(kind);
        return ammo;
    }

    /** The generic {@code intent} lines with {ammo} read as {@code word}. */
    private static List<String> ammoLines(String intent, String word) {
        return CompanionRestockLines.GENERIC.get(intent).stream()
                .map(line -> CompanionRestockLines.withAmmo(line, word)).toList();
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
