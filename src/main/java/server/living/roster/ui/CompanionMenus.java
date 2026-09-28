package server.living.roster.ui;

import client.Character;
import client.Client;
import server.living.LivingWorld;
import server.living.bond.ContractService;
import server.living.config.Content.Persona;
import server.living.resident.LivingTalk;
import server.living.resident.LivingTalk.Option;
import server.living.resident.ResidentDialogue;
import server.living.roster.CompanionDirectory;
import server.living.roster.CompanionView;
import server.living.roster.RecallTravel;
import server.living.roster.RosterService;
import server.living.roster.RosterVoice;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.MapFactory;
import tools.PacketCreator;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * Crew window v0 (docs/BOT_MANAGEMENT_PLAN.md P1.5) in the stock NPC window: the companion card, the bench card,
 * the Crew window and the swap pickers. Each window is the answer to the player's own click (a double-click on
 * the companion, the Adventurer's Roster, a menu pick), so {@link LivingTalk} never pushes one over another, and an
 * entry point asks {@link LivingTalk#mayShow} before it reads any rows. Only the viewer's own companions are listed;
 * every action goes through {@link RosterService} (bench, call, swap) and so behind the P1 guards, as a command on
 * companion-ops (plan P2a). The companion speaks on its card and its pickers; what an action did is the last window,
 * written in the player's notes like the Crew window (OK closes it), or pink text when a window cannot open by the
 * time the command is done or the command took long.
 */
public final class CompanionMenus {
    /** The Crew window reads as the player's own notes: their avatar on the right (P0 probe 5b). */
    static final int NOTES_SPEAKER = 2;
    /**
     * The stock object NPC "Bulletin Board" (P0 probe 5c): the notes' NPC id, and the face of a companion with no
     * rendered portrait of its own. Never a borrowed townsperson, never 9010000 (the portrait policy).
     */
    static final int BOARD_NPC = 9040011;
    /** An action done later than this after its click answers in pink text: no window pops up mid-fight. */
    static final long LATE_OUTCOME_MS = 1_000L;

    private final LivingWorld world;
    private final IntFunction<String> placeNames;

    public CompanionMenus(LivingWorld world) {
        this(world, CompanionMenus::placeName);
    }

    CompanionMenus(LivingWorld world, IntFunction<String> placeNames) {
        this.world = world;
        this.placeNames = placeNames;
    }

    // ---- entry points --------------------------------------------------------------------

    /** Double-clicking your own companion: the card of an ACTIVE one, the bench card of a benched or away one. */
    public void card(Character owner, RosterEntry entry) {
        // a window awaiting its reply, or a click inside 500 ms: refused before any row is read
        Persona persona = LivingTalk.mayShow(owner) ? world.content().persona(entry.personaId()) : null;
        if (persona == null) {
            owner.sendPacket(PacketCreator.enableActions());
            return;
        }
        if (entry.state() != RosterState.ACTIVE) {
            benchCard(owner, persona, entry);
            return;
        }
        CompanionView view = view(entry);
        List<Option> options = new ArrayList<>();
        // the last one out is benched only by someone holding the Roster, which calls anyone back; else it is rotated
        if (rows(owner, true).size() != 1 || holdsRosterBook(owner)) {
            options.add(new Option("Take a break at camp", () -> confirmBench(owner, persona, () -> command(owner, "menu.bench",
                    () -> RosterVoice.line(persona, world.roster().bench(owner, persona))))));
        }
        if (!rows(owner, false).isEmpty()) {
            options.add(new Option("Let someone take your place...", () -> swapPicker(owner, persona, true, "who takes my place?")));
        }
        options.add(new Option("The whole crew...", () -> crew(owner)));
        if (world.roster().companion(entry) != null) {
            options.add(new Option("Let me see your gear", () -> showGear(owner, persona)));
        }
        options.add(new Option("Never mind", null));
        LivingTalk.open(owner, portrait(persona)).menu(header(view), options).show();
    }

    /** The Adventurer's Roster: the Crew window from anywhere, even with every companion benched. */
    public void rosterBook(Character owner) {
        if (!LivingTalk.mayShow(owner)) {
            owner.sendPacket(PacketCreator.enableActions());
            return;
        }
        if (world.roster().roster(owner).isEmpty()) {
            owner.dropMessage(5, "Nobody travels with you yet.");
            owner.sendPacket(PacketCreator.enableActions());
            return;
        }
        crew(owner);
    }

    /** Your party companions, then your bench; a row opens that companion's card. */
    public void crew(Character owner) {
        List<RosterEntry> party = rows(owner, true);
        List<RosterEntry> bench = rows(owner, false);
        List<Option> options = new ArrayList<>();
        for (RosterEntry entry : party) {
            CompanionView view = view(entry);
            options.add(new Option(partyRow(view), () -> reopen(owner, view)));
        }
        if (party.isEmpty()) {
            options.add(Option.heading("(nobody out with me)"));
        }
        // the bench seats are the roster places the party does not use
        options.add(Option.heading("\r\n#eBench (" + bench.size() + " of " + (world.config().ROSTER_MAX - party.size()) + ")#n"));
        for (RosterEntry entry : bench) {
            CompanionView view = view(entry);
            options.add(new Option(benchRow(view), () -> reopen(owner, view)));
        }
        if (bench.isEmpty()) {
            options.add(Option.heading("(nobody at camp)"));
        }
        // a full party cannot invite (P0 probe 12): one of yours steps out so a friend can be asked
        if (!party.isEmpty() && owner.getParty() != null && owner.getParty().getMembers().size() >= RosterService.PARTY_CAP) {
            options.add(new Option("Make room for a friend...", () -> makeRoomPicker(owner)));
        }
        int seats = world.slots().occupied(owner); // the members, and the seats kept for those away or on their way
        LivingTalk.open(owner, BOARD_NPC).speaker(NOTES_SPEAKER)
                .menu("#eParty " + seats + "/" + RosterService.PARTY_CAP + "#n", options)
                .show();
    }

    // ---- screens -------------------------------------------------------------------------

    private void benchCard(Character owner, Persona persona, RosterEntry entry) {
        CompanionView view = view(entry);
        List<Option> options = new ArrayList<>();
        if (view.travelMs() <= 0) {
            options.add(new Option("Come join me", () -> join(owner, persona, entry)));
            if (!rows(owner, true).isEmpty()) {
                options.add(new Option("Swap in for...", () -> swapPicker(owner, persona, false, "who should i take over from?")));
            }
        }
        options.add(new Option("Never mind", null));
        String status = view.travelMs() > 0 ? onTheWay(view) : duty(view);
        LivingTalk.open(owner, portrait(persona)).menu(header(view) + status + "\r\n", options).show();
    }

    /**
     * With no seat for it the companion asks whom to take over from; it is never sent for a seat that is not there.
     * In a shared party the open seats may be a friend's share (lending them is P9) or held empty by the leader's
     * expel (the leader cap), and it says which; with the leader cap and nobody out to take over from, it stays.
     */
    private void join(Character owner, Persona persona, RosterEntry entry) {
        if (!world.roster().canSeat(owner, entry)) {
            boolean full = world.slots().occupied(owner) >= RosterService.PARTY_CAP;
            boolean leaderCapped = !full && world.slots().leaderCapped(owner);
            if (leaderCapped && rows(owner, true).isEmpty()) {
                LivingTalk.open(owner, portrait(persona)).ok(RosterService.LEADER_CAP_LINE).show();
                return;
            }
            swapPicker(owner, persona, false, full ? "your party's full. who should i take over from?"
                    : leaderCapped ? "the leader sent one of us out. who should i take over from?"
                    : "the other seats are your friend's. who should i take over from?");
            return;
        }
        command(owner, "menu.call", () -> RosterVoice.line(persona, world.roster().call(owner, persona)));
    }

    /** Whom of yours to step out for a friend: they bench and come back once there is room again. */
    private void makeRoomPicker(Character owner) {
        List<Option> options = new ArrayList<>();
        for (RosterEntry entry : rows(owner, true)) {
            Persona persona = world.content().persona(entry.personaId());
            if (persona != null) {
                options.add(new Option(partyRow(view(entry)), () -> confirmBench(owner, persona, () -> command(owner, "menu.make_room",
                        () -> world.partySync().makeRoom(owner.getId(), persona.id())
                                ? persona.name() + " steps out to make room for your friend."
                                : persona.name() + " is not out with you right now."))));
            }
        }
        options.add(new Option("Never mind", null));
        LivingTalk.open(owner, BOARD_NPC).speaker(NOTES_SPEAKER).menu("Who steps out for your friend?\r\n", options).show();
    }

    /**
     * {@code outgoing}: {@code fixed} is out with you and a bench companion takes its place; otherwise {@code fixed}
     * comes in for one that is out. Both are {@link RosterService#swap}(out, in). {@code prompt} is {@code fixed}'s line.
     */
    private void swapPicker(Character owner, Persona fixed, boolean outgoing, String prompt) {
        List<Option> options = new ArrayList<>();
        for (RosterEntry entry : rows(owner, !outgoing)) {
            Persona other = world.content().persona(entry.personaId());
            if (other == null) {
                continue;
            }
            CompanionView view = view(entry);
            options.add(new Option(outgoing ? benchRow(view) : partyRow(view), () -> confirmBench(owner, outgoing ? fixed : other,
                    () -> command(owner, "menu.swap", () -> RosterVoice.line(fixed, outgoing ? world.roster().swap(owner, fixed, other)
                            : world.roster().swap(owner, other, fixed))))));
        }
        if (options.isEmpty()) {
            outcome(owner, "Nobody to swap with right now.");
            return;
        }
        options.add(new Option("Never mind", null));
        LivingTalk.open(owner, portrait(fixed)).menu(prompt + "\r\n", options).show();
    }

    /** A row: ownership and state are read again, so a companion released or moved meanwhile is never acted on stale. */
    private void reopen(Character owner, CompanionView row) {
        Persona persona = world.content().persona(row.personaId());
        if (persona == null) {
            return; // the window closes and the client's actions come back
        }
        if (world.directory().resolveName(owner, persona.name()) instanceof CompanionDirectory.Relation.OwnCompanion own) {
            card(owner, own.entry());
        } else {
            outcome(owner, persona.name() + " is not in your roster.");
        }
    }

    /** What the action did, as the window's last page in the player's notes: the answer to the click; OK closes it. */
    private void outcome(Character owner, String line) {
        LivingTalk.open(owner, BOARD_NPC).speaker(NOTES_SPEAKER).ok(line).show();
    }

    /**
     * Runs a roster action on companion-ops; the click's window has closed by then (the client's actions came back).
     * Its line is answered on the owner's own packet thread, so the check and the window are one step against their
     * NPC clicks: the notes' last page when a window may open and the action was quick, else pink text (the
     * companion's own words were said by the verb). Nothing is ever sent over a window awaiting its reply, and no
     * window pops up long after the click.
     */
    private void command(Character owner, String name, Supplier<String> action) {
        long clickedAtMs = System.currentTimeMillis();
        world.executor().submit(name, () -> {
            String line = action.get();
            Client client = owner.getClient();
            if (client == null) {
                return; // logged off meanwhile
            }
            client.runOnIoThread(() -> {
                if (System.currentTimeMillis() - clickedAtMs <= LATE_OUTCOME_MS && LivingTalk.mayShow(owner)) {
                    outcome(owner, line);
                } else {
                    owner.dropMessage(5, line);
                }
            });
        });
    }

    /**
     * A menu pick that benches {@code persona} (a break, a swap, making room): where ROSTER_CONTRACT_MODE still lets a
     * bench end its contract and it has little patience for camp (ContractService.benchWalkOutAfter), a Yes/No in the
     * player's notes asks first, as the answer to the pick; Yes runs {@code bench}, No closes. Otherwise it runs at once.
     */
    private void confirmBench(Character owner, Persona persona, Runnable bench) {
        ContractService contracts = world.contracts();
        String after = contracts == null ? null : contracts.benchWalkOutAfter(owner.getId(), persona);
        if (after == null) {
            bench.run();
            return;
        }
        LivingTalk.open(owner, BOARD_NPC).speaker(NOTES_SPEAKER)
                .yesNo(persona.name() + " won't sit at camp for long: after " + after + " on the bench, " + persona.name()
                        + " walks out for good.\r\n\r\nSend " + persona.name() + " to camp anyway?", bench, null)
                .show();
    }

    /** The stock character info, as double-clicking a stranger shows it, of the companion as it is out now. */
    private void showGear(Character owner, Persona persona) {
        RosterEntry now = world.roster().entry(owner.getId(), persona.id());
        Character bot = now == null || now.state() != RosterState.ACTIVE ? null : world.roster().companion(now);
        if (bot == null) {
            outcome(owner, persona.name() + " is not out with you right now.");
            return;
        }
        bot.exportExcludedItems(owner.getClient());
        owner.sendPacket(PacketCreator.charInfo(bot));
    }

    // ---- rows ----------------------------------------------------------------------------

    /** The owner's roster rows out with them ({@code party}) or not (benched and away). */
    private List<RosterEntry> rows(Character owner, boolean party) {
        List<RosterEntry> out = new ArrayList<>();
        for (RosterEntry entry : world.roster().roster(owner)) {
            if (entry.state() != RosterState.RELEASED && (entry.state() == RosterState.ACTIVE) == party) {
                out.add(entry);
            }
        }
        return out;
    }

    private CompanionView view(RosterEntry entry) {
        return CompanionView.of(world, entry, placeNames);
    }

    private boolean holdsRosterBook(Character owner) {
        int book = world.config().ROSTER_BOOK_ITEM_ID;
        return book > 0 && owner.haveItem(book);
    }

    /** "Hadrik, Lv31 Fighter - Perion - HP 90%". */
    static String partyRow(CompanionView view) {
        return view.name() + levelJob(view) + (view.loaded()
                ? " - " + view.where() + " - HP " + view.hpPercent() + "%" : " - on the way");
    }

    /**
     * "Mira, Lv28 Hunter - train @ Ant Tunnel I - 12% EXP banked" ("- ready to level" once it would level her), or
     * "... - on the way (~40 s)" once called.
     */
    static String benchRow(CompanionView view) {
        return view.name() + levelJob(view) + " - " + (view.travelMs() > 0 ? onTheWay(view) : duty(view));
    }

    /** "#eHadrik#n, Lv31 Fighter - HP 812/900", then a line break. */
    static String header(CompanionView view) {
        return "#e" + view.name() + "#n" + levelJob(view)
                + (view.loaded() ? " - HP " + view.hp() + "/" + view.maxHp() : "") + "\r\n";
    }

    private static String duty(CompanionView view) {
        return (view.state() == RosterState.AWAY ? "away: " : "") + view.duty()
                + (view.where().isEmpty() ? "" : " @ " + view.where())
                + (view.bankedExpPercent() >= 100 ? " - ready to level"
                : view.bankedExpPercent() > 0 ? " - " + view.bankedExpPercent() + "% EXP banked" : "");
    }

    private static String onTheWay(CompanionView view) {
        return "on the way (" + RecallTravel.describe(view.travelMs()) + ")";
    }

    /** ", Lv31 Fighter"; nothing while the level is unknown (a companion that has never been loaded). */
    private static String levelJob(CompanionView view) {
        return view.level() <= 0 ? "" : ", Lv" + view.level() + (view.job().isEmpty() ? "" : " " + view.job());
    }

    private static int portrait(Persona persona) {
        int own = ResidentDialogue.renderedPortraitOf(persona);
        return own > 0 ? own : BOARD_NPC;
    }

    private static String placeName(int mapId) {
        String name = MapFactory.loadPlaceName(mapId);
        return name == null || name.isEmpty() ? "camp" : name;
    }
}
