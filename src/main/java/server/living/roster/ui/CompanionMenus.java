package server.living.roster.ui;

import client.Character;
import client.Client;
import server.living.LivingWorld;
import server.living.bond.ContractService;
import server.living.config.Content.Persona;
import server.living.resident.LivingTalk;
import server.living.resident.ResidentDialogue;
import server.living.roster.CompanionManager;
import server.living.roster.CompanionView;
import server.living.roster.CompanionViews;
import server.living.roster.RecallTravel;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import tools.PacketCreator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * The companion manager in the stock NPC window (docs/BOT_MANAGEMENT_PLAN.md P1.5, P3): the entry points (a double-click
 * on your own companion, the Adventurer's Roster, "The whole crew...") and what the screens share. The screens live in
 * {@link CompanionCardScreens} (a companion's card and its pages) and {@link CompanionCrewScreens} (the Crew window and
 * the crew-wide pages). Each window is the answer to the player's own click (a double-click on the companion, the
 * Adventurer's Roster, a menu pick), so {@link LivingTalk} never pushes one over another, and an entry point asks
 * {@link LivingTalk#mayShow} before it reads any rows. Only the viewer's own companions are listed; every action goes
 * through {@link server.living.roster.RosterService} or {@link CompanionManager} as a command on companion-ops (plan
 * P2a). Rows and views come from the {@link CompanionViews} cache, so a render reads no database. The companion speaks
 * on its card and its pickers; what a card action did is the top line of that card, read afresh (the notes' last page
 * only when there is no card to return to), or pink text when a window cannot open by the time the command is done or
 * the command took long.
 */
public final class CompanionMenus {
    /** The Crew window reads as the player's own notes: their avatar on the right (P0 probe 5b). */
    static final int NOTES_SPEAKER = 2;
    /**
     * The stock object NPC "Bulletin Board" (P0 probe 5c): the notes' NPC id, and the face of a companion with no
     * rendered portrait of its own. Never a borrowed townsperson, never 9010000 (the portrait policy).
     */
    public static final int BOARD_NPC = 9040011;
    /** An action done later than this after its click answers in pink text: no window pops up mid-fight. */
    static final long LATE_OUTCOME_MS = 1_000L;
    /** The line a screen re-opened by {@link #ifUnchanged} may show on top. */
    static final String THINGS_CHANGED = "Things changed meanwhile.";

    final LivingWorld world;
    final IntFunction<String> placeNames;
    private final CompanionCardScreens cards;
    private final CompanionCrewScreens crews;

    public CompanionMenus(LivingWorld world) {
        this(world, CompanionView::placeName);
    }

    CompanionMenus(LivingWorld world, IntFunction<String> placeNames) {
        this.world = world;
        this.placeNames = placeNames;
        this.cards = new CompanionCardScreens(this);
        this.crews = new CompanionCrewScreens(this);
    }

    // ---- entry points --------------------------------------------------------------------

    /** Double-clicking your own companion: the card of an ACTIVE one, the bench card of a benched or away one. */
    public void card(Character owner, RosterEntry entry) {
        card(owner, entry, false, null);
    }

    /** A Crew window row: the companion's card, with Back to that Crew window ({@code crewVoice} speaks it, or the notes). */
    void cardFromCrew(Character owner, RosterEntry entry, Persona crewVoice) {
        card(owner, entry, true, crewVoice);
    }

    private void card(Character owner, RosterEntry entry, boolean fromCrew, Persona crewVoice) {
        // a window awaiting its reply, or a click inside 500 ms: refused before any row is read
        Persona persona = LivingTalk.mayShow(owner) ? world.content().persona(entry.personaId()) : null;
        if (persona == null) {
            owner.sendPacket(PacketCreator.enableActions());
            return;
        }
        cards.show(owner, persona, fromCrew, crewVoice);
    }

    /** The Adventurer's Roster: the Crew window from anywhere, even with every companion benched. */
    public void rosterBook(Character owner) {
        if (!LivingTalk.mayShow(owner)) {
            owner.sendPacket(PacketCreator.enableActions());
            return;
        }
        if (rows(owner).isEmpty()) {
            owner.dropMessage(5, "Nobody travels with you yet.");
            owner.sendPacket(PacketCreator.enableActions());
            return;
        }
        crew(owner);
    }

    /** Your party companions, then your bench; a row opens that companion's card. */
    public void crew(Character owner) {
        crews.show(owner);
    }

    /**
     * "Adventurers looking for a crew" (P4, decision 4 b) in the player's notes, with Back to the Crew window: the
     * citizens they have met who would join them, each opening its hire terms.
     */
    public void hireList(Character owner) {
        if (!LivingTalk.mayShow(owner)) {
            owner.sendPacket(PacketCreator.enableActions());
            return;
        }
        crews.hireList(owner, null);
    }

    /** A companion's screens, for a crew page that opens one of them (a card is opened through {@link #card}). */
    CompanionCardScreens cards() {
        return cards;
    }

    /** The crew-wide screens, for a card page that opens one of them. */
    CompanionCrewScreens crews() {
        return crews;
    }

    // ---- shared by the screens ------------------------------------------------------------

    /** The typed facade the P3 screens submit their commands to; a world without one (a test's) gets a fresh one. */
    CompanionManager manager() {
        CompanionManager manager = world.manager();
        return manager != null ? manager : new CompanionManager(world);
    }

    /**
     * The owner's CompanionViews version, taken by a screen before it reads any row, so its options are stamped with the
     * version of the rows they show; 0 without a cache (a test's world).
     */
    long version(Character owner) {
        CompanionViews views = world.views();
        return views == null ? 0L : views.version(owner.getId());
    }

    /**
     * An option's action stamped with {@code rendered}, the owner's {@link #version} taken before the screen read its
     * rows (plan flow 1: "if Tobi's state changed meanwhile, the window re-renders"): clicked after a roster write or a
     * party change touched the owner's rows, it runs {@code rerender} (the screen again, read afresh, as the answer to
     * the click) instead of {@code action}. Without a cache (a test's world) it always acts.
     */
    Runnable ifUnchanged(Character owner, long rendered, Runnable action, Runnable rerender) {
        CompanionViews views = world.views();
        return () -> {
            if (views != null && views.version(owner.getId()) != rendered) {
                rerender.run();
            } else {
                action.run();
            }
        };
    }

    /** One companion's version (its card and pages), taken before the screen reads its row; 0 without a cache. */
    long version(Character owner, Persona persona) {
        CompanionViews views = world.views();
        return views == null ? 0L : views.version(owner.getId(), persona.id());
    }

    /**
     * As {@link #ifUnchanged(Character, long, Runnable, Runnable)} for a screen about {@code persona} alone: only a change
     * to its row (or to the owner's rows naming none) makes the click stale, not another companion's arrival or duty.
     */
    Runnable ifUnchanged(Character owner, Persona persona, long rendered, Runnable action, Runnable rerender) {
        CompanionViews views = world.views();
        return () -> {
            if (views != null && views.version(owner.getId(), persona.id()) != rendered) {
                rerender.run();
            } else {
                action.run();
            }
        };
    }

    /** What the action did, as the window's last page in the player's notes: the answer to the click; OK closes it. */
    void outcome(Character owner, String line) {
        LivingTalk.open(owner, BOARD_NPC).speaker(NOTES_SPEAKER).ok(line).show();
    }

    /**
     * Runs a roster action for {@code persona} on companion-ops; the click's window has closed by then (the client's
     * actions came back). Its line is answered on the owner's own packet thread, so the check and the window are one
     * step against their NPC clicks: {@code persona}'s card again, read afresh with the line on top (the notes' last
     * page once it is no longer in the roster), when a window may open and the action was quick, else pink text (the
     * companion's own words were said by the verb). Nothing is ever sent over a window awaiting its reply, and no
     * window pops up long after the click. An empty line says nothing: the action's own window (a trade) or the
     * companion's reply in chat (an order) is the answer.
     */
    void command(Character owner, Persona persona, String name, Supplier<String> action) {
        command(owner, name, action, line -> {
            if (!line.isEmpty()) {
                cards.answer(owner, persona, line);
            }
        });
    }

    /**
     * {@link #command(Character, Persona, String, Supplier)} whose line, when a window may open and the action was quick,
     * is answered by {@code answer} on the owner's packet thread (a screen rendered again, say; an empty line too)
     * instead of the card; otherwise it is pink text as there, and an empty line says nothing.
     */
    void command(Character owner, String name, Supplier<String> action, Consumer<String> answer) {
        long clickedAtMs = System.currentTimeMillis();
        world.executor().submit(name, () -> {
            String said = action.get();
            String line = said == null ? "" : said;
            Client client = owner.getClient();
            if (client == null) {
                return; // logged off meanwhile
            }
            client.runOnIoThread(() -> {
                if (System.currentTimeMillis() - clickedAtMs <= LATE_OUTCOME_MS && LivingTalk.mayShow(owner)) {
                    answer.accept(line);
                } else if (!line.isEmpty()) {
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
    void confirmBench(Character owner, Persona persona, Runnable bench) {
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

    /** Every roster row of the owner's, from the view cache. */
    List<RosterEntry> rows(Character owner) {
        CompanionViews views = world.views();
        return views == null ? world.roster().roster(owner) : views.rows(owner.getId());
    }

    /** The owner's roster rows out with them ({@code party}) or not (benched and away), from the view cache. */
    List<RosterEntry> rows(Character owner, boolean party) {
        List<RosterEntry> out = new ArrayList<>();
        for (RosterEntry entry : rows(owner)) {
            if (entry.state() != RosterState.RELEASED && (entry.state() == RosterState.ACTIVE) == party) {
                out.add(entry);
            }
        }
        return out;
    }

    /** The row as a view, from the cache (read afresh only for a row the cache no longer has). */
    CompanionView view(Character owner, RosterEntry entry) {
        CompanionViews views = world.views();
        CompanionView cached = views == null ? null : views.view(owner.getId(), entry.personaId());
        return cached != null ? cached : CompanionView.of(world, entry, placeNames);
    }

    /** The persona ids in the owner's usual lineup, from the view cache. */
    Set<String> lineup(Character owner) {
        CompanionViews views = world.views();
        return views == null ? world.store().lineupSlots(owner.getId()).keySet() : views.lineup(owner.getId());
    }

    boolean holdsRosterBook(Character owner) {
        int book = world.config().ROSTER_BOOK_ITEM_ID;
        return book > 0 && owner.haveItem(book);
    }

    // ---- rows ----------------------------------------------------------------------------

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

    static String duty(CompanionView view) {
        // its camp, never the map it stands on: one benched and still walking out is loaded where its owner is
        String camp = view.camp().isEmpty() ? view.where() : view.camp();
        return (view.state() == RosterState.AWAY ? "away: " : "") + view.duty()
                + (camp.isEmpty() ? "" : " @ " + camp)
                + (view.bankedExpPercent() >= 100 ? " - ready to level"
                : view.bankedExpPercent() > 0 ? " - " + view.bankedExpPercent() + "% EXP banked" : "");
    }

    static String onTheWay(CompanionView view) {
        return "on the way (" + RecallTravel.describe(view.travelMs()) + ")";
    }

    /** A rate in percent: "1.2", "31" (one decimal below 10). */
    static String percent(double value) {
        return value < 10.0d ? String.format(Locale.ROOT, "%.1f", value) : String.valueOf(Math.round(value));
    }

    /** Meso: "850", "4.1k", "12k" (one decimal below 10k); a negative amount keeps its sign. */
    static String meso(double value) {
        double size = Math.abs(value);
        if (size < 1_000.0d) {
            return String.valueOf(Math.round(value));
        }
        return size < 10_000.0d ? String.format(Locale.ROOT, "%.1fk", value / 1_000.0d) : Math.round(value / 1_000.0d) + "k";
    }

    /** ", Lv31 Fighter"; nothing while the level is unknown (a companion that has never been loaded). */
    static String levelJob(CompanionView view) {
        return view.level() <= 0 ? "" : ", Lv" + view.level() + (view.job().isEmpty() ? "" : " " + view.job());
    }

    /** The companion's own rendered portrait, else the Bulletin Board (the portrait policy). */
    static int portrait(Persona persona) {
        int own = ResidentDialogue.renderedPortraitOf(persona);
        return own > 0 ? own : BOARD_NPC;
    }
}
