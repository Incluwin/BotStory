package server.living.roster.ui;

import client.Character;
import client.Client;
import net.packet.Packet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.resident.LivingTalk;
import server.living.resident.NativeTalkPage;
import server.living.resident.NativeTalkRenderer;
import server.living.roster.CompanionManager;
import server.living.roster.CompanionSlots;
import server.living.roster.CompanionView;
import server.living.roster.RosterResult;
import server.living.roster.RosterService;
import server.living.roster.ui.NativeCrewPackets.Action;
import server.living.roster.ui.NativeCrewPackets.Close;
import server.living.roster.ui.NativeCrewPackets.Hello;
import server.living.roster.ui.NativeCrewPackets.Op;
import server.living.roster.ui.NativeCrewPackets.Open;
import server.living.roster.ui.NativeCrewPackets.PageReply;
import server.living.roster.ui.NativeCrewPackets.Row;
import server.living.roster.ui.NativeCrewPackets.Snapshot;
import server.living.roster.ui.NativeCrewPackets.Verb;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.LongSupplier;

import static server.living.roster.ui.NativeCrewPackets.BENCH;
import static server.living.roster.ui.NativeCrewPackets.CALL;
import static server.living.roster.ui.NativeCrewPackets.CAN_COME;
import static server.living.roster.ui.NativeCrewPackets.COMMITTED;
import static server.living.roster.ui.NativeCrewPackets.GUEST;
import static server.living.roster.ui.NativeCrewPackets.HP_UNKNOWN;
import static server.living.roster.ui.NativeCrewPackets.OUT;
import static server.living.roster.ui.NativeCrewPackets.PAGES_PROTOCOL;
import static server.living.roster.ui.NativeCrewPackets.PROTOCOL;
import static server.living.roster.ui.NativeCrewPackets.SOMEONE_OUT;
import static server.living.roster.ui.NativeCrewPackets.SWAP_IN;
import static server.living.roster.ui.NativeCrewPackets.SWAP_OUT;
import static server.living.roster.ui.NativeCrewPackets.USUAL_CALLABLE;

/**
 * The native Crew window (docs/BOT_MANAGEMENT_P10_PLAN.md, P10): the server half of the mouse-driven crew window a client
 * with the P10 DLL draws. It replaces nothing; every row, flag and click is the NPC-dialog Crew window's and the cards'
 * own code (CompanionCrewScreens, CompanionCardScreens), so both windows always say and do the same.
 *
 * <p>Safety: a connection is sent CREW_WINDOW (0x17A) only after its client said HELLO; it is served at {@link
 * #negotiated} = min(its HELLO's protocol, {@link NativeCrewPackets#PROTOCOL}), which WELCOME carries ({@link
 * Client#getCrewWindowProtocol} keeps the client's own). WELCOME says {@code enabled} only when
 * living_world.CREW_WINDOW_NATIVE allows the character ("gm": a GM) and the living world runs. Protocol 1 is the crew
 * view; protocol 2 adds native pages: PAGE and PAGE_CLOSE go only to a connection at 2, and only it may send
 * PAGE_REPLY or the verb ACTIONS (a protocol-1 connection's are ignored).</p>
 *
 * <p>Native pages (Step 7): this window draws {@link LivingTalk}'s native talks ({@link NativeTalkRenderer}, registered
 * by {@link #install}). ACTIONS shows the NPC Crew window's actions as a native page (CompanionCrewScreens), and every
 * page it leads to is a native talk answered through LivingTalk's own reply code ({@link LivingTalk#respondNative}) on
 * the player's packet thread, as an NPC reply is. MORE still opens the NPC Crew window at every protocol.</p>
 *
 * <p>A SNAPSHOT is read on companion-ops (the view cache, as a render) and sent on the owner's packet thread: on OPEN,
 * after each click's answer, and while the window is open whenever its bytes changed, looked at every {@link #WATCH_MS}
 * (HP, map, the time left on the way and errands are read at render time, so they are polled, not signalled). Watching
 * stops on CLOSE, a logout or channel change, the switch no longer allowing the character (CLOSE is sent), or {@link
 * #IDLE_MS} without an OPEN or a click.</p>
 *
 * <p>A click (ACTION) is the player's own click, answered on their packet thread as an NPC click is: it is checked
 * against the stamps the snapshot carried (a row's CompanionViews version, or the owner's) and the flags the row's card
 * offers now; a stale or refused click answers "Things changed meanwhile." in pink with a fresh snapshot and does
 * nothing else. Otherwise it runs the card's or the Crew window's own action: the follow-up windows (the swap picker
 * for a full party, the bench warning's Yes/No, the leader cap's OK) open exactly as from the card, and the action's
 * line is pink text, as the Crew window answers, followed by a fresh snapshot.</p>
 */
public final class NativeCrewWindow implements NativeTalkRenderer {
    private static final Logger log = LoggerFactory.getLogger(NativeCrewWindow.class);
    /** How often the open windows are read again (on companion-ops) and sent when they changed. */
    public static final long WATCH_MS = 1_000L;
    /** A window with no OPEN or click for this long is no longer watched; its next click watches it again. */
    static final long IDLE_MS = 30L * 60_000L;
    /** A guest row's persona id: this, then the citizen's character id ("guest:5678"). */
    static final String GUEST_PREFIX = "guest:";

    private final LivingWorld world;
    private final CompanionMenus menus;
    private final LongSupplier clock;
    /** Owner character id -> the window open on their connection. */
    private final Map<Integer, Watch> watches = new ConcurrentHashMap<>();
    /** A watch pass is queued on companion-ops: the timer never queues a second behind it. */
    private final AtomicBoolean watchQueued = new AtomicBoolean();

    public NativeCrewWindow(LivingWorld world) {
        this(world, new CompanionMenus(world), System::currentTimeMillis);
    }

    NativeCrewWindow(LivingWorld world, CompanionMenus menus, LongSupplier clock) {
        this.world = world;
        this.menus = menus;
        this.clock = clock;
    }

    /** One open window: its owner and connection, when it was last opened or clicked, and the snapshot last sent. */
    private static final class Watch {
        final Character owner;
        final Client client;
        volatile long touchedAtMs;
        /** The bytes of the last SNAPSHOT sent; read and written on companion-ops only. */
        byte[] sent;

        Watch(Character owner, Client client, long touchedAtMs) {
            this.owner = owner;
            this.client = client;
            this.touchedAtMs = touchedAtMs;
        }
    }

    /** A clicked row as the click may act on it: still the owner's, unchanged since its snapshot, and its card's flags now. */
    private record Target(Persona persona, RosterEntry entry, CompanionView view, int flags) {
        boolean offers(int flag) {
            return (flags & flag) != 0;
        }
    }

    // ---- packets in ----------------------------------------------------------------------------------

    /**
     * A decoded CREW_WINDOW packet from {@code client} (its character {@code chr}), on its packet thread. HELLO is
     * answered even while the living world is off (WELCOME, not enabled); anything else from a connection that did not
     * say HELLO is dropped with nothing sent back.
     */
    public static void receive(Client client, Character chr, Op op, LivingWorld world) {
        NativeCrewWindow window = world == null ? null : world.crewWindow();
        if (op instanceof Hello hello) {
            hello(client, chr, hello, window);
            return;
        }
        if (negotiated(client) < 1) {
            return; // no HELLO: a client without the DLL never sends this
        }
        if (window == null) {
            client.setCrewWindowOpen(false);
            client.sendPacket(NativeCrewPackets.close()); // the living world is off: no window may be open
            return;
        }
        window.received(client, chr, op);
    }

    /**
     * HELLO: the client's protocol is kept on its Client (from now on it may be sent CREW_WINDOW packets) and WELCOME
     * answers the protocol it is served at ({@link #negotiated}: a later DLL gets this server's) and whether this
     * character may open the window. A protocol of 0 or less is no HELLO and is not answered. HELLO is once per
     * connection (a channel change is a new Client): a repeat is neither answered nor logged.
     */
    private static void hello(Client client, Character chr, Hello hello, NativeCrewWindow window) {
        if (hello.protocol() <= 0 || client.getCrewWindowProtocol() != 0) {
            return;
        }
        client.setCrewWindowProtocol(hello.protocol());
        int served = negotiated(client);
        boolean enabled = window != null && window.allows(chr);
        client.sendPacket(NativeCrewPackets.welcome(served, enabled));
        log.info("[crew-window] HELLO from {}: protocol {}, DLL build {}, served at {}, enabled={}", chr.getName(),
                hello.protocol(), hello.dllBuild(), served, enabled);
    }

    /** The protocol {@code client} is served at: min(its HELLO's, this server's); 0 when it said no HELLO. */
    static int negotiated(Client client) {
        int said = client.getCrewWindowProtocol();
        return said <= 0 ? 0 : Math.min(said, PROTOCOL);
    }

    /** CREW_WINDOW_NATIVE lets this character use the window ("gm": a GM character). */
    boolean allows(Character chr) {
        return world.config().crewWindowNative(chr.isGM());
    }

    private void received(Client client, Character owner, Op op) {
        if (op instanceof Close) {
            client.setCrewWindowOpen(false);
            watches.computeIfPresent(owner.getId(), (id, watch) -> watch.client == client ? null : watch);
            LivingTalk.endNative(owner); // its page went with the window, unanswered
            return;
        }
        if (negotiated(client) < PAGES_PROTOCOL
                && (op instanceof PageReply || op instanceof Action action && action.verb() == Verb.ACTIONS)) {
            return; // protocol 1 has no pages: an op or verb it does not know is ignored
        }
        if (!owner.isLoggedinWorld()) {
            return; // the Cash Shop or MTS: the client closed its window on the way in, and nothing there is acted on
        }
        if (!allows(owner)) {
            watches.remove(owner.getId());
            LivingTalk.endNative(owner);
            client.setCrewWindowOpen(false);
            send(client, NativeCrewPackets.close()); // the switch or the character changed since WELCOME
            return;
        }
        if (op instanceof Open) {
            client.setCrewWindowOpen(true);
        }
        if (!(op instanceof PageReply) || client.isCrewWindowOpen()) {
            // a page's reply (its Close too) touches the watch of the window it is shown in; one from a client whose
            // window has closed is no sign of an open window and starts no watch
            watch(client, owner);
        }
        if (op instanceof Open) {
            refresh(client, owner);
        } else if (op instanceof PageReply reply) {
            // a native page's click, answered by the page's own code on this packet thread, as an NPC reply is; a
            // reply to an older page does nothing (the newer page stays)
            LivingTalk.respondNative(owner, reply.stamp(), (byte) reply.action(), reply.selection());
        } else if (op instanceof Action action && action.verb() == Verb.ACTIONS) {
            // a native page, which waits on no NPC window and is never waited on by one: no NPC click rules; a failure
            // is logged here, never thrown into the packet handler (where it would count as a malformed packet)
            try {
                menus.crews().nativeActions(owner);
            } catch (RuntimeException failure) {
                log.warn("[crew-window] the actions page for {} could not be shown", owner.getName(), failure);
            }
        } else if (op instanceof Action action) {
            // the player's own click, as an NPC click: refused while a window awaits its reply, and the windows it
            // opens are its answer (no click throttle)
            if (!LivingTalk.answerScriptClick(owner, () -> act(client, owner, action))) {
                refresh(client, owner);
            }
        }
    }

    // ---- the verbs -----------------------------------------------------------------------------------

    private void act(Client client, Character owner, Action action) {
        menus.cards().forgetCrewParent(owner); // the cards this click leads to have no Back to the NPC Crew window
        switch (action.verb()) {
            case CARD -> card(client, owner, action.a());
            case MORE -> menus.crew(owner); // the NPC Crew window, in the player's notes
            case CALL -> {
                Target called = target(owner, action.a(), action.aVersion());
                if (called == null || !called.offers(CALL)) {
                    changed(client, owner);
                    return;
                }
                menus.cards().join(owner, called.persona(), called.entry(), quietly(client));
            }
            case BENCH -> {
                Target benched = target(owner, action.a(), action.aVersion());
                if (benched == null || !benched.offers(BENCH)) {
                    changed(client, owner);
                    return;
                }
                menus.cards().takeABreak(owner, benched.persona(), quietly(client), CompanionMenus.Surface.NATIVE);
            }
            case SWAP -> swap(client, owner, action);
            case USUAL -> usual(client, owner, action.ownerVersion());
            case FOLLOW -> order(client, owner, "crew_window.follow", manager -> manager.followAll(owner));
            case WAIT -> order(client, owner, "crew_window.wait", manager -> manager.waitAll(owner));
            case GATHER -> order(client, owner, "crew_window.gather", manager -> manager.gatherAll(owner));
            case REBUFF -> order(client, owner, "crew_window.rebuff", manager -> manager.rebuffAll(owner));
        }
    }

    /**
     * A row's card, as a click on the Crew window's row opens it (ownership read again), but without Back: the native
     * window stays open behind it. A guest's row opens its hire terms, only while that citizen is a guest in the
     * owner's party.
     */
    private void card(Client client, Character owner, String personaId) {
        if (!personaId.startsWith(GUEST_PREFIX)) {
            if (rosterEntry(owner, personaId) == null) {
                changed(client, owner); // only the owner's own rows: a made-up id is never looked up
                return;
            }
            menus.crews().card(owner, personaId);
            return;
        }
        int citizenId = guestId(personaId);
        String key = CompanionSlots.characterKey(citizenId);
        if (citizenId <= 0 || menus.crews().guests(owner).stream().noneMatch(guest -> guest.personaId().equals(key))) {
            changed(client, owner);
            return;
        }
        new HireScreens(world).terms(owner, citizenId, HireScreens.Context.HIRE_LIST);
    }

    /**
     * SWAP a=out b=in: a pick one of the two swap pickers would list. The outgoing one's card ("Let someone take your
     * place...") lists those at camp who can come at once; the incoming one's ("Swap in for...") those out with the
     * owner. Then the pick's own action ({@link CompanionCardScreens#swap}), in the words of the one whose picker it is.
     */
    private void swap(Client client, Character owner, Action action) {
        Target out = target(owner, action.a(), action.aVersion());
        Target in = target(owner, action.b(), action.bVersion());
        if (out == null || in == null || out.persona().id().equals(in.persona().id())) {
            changed(client, owner);
            return;
        }
        String incoming = in.persona().id();
        boolean inPicks = in.offers(SWAP_IN) && out.entry().state() == RosterState.ACTIVE;
        boolean outPicks = out.offers(SWAP_OUT)
                && menus.cards().swapIns(owner).stream().anyMatch(entry -> entry.personaId().equals(incoming));
        if (!inPicks && !outPicks) {
            changed(client, owner);
            return;
        }
        Persona voice = inPicks ? in.persona() : out.persona();
        menus.cards().swap(owner, out.persona(), in.persona(), voice, quietly(client), CompanionMenus.Surface.NATIVE);
    }

    /** "Call my usual lineup", stamped with the owner's version as the Crew window stamps it. */
    private void usual(Client client, Character owner, int stamp) {
        CompanionCrewScreens crews = menus.crews();
        if ((int) menus.version(owner) != stamp || !crews.usualCallable(owner, crews.blocks(owner).bench())) {
            changed(client, owner);
            return;
        }
        menus.commandQuietly(owner, "crew_window.call_usual",
                CompanionCrewScreens.said(menus.manager(), manager -> manager.callUsual(owner)),
                line -> answered(client, owner, line));
    }

    /** "Everyone: ...": offered while someone is out with the owner; an order that went out says nothing of its own. */
    private void order(Client client, Character owner, String name, Function<CompanionManager, RosterResult> order) {
        if (!CompanionCrewScreens.anyoneOut(menus.crews().blocks(owner).party())) {
            changed(client, owner);
            return;
        }
        menus.commandQuietly(owner, name, CompanionCrewScreens.ordered(menus.manager(), order),
                line -> answered(client, owner, line));
    }

    /**
     * The owner's companion {@code personaId} as a click stamped {@code stamp} may act on it: still in their roster, its
     * row version unchanged since the snapshot (taken before the row is read, as a card stamps its options), with the
     * flags its card offers now; null when not.
     */
    private Target target(Character owner, String personaId, int stamp) {
        // the owner's own row first: an id the client made up ("hire:05") never reaches a persona or version lookup
        RosterEntry entry = rosterEntry(owner, personaId);
        if (entry == null || (int) menus.version(owner, personaId) != stamp) {
            return null;
        }
        Persona persona = world.content().persona(personaId);
        if (persona == null) {
            return null;
        }
        CompanionView view = menus.view(owner, entry);
        int flags = rowFlags(owner, persona, view, !menus.cards().swapIns(owner).isEmpty(), !menus.rows(owner, true).isEmpty());
        return new Target(persona, entry, view, flags);
    }

    /** The owner's roster row named exactly {@code personaId} (not released), or null. */
    private RosterEntry rosterEntry(Character owner, String personaId) {
        if (personaId.isEmpty()) {
            return null;
        }
        for (RosterEntry row : menus.rows(owner)) {
            if (row.personaId().equals(personaId) && row.state() != RosterState.RELEASED) {
                return row;
            }
        }
        return null;
    }

    /** The click could not act on what it was made on: pink "Things changed meanwhile.", a fresh snapshot, nothing else. */
    private void changed(Client client, Character owner) {
        owner.dropMessage(5, CompanionMenus.THINGS_CHANGED);
        refresh(client, owner);
    }

    /** A click's answer, on the owner's packet thread: its line in pink (as the Crew window's), then a fresh snapshot. */
    private void answered(Client client, Character owner, String line) {
        if (!line.isEmpty()) {
            owner.dropMessage(5, line);
        }
        refresh(client, owner);
    }

    /** The card's actions run as this window's: on companion-ops, answered by {@link #answered}. */
    private CompanionCardScreens.Commands quietly(Client client) {
        return (owner, persona, name, action) -> menus.commandQuietly(owner, name, action,
                line -> answered(client, owner, line));
    }

    // ---- snapshots and watching ----------------------------------------------------------------------

    private void watch(Client client, Character owner) {
        long now = clock.getAsLong();
        watches.compute(owner.getId(), (id, watch) -> {
            if (watch == null || watch.client != client || watch.owner != owner) {
                return new Watch(owner, client, now);
            }
            watch.touchedAtMs = now;
            return watch;
        });
    }

    /** A fresh SNAPSHOT, read on companion-ops and sent on the owner's packet thread whether or not it changed. */
    private void refresh(Client client, Character owner) {
        world.executor().submit("crew_window.snapshot", () -> {
            Packet snapshot = NativeCrewPackets.snapshot(snapshot(owner));
            Watch watch = watches.get(owner.getId());
            if (watch != null && watch.client == client) {
                watch.sent = snapshot.getBytes();
            }
            client.runOnIoThread(() -> {
                if (allows(owner)) { // never after the CLOSE a switch change sent meanwhile
                    send(client, snapshot);
                }
            });
        });
    }

    /** The timer's hand-over every {@link #WATCH_MS}: one pass over the open windows on companion-ops, never two queued. */
    public void tick() {
        if (watches.isEmpty() || !watchQueued.compareAndSet(false, true)) {
            return;
        }
        world.executor().submit("crew_window.watch", this::watchOpenWindows)
                .whenComplete((done, failure) -> watchQueued.set(false));
    }

    /**
     * Every open window, on companion-ops: read again and sent when its bytes changed; closed (CLOSE) when the switch no
     * longer allows its owner; dropped once its connection is gone (a logout, a channel change, the Cash Shop) or idle.
     */
    void watchOpenWindows() {
        long now = clock.getAsLong();
        for (Watch watch : watches.values()) {
            Character owner = watch.owner;
            Client client = watch.client;
            if (client.getPlayer() != owner || owner.getClient() != client || !owner.isLoggedinWorld()) {
                watches.remove(owner.getId(), watch);
                continue;
            }
            if (!allows(owner)) {
                watches.remove(owner.getId(), watch);
                LivingTalk.endNative(owner);
                client.setCrewWindowOpen(false);
                client.runOnIoThread(() -> send(client, NativeCrewPackets.close()));
                continue;
            }
            if (now - watch.touchedAtMs > IDLE_MS) {
                watches.remove(owner.getId(), watch);
                continue;
            }
            try {
                Packet snapshot = NativeCrewPackets.snapshot(snapshot(owner));
                byte[] bytes = snapshot.getBytes();
                if (!Arrays.equals(bytes, watch.sent)) {
                    watch.sent = bytes;
                    client.runOnIoThread(() -> {
                        if (allows(owner)) {
                            send(client, snapshot);
                        }
                    });
                }
            } catch (RuntimeException failure) {
                log.warn("native Crew window of {} could not be read", owner.getName(), failure);
            }
        }
    }

    /** True while the owner's window is watched (tests; the timer reads {@link #watches} directly). */
    boolean watching(Character owner) {
        return watches.containsKey(owner.getId());
    }

    /**
     * CREW_WINDOW_NATIVE changed at runtime ({@code !world crewwindow}): every online character whose client said HELLO
     * hears WELCOME again, at the protocol it is served at, with what the switch allows it now; one no longer allowed
     * gets CLOSE first, its window is no longer watched and its native page is ended.
     */
    public void switchChanged(Iterable<Character> online) {
        for (Character chr : online) {
            Client client = chr.getClient();
            if (client == null || negotiated(client) < 1) {
                continue;
            }
            boolean enabled = allows(chr);
            if (!enabled) {
                watches.remove(chr.getId());
                LivingTalk.endNative(chr);
                client.setCrewWindowOpen(false);
            }
            int served = negotiated(client);
            client.runOnIoThread(() -> {
                if (!enabled) {
                    send(client, NativeCrewPackets.close());
                }
                send(client, NativeCrewPackets.welcome(served, enabled));
            });
        }
    }

    // ---- native pages (Step 7) -----------------------------------------------------------------------

    /** LivingTalk's native pages are drawn by this window from now on: called when the living world starts. */
    public void install() {
        LivingTalk.nativeRenderer(this);
    }

    /** No more native pages from this window: the living world stopped. */
    public void uninstall() {
        LivingTalk.clearNativeRenderer(this);
    }

    /**
     * A native page may be drawn for {@code chr}: its connection is served at protocol 2 or later, it stands in the
     * world (not the Cash Shop or MTS) and the switch allows it. Otherwise LivingTalk shows the NPC window instead.
     */
    /**
     * The Adventurer's Roster on a client that has the native window with every NPC Crew window function (protocol 2):
     * asks it to open its window (OPEN; the client answers with its own OPEN, and the crew is watched from then on),
     * in the world and while the switch allows it. False when the NPC Crew window answers the Roster instead.
     */
    public boolean openFor(Character owner) {
        Client client = owner.getClient();
        if (client == null || negotiated(client) < PAGES_PROTOCOL || !owner.isLoggedinWorld() || !allows(owner)) {
            return false;
        }
        send(client, NativeCrewPackets.open());
        return true;
    }

    @Override
    public boolean draws(Character chr) {
        Client client = chr.getClient();
        return client != null && negotiated(client) >= PAGES_PROTOCOL && client.isCrewWindowOpen() && chr.isLoggedinWorld()
                && allows(chr);
    }

    /** PAGE, to a connection at protocol 2 or later the switch still allows. */
    @Override
    public void page(Character chr, NativeTalkPage page) {
        sendPage(chr, NativeCrewPackets.page(page));
    }

    /** PAGE_CLOSE, to a connection at protocol 2 or later the switch still allows. */
    @Override
    public void close(Character chr, int stamp) {
        sendPage(chr, NativeCrewPackets.pageClose(stamp));
    }

    private void sendPage(Character chr, Packet packet) {
        Client client = chr.getClient();
        if (client != null && negotiated(client) >= PAGES_PROTOCOL && allows(chr)) {
            client.sendPacket(packet);
        }
    }

    /**
     * The owner's crew as the Crew window shows it now: the party block (guests last) and the bench block, each row
     * stamped with its version and flagged with what its card offers; the owner's version stamps the whole.
     */
    Snapshot snapshot(Character owner) {
        CompanionCrewScreens crews = menus.crews();
        long ownerVersion = menus.version(owner); // before any row is read, as the Crew window stamps its options
        Map<String, Long> versions = new HashMap<>();
        for (RosterEntry entry : menus.rows(owner)) {
            versions.put(entry.personaId(), menus.version(owner, entry.personaId())); // each before its view is read
        }
        CompanionCrewScreens.Blocks blocks = crews.blocks(owner);
        boolean anyoneToComeIn = !menus.cards().swapIns(owner).isEmpty();
        boolean anyoneOut = !menus.rows(owner, true).isEmpty();
        List<Row> party = new ArrayList<>();
        for (CompanionView view : blocks.party()) {
            party.add(row(owner, view, crews.partyText(view), versions, anyoneToComeIn, anyoneOut));
        }
        for (CompanionView guest : crews.guests(owner)) {
            party.add(new Row(GUEST_PREFIX + CompanionSlots.characterIdOf(guest.personaId()), 0, guest.name(),
                    levelJob(guest), detail(guest, CompanionCrewScreens.partyLine(guest)), hpPercent(guest), GUEST));
        }
        List<Row> bench = new ArrayList<>();
        for (CompanionView view : blocks.bench()) {
            bench.add(row(owner, view, crews.benchText(view), versions, anyoneToComeIn, anyoneOut));
        }
        int flags = (crews.usualCallable(owner, blocks.bench()) ? USUAL_CALLABLE : 0)
                | (CompanionCrewScreens.anyoneOut(blocks.party()) ? SOMEONE_OUT : 0);
        // the bench seats are the roster places the party block does not use, as the Crew window counts them
        return new Snapshot((int) ownerVersion, world.slots().occupied(owner), RosterService.PARTY_CAP,
                world.config().ROSTER_MAX - blocks.party().size(), CompanionCrewScreens.kept(blocks.bench()), flags,
                party, bench);
    }

    private Row row(Character owner, CompanionView view, String text, Map<String, Long> versions, boolean anyoneToComeIn,
                    boolean anyoneOut) {
        long version = versions.computeIfAbsent(view.personaId(), id -> menus.version(owner, id));
        Persona persona = world.content().persona(view.personaId());
        return new Row(view.personaId(), (int) version, view.name(), levelJob(view), detail(view, text), hpPercent(view),
                rowFlags(owner, persona, view, anyoneToComeIn, anyoneOut));
    }

    /**
     * What the companion's card offers now, as row flags, by the card's own rules: CALL ("Come join me"), and unless it
     * is tied to a run (COMMITTED) BENCH ("Take a break at camp"), SWAP_OUT ("Let someone take your place...") and
     * SWAP_IN ("Swap in for..."). None for a row whose persona is unknown: no card opens for it.
     */
    private int rowFlags(Character owner, Persona persona, CompanionView view, boolean anyoneToComeIn, boolean anyoneOut) {
        if (persona == null) {
            return 0;
        }
        CompanionCardScreens cards = menus.cards();
        RosterState state = view.state();
        boolean committed = view.committed();
        int flags = committed ? COMMITTED : 0;
        if (CompanionCardScreens.offersCall(state, view)) {
            flags |= CALL;
        }
        if (!committed && cards.offersBreak(owner, persona, state)) {
            flags |= BENCH;
        }
        if (!committed && CompanionCardScreens.offersSwapOut(state, anyoneToComeIn)) {
            flags |= SWAP_OUT;
        }
        if (!committed && CompanionCardScreens.offersSwapIn(state, view, anyoneOut)) {
            flags |= SWAP_IN;
        }
        // the partners each swap picker lists: those out with the owner, and those at camp who can come at once
        if (state == RosterState.ACTIVE) {
            flags |= OUT;
        }
        if ((state == RosterState.BENCHED || state == RosterState.AWAY) && view.travelMs() <= 0) {
            flags |= CAN_COME;
        }
        return flags;
    }

    /** "Lv31 Fighter": the NPC row's ", Lv31 Fighter" without its comma; empty while the level is unknown. */
    static String levelJob(CompanionView view) {
        String levelJob = CompanionMenus.levelJob(view);
        return levelJob.startsWith(", ") ? levelJob.substring(2) : levelJob;
    }

    /** The NPC row's text after "Name, LvN Job - ": "Perion - HP 90%", "train @ Ant Tunnel I - 12% EXP banked". */
    static String detail(CompanionView view, String text) {
        String prefix = view.name() + CompanionMenus.levelJob(view) + " - ";
        return text.startsWith(prefix) ? text.substring(prefix.length()) : text;
    }

    /** 0-100 for a loaded companion, {@link NativeCrewPackets#HP_UNKNOWN} for one that is not. */
    static int hpPercent(CompanionView view) {
        return view.loaded() ? Math.max(0, Math.min(100, view.hpPercent())) : HP_UNKNOWN;
    }

    /** The citizen id of a guest row's id as this server writes it ("guest:5678"); 0 for any other spelling. */
    static int guestId(String personaId) {
        String digits = personaId.substring(GUEST_PREFIX.length());
        try {
            int id = Integer.parseInt(digits);
            return id > 0 && Integer.toString(id).equals(digits) ? id : 0;
        } catch (NumberFormatException notAnId) {
            return 0;
        }
    }

    /** Sends to a connection that said HELLO (served at protocol 1 or later), and to no other. */
    private static void send(Client client, Packet packet) {
        if (negotiated(client) >= 1) {
            client.sendPacket(packet);
        }
    }
}
