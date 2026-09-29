package server.living.roster.ui;

import client.Character;
import client.Client;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.resident.LivingTalk;
import server.living.roster.HiredHands;
import tools.PacketCreator;

import java.util.Locale;

/**
 * The hire terms window (docs/BOT_MANAGEMENT_PLAN.md P4, flow 9 step 4 and "Hire list"), shared by a citizen's own
 * offer after party time and the Crew window's "Adventurers looking for a crew": the quest-style Accept/Decline window
 * under the Bulletin Board portrait (a hire has no rendered face), in the citizen's voice, with its name, level, job,
 * fee and daily wage. Accept runs {@link HiredHands#hire} on companion-ops on the terms shown and the citizen answers
 * in its own words (a whisper; the roster's line in pink when it is on its way or waits for room), or the terms open
 * again when they changed meanwhile; Decline answers in character. The window is only ever the answer to the player's
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
        terms(owner, citizenCharacterId, context, null);
    }

    /** {@code top}: a line above the terms ("Things changed meanwhile."), or null. */
    private void terms(Character owner, int citizenCharacterId, Context context, String top) {
        HiredHands hands = world.hiredHands();
        Persona persona = hands == null || !LivingTalk.mayShow(owner) ? null : hands.persona(HiredHands.hireId(citizenCharacterId));
        HiredHands.Terms terms = persona == null ? null : hands.terms(citizenCharacterId);
        if (terms == null) {
            owner.sendPacket(PacketCreator.enableActions());
            return;
        }
        String lead = hands.voice(citizenCharacterId, context == Context.OFFER
                ? "this was fun. want me to stick around?" : "looking for work, actually. want a hand?");
        String text = (top == null ? "" : top + "\r\n\r\n") + lead + "\r\n\r\n"
                + "#e" + persona.name() + "#n, Lv" + terms.level() + (terms.job().isEmpty() ? "" : " " + terms.job()) + "\r\n"
                + "Fee: #b" + meso(terms.fee()) + " meso#k up front\r\n"
                + "Wages: #b" + meso(terms.dailyWage()) + " meso#k a day on the bench, out of my haul unless you pay ahead";
        LivingTalk.open(owner, CompanionMenus.BOARD_NPC)
                .acceptDecline(text, () -> accept(owner, citizenCharacterId, terms, context),
                        () -> world.residents().whisper(persona, owner, hands.voice(citizenCharacterId,
                                context == Context.OFFER ? "no worries. i'll be around." : "sure. you know where to find me.")))
                .show();
    }

    /** The hire on companion-ops; its answer on the owner's packet thread, as a click's answer is (CompanionMenus.command). */
    private void accept(Character owner, int citizenCharacterId, HiredHands.Terms shown, Context context) {
        long clickedAtMs = System.currentTimeMillis();
        world.executor().submit("hire.accept " + citizenCharacterId, () -> {
            HiredHands.Outcome outcome = world.hiredHands().hire(owner, citizenCharacterId, shown);
            Client client = owner.getClient();
            if (client == null) {
                return; // logged off meanwhile
            }
            client.runOnIoThread(() -> answer(owner, citizenCharacterId, context, outcome, clickedAtMs));
        });
    }

    /**
     * Terms changed since the window: they open again with the change on top when a window may open and the click was
     * quick, else pink text. Anything else: the citizen's own words by whisper, and the roster's line in pink.
     */
    private void answer(Character owner, int citizenCharacterId, Context context, HiredHands.Outcome outcome, long clickedAtMs) {
        if (outcome.result() == HiredHands.Result.CHANGED) {
            if (System.currentTimeMillis() - clickedAtMs <= CompanionMenus.LATE_OUTCOME_MS && LivingTalk.mayShow(owner)) {
                terms(owner, citizenCharacterId, context, CompanionMenus.THINGS_CHANGED);
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
