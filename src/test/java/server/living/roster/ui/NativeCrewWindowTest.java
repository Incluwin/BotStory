package server.living.roster.ui;

import client.Character;
import client.Client;
import client.Job;
import config.LivingWorldConfig;
import net.packet.Packet;
import net.server.channel.handlers.CrewWindowHandler;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import server.living.LivingWorld;
import server.living.bond.ContractService;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.resident.LivingTalk;
import server.living.resident.NativeTalkPage;
import server.living.resident.ResidentRuntime;
import server.living.roster.Commitments;
import server.living.roster.CompanionDirectory;
import server.living.roster.CompanionDirectory.Relation;
import server.living.roster.CompanionExecutor;
import server.living.roster.CompanionManager;
import server.living.roster.CompanionSlots;
import server.living.roster.CompanionViews;
import server.living.roster.DirectExecutor;
import server.living.roster.HiredHands;
import server.living.roster.RosterResult;
import server.living.roster.RosterService;
import server.living.roster.ui.NativeCrewPackets.Action;
import server.living.roster.ui.NativeCrewPackets.Close;
import server.living.roster.ui.NativeCrewPackets.Hello;
import server.living.roster.ui.NativeCrewPackets.Open;
import server.living.roster.ui.NativeCrewPackets.PageReply;
import server.living.roster.ui.NativeCrewPackets.Row;
import server.living.roster.ui.NativeCrewPackets.Snapshot;
import server.living.roster.ui.NativeCrewPackets.Verb;
import server.living.store.LivingStore;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.HaulItem;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;
import testutil.NativePages;
import testutil.NpcTalks;
import testutil.NpcTalks.Talk;
import testutil.Packets;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The native Crew window's server half (docs/BOT_MANAGEMENT_P10_PLAN.md, P10): HELLO gating (nothing on 0x17A for a
 * connection that did not say HELLO; WELCOME enabled per living_world.CREW_WINDOW_NATIVE and GM status), a snapshot's
 * rows reading exactly as the NPC Crew window's and flagged with exactly what each card offers, each verb reaching the
 * same roster or manager call as the NPC path (with the same follow-up windows), stale stamps and refused verbs
 * answering "Things changed meanwhile." and doing nothing, and the watcher sending only what changed.
 */
class NativeCrewWindowTest {
    private static final int ANN = 10;
    private static final int HADRIK_PORTRAIT = 9970001;
    private static final int STOLEN_FENCE = 4000021;
    private static final int ANT_TUNNEL = 105040300;
    private static final int KIRA = 555;
    private static final short SEND_CREW_WINDOW = 0x17A;
    private static final byte MENU = 4;
    private static final byte YES_NO = 1;

    private final LivingWorld world = mock(LivingWorld.class);
    private final LivingWorldConfig config = new LivingWorldConfig();
    private final LivingContent content = mock(LivingContent.class);
    private final RosterService roster = mock(RosterService.class);
    private final LivingStore store = mock(LivingStore.class);
    private final ResidentRuntime residents = mock(ResidentRuntime.class);
    private final CompanionDirectory directory = mock(CompanionDirectory.class);
    private final CompanionSlots slots = mock(CompanionSlots.class);
    private final CompanionManager manager = mock(CompanionManager.class);
    private final Character owner = mock(Character.class);
    private final Client client = mock(Client.class);
    private final Character hadrikBot = mock(Character.class);
    private final List<RosterEntry> rows = new ArrayList<>();
    private final Map<String, Persona> personas = new HashMap<>();
    private final AtomicInteger protocol = new AtomicInteger();
    private final java.util.concurrent.atomic.AtomicBoolean windowOpen = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean windowEnabled = new java.util.concurrent.atomic.AtomicBoolean();
    private final AtomicLong now = new AtomicLong(1_000_000L);
    /** The flags that name a swap picker's partners rather than an option of the row's own card. */
    private static final int PARTNERS = NativeCrewPackets.OUT | NativeCrewPackets.CAN_COME;

    private MockedStatic<LivingWorld> livingWorld;
    private CompanionViews views;
    private CompanionMenus menus;
    private NativeCrewWindow window;

    @BeforeEach
    void wire() {
        config.CREW_WINDOW_NATIVE = "all";
        when(owner.getId()).thenReturn(ANN);
        when(owner.getName()).thenReturn("Ann");
        when(owner.getClient()).thenReturn(client);
        when(owner.isLoggedinWorld()).thenReturn(true);
        when(client.getPlayer()).thenReturn(owner);
        when(client.canClickNPC()).thenReturn(true);
        when(client.onIoThread()).thenReturn(true);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run(); // the owner's packet thread, at once
            return null;
        }).when(client).runOnIoThread(any());
        doAnswer(inv -> {
            protocol.set(inv.getArgument(0));
            return null;
        }).when(client).setCrewWindowProtocol(anyInt());
        when(client.getCrewWindowProtocol()).thenAnswer(inv -> protocol.get());
        doAnswer(inv -> {
            windowOpen.set(inv.getArgument(0));
            return null;
        }).when(client).setCrewWindowOpen(org.mockito.ArgumentMatchers.anyBoolean());
        when(client.isCrewWindowOpen()).thenAnswer(inv -> windowOpen.get());
        doAnswer(inv -> {
            windowEnabled.set(inv.getArgument(0));
            return null;
        }).when(client).setCrewWindowEnabled(org.mockito.ArgumentMatchers.anyBoolean());
        when(client.isCrewWindowEnabled()).thenAnswer(inv -> windowEnabled.get());
        when(world.content()).thenReturn(content);
        when(world.roster()).thenReturn(roster);
        when(world.store()).thenReturn(store);
        when(world.residents()).thenReturn(residents);
        when(world.directory()).thenReturn(directory);
        when(world.config()).thenReturn(config);
        CompanionExecutor ops = DirectExecutor.inline();
        when(world.executor()).thenReturn(ops);
        when(world.slots()).thenReturn(slots);
        when(world.manager()).thenReturn(manager);
        views = new CompanionViews(world, mapId -> "Ant Tunnel", itemId -> "Stolen Fence");
        when(world.views()).thenReturn(views);
        when(store.roster(ANN)).thenAnswer(inv -> List.copyOf(rows));
        when(roster.roster(owner)).thenReturn(rows);
        when(roster.describeAssignment(any())).thenAnswer(inv -> ((RosterEntry) inv.getArgument(0)).assignment() == Assignment.TRAIN
                ? "train" : "farm Stolen Fence x200");
        when(roster.canSeat(any(), any())).thenReturn(true);
        when(roster.call(any(), any())).thenAnswer(inv -> new RosterResult.Ok(((Persona) inv.getArgument(1)).name()
                + " is on the way (1m)."));
        when(roster.bench(any(), any())).thenAnswer(inv -> new RosterResult.Ok(((Persona) inv.getArgument(1)).name()
                + " heads back to camp to train."));
        when(roster.swap(any(), any(), any())).thenReturn(new RosterResult.Ok("Mira takes Hadrik's place."));
        when(slots.occupied(owner)).thenReturn(3);
        when(content.portraitNpcId("hadrik")).thenReturn(HADRIK_PORTRAIT);
        MapleMap perion = mock(MapleMap.class);
        when(perion.getMapName()).thenReturn("Perion");
        when(owner.getMap()).thenReturn(perion);
        when(hadrikBot.getId()).thenReturn(900);
        when(hadrikBot.getName()).thenReturn("Hadrik");
        when(hadrikBot.getLevel()).thenReturn(31);
        when(hadrikBot.getJob()).thenReturn(Job.FIGHTER);
        when(hadrikBot.getMap()).thenReturn(perion);
        when(hadrikBot.getHp()).thenReturn(812);
        when(hadrikBot.getCurrentMaxHp()).thenReturn(900);
        livingWorld = mockStatic(LivingWorld.class);
        livingWorld.when(LivingWorld::get).thenReturn(world);
        menus = new CompanionMenus(world, mapId -> "Ant Tunnel");
        window = new NativeCrewWindow(world, menus, now::get);
        when(world.crewWindow()).thenReturn(window);
    }

    @AfterEach
    void close() {
        window.uninstall();
        LivingTalk.forget(owner);
        LivingTalk.endNative(owner);
        livingWorld.close();
    }

    // ---- HELLO gating ------------------------------------------------------------------------------

    @Test
    void aConnectionThatSaidNoHelloIsNeverSentTheWindowsOpcode() {
        hadrik();
        add(row("mira", RosterState.BENCHED), "Mira", 901, 28, Job.HUNTER);

        NativeCrewWindow.receive(client, owner, new Open(), world);
        NativeCrewWindow.receive(client, owner, new Action(Verb.CALL, 0, "mira", 0, "", 0), world);
        NativeCrewWindow.receive(client, owner, new Action(Verb.FOLLOW, 0, "", 0, "", 0), world);
        window.tick();
        window.watchOpenWindows();
        window.switchChanged(List.of(owner));

        assertTrue(crewPackets().isEmpty(), "no 0x17A packet without HELLO");
        assertFalse(window.watching(owner));
        verify(roster, never()).call(any(), any());
        verify(manager, never()).followAll(any());
    }

    @Test
    void welcomeIsEnabledAsTheSwitchAllowsTheCharacter() {
        Object[][] cases = {
                {"off", false, false}, {"off", true, false},
                {"gm", false, false}, {"gm", true, true},
                {"all", false, true}, {"all", true, true},
        };
        for (Object[] each : cases) {
            config.CREW_WINDOW_NATIVE = (String) each[0];
            when(owner.isGM()).thenReturn((Boolean) each[1]);
            protocol.set(0);
            clearInvocations(client);

            NativeCrewWindow.receive(client, owner, new Hello(1, 20261012), world);

            assertEquals(1, protocol.get());
            assertEquals(1, crewPackets().size());
            assertArrayEquals(NativeCrewPackets.welcome(1, (Boolean) each[2]).getBytes(), crewPackets().get(0),
                    each[0] + (((Boolean) each[1]) ? " GM" : " player"));
        }
    }

    @Test
    void helloIsAnsweredAtTheLowerOfTheClientsAndTheServersProtocol() {
        int[][] cases = {{1, 1}, {2, 2}, {3, 2}, {0x7FFF, 2}};
        for (int[] each : cases) {
            protocol.set(0);
            clearInvocations(client);

            NativeCrewWindow.receive(client, owner, new Hello(each[0], 20270101), world);

            assertEquals(each[0], protocol.get(), "the client's own protocol is kept");
            assertEquals(each[1], NativeCrewWindow.negotiated(client));
            assertEquals(1, crewPackets().size());
            assertArrayEquals(NativeCrewPackets.welcome(each[1], true).getBytes(), crewPackets().get(0), "HELLO " + each[0]);
        }
    }

    @Test
    void aLaterDllIsServedAtThisServersProtocol() {
        hadrik();
        when(manager.followAll(owner)).thenReturn(new RosterResult.Ok(""));

        NativeCrewWindow.receive(client, owner, new Hello(3, 20270101), world);
        NativeCrewWindow.receive(client, owner, new Open(), world);
        NativeCrewWindow.receive(client, owner, new Action(Verb.FOLLOW, 0, "", 0, "", 0), world);

        assertArrayEquals(NativeCrewPackets.welcome(2, true).getBytes(), crewPackets().get(0));
        assertEquals(NativeCrewPackets.TYPE_SNAPSHOT, last()[2]);
        verify(manager).followAll(owner);
    }

    @Test
    void aHelloOfProtocolZeroIsNoHelloAndIsNotAnswered() {
        NativeCrewWindow.receive(client, owner, new Hello(0, 20261012), world);

        assertEquals(0, protocol.get());
        assertTrue(crewPackets().isEmpty());
    }

    @Test
    void withTheLivingWorldOffAHelloIsAnsweredNotEnabledAndAnOpenIsClosed() {
        NativeCrewWindow.receive(client, owner, new Hello(1, 20261012), null);
        windowOpen.set(true); // a window the client opened before the living world went off
        NativeCrewWindow.receive(client, owner, new Open(), null);

        assertEquals(List.of(Arrays.toString(NativeCrewPackets.welcome(1, false).getBytes()),
                Arrays.toString(NativeCrewPackets.close().getBytes())), crewPackets().stream().map(Arrays::toString).toList());
        assertFalse(windowOpen.get(), "the CLOSE it sent closed the window");
    }

    @Test
    void anOpenTheSwitchNoLongerAllowsIsAnsweredWithClose() {
        hello();
        config.CREW_WINDOW_NATIVE = "gm";

        windowOpen.set(true);
        NativeCrewWindow.receive(client, owner, new Open(), world);

        assertArrayEquals(NativeCrewPackets.close().getBytes(), last());
        assertFalse(window.watching(owner));
        assertFalse(windowOpen.get(), "the CLOSE it sent closed the window");
    }

    @Test
    void theHandlerDropsAMalformedPacketAndAnswersAHello() {
        CrewWindowHandler handler = new CrewWindowHandler();

        handler.handlePacket(Packets.buildInPacket(out -> out.writeByte(3)), client);
        assertTrue(crewPackets().isEmpty());
        assertEquals(0, protocol.get());

        handler.handlePacket(Packets.buildInPacket(out -> {
            out.writeByte(0);
            out.writeShort(1);
            out.writeInt(20261012);
        }), client);
        assertArrayEquals(NativeCrewPackets.welcome(1, true).getBytes(), last());
    }

    // ---- snapshot rows -----------------------------------------------------------------------------

    @Test
    void eachRowsDetailIsTheNpcCrewWindowsRowTextAfterNameLevelAndJob() {
        fullCrew();

        menus.crew(owner);
        List<String> npcRows = lastTalk().labels().subList(0, 7);
        Snapshot snapshot = window.snapshot(owner);

        List<Row> all = new ArrayList<>(snapshot.party());
        all.addAll(snapshot.bench());
        assertEquals(npcRows, all.stream().map(row -> row.name() + (row.levelJob().isEmpty() ? "" : ", " + row.levelJob())
                + " - " + row.detail()).toList());
        assertEquals(List.of("hadrik", "tobi", "vex", "dorran", "guest:" + KIRA),
                snapshot.party().stream().map(Row::personaId).toList());
        assertEquals(List.of("mira", "olwen"), snapshot.bench().stream().map(Row::personaId).toList());
        assertEquals("Perion - HP 90%", snapshot.party().get(0).detail());
        assertEquals("Lv31 Fighter", snapshot.party().get(0).levelJob());
        assertEquals(90, snapshot.party().get(0).hpPercent());
        assertEquals(NativeCrewPackets.HP_UNKNOWN, snapshot.party().get(1).hpPercent(), "Tobi is not loaded");
        assertEquals("on the way (0:40)", snapshot.party().get(1).detail());
        assertEquals("waiting outside", snapshot.party().get(2).detail());
        assertEquals("offline", snapshot.party().get(3).detail());
        assertEquals("Henesys - HP 100% (guest)", snapshot.party().get(4).detail());
        assertEquals("farm @ Ant Tunnel - 143/200 Stolen Fence - 10% EXP banked - 3,120 meso, 2 stacks",
                snapshot.bench().get(0).detail());
        assertEquals("away: train @ Ant Tunnel", snapshot.bench().get(1).detail());
        // the header, as the Crew window's: Party 3/6 (1 seat kept), four of the ten roster places in the party block
        assertEquals(3, snapshot.seats());
        assertEquals(RosterService.PARTY_CAP, snapshot.partyCap());
        assertEquals(6, snapshot.benchSeats());
        assertEquals("(1 seat kept)", snapshot.seatsNote());
        assertEquals(NativeCrewPackets.SOMEONE_OUT, snapshot.flags(), "Hadrik is out; nobody of a usual lineup at camp");
        assertEquals((int) views.version(ANN), snapshot.ownerVersion());
        assertEquals((int) views.version(ANN, "mira"), snapshot.bench().get(0).rowVersion());
        assertEquals(0, snapshot.party().get(4).rowVersion(), "a guest has no row version");
    }

    @Test
    void eachRowsFlagsAreTheOptionsItsCardOffers() {
        fullCrew();
        Snapshot snapshot = window.snapshot(owner);
        Map<String, Integer> flags = new HashMap<>();
        for (Row row : snapshot.party()) {
            flags.put(row.personaId(), row.flags() & ~PARTNERS);
        }
        for (Row row : snapshot.bench()) {
            flags.put(row.personaId(), row.flags() & ~PARTNERS);
        }

        int callAndSwapIn = NativeCrewPackets.CALL | NativeCrewPackets.SWAP_IN;
        assertEquals(NativeCrewPackets.SWAP_OUT, flags.get("hadrik"), "the last one out: rotated, never benched");
        assertEquals(NativeCrewPackets.BENCH, flags.get("tobi"), "on the way, Hadrik out");
        assertEquals(NativeCrewPackets.CALL | NativeCrewPackets.BENCH, flags.get("vex"), "waiting outside: Come join me");
        assertEquals(callAndSwapIn, flags.get("dorran"), "parked: the bench card");
        assertEquals(NativeCrewPackets.GUEST, flags.get("guest:" + KIRA));
        assertEquals(callAndSwapIn, flags.get("mira"));
        assertEquals(callAndSwapIn, flags.get("olwen"));
        // and each card offers exactly that
        for (RosterEntry entry : List.copyOf(rows)) {
            assertCardOffers(entry, flags.get(entry.personaId()));
        }
    }

    @Test
    void oneTiedToARunIsFlaggedCommittedAndOffersNoBenchOrSwapAsItsCard() {
        hadrik();
        add(row("tobi", RosterState.ACTIVE), "Tobi", 902, 30, Job.CLERIC);
        add(row("mira", RosterState.BENCHED), "Mira", 901, 28, Job.HUNTER);
        Commitments commitments = mock(Commitments.class);
        when(commitments.isCommitted(900)).thenReturn(true); // Hadrik is in a run
        when(commitments.isCommitted(901)).thenReturn(true); // and Mira waits for one
        when(world.commitments()).thenReturn(commitments);

        Snapshot snapshot = window.snapshot(owner);

        assertEquals(NativeCrewPackets.COMMITTED, snapshot.party().get(0).flags() & ~PARTNERS);
        assertEquals(NativeCrewPackets.BENCH | NativeCrewPackets.SWAP_OUT, snapshot.party().get(1).flags() & ~PARTNERS);
        assertEquals(NativeCrewPackets.COMMITTED | NativeCrewPackets.CALL, snapshot.bench().get(0).flags() & ~PARTNERS,
                "it can still be called, not swapped in");
        for (RosterEntry entry : List.copyOf(rows)) {
            int flags = (entry.personaId().equals("hadrik") ? snapshot.party().get(0)
                    : entry.personaId().equals("tobi") ? snapshot.party().get(1) : snapshot.bench().get(0)).flags();
            assertCardOffers(entry, flags & ~NativeCrewPackets.COMMITTED & ~PARTNERS);
        }
    }

    @Test
    void holdingTheRosterTheLastOneOutMayTakeABreak() {
        hadrik();
        when(owner.haveItem(config.ROSTER_BOOK_ITEM_ID)).thenReturn(true);

        assertEquals(NativeCrewPackets.BENCH, window.snapshot(owner).party().get(0).flags() & ~PARTNERS);
    }

    @Test
    void eachRowSaysWhomTheSwapPickersWouldPairItWith() {
        fullCrew();
        Snapshot snapshot = window.snapshot(owner);
        Map<String, Integer> partners = new HashMap<>();
        for (Row row : snapshot.party()) {
            partners.put(row.personaId(), row.flags() & PARTNERS);
        }
        for (Row row : snapshot.bench()) {
            partners.put(row.personaId(), row.flags() & PARTNERS);
        }

        assertEquals(NativeCrewPackets.OUT, partners.get("hadrik"), "out with Ann: in the 'Swap in for...' picker");
        assertEquals(0, partners.get("tobi"), "on the way: in neither picker");
        assertEquals(0, partners.get("vex"), "waiting outside a run: in neither picker");
        assertEquals(0, partners.get("dorran"), "logged off with Ann: in neither picker");
        assertEquals(0, partners.get("guest:" + KIRA));
        assertEquals(NativeCrewPackets.CAN_COME, partners.get("mira"), "at camp: in the 'take your place' picker");
        assertEquals(NativeCrewPackets.CAN_COME, partners.get("olwen"), "away, not on her way");
    }

    @Test
    void theUsualLineupFlagIsSetWhileSomeoneOfItSitsAtCamp() {
        add(row("mira", RosterState.BENCHED), "Mira", 901, 28, Job.HUNTER);
        when(store.lineupSlots(ANN)).thenReturn(Map.of("mira", 1));

        assertEquals(NativeCrewPackets.USUAL_CALLABLE, window.snapshot(owner).flags());
    }

    // ---- review fixes (int/p9aq review) -------------------------------------------------------------

    @Test
    void aSecondHelloOnTheSameConnectionIsNeitherAnsweredNorStored() {
        hello();

        NativeCrewWindow.receive(client, owner, new Hello(NativeCrewPackets.PROTOCOL, 20261012), world);
        NativeCrewWindow.receive(client, owner, new Hello(NativeCrewPackets.PROTOCOL + 1, 20261012), world);

        verify(client, never()).sendPacket(any());
        assertEquals(NativeCrewPackets.PROTOCOL, protocol.get());
    }

    @Test
    void aClickOnAnIdNotInTheRosterIsRefusedBeforeAnyPersonaLookup() {
        hadrik();
        openWindow();
        clearInvocations(content);

        for (Verb verb : List.of(Verb.CALL, Verb.BENCH, Verb.CARD)) {
            NativeCrewWindow.receive(client, owner, new Action(verb, 0, "hire:05", 0, "", 0), world);
        }
        NativeCrewWindow.receive(client, owner, new Action(Verb.SWAP, 0, "hadrik", 0, "hire:+5", 0), world);

        verify(content, never()).persona("hire:05");
        verify(content, never()).persona("hire:+5");
        verify(owner, org.mockito.Mockito.times(4)).dropMessage(5, CompanionMenus.THINGS_CHANGED);
        verify(roster, never()).call(any(), any());
        verify(roster, never()).bench(any(), any());
        verify(roster, never()).swap(any(), any(), any());
    }

    @Test
    void aGuestIdIsReadOnlyAsTheServerWritesIt() {
        assertEquals(5678, NativeCrewWindow.guestId("guest:5678"));
        assertEquals(0, NativeCrewWindow.guestId("guest:05678"));
        assertEquals(0, NativeCrewWindow.guestId("guest:+5678"));
        assertEquals(0, NativeCrewWindow.guestId("guest:0"));
        assertEquals(0, NativeCrewWindow.guestId("guest:-1"));
        assertEquals(0, NativeCrewWindow.guestId("guest:"));
    }

    @Test
    void clicksAndOpensFromTheCashShopAreNotActedOn() {
        hadrik();
        add(row("mira", RosterState.BENCHED), "Mira", 901, 28, Job.HUNTER);
        openWindow();
        clearInvocations(client);
        when(owner.isLoggedinWorld()).thenReturn(false); // in the Cash Shop or MTS

        act(Verb.CALL, "mira", "");
        NativeCrewWindow.receive(client, owner, new Open(), world);

        verify(roster, never()).call(any(), any());
        verify(client, never()).sendPacket(any());
    }

    @Test
    void aSnapshotReadBeforeTheSwitchTurnedOffIsNeverSent() {
        hadrik();
        openWindow();
        clearInvocations(client);
        List<Runnable> packetThread = new ArrayList<>();
        doAnswer(inv -> {
            packetThread.add(inv.getArgument(0)); // held: the switch turns off before the packet thread runs it
            return null;
        }).when(client).runOnIoThread(any());

        NativeCrewWindow.receive(client, owner, new Open(), world);
        config.CREW_WINDOW_NATIVE = "off";
        packetThread.forEach(Runnable::run);

        verify(client, never()).sendPacket(any());
    }

    @Test
    void aNativeClickLeadsToCardsWithNoBackToTheNpcCrewWindow() {
        hadrik();
        RosterEntry mira = add(row("mira", RosterState.BENCHED), "Mira", 901, 28, Job.HUNTER);
        when(roster.canSeat(owner, mira)).thenReturn(false);
        when(slots.occupied(owner)).thenReturn(RosterService.PARTY_CAP);
        menus.cards().show(owner, personas.get("mira"), true, null); // a card opened earlier from the NPC Crew window
        LivingTalk.forget(owner);
        openWindow();

        act(Verb.CALL, "mira", ""); // a full party: the card's swap picker
        pick("Back"); // the picker's Back reopens Mira's own card

        assertFalse(lastTalk().labels().contains("Back"), lastTalk().labels().toString());
    }

    // ---- the verbs ---------------------------------------------------------------------------------

    @Test
    void callIsTheCardsComeJoinMeAndAnswersInPinkWithAFreshSnapshot() {
        hadrik();
        add(row("mira", RosterState.BENCHED), "Mira", 901, 28, Job.HUNTER);
        openWindow();

        act(Verb.CALL, "mira", "");

        verify(roster).canSeat(owner, rows.get(1));
        verify(roster).call(owner, personas.get("mira"));
        verify(owner).dropMessage(5, "Mira is on the way (1m).");
        assertEquals(NativeCrewPackets.TYPE_SNAPSHOT, last()[2]);
    }

    @Test
    void callWithThePartyFullOpensTheCardsSwapPickerInsteadOfCalling() {
        hadrik();
        RosterEntry mira = add(row("mira", RosterState.BENCHED), "Mira", 901, 28, Job.HUNTER);
        when(roster.canSeat(owner, mira)).thenReturn(false);
        when(slots.occupied(owner)).thenReturn(RosterService.PARTY_CAP);
        openWindow();

        act(Verb.CALL, "mira", "");

        Talk picker = lastTalk();
        assertTrue(picker.text().startsWith("your party's full. who should i take over from?\r\n"), picker.text());
        assertEquals(List.of("Hadrik, Lv31 Fighter - Perion - HP 90%", "Back"), picker.labels());
        verify(roster, never()).call(any(), any());
        pick("Hadrik, Lv31 Fighter - Perion - HP 90%");
        verify(roster).swap(owner, personas.get("hadrik"), personas.get("mira"));
    }

    @Test
    void callUnderTheLeaderCapWithNobodyOutShowsTheLeaderCapsOk() {
        RosterEntry mira = add(row("mira", RosterState.BENCHED), "Mira", 901, 28, Job.HUNTER);
        when(roster.canSeat(owner, mira)).thenReturn(false);
        when(slots.leaderCapped(owner)).thenReturn(true);
        openWindow();

        act(Verb.CALL, "mira", "");

        assertEquals(RosterService.LEADER_CAP_LINE, lastTalk().text());
        verify(roster, never()).call(any(), any());
    }

    @Test
    void benchIsTheCardsTakeABreakAtCamp() {
        hadrik();
        add(row("tobi", RosterState.ACTIVE), "Tobi", 902, 30, Job.CLERIC);
        openWindow();

        act(Verb.BENCH, "hadrik", "");

        verify(roster).bench(owner, personas.get("hadrik"));
        verify(owner).dropMessage(5, "Hadrik heads back to camp to train.");
        assertEquals(NativeCrewPackets.TYPE_SNAPSHOT, last()[2]);
    }

    @Test
    void benchAsksTheBenchWarningsYesNoFirstWhereABenchCanEndTheContract() {
        hadrik();
        add(row("tobi", RosterState.ACTIVE), "Tobi", 902, 30, Job.CLERIC);
        ContractService contracts = mock(ContractService.class);
        when(contracts.benchWalkOutAfter(ANN, personas.get("hadrik"))).thenReturn("an hour");
        when(world.contracts()).thenReturn(contracts);
        openWindow();

        act(Verb.BENCH, "hadrik", "");

        Talk ask = lastTalk();
        assertEquals(YES_NO, ask.msgType());
        assertTrue(ask.text().startsWith("Hadrik won't sit at camp for long: after an hour on the bench"), ask.text());
        verify(roster, never()).bench(any(), any());

        assertTrue(LivingTalk.respond(client, YES_NO, (byte) 1, Packets.buildInPacket(out -> { })));

        verify(roster).bench(owner, personas.get("hadrik"));
        verify(owner).dropMessage(5, "Hadrik heads back to camp to train.");
    }

    @Test
    void benchOnTheLastOneOutIsRefusedAsTheCardRefusesIt() {
        hadrik();
        openWindow();

        act(Verb.BENCH, "hadrik", "");

        verify(roster, never()).bench(any(), any());
        verify(owner).dropMessage(5, CompanionMenus.THINGS_CHANGED);
        assertEquals(NativeCrewPackets.TYPE_SNAPSHOT, last()[2]);
    }

    @Test
    void swapIsTheSwapPickersPickInTheIncomingOnesWords() {
        hadrik();
        add(row("mira", RosterState.BENCHED), "Mira", 901, 28, Job.HUNTER);
        openWindow();

        act(Verb.SWAP, "hadrik", "mira");

        verify(roster).swap(owner, personas.get("hadrik"), personas.get("mira"));
        verify(owner).dropMessage(5, "Mira takes Hadrik's place.");
        assertEquals(NativeCrewPackets.TYPE_SNAPSHOT, last()[2]);
    }

    @Test
    void aSwapNoPickerWouldListIsRefused() {
        hadrik();
        add(row("tobi", RosterState.ACTIVE), "Tobi", 902, 30, Job.CLERIC);
        add(row("mira", RosterState.BENCHED), "Mira", 901, 28, Job.HUNTER);
        openWindow();

        act(Verb.SWAP, "mira", "hadrik"); // backwards: Mira is not out
        act(Verb.SWAP, "hadrik", "tobi"); // both out
        act(Verb.SWAP, "hadrik", "hadrik");

        verify(roster, never()).swap(any(), any(), any());
        verify(owner, org.mockito.Mockito.times(3)).dropMessage(5, CompanionMenus.THINGS_CHANGED);
    }

    @Test
    void aStaleStampAnswersThingsChangedAndDoesNothingElse() {
        hadrik();
        add(row("mira", RosterState.BENCHED), "Mira", 901, 28, Job.HUNTER);
        openWindow();
        Row mira = window.snapshot(owner).bench().get(0);
        views.invalidate(ANN, "mira"); // her row changed after the snapshot
        clearInvocations(client);

        NativeCrewWindow.receive(client, owner, new Action(Verb.CALL, 0, "mira", mira.rowVersion(), "", 0), world);

        verify(roster, never()).call(any(), any());
        verify(owner).dropMessage(5, CompanionMenus.THINGS_CHANGED);
        assertEquals(1, crewPackets().size());
        assertEquals(NativeCrewPackets.TYPE_SNAPSHOT, last()[2]);
        assertTrue(window.snapshot(owner).bench().get(0).rowVersion() != mira.rowVersion(), "the fresh stamp");
    }

    @Test
    void anotherCompanionsChangeLeavesARowsStampFresh() {
        hadrik();
        add(row("mira", RosterState.BENCHED), "Mira", 901, 28, Job.HUNTER);
        openWindow();
        Row mira = window.snapshot(owner).bench().get(0);
        views.invalidate(ANN, "hadrik");

        NativeCrewWindow.receive(client, owner, new Action(Verb.CALL, 0, "mira", mira.rowVersion(), "", 0), world);

        verify(roster).call(owner, personas.get("mira"));
    }

    @Test
    void usualIsCallMyUsualLineupStampedWithTheOwnersVersion() {
        add(row("mira", RosterState.BENCHED), "Mira", 901, 28, Job.HUNTER);
        when(store.lineupSlots(ANN)).thenReturn(Map.of("mira", 1));
        when(manager.callUsual(owner)).thenReturn(new RosterResult.Ok("Mira is on the way."));
        openWindow();
        int stamp = window.snapshot(owner).ownerVersion();

        NativeCrewWindow.receive(client, owner, new Action(Verb.USUAL, stamp, "", 0, "", 0), world);
        verify(manager).callUsual(owner);
        verify(owner).dropMessage(5, "Mira is on the way.");

        views.invalidate(ANN);
        NativeCrewWindow.receive(client, owner, new Action(Verb.USUAL, stamp, "", 0, "", 0), world);
        verify(manager).callUsual(owner); // still once
        verify(owner).dropMessage(5, CompanionMenus.THINGS_CHANGED);
    }

    @Test
    void theOrdersReachTheManagerAndSayNothingWhenTheyWentOut() {
        hadrik();
        when(manager.followAll(owner)).thenReturn(new RosterResult.Ok("followAll done."));
        when(manager.waitAll(owner)).thenReturn(new RosterResult.Ok("waitAll done."));
        when(manager.gatherAll(owner)).thenReturn(new RosterResult.Ok("gatherAll done."));
        when(manager.rebuffAll(owner)).thenReturn(new RosterResult.Failed("Nobody of yours can rebuff right now."));
        openWindow();

        for (Verb verb : List.of(Verb.FOLLOW, Verb.WAIT, Verb.GATHER, Verb.REBUFF)) {
            clearInvocations(client);
            act(verb, "", "");
            assertEquals(NativeCrewPackets.TYPE_SNAPSHOT, last()[2], verb + " is followed by a snapshot");
        }

        verify(manager).followAll(owner);
        verify(manager).waitAll(owner);
        verify(manager).gatherAll(owner);
        verify(manager).rebuffAll(owner);
        verify(owner, never()).dropMessage(5, "followAll done.");
        verify(owner).dropMessage(5, "Nobody of yours can rebuff right now.");
    }

    @Test
    void anOrderWithNobodyOutIsRefused() {
        add(row("mira", RosterState.BENCHED), "Mira", 901, 28, Job.HUNTER);
        openWindow();

        act(Verb.FOLLOW, "", "");

        verify(manager, never()).followAll(any());
        verify(owner).dropMessage(5, CompanionMenus.THINGS_CHANGED);
    }

    @Test
    void cardOpensTheCompanionsCardWithoutBackAndMoreTheNpcCrewWindow() {
        RosterEntry hadrikRow = hadrik();
        when(directory.resolveName(owner, "Hadrik")).thenReturn(new Relation.OwnCompanion(hadrikRow));
        openWindow();

        act(Verb.CARD, "hadrik", "");
        Talk card = lastTalk();
        assertEquals(HADRIK_PORTRAIT, card.npcId());
        assertTrue(card.text().startsWith("#eHadrik#n"), card.text());
        assertFalse(card.labels().contains("Back"), "the native window stays open behind it");
        verify(directory).resolveName(owner, "Hadrik"); // ownership read again at the click

        LivingTalk.forget(owner);
        act(Verb.MORE, "", "");
        Talk crew = lastTalk();
        assertEquals(CompanionMenus.BOARD_NPC, crew.npcId());
        assertEquals(CompanionMenus.NOTES_SPEAKER, crew.speaker());
        assertTrue(crew.text().startsWith("#eParty 3/6#n"), crew.text());
    }

    @Test
    void aGuestsCardIsItsHireTermsOnlyWhileItIsAGuest() {
        hadrik();
        openWindow();
        act(Verb.CARD, "guest:" + KIRA, "");
        verify(owner).dropMessage(5, CompanionMenus.THINGS_CHANGED);

        guestInParty("Kira", KIRA, 25, Job.ASSASSIN);
        HiredHands hands = mock(HiredHands.class);
        Persona kira = mock(Persona.class);
        when(kira.name()).thenReturn("Kira");
        when(kira.id()).thenReturn(HiredHands.hireId(KIRA));
        when(hands.persona(HiredHands.hireId(KIRA))).thenReturn(kira);
        when(hands.terms(KIRA)).thenReturn(new HiredHands.Terms(25, "Assassin", 32_000L, 900L));
        when(hands.voice(eq(KIRA), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(world.hiredHands()).thenReturn(hands);

        act(Verb.CARD, "guest:" + KIRA, "");

        assertTrue(lastTalk().text().contains("#eKira#n, Lv25 Assassin"), lastTalk().text());
    }

    @Test
    void aClickWhileAnotherWindowAwaitsItsReplyDoesNothingButRedrawTheWindow() {
        hadrik();
        add(row("mira", RosterState.BENCHED), "Mira", 901, 28, Job.HUNTER);
        openWindow();
        LivingTalk.open(owner, 9000000).ok("a stock window the player opened meanwhile").show();
        clearInvocations(client);

        act(Verb.CALL, "mira", "");

        verify(roster, never()).call(any(), any());
        assertEquals(NativeCrewPackets.TYPE_SNAPSHOT, last()[2]);
        assertTrue(NpcTalks.talks(sent()).isEmpty(), "never a second NPC_TALK over a waiting window");
    }

    // ---- watching ----------------------------------------------------------------------------------

    @Test
    void theWatcherSendsOnlyWhatChangedAndStopsOnClose() {
        hadrik();
        openWindow();
        assertEquals(1, crewPackets().size(), "OPEN: one snapshot");

        window.watchOpenWindows();
        assertEquals(1, crewPackets().size(), "nothing changed: nothing sent");

        when(hadrikBot.getHp()).thenReturn(450); // read at render time, never signalled
        window.watchOpenWindows();
        assertEquals(2, crewPackets().size());
        assertTrue(window.snapshot(owner).party().get(0).detail().endsWith("HP 50%"));
        window.watchOpenWindows();
        assertEquals(2, crewPackets().size());

        NativeCrewWindow.receive(client, owner, new Close(), world);
        when(hadrikBot.getHp()).thenReturn(900);
        window.watchOpenWindows();
        window.tick();
        assertEquals(2, crewPackets().size(), "closed: no longer watched");
        assertFalse(window.watching(owner));
    }

    @Test
    void theTimerHandsOverOnlyWhileAWindowIsOpen() {
        hadrik();
        hello();
        window.tick();
        verify(world.executor(), never()).submit(eq("crew_window.watch"), any());

        NativeCrewWindow.receive(client, owner, new Open(), world);
        when(hadrikBot.getHp()).thenReturn(450);
        window.tick();

        verify(world.executor()).submit(eq("crew_window.watch"), any());
        assertEquals(2, crewPackets().size());
    }

    @Test
    void turningTheSwitchOffClosesTheOpenWindow() {
        hadrik();
        openWindow();
        config.CREW_WINDOW_NATIVE = "off";

        window.watchOpenWindows();

        assertArrayEquals(NativeCrewPackets.close().getBytes(), last());
        assertFalse(window.watching(owner));
        assertFalse(windowOpen.get(), "the CLOSE it sent closed the window");
        clearInvocations(client);
        window.watchOpenWindows();
        assertTrue(crewPackets().isEmpty());
    }

    @Test
    void aWindowIdleForHalfAnHourIsNoLongerWatchedUntilItsNextClick() {
        hadrik();
        when(manager.followAll(owner)).thenReturn(new RosterResult.Ok(""));
        openWindow();
        now.addAndGet(NativeCrewWindow.IDLE_MS + 1);

        window.watchOpenWindows();

        assertFalse(window.watching(owner));
        assertEquals(1, crewPackets().size(), "no CLOSE: the switch still allows it");
        act(Verb.FOLLOW, "", "");
        assertTrue(window.watching(owner));
    }

    @Test
    void aLogoutOrChannelChangeStopsTheWatch() {
        hadrik();
        openWindow();
        when(client.getPlayer()).thenReturn(null); // the connection is gone

        window.watchOpenWindows();

        assertFalse(window.watching(owner));
        assertEquals(1, crewPackets().size());
    }

    @Test
    void theSwitchChangingTellsEveryHelloClientAndClosesWhatItNoLongerAllows() {
        hadrik();
        openWindow();
        Character ben = mock(Character.class);
        Client benClient = mock(Client.class);
        when(ben.getClient()).thenReturn(benClient); // no HELLO: a client without the DLL
        clearInvocations(client);

        config.CREW_WINDOW_NATIVE = "gm";
        window.switchChanged(List.of(owner, ben));

        assertEquals(List.of(Arrays.toString(NativeCrewPackets.close().getBytes()),
                Arrays.toString(NativeCrewPackets.welcome(NativeCrewPackets.PROTOCOL, false).getBytes())),
                crewPackets().stream().map(Arrays::toString).toList());
        assertFalse(window.watching(owner));
        assertFalse(windowOpen.get(), "the CLOSE it sent closed the window");
        verify(benClient, never()).sendPacket(any());

        clearInvocations(client);
        config.CREW_WINDOW_NATIVE = "all";
        window.switchChanged(List.of(owner, ben));
        assertEquals(List.of(Arrays.toString(NativeCrewPackets.welcome(NativeCrewPackets.PROTOCOL, true).getBytes())),
                crewPackets().stream().map(Arrays::toString).toList());
    }

    // ---- native pages (protocol 2, Step 7) ---------------------------------------------------------

    @Test
    void actionsShowsTheNativeActionsPageAndItsReplyActsAndClosesThePage() {
        hadrik();
        when(manager.followAll(owner)).thenReturn(new RosterResult.Ok(""));
        window.install();
        openWindow();

        act(Verb.ACTIONS, "", "");

        NativeTalkPage page = lastPage();
        assertEquals(NativeTalkPage.Kind.MENU, page.kind());
        assertEquals("What should my crew do?\r\n", page.text());
        assertEquals(List.of("Remember this lineup", "Everyone: follow me", "Everyone: wait here", "Everyone: gather up",
                "Everyone: rebuff", "Formation...", "My lineups..."), NativePages.labels(page));
        assertTrue(NpcTalks.talks(sent()).isEmpty(), "never an NPC window");
        clearInvocations(client);

        NativeCrewWindow.receive(client, owner,
                new PageReply(page.stamp(), 1, NativePages.selection(page, "Everyone: follow me")), world);

        verify(manager).followAll(owner);
        assertArrayEquals(NativeCrewPackets.pageClose(page.stamp()).getBytes(), last());
        assertTrue(NpcTalks.talks(sent()).isEmpty(), "the answer is pink text, never the NPC Crew window");
        assertFalse(LivingTalk.nativeActive(owner));
    }

    @Test
    void aReplyToAPageNoWindowShowsStartsNoWatch() {
        hadrik();
        window.install();
        openWindow();
        act(Verb.ACTIONS, "", "");
        NativeTalkPage page = lastPage();
        NativeCrewWindow.receive(client, owner, new Close(), world);
        assertFalse(window.watching(owner));

        // the DLL ends a PAGE that arrived after X or U with Close (255)
        NativeCrewWindow.receive(client, owner, new PageReply(page.stamp(), 255, -1), world);
        assertFalse(window.watching(owner), "the Close of a page no window shows is no open window");
        NativeCrewWindow.receive(client, owner,
                new PageReply(page.stamp(), 1, NativePages.selection(page, "Remember this lineup")), world);
        assertFalse(window.watching(owner), "a reply no native talk waits for is no open window");
        verify(manager, never()).saveLineup(any());
    }

    @Test
    void aPagesCloseInAWindowThatStaysOpenWatchesItAgainAfterAnIdleHalfHour() {
        hadrik();
        window.install();
        openWindow();
        act(Verb.ACTIONS, "", "");
        NativeTalkPage page = lastPage();
        now.addAndGet(NativeCrewWindow.IDLE_MS + 1);
        window.watchOpenWindows();
        assertFalse(window.watching(owner));

        NativeCrewWindow.receive(client, owner, new PageReply(page.stamp(), 255, -1), world);

        assertTrue(window.watching(owner), "the window is still open: its page's Close watches it again");
        assertArrayEquals(NativeCrewPackets.pageClose(page.stamp()).getBytes(), last());
    }

    @Test
    void aWindowClosedSinceItsOpenIsSentNoPageButTheNpcWindow() {
        hadrik();
        window.install();
        openWindow();
        NativeCrewWindow.receive(client, owner, new Close(), world);
        clearInvocations(client);

        menus.crews().nativeActions(owner); // a native page asked for after the window closed

        assertTrue(crewPages().isEmpty(), "no PAGE for a window that is not open");
        assertFalse(LivingTalk.nativeActive(owner));
        assertEquals(1, NpcTalks.talks(sent()).size(), "the NPC window stands in for it");
    }

    @Test
    void aStoreReadPageForAWindowClosedMeanwhileIsShownAsTheNpcWindow() {
        hadrik();
        when(manager.namedLineups(owner)).thenReturn(Map.of());
        List<Runnable> companionOps = new ArrayList<>();
        CompanionExecutor queued = DirectExecutor.queued(companionOps);
        when(world.executor()).thenReturn(queued);
        window.install();
        hello();
        NativeCrewWindow.receive(client, owner, new Open(), world);
        companionOps.clear();
        act(Verb.ACTIONS, "", "");
        NativeTalkPage page = lastPage();
        NativeCrewWindow.receive(client, owner,
                new PageReply(page.stamp(), 1, NativePages.selection(page, "My lineups...")), world);
        NativeCrewWindow.receive(client, owner, new Close(), world); // closed before the store answered
        clearInvocations(client);

        companionOps.forEach(Runnable::run);

        assertTrue(crewPages().isEmpty(), "no PAGE for a window that is not open");
        List<Talk> npc = NpcTalks.talks(sent());
        assertEquals(1, npc.size(), "the page the click asked for, as an NPC window");
        assertTrue(npc.get(0).text().contains("The lineups I keep:"), npc.get(0).text());
    }

    @Test
    void aStoreReadPageForAClosedWindowWaitsForAnOpenNpcWindowAsTheNpcPageDoes() {
        hadrik();
        when(manager.namedLineups(owner)).thenReturn(Map.of());
        List<Runnable> companionOps = new ArrayList<>();
        CompanionExecutor queued = DirectExecutor.queued(companionOps);
        when(world.executor()).thenReturn(queued);
        window.install();
        hello();
        NativeCrewWindow.receive(client, owner, new Open(), world);
        companionOps.clear();
        act(Verb.ACTIONS, "", "");
        NativeTalkPage page = lastPage();
        NativeCrewWindow.receive(client, owner,
                new PageReply(page.stamp(), 1, NativePages.selection(page, "My lineups...")), world);
        NativeCrewWindow.receive(client, owner, new Close(), world);
        LivingTalk.open(owner, 9000000).ok("a stock window the player opened").show();
        clearInvocations(client);

        companionOps.forEach(Runnable::run);

        verify(client, never()).sendPacket(any()); // nothing over the NPC window that waits, as on the NPC path
    }

    @Test
    void theRosterOpensTheNativeWindowOnAProtocolTwoClientAndTheNpcWindowOtherwise() {
        hadrik();
        hello();

        menus.rosterBook(owner);

        assertArrayEquals(NativeCrewPackets.open().getBytes(), crewPackets().get(0), "the client is asked to open");
        assertTrue(NpcTalks.talks(sent()).isEmpty(), "no NPC Crew window");
        verify(owner).sendPacket(org.mockito.ArgumentMatchers.argThat(packet -> Arrays.equals(packet.getBytes(),
                tools.PacketCreator.enableActions().getBytes())));

        clearInvocations(client);
        config.CREW_WINDOW_NATIVE = "off";
        menus.rosterBook(owner);
        assertTrue(crewPackets().isEmpty(), "switched off: no OPEN");
        assertEquals(1, NpcTalks.talks(sent()).size(), "the NPC Crew window");

        LivingTalk.forget(owner);
        clearInvocations(client);
        config.CREW_WINDOW_NATIVE = "all";
        protocol.set(1); // the trial DLL: its More opened the NPC Crew window, so the Roster stays the NPC one
        menus.rosterBook(owner);
        assertTrue(crewPackets().isEmpty());
        assertEquals(1, NpcTalks.talks(sent()).size());
    }

    @Test
    void aHelloBeforeTheWorldStartedLeavesTheRosterTheNpcWindowUntilTheWorldTellsItAgain() {
        hadrik();
        NativeCrewWindow.receive(client, owner, new Hello(NativeCrewPackets.PROTOCOL, 20261012), null);
        assertArrayEquals(NativeCrewPackets.welcome(NativeCrewPackets.PROTOCOL, false).getBytes(), last());
        clearInvocations(client);

        menus.rosterBook(owner);
        assertTrue(crewPackets().isEmpty(), "no OPEN a client told \"not enabled\" would ignore");
        assertEquals(1, NpcTalks.talks(sent()).size(), "the NPC Crew window instead");

        LivingTalk.forget(owner);
        clearInvocations(client);
        window.started(List.of(owner)); // the living world is up now
        assertArrayEquals(NativeCrewPackets.welcome(NativeCrewPackets.PROTOCOL, true).getBytes(), last());

        clearInvocations(client);
        menus.rosterBook(owner);
        assertArrayEquals(NativeCrewPackets.open().getBytes(), last());
        assertTrue(NpcTalks.talks(sent()).isEmpty());
    }

    @Test
    void aHelloThatRacedTheWorldsStartReadsTheWorldAgainAndIsEnabled() {
        hadrik();
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        // the first read is before the world was there; the world starts before the HELLO reads it again
        NativeCrewWindow.receiveFrom(client, owner, new Hello(NativeCrewPackets.PROTOCOL, 20261012),
                () -> reads.getAndIncrement() == 0 ? null : world);

        assertArrayEquals(NativeCrewPackets.welcome(NativeCrewPackets.PROTOCOL, true).getBytes(), last());
        assertTrue(windowEnabled.get());
    }

    @Test
    void theEnabledBitTurnsOnOnlyWithTheWelcomeThatSaysSo() {
        hadrik();
        NativeCrewWindow.receive(client, owner, new Hello(NativeCrewPackets.PROTOCOL, 20261012), null);
        clearInvocations(client);
        List<Runnable> packetThread = new ArrayList<>();
        doAnswer(inv -> {
            packetThread.add(inv.getArgument(0)); // held: a Roster click is handled on this thread first
            return null;
        }).when(client).runOnIoThread(any());

        window.started(List.of(owner));
        menus.rosterBook(owner);
        assertTrue(crewPackets().isEmpty(), "no OPEN before the WELCOME that enables the client");
        assertEquals(1, NpcTalks.talks(sent()).size(), "the NPC Crew window instead");

        packetThread.forEach(Runnable::run);
        assertArrayEquals(NativeCrewPackets.welcome(NativeCrewPackets.PROTOCOL, true).getBytes(), last());
        assertTrue(windowEnabled.get());
    }

    @Test
    void theWatchersCloseTurnsTheBitOffBeforeItGoesOut() {
        hadrik();
        config.CREW_WINDOW_NATIVE = "gm";
        when(owner.isGM()).thenReturn(true);
        openWindow();
        when(owner.isGM()).thenReturn(false);
        List<Runnable> packetThread = new ArrayList<>();
        doAnswer(inv -> {
            packetThread.add(inv.getArgument(0)); // held: a Roster click comes in before the CLOSE goes out
            return null;
        }).when(client).runOnIoThread(any());

        window.watchOpenWindows();

        assertFalse(windowEnabled.get(), "the Roster opens the NPC Crew window, not a window being closed");
        assertEquals(1, packetThread.size(), "the CLOSE waits on the packet thread");
    }

    @Test
    void aWelcomeQueuedBeforeTheWatchersCloseNeverLeavesTheBitOn() {
        hadrik();
        config.CREW_WINDOW_NATIVE = "gm";
        when(owner.isGM()).thenReturn(true);
        openWindow();
        when(owner.isGM()).thenReturn(false);
        List<Runnable> packetThread = new ArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean flipped = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(inv -> {
            if (flipped.compareAndSet(false, true)) {
                // "!world crewwindow off" then "all" land after the watcher cleared the bit, before its CLOSE is queued
                config.CREW_WINDOW_NATIVE = "off";
                window.switchChanged(List.of(owner));
                config.CREW_WINDOW_NATIVE = "all";
                window.switchChanged(List.of(owner));
            }
            packetThread.add(inv.getArgument(0));
            return null;
        }).when(client).runOnIoThread(any());

        window.watchOpenWindows();
        packetThread.forEach(Runnable::run);

        assertEquals(NativeCrewPackets.TYPE_CLOSE, last()[2], "the watcher's CLOSE went out last");
        assertFalse(windowEnabled.get(), "after the last CLOSE the client is disabled, and so is the bit");
    }

    @Test
    void everyCloseTheServerSendsLeavesTheRosterTheNpcWindow() {
        hadrik();
        // the watcher's CLOSE: the character lost what the switch asks for while its window was open
        config.CREW_WINDOW_NATIVE = "gm";
        when(owner.isGM()).thenReturn(true);
        openWindow();
        assertTrue(windowEnabled.get());
        when(owner.isGM()).thenReturn(false);
        window.watchOpenWindows();
        assertFalse(windowEnabled.get(), "the watcher's CLOSE disabled the client");

        // the CLOSE that answers an OPEN the switch no longer allows
        windowEnabled.set(true);
        NativeCrewWindow.receive(client, owner, new Open(), world);
        assertFalse(windowEnabled.get(), "the OPEN's CLOSE disabled the client");

        // the CLOSE with the living world off
        windowEnabled.set(true);
        NativeCrewWindow.receive(client, owner, new Open(), null);
        assertFalse(windowEnabled.get(), "the world-off CLOSE disabled the client");

        LivingTalk.forget(owner);
        clearInvocations(client);
        when(owner.isGM()).thenReturn(true);
        menus.rosterBook(owner);
        assertTrue(crewPackets().isEmpty(), "no OPEN a disabled client would ignore");
        assertEquals(1, NpcTalks.talks(sent()).size());
    }

    @Test
    void theRosterOverAnOpenWindowWhoseWatchWentIdleWatchesItAgainWithAFreshSnapshot() {
        hadrik();
        openWindow();
        now.addAndGet(NativeCrewWindow.IDLE_MS + 1);
        window.watchOpenWindows();
        assertFalse(window.watching(owner));
        clearInvocations(client);

        menus.rosterBook(owner);

        assertTrue(window.watching(owner));
        assertEquals(NativeCrewPackets.TYPE_SNAPSHOT, last()[2], "a fresh snapshot, never an OPEN the client ignores");
        assertEquals(1, crewPackets().size());
    }

    @Test
    void anActionsPageThatFailsIsLoggedAndNeverThrownIntoThePacketHandler() {
        hadrik();
        window.install();
        openWindow();
        when(owner.getParty()).thenThrow(new IllegalStateException("the party went away meanwhile"));

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> NativeCrewWindow.receive(client, owner,
                new Action(Verb.ACTIONS, 0, "", 0, "", 0), world));
        assertFalse(LivingTalk.nativeActive(owner));
    }

    @Test
    void aPageReplyRunsOnThePlayersPacketThreadNotOnCompanionOps() {
        hadrik();
        List<Runnable> companionOps = new ArrayList<>();
        CompanionExecutor queued = DirectExecutor.queued(companionOps);
        when(world.executor()).thenReturn(queued);
        when(manager.saveLineup(owner)).thenReturn(new RosterResult.Ok("Remembered."));
        window.install();
        hello();
        NativeCrewWindow.receive(client, owner, new Open(), world);
        companionOps.clear();
        clearInvocations(client);
        act(Verb.ACTIONS, "", "");
        NativeTalkPage page = lastPage();
        assertTrue(companionOps.isEmpty(), "the page is drawn on the packet thread");

        NativeCrewWindow.receive(client, owner,
                new PageReply(page.stamp(), 1, NativePages.selection(page, "Remember this lineup")), world);

        assertArrayEquals(NativeCrewPackets.pageClose(page.stamp()).getBytes(), last(), "answered at once");
        verify(manager, never()).saveLineup(any()); // the roster write itself waits for companion-ops
        companionOps.forEach(Runnable::run);
        verify(manager).saveLineup(owner);
        verify(owner).dropMessage(5, "Remembered.");
    }

    @Test
    void aProtocolOneClientNeverGetsAPageAndItsActionsAndRepliesAreIgnored() {
        hadrik();
        add(row("tobi", RosterState.ACTIVE), "Tobi", 902, 30, Job.CLERIC);
        ContractService contracts = mock(ContractService.class);
        when(contracts.benchWalkOutAfter(ANN, personas.get("hadrik"))).thenReturn("an hour");
        when(world.contracts()).thenReturn(contracts);
        window.install();
        NativeCrewWindow.receive(client, owner, new Hello(1, 20261012), world);
        assertArrayEquals(NativeCrewPackets.welcome(1, true).getBytes(), last());
        NativeCrewWindow.receive(client, owner, new Open(), world);
        clearInvocations(client);

        act(Verb.ACTIONS, "", "");
        NativeCrewWindow.receive(client, owner, new PageReply(1, 1, 1_000), world);
        verify(client, never()).sendPacket(any());

        act(Verb.BENCH, "hadrik", ""); // the bench warning: the NPC Yes/No, as before
        assertEquals(YES_NO, lastTalk().msgType());
        assertTrue(LivingTalk.respond(client, YES_NO, (byte) 1, Packets.buildInPacket(out -> { })));
        verify(roster).bench(owner, personas.get("hadrik"));
        assertTrue(crewPages().isEmpty(), "never a PAGE at protocol 1");
        assertTrue(crewPackets().stream().noneMatch(bytes -> bytes[2] == NativeCrewPackets.TYPE_PAGE_CLOSE));

        LivingTalk.forget(owner);
        act(Verb.MORE, "", "");
        assertTrue(lastTalk().text().startsWith("#eParty "), "MORE: the NPC Crew window");
    }

    @Test
    void benchAtProtocolTwoAsksTheBenchWarningNatively() {
        hadrik();
        add(row("tobi", RosterState.ACTIVE), "Tobi", 902, 30, Job.CLERIC);
        ContractService contracts = mock(ContractService.class);
        when(contracts.benchWalkOutAfter(ANN, personas.get("hadrik"))).thenReturn("an hour");
        when(world.contracts()).thenReturn(contracts);
        window.install();
        openWindow();

        act(Verb.BENCH, "hadrik", "");

        NativeTalkPage ask = lastPage();
        assertEquals(NativeTalkPage.Kind.YES_NO, ask.kind());
        assertTrue(ask.text().startsWith("Hadrik won't sit at camp for long: after an hour on the bench"), ask.text());
        assertTrue(NpcTalks.talks(sent()).isEmpty());
        verify(roster, never()).bench(any(), any());

        NativeCrewWindow.receive(client, owner, new PageReply(ask.stamp(), 1, -1), world);

        verify(roster).bench(owner, personas.get("hadrik"));
        verify(owner).dropMessage(5, "Hadrik heads back to camp to train.");
        List<byte[]> crew = crewPackets();
        assertEquals(NativeCrewPackets.TYPE_SNAPSHOT, crew.get(crew.size() - 2)[2], "the answer's fresh snapshot");
        assertArrayEquals(NativeCrewPackets.pageClose(ask.stamp()).getBytes(), last());
    }

    @Test
    void swapAtProtocolTwoAsksTheBenchWarningNatively() {
        hadrik();
        add(row("mira", RosterState.BENCHED), "Mira", 901, 28, Job.HUNTER);
        ContractService contracts = mock(ContractService.class);
        when(contracts.benchWalkOutAfter(ANN, personas.get("hadrik"))).thenReturn("an hour");
        when(world.contracts()).thenReturn(contracts);
        window.install();
        openWindow();

        act(Verb.SWAP, "hadrik", "mira");
        assertEquals(NativeTalkPage.Kind.YES_NO, lastPage().kind());
        NativeCrewWindow.receive(client, owner, new PageReply(lastPage().stamp(), 0, -1), world); // No

        verify(roster, never()).swap(any(), any(), any());
        assertArrayEquals(NativeCrewPackets.pageClose(lastPage().stamp()).getBytes(), last());
    }

    @Test
    void aReplyToAnOlderPageOrAfterTheWindowClosedDoesNothing() {
        hadrik();
        window.install();
        openWindow();
        act(Verb.ACTIONS, "", "");
        NativeTalkPage page = lastPage();
        int follow = NativePages.selection(page, "Everyone: follow me");
        clearInvocations(client);

        NativeCrewWindow.receive(client, owner, new PageReply(page.stamp() - 1, 1, follow), world);
        verify(client, never()).sendPacket(any());
        assertTrue(LivingTalk.nativeActive(owner), "the newer page stays");

        NativeCrewWindow.receive(client, owner, new Close(), world);
        assertFalse(LivingTalk.nativeActive(owner), "the window took its page with it");
        NativeCrewWindow.receive(client, owner, new PageReply(page.stamp(), 1, follow), world);

        verify(manager, never()).followAll(any());
        verify(client, never()).sendPacket(any());
    }

    @Test
    void moreStillOpensTheNpcCrewWindowAtProtocolTwo() {
        hadrik();
        window.install();
        openWindow();

        act(Verb.MORE, "", "");

        assertTrue(lastTalk().text().startsWith("#eParty 3/6#n"), lastTalk().text());
        assertTrue(crewPages().isEmpty());
    }

    @Test
    void turningTheSwitchOffEndsTheNativePage() {
        hadrik();
        window.install();
        openWindow();
        act(Verb.ACTIONS, "", "");
        assertTrue(LivingTalk.nativeActive(owner));
        clearInvocations(client);

        config.CREW_WINDOW_NATIVE = "off";
        window.switchChanged(List.of(owner));

        assertFalse(LivingTalk.nativeActive(owner));
        assertEquals(List.of(Arrays.toString(NativeCrewPackets.close().getBytes()),
                Arrays.toString(NativeCrewPackets.welcome(2, false).getBytes())),
                crewPackets().stream().map(Arrays::toString).toList());
    }

    @Test
    void theWindowDrawsPagesOnlyForAProtocolTwoConnectionInTheWorldThatTheSwitchAllows() {
        hello();
        assertFalse(window.draws(owner), "HELLO alone: the window is not open");
        windowOpen.set(true); // its OPEN
        assertTrue(window.draws(owner));
        when(owner.isLoggedinWorld()).thenReturn(false);
        assertFalse(window.draws(owner), "the Cash Shop");
        when(owner.isLoggedinWorld()).thenReturn(true);
        config.CREW_WINDOW_NATIVE = "off";
        assertFalse(window.draws(owner));
        config.CREW_WINDOW_NATIVE = "all";
        protocol.set(1);
        assertFalse(window.draws(owner));
        window.page(owner, new NativeTalkPage(5, NativeTalkPage.Kind.OK, 0, "x", List.of()));
        window.close(owner, 5);
        verify(client, never()).sendPacket(any());
    }

    // ---- helpers -----------------------------------------------------------------------------------

    /** Every PAGE sent since the last clear, read back as the DLL reads it. */
    private List<NativeTalkPage> crewPages() {
        List<NativeTalkPage> out = new ArrayList<>();
        for (byte[] bytes : crewPackets()) {
            if (bytes[2] == NativeCrewPackets.TYPE_PAGE) {
                out.add(readPage(bytes));
            }
        }
        return out;
    }

    private NativeTalkPage lastPage() {
        List<NativeTalkPage> pages = crewPages();
        assertFalse(pages.isEmpty(), "no PAGE was sent");
        return pages.get(pages.size() - 1);
    }

    /** PAGE: int stamp, byte kind, byte flags, string text, short entryCount, entries (byte type, int selection, string). */
    private static NativeTalkPage readPage(byte[] bytes) {
        ByteBuffer in = ByteBuffer.wrap(bytes, 3, bytes.length - 3).order(ByteOrder.LITTLE_ENDIAN);
        int stamp = in.getInt();
        NativeTalkPage.Kind kind = NativeTalkPage.Kind.values()[in.get() & 0xFF];
        int flags = in.get() & 0xFF;
        String text = string(in);
        int count = in.getShort();
        List<NativeTalkPage.Entry> entries = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            int type = in.get();
            int selection = in.getInt();
            String label = string(in);
            entries.add(type == 1 ? NativeTalkPage.Entry.option(selection, label) : NativeTalkPage.Entry.heading(label));
        }
        assertFalse(in.hasRemaining(), "bytes after the last entry");
        return new NativeTalkPage(stamp, kind, flags, text, entries);
    }

    private static String string(ByteBuffer in) {
        byte[] text = new byte[in.getShort()];
        in.get(text);
        return new String(text, StandardCharsets.US_ASCII);
    }

    /** HELLO with this server's protocol, then OPEN; the invocations so far are cleared but the window's first snapshot. */
    private void openWindow() {
        hello();
        NativeCrewWindow.receive(client, owner, new Open(), world);
        assertEquals(NativeCrewPackets.TYPE_SNAPSHOT, last()[2]);
        assertTrue(window.watching(owner));
    }

    private void hello() {
        NativeCrewWindow.receive(client, owner, new Hello(NativeCrewPackets.PROTOCOL, 20261012), world);
        clearInvocations(client);
    }

    /** A click stamped as the current snapshot stamps it. */
    private void act(Verb verb, String a, String b) {
        Snapshot snapshot = window.snapshot(owner);
        NativeCrewWindow.receive(client, owner, new Action(verb, snapshot.ownerVersion(), a, stamp(snapshot, a), b,
                stamp(snapshot, b)), world);
    }

    private static int stamp(Snapshot snapshot, String personaId) {
        List<Row> all = new ArrayList<>(snapshot.party());
        all.addAll(snapshot.bench());
        return all.stream().filter(row -> row.personaId().equals(personaId)).mapToInt(Row::rowVersion).findFirst().orElse(0);
    }

    /** The card of {@code entry}, opened as a double-click opens it, offers exactly what {@code flags} say. */
    private void assertCardOffers(RosterEntry entry, int flags) {
        LivingTalk.forget(owner);
        menus.card(owner, entry);
        List<String> labels = lastTalk().labels();
        String who = entry.personaId() + " " + labels;
        assertEquals((flags & NativeCrewPackets.CALL) != 0, labels.contains("Come join me"), who);
        assertEquals((flags & NativeCrewPackets.BENCH) != 0, labels.contains("Take a break at camp"), who);
        assertEquals((flags & NativeCrewPackets.SWAP_OUT) != 0, labels.contains("Let someone take your place..."), who);
        assertEquals((flags & NativeCrewPackets.SWAP_IN) != 0, labels.contains("Swap in for..."), who);
        LivingTalk.forget(owner);
    }

    /** CrewScreensTest's crew: every seat state, a guest, a farming bench companion and one away. */
    private void fullCrew() {
        hadrik();
        add(new RosterEntry(ANN, "tobi", RosterState.TRAVELLING, Assignment.TRAIN, 0, 0, ANT_TUNNEL, 0L, 0L, 0L, 0L, 0L, 0L, 0,
                null, System.currentTimeMillis() + 40_000L, 0, 0L), "Tobi", 902, 30, Job.CLERIC);
        add(row("vex", RosterState.HOLD), "Vex", 903, 22, Job.MAGICIAN);
        add(row("dorran", RosterState.PARKED), "Dorran", 904, 35, Job.SPEARMAN);
        add(new RosterEntry(ANN, "mira", RosterState.BENCHED, Assignment.FARM_ITEM, STOLEN_FENCE, 200, ANT_TUNNEL, 4_872L,
                3_120L, 0L, 0L, 0L, 0L, 0, null), "Mira", 901, 28, Job.HUNTER);
        when(store.haul(ANN, "mira")).thenReturn(List.of(new HaulItem(STOLEN_FENCE, 143), new HaulItem(4000019, 5)));
        add(row("olwen", RosterState.AWAY), "Olwen", 905, 26, Job.BANDIT);
        guestInParty("Kira", KIRA, 25, Job.ASSASSIN);
    }

    /** Hadrik, out with Ann and loaded: Lv31 Fighter in Perion at 812/900 HP. */
    private RosterEntry hadrik() {
        RosterEntry entry = add(row("hadrik", RosterState.ACTIVE), "Hadrik", 900, 31, Job.FIGHTER);
        when(roster.companion(entry)).thenReturn(hadrikBot);
        return entry;
    }

    private RosterEntry add(RosterEntry entry, String name, int characterId, int level, Job job) {
        Persona persona = mock(Persona.class);
        when(persona.id()).thenReturn(entry.personaId());
        when(persona.name()).thenReturn(name);
        when(content.persona(entry.personaId())).thenReturn(persona);
        personas.put(entry.personaId(), persona);
        when(residents.characterIdOf(entry.personaId())).thenReturn(characterId);
        when(store.characterLevelAndJob(characterId)).thenReturn(new int[] {level, job.getId()});
        rows.add(entry);
        views.invalidate(ANN);
        return entry;
    }

    /** A citizen guest in Ann's party, on the GUEST seat charged to her. */
    private void guestInParty(String name, int characterId, int level, Job job) {
        Character citizen = mock(Character.class);
        when(citizen.getName()).thenReturn(name);
        when(citizen.getLevel()).thenReturn(level);
        when(citizen.getJob()).thenReturn(job);
        MapleMap henesys = mock(MapleMap.class);
        when(henesys.getMapName()).thenReturn("Henesys");
        when(citizen.getMap()).thenReturn(henesys);
        when(citizen.getHp()).thenReturn(500);
        when(citizen.getCurrentMaxHp()).thenReturn(500);
        PartyCharacter annRow = mock(PartyCharacter.class);
        when(annRow.getId()).thenReturn(ANN);
        PartyCharacter guestRow = mock(PartyCharacter.class);
        when(guestRow.getId()).thenReturn(characterId);
        when(guestRow.getPlayer()).thenReturn(citizen);
        Party party = mock(Party.class);
        when(party.getMembers()).thenReturn(List.of(annRow, guestRow));
        when(owner.getParty()).thenReturn(party);
        when(slots.reservations(ANN)).thenReturn(List.of(new CompanionSlots.Reservation(ANN,
                CompanionSlots.characterKey(characterId), CompanionSlots.Kind.GUEST, Long.MAX_VALUE, 1L)));
    }

    private void pick(String label) {
        int selection = lastTalk().selection(label);
        assertTrue(LivingTalk.respond(client, MENU, (byte) 1, Packets.buildInPacket(out -> out.writeInt(selection))));
    }

    private List<Packet> sent() {
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(client, atLeastOnce()).sendPacket(captor.capture());
        return captor.getAllValues();
    }

    /** Every 0x17A packet sent since the last clear, as bytes. */
    private List<byte[]> crewPackets() {
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(client, org.mockito.Mockito.atLeast(0)).sendPacket(captor.capture());
        List<byte[]> out = new ArrayList<>();
        for (Packet packet : captor.getAllValues()) {
            byte[] bytes = packet.getBytes();
            if (bytes.length >= 3 && ((bytes[0] & 0xFF) | (bytes[1] & 0xFF) << 8) == SEND_CREW_WINDOW) {
                out.add(bytes);
            }
        }
        return out;
    }

    /** The last 0x17A packet sent. */
    private byte[] last() {
        List<byte[]> crew = crewPackets();
        assertFalse(crew.isEmpty(), "no 0x17A packet was sent");
        return crew.get(crew.size() - 1);
    }

    private Talk lastTalk() {
        List<Talk> talks = NpcTalks.talks(sent());
        return talks.get(talks.size() - 1);
    }

    private static RosterEntry row(String personaId, RosterState state) {
        return new RosterEntry(ANN, personaId, state, Assignment.TRAIN, 0, 0, ANT_TUNNEL, 0L, 0L, 0L, 0L, 0L, 0L, 0, null);
    }
}
