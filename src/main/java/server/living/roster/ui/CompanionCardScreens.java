package server.living.roster.ui;

import client.Character;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.resident.LivingTalk;
import server.living.resident.LivingTalk.Option;
import server.living.roster.CompanionView;
import server.living.roster.RosterResult;
import server.living.roster.RosterService;
import server.living.roster.RosterVoice;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.util.ArrayList;
import java.util.List;

import static server.living.roster.ui.CompanionMenus.benchRow;
import static server.living.roster.ui.CompanionMenus.duty;
import static server.living.roster.ui.CompanionMenus.header;
import static server.living.roster.ui.CompanionMenus.onTheWay;
import static server.living.roster.ui.CompanionMenus.partyRow;
import static server.living.roster.ui.CompanionMenus.portrait;

/**
 * A companion's own screens (docs/BOT_MANAGEMENT_PLAN.md P1.5, P3 "Companion card"): the card of one out with you, the
 * bench card of one that is not, and the pages they open (the swap pickers, the gear). The companion speaks on them
 * under the portrait policy; each page is the answer to a click on the one before it. Reached only through
 * {@link CompanionMenus#card}, which has checked that a window may open.
 */
final class CompanionCardScreens {
    private final CompanionMenus menus;
    private final LivingWorld world;

    CompanionCardScreens(CompanionMenus menus) {
        this.menus = menus;
        this.world = menus.world;
    }

    /** The card of an ACTIVE companion, the bench card of a benched or away one. */
    void show(Character owner, Persona persona, RosterEntry entry) {
        if (entry.state() != RosterState.ACTIVE) {
            benchCard(owner, persona, entry);
            return;
        }
        CompanionView view = menus.view(owner, entry);
        List<Option> options = new ArrayList<>();
        // the last one out is benched only by someone holding the Roster, which calls anyone back; else it is rotated
        if (menus.rows(owner, true).size() != 1 || menus.holdsRosterBook(owner)) {
            options.add(new Option("Take a break at camp", () -> menus.confirmBench(owner, persona, () -> menus.command(owner,
                    "menu.bench", () -> RosterVoice.line(persona, world.roster().bench(owner, persona))))));
        }
        if (!menus.rows(owner, false).isEmpty()) {
            options.add(new Option("Let someone take your place...", () -> swapPicker(owner, persona, true, "who takes my place?")));
        }
        options.add(new Option("The whole crew...", () -> menus.crew(owner)));
        if (world.roster().companion(entry) != null) {
            options.add(new Option("Let me see your gear", () -> showGear(owner, persona)));
        }
        options.add(new Option("Never mind", null));
        LivingTalk.open(owner, portrait(persona)).menu(header(view), options).show();
    }

    private void benchCard(Character owner, Persona persona, RosterEntry entry) {
        CompanionView view = menus.view(owner, entry);
        List<Option> options = new ArrayList<>();
        if (view.travelMs() <= 0) {
            options.add(new Option("Come join me", () -> join(owner, persona, entry)));
            if (!menus.rows(owner, true).isEmpty()) {
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
            if (leaderCapped && menus.rows(owner, true).isEmpty()) {
                LivingTalk.open(owner, portrait(persona)).ok(RosterService.LEADER_CAP_LINE).show();
                return;
            }
            swapPicker(owner, persona, false, full ? "your party's full. who should i take over from?"
                    : leaderCapped ? "the leader sent one of us out. who should i take over from?"
                    : "the other seats are your friend's. who should i take over from?");
            return;
        }
        menus.command(owner, "menu.call", () -> RosterVoice.line(persona, world.roster().call(owner, persona)));
    }

    /**
     * {@code outgoing}: {@code fixed} is out with you and a bench companion takes its place; otherwise {@code fixed}
     * comes in for one that is out. Both are {@link RosterService#swap}(out, in). {@code prompt} is {@code fixed}'s line.
     */
    private void swapPicker(Character owner, Persona fixed, boolean outgoing, String prompt) {
        List<Option> options = new ArrayList<>();
        for (RosterEntry entry : menus.rows(owner, !outgoing)) {
            Persona other = world.content().persona(entry.personaId());
            if (other == null) {
                continue;
            }
            CompanionView view = menus.view(owner, entry);
            options.add(new Option(outgoing ? benchRow(view) : partyRow(view), () -> menus.confirmBench(owner, outgoing ? fixed : other,
                    () -> menus.command(owner, "menu.swap", () -> RosterVoice.line(fixed, outgoing ? world.roster().swap(owner, fixed, other)
                            : world.roster().swap(owner, other, fixed))))));
        }
        if (options.isEmpty()) {
            menus.outcome(owner, "Nobody to swap with right now.");
            return;
        }
        options.add(new Option("Never mind", null));
        LivingTalk.open(owner, portrait(fixed)).menu(prompt + "\r\n", options).show();
    }

    /** The stock character info, as double-clicking a stranger shows it, of the companion as it is out now. */
    private void showGear(Character owner, Persona persona) {
        RosterResult shown = menus.manager().showGear(owner, persona);
        if (!shown.ok()) {
            menus.outcome(owner, persona.name() + " is not out with you right now.");
        }
    }
}
