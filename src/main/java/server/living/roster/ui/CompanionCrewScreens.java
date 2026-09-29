package server.living.roster.ui;

import client.Character;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.resident.LivingTalk;
import server.living.resident.LivingTalk.Option;
import server.living.roster.CompanionDirectory;
import server.living.roster.CompanionView;
import server.living.roster.RosterService;
import server.living.store.LivingStore.RosterEntry;

import java.util.ArrayList;
import java.util.List;

import static server.living.roster.ui.CompanionMenus.BOARD_NPC;
import static server.living.roster.ui.CompanionMenus.NOTES_SPEAKER;
import static server.living.roster.ui.CompanionMenus.benchRow;
import static server.living.roster.ui.CompanionMenus.partyRow;

/**
 * The Crew window and its crew-wide pages (docs/BOT_MANAGEMENT_PLAN.md P1.5, P3 "Crew window"), written in the player's
 * own notes: the party block, the bench block, and "Make room for a friend...". A row opens that companion's card
 * through {@link CompanionMenus#card}. Reached through {@link CompanionMenus#crew} and the Adventurer's Roster.
 */
final class CompanionCrewScreens {
    private final CompanionMenus menus;
    private final LivingWorld world;

    CompanionCrewScreens(CompanionMenus menus) {
        this.menus = menus;
        this.world = menus.world;
    }

    /** Your party companions, then your bench; a row opens that companion's card. */
    void show(Character owner) {
        List<RosterEntry> party = menus.rows(owner, true);
        List<RosterEntry> bench = menus.rows(owner, false);
        List<Option> options = new ArrayList<>();
        for (RosterEntry entry : party) {
            CompanionView view = menus.view(owner, entry);
            options.add(new Option(partyRow(view), () -> reopen(owner, view)));
        }
        if (party.isEmpty()) {
            options.add(Option.heading("(nobody out with me)"));
        }
        // the bench seats are the roster places the party does not use
        options.add(Option.heading("\r\n#eBench (" + bench.size() + " of " + (world.config().ROSTER_MAX - party.size()) + ")#n"));
        for (RosterEntry entry : bench) {
            CompanionView view = menus.view(owner, entry);
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

    /** Whom of yours to step out for a friend: they bench and come back once there is room again. */
    private void makeRoomPicker(Character owner) {
        List<Option> options = new ArrayList<>();
        for (RosterEntry entry : menus.rows(owner, true)) {
            Persona persona = world.content().persona(entry.personaId());
            if (persona != null) {
                options.add(new Option(partyRow(menus.view(owner, entry)), () -> menus.confirmBench(owner, persona,
                        () -> menus.command(owner, "menu.make_room", () -> world.partySync().makeRoom(owner.getId(), persona.id())
                                ? persona.name() + " steps out to make room for your friend."
                                : persona.name() + " is not out with you right now."))));
            }
        }
        options.add(new Option("Never mind", null));
        LivingTalk.open(owner, BOARD_NPC).speaker(NOTES_SPEAKER).menu("Who steps out for your friend?\r\n", options).show();
    }

    /** A row: ownership and state are read again, so a companion released or moved meanwhile is never acted on stale. */
    private void reopen(Character owner, CompanionView row) {
        Persona persona = world.content().persona(row.personaId());
        if (persona == null) {
            return; // the window closes and the client's actions come back
        }
        if (world.directory().resolveName(owner, persona.name()) instanceof CompanionDirectory.Relation.OwnCompanion own) {
            menus.card(owner, own.entry());
        } else {
            menus.outcome(owner, persona.name() + " is not in your roster.");
        }
    }
}
