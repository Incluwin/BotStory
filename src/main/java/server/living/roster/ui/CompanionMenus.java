package server.living.roster.ui;

import client.Character;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.resident.LivingTalk;
import server.living.resident.LivingTalk.Option;
import server.living.resident.ResidentDialogue;
import server.living.roster.CompanionDirectory;
import server.living.roster.CompanionView;
import server.living.roster.RosterService;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.MapFactory;
import tools.PacketCreator;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * Crew window v0 (docs/BOT_MANAGEMENT_PLAN.md P1.5) in the stock NPC window: the companion card, the bench card,
 * the Crew window and the swap pickers. Each window is the answer to the player's own click (a double-click on
 * the companion, the Adventurer's Roster, a menu pick), so {@link LivingTalk} never pushes one over another.
 * Only the viewer's own companions are listed; every action goes through {@link RosterService} (bench, call,
 * swap) and so behind the P1 guards. The companion answers in its own voice, and the window's last page tells
 * the outcome; OK closes it.
 */
public final class CompanionMenus {
    /** The Crew window reads as the player's own notes: their avatar on the right (P0 probe 5b). */
    static final int NOTES_SPEAKER = 2;
    /**
     * The stock object NPC "Bulletin Board" (P0 probe 5c): the notes' NPC id, and the face of a companion with no
     * rendered portrait of its own. Never a borrowed townsperson, never 9010000 (the portrait policy).
     */
    static final int BOARD_NPC = 9040011;

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
        Persona persona = world.content().persona(entry.personaId());
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
        options.add(new Option("Take a break at camp", () -> outcome(owner, persona, world.roster().bench(owner, persona))));
        if (!rows(owner, false).isEmpty()) {
            options.add(new Option("Swap me out for...", () -> swapPicker(owner, persona, true)));
        }
        options.add(new Option("The whole crew...", () -> crew(owner)));
        Character bot = world.roster().companion(entry);
        if (bot != null) {
            options.add(new Option("Let me see your gear", () -> showGear(owner, bot)));
        }
        options.add(new Option("Never mind", null));
        LivingTalk.open(owner, portrait(persona)).menu(header(view), options).show();
    }

    /** The Adventurer's Roster: the Crew window from anywhere, even with every companion benched. */
    public void rosterBook(Character owner) {
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
        options.add(Option.heading("\r\n#eBench (" + bench.size() + " of " + world.config().ROSTER_MAX + ")#n"));
        for (RosterEntry entry : bench) {
            CompanionView view = view(entry);
            options.add(new Option(benchRow(view), () -> reopen(owner, view)));
        }
        if (bench.isEmpty()) {
            options.add(Option.heading("(nobody at camp)"));
        }
        int members = owner.getParty() == null ? 1 : owner.getParty().getMembers().size();
        LivingTalk.open(owner, BOARD_NPC).speaker(NOTES_SPEAKER)
                .menu("#eParty " + members + "/" + RosterService.PARTY_CAP + "#n", options)
                .show();
    }

    // ---- screens -------------------------------------------------------------------------

    private void benchCard(Character owner, Persona persona, RosterEntry entry) {
        CompanionView view = view(entry);
        List<Option> options = new ArrayList<>();
        options.add(new Option("Come join me", () -> outcome(owner, persona, world.roster().call(owner, persona))));
        if (!rows(owner, true).isEmpty()) {
            options.add(new Option("Swap in for...", () -> swapPicker(owner, persona, false)));
        }
        options.add(new Option("Never mind", null));
        LivingTalk.open(owner, portrait(persona)).menu(header(view) + duty(view) + "\r\n", options).show();
    }

    /**
     * {@code outgoing}: {@code fixed} is out with you and a bench companion takes its place; otherwise {@code fixed}
     * comes in for one that is out. Both are {@link RosterService#swap}(out, in).
     */
    private void swapPicker(Character owner, Persona fixed, boolean outgoing) {
        List<Option> options = new ArrayList<>();
        for (RosterEntry entry : rows(owner, !outgoing)) {
            Persona other = world.content().persona(entry.personaId());
            if (other == null) {
                continue;
            }
            CompanionView view = view(entry);
            options.add(new Option(outgoing ? benchRow(view) : partyRow(view), () -> outcome(owner, fixed, outgoing
                    ? world.roster().swap(owner, fixed, other) : world.roster().swap(owner, other, fixed))));
        }
        if (options.isEmpty()) {
            outcome(owner, fixed, "nobody to swap with right now.");
            return;
        }
        options.add(new Option("Never mind", null));
        LivingTalk.open(owner, portrait(fixed))
                .menu(outgoing ? "who takes my place?\r\n" : "who should i take over from?\r\n", options)
                .show();
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
            outcome(owner, persona, persona.name() + " is not in your roster.");
        }
    }

    /** The action's result as the window's last page, the answer to the click; OK closes it. */
    private void outcome(Character owner, Persona persona, String line) {
        LivingTalk.open(owner, portrait(persona)).ok(line).show();
    }

    /** The stock character info, as double-clicking a stranger shows it. */
    private static void showGear(Character owner, Character bot) {
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

    /** "Hadrik, Lv31 Fighter - Perion - HP 90%". */
    static String partyRow(CompanionView view) {
        return view.name() + ", " + levelJob(view) + (view.loaded()
                ? " - " + view.where() + " - HP " + view.hpPercent() + "%" : " - on the way");
    }

    /** "Mira, Lv28 Hunter - train @ Ant Tunnel I - 12% EXP banked". */
    static String benchRow(CompanionView view) {
        return view.name() + ", " + levelJob(view) + " - " + duty(view);
    }

    /** "#eHadrik#n, Lv31 Fighter - HP 812/900", then a line break. */
    static String header(CompanionView view) {
        return "#e" + view.name() + "#n, " + levelJob(view)
                + (view.loaded() ? " - HP " + view.hp() + "/" + view.maxHp() : "") + "\r\n";
    }

    private static String duty(CompanionView view) {
        return (view.state() == RosterState.AWAY ? "away: " : "") + view.duty()
                + (view.where().isEmpty() ? "" : " @ " + view.where())
                + (view.bankedExpPercent() > 0 ? " - " + view.bankedExpPercent() + "% EXP banked" : "");
    }

    private static String levelJob(CompanionView view) {
        return "Lv" + view.level() + (view.job().isEmpty() ? "" : " " + view.job());
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
