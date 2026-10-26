package server.living.roster.ui;

import client.Character;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.resident.LivingTalk;
import server.living.resident.LivingTalk.Option;
import server.living.roster.CompanionLoans;
import server.living.roster.RosterResult;
import server.living.roster.RosterVoice;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.List;

/**
 * Lending's windows (docs/COMPANION_LENDING_PLAN.md, 3.4), in the companion's voice like its card: the owner's "Lend to a
 * friend..." picker (who here, then for how long), the owner's card of a companion out on loan (call it back), and the
 * card the friend opens by double-clicking a companion lent to them (four field orders and "head back"). Every action
 * runs on companion-ops, where CompanionLoans checks again who may ask.
 */
final class LoanScreens {
    private final CompanionMenus menus;
    private final LivingWorld world;

    LoanScreens(CompanionMenus menus) {
        this.menus = menus;
        this.world = menus.world;
    }

    /** True when the party card offers "Lend to a friend...": the switch is on and the companion may be lent now. */
    boolean offersLend(Character owner, Persona persona) {
        CompanionLoans loans = world.loans();
        return loans != null && world.config().COMPANION_LENDING_ENABLED && loans.refusalToLend(owner, persona) == null;
    }

    /** "Lend to a friend...": the buddies and guild mates here who could take it along now. */
    void lendPicker(Character owner, Persona persona, Runnable back) {
        CompanionLoans loans = world.loans();
        MapleMap map = owner.getMap();
        List<CompanionLoans.Friend> friends = loans == null || map == null ? List.of()
                : loans.friendsFor(owner, map.getAllPlayers());
        if (friends.isEmpty()) {
            LivingTalk.open(owner, CompanionMenus.portrait(persona))
                    .ok("nobody here can take me along. a buddy or a guild mate has to be right here, with room in"
                            + " their party.").back(back).show();
            return;
        }
        List<Option> options = new ArrayList<>();
        for (CompanionLoans.Friend friend : friends) {
            options.add(new Option(friend.name() + " (" + friend.tie() + ")",
                    () -> lengthPicker(owner, persona, friend, () -> lendPicker(owner, persona, back))));
        }
        LivingTalk.open(owner, CompanionMenus.portrait(persona)).menu("who should i go with?", options).back(back).show();
    }

    private void lengthPicker(Character owner, Persona persona, CompanionLoans.Friend friend, Runnable back) {
        List<Option> options = new ArrayList<>();
        for (int minutes : world.loans().lengths()) {
            options.add(new Option(CompanionLoans.describeLength(minutes), () -> menus.command(owner, persona,
                    "menu.lend", () -> RosterVoice.line(persona, world.loans().offer(owner, persona,
                            friend.characterId(), minutes)))));
        }
        LivingTalk.open(owner, CompanionMenus.portrait(persona))
                .menu("how long should i go with " + friend.name() + "?", options).back(back).show();
    }

    /** The owner's card of a companion out on loan: who has it and how long, and calling it back. */
    void lentCard(Character owner, Persona persona, CompanionLoans.Loan loan, String note, Runnable back) {
        Character friend = world.roster().onlineCharacter(loan.borrowerId());
        String friendName = friend == null ? "a friend" : friend.getName();
        List<Option> options = new ArrayList<>();
        options.add(new Option("Call " + persona.name() + " back from " + friendName, () -> menus.command(owner, persona,
                "menu.loan.recall", () -> RosterVoice.line(persona, world.loans().recall(owner, persona)))));
        options.add(new Option("The whole crew...", () -> menus.crews().show(owner, persona)));
        String text = (note.isEmpty() ? "" : note + "\r\n") + persona.name() + ", out with " + friendName
                + " (" + minutesLeft(loan) + " left)\r\n" + (loan.waitingOutside() ? "waiting outside a run\r\n" : "");
        LivingTalk.open(owner, CompanionMenus.portrait(persona)).menu(text, options).back(back).show();
    }

    /**
     * The card of a companion out on loan to {@code friend} (their double-click). It reads the loan afresh; one no
     * longer lent to them keeps the stock character info (false, nothing shown).
     */
    boolean borrowedCard(Character friend, Persona persona, String note) {
        CompanionLoans loans = world.loans();
        CompanionLoans.Loan loan = loans == null || persona == null ? null : loans.lentOut(persona.id());
        if (loan == null || !loans.mayDirect(friend, loan.characterId())) {
            return false;
        }
        Character owner = world.roster().onlineCharacter(loan.ownerId());
        String ownerName = owner == null ? "my owner" : owner.getName();
        List<Option> options = new ArrayList<>();
        options.add(new Option("Stick with me", () -> direct(friend, persona, CompanionLoans.Order.FOLLOW)));
        options.add(new Option("Wait here", () -> direct(friend, persona, CompanionLoans.Order.WAIT)));
        options.add(new Option("Hunt here while I'm busy", () -> direct(friend, persona, CompanionLoans.Order.HUNT)));
        options.add(new Option("Head back to " + ownerName, () -> answer(friend, persona, "menu.loan.send_back",
                () -> world.loans().sendBack(friend, persona))));
        String text = (note.isEmpty() ? "" : note + "\r\n") + persona.name() + ", on loan from " + ownerName + " ("
                + minutesLeft(loan) + " left)\r\n" + (loan.waitingOutside() ? "waiting outside\r\n" : "");
        LivingTalk.open(friend, CompanionMenus.portrait(persona)).menu(text, options).show();
        return true;
    }

    private void direct(Character friend, Persona persona, CompanionLoans.Order order) {
        answer(friend, persona, "menu.loan.order", () -> world.loans().direct(friend, persona, order));
    }

    /** A friend's action on companion-ops; its line answers on the borrowed card, or in the notes once it went home. */
    private void answer(Character friend, Persona persona, String name, java.util.function.Supplier<RosterResult> verb) {
        menus.command(friend, name, () -> RosterVoice.line(persona, verb.get()), line -> {
            if (!line.isEmpty() && !borrowedCard(friend, persona, line)) {
                menus.outcome(friend, line);
            }
        });
    }

    private static String minutesLeft(CompanionLoans.Loan loan) {
        long minutes = Math.max(1L, (loan.msLeft(System.currentTimeMillis()) + 59_999L) / 60_000L);
        return minutes == 1 ? "1 minute" : minutes + " minutes";
    }
}
