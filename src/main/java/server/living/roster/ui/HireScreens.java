package server.living.roster.ui;

import client.Character;
import client.Client;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.resident.LivingTalk;
import server.living.roster.HireOffers;
import server.living.roster.HiredHands;
import tools.PacketCreator;

import java.util.Locale;

/**
 * The hire terms window (docs/BOT_MANAGEMENT_PLAN.md P4, flow 9 step 4 and "Hire list"), shared by a citizen's own
 * offer after party time and the Crew window's "Adventurers looking for a crew": the quest-style Accept/Decline window
 * under the Bulletin Board portrait (a hire has no rendered face), in the citizen's voice, with its name, level, job,
 * fee and hourly wage (the card's Wages page's unit). Accept runs {@link HiredHands#hire} on companion-ops on the terms
 * shown and the citizen answers in its own words (a whisper; the roster's line in pink when it is on its way or waits
 * for room), or the terms open again when they changed meanwhile. Decline answers an offer in character; from the hire
 * list it goes back to the list, and anywhere else it just closes. The window is only ever the answer to the player's
 * own click ({@link LivingTalk}); every path ends in a window, a whisper or pink text, or enableActions.
 */
public final class HireScreens {
    /** Where the terms were asked for: the citizen's own offer, or the Crew window's hire list. */
    public enum Context { OFFER, HIRE_LIST }

    private final LivingWorld world;

    public HireScreens(LivingWorld world) {
        this.world = world;
    }

    /**
     * The citizen's terms for the owner, as the answer to their click; refused with enableActions while another window
     * awaits a reply (within the click throttle) or when the citizen cannot be hired any more (gone, level unknown).
     */
    public void terms(Character owner, int citizenCharacterId, Context context) {
        terms(owner, citizenCharacterId, context, null, null);
    }

    /** {@code backToList}: the hire list the terms were opened from, which Decline opens again (null: it closes). */
    public void terms(Character owner, int citizenCharacterId, Context context, Runnable backToList) {
        terms(owner, citizenCharacterId, context, null, backToList);
    }

    /** {@code top}: a line above the terms ("Things changed meanwhile."), or null. */
    private void terms(Character owner, int citizenCharacterId, Context context, String top, Runnable backToList) {
        HiredHands hands = world.hiredHands();
        Persona persona = hands == null || !LivingTalk.mayShow(owner) ? null : hands.persona(HiredHands.hireId(citizenCharacterId));
        HiredHands.Terms terms = persona == null ? null : hands.terms(owner, citizenCharacterId);
        if (terms == null) {
            owner.sendPacket(PacketCreator.enableActions());
            return;
        }
        String lead = hands.voice(citizenCharacterId, context == Context.OFFER
                ? "this was fun. want me to stick around?" : "looking for work, actually. want a hand?");
        String text = (top == null ? "" : top + "\r\n\r\n") + lead + "\r\n\r\n"
                + "#e" + persona.name() + "#n, Lv" + terms.level() + (terms.job().isEmpty() ? "" : " " + terms.job()) + "\r\n"
                + "Fee: #b" + meso(terms.fee()) + " meso#k up front\r\n"
                + "Wages: #b" + meso(terms.hourlyWage()) + " meso#k an hour while I work at camp (nothing while I'm out"
                + " with you or resting)";
        LivingTalk.open(owner, CompanionMenus.BOARD_NPC)
                .acceptDecline(text, () -> accept(owner, citizenCharacterId, terms, context, backToList),
                        () -> decline(owner, citizenCharacterId, persona, context, backToList))
                .show();
    }

    /**
     * An offer: in character, the offer closes and the citizen will not ask again for a while (HireOffers). From the
     * hire list: the list again, as the answer to the click (comparing candidates is no string of whispers); else it
     * closes quietly.
     */
    private void decline(Character owner, int citizenCharacterId, Persona persona, Context context, Runnable backToList) {
        if (context != Context.OFFER) {
            if (backToList != null) {
                backToList.run();
            }
            return;
        }
        world.residents().whisper(persona, owner, world.hiredHands().voice(citizenCharacterId, "no worries. i'll be around."));
        HireOffers offers = world.hireOffers();
        if (offers != null) {
            int ownerId = owner.getId();
            world.executor().submit("hire.decline " + citizenCharacterId, () -> offers.declined(ownerId, citizenCharacterId));
        }
    }

    /** The hire on companion-ops; its answer on the owner's packet thread, as a click's answer is (CompanionMenus.command). */
    private void accept(Character owner, int citizenCharacterId, HiredHands.Terms shown, Context context, Runnable backToList) {
        long clickedAtMs = System.currentTimeMillis();
        world.executor().submit("hire.accept " + citizenCharacterId, () -> {
            HiredHands.Outcome outcome = world.hiredHands().hire(owner, citizenCharacterId, shown);
            Client client = owner.getClient();
            if (client == null) {
                return; // logged off meanwhile
            }
            client.runOnIoThread(() -> answer(owner, citizenCharacterId, context, backToList, outcome, clickedAtMs));
        });
    }

    /**
     * Terms changed since the window: they open again with the change on top when a window may open and the click was
     * quick, else pink text. Anything else: the citizen's own words by whisper (a hire's "deal" was said as it was
     * paid), and the roster's line in pink.
     */
    private void answer(Character owner, int citizenCharacterId, Context context, Runnable backToList,
                        HiredHands.Outcome outcome, long clickedAtMs) {
        if (outcome.result() == HiredHands.Result.CHANGED) {
            if (System.currentTimeMillis() - clickedAtMs <= CompanionMenus.LATE_OUTCOME_MS && LivingTalk.mayShow(owner)) {
                terms(owner, citizenCharacterId, context, CompanionMenus.THINGS_CHANGED, backToList);
            } else {
                owner.dropMessage(5, outcome.notice());
            }
            return;
        }
        Persona persona = world.hiredHands().persona(HiredHands.hireId(citizenCharacterId));
        if (persona != null && !outcome.line().isEmpty()) {
            world.residents().whisper(persona, owner, outcome.line());
        }
        if (!outcome.notice().isEmpty()) {
            owner.dropMessage(5, outcome.notice());
        }
    }

    /** "32,000". */
    private static String meso(long amount) {
        return String.format(Locale.ROOT, "%,d", amount);
    }
}
