package server.living.roster;

import server.living.config.Content.Persona;

/**
 * Says a {@link RosterResult} to the owner (docs/BOT_MANAGEMENT_PLAN.md, "RosterService"). The words are the ones the
 * roster verbs always printed, so the chat commands in ResidentDialogue and the Crew window read the same; the
 * companion's own in-character reply (its whisper or bubble) is sent by the verb itself.
 */
public final class RosterVoice {
    private RosterVoice() {
    }

    public static String line(Persona persona, RosterResult result) {
        return line(persona == null ? "They" : persona.name(), result);
    }

    public static String line(String name, RosterResult result) {
        return switch (result) {
            case RosterResult.Ok ok -> ok.line();
            case RosterResult.Travelling travelling -> name + " is on the way (" + RecallTravel.describe(travelling.etaMs()) + ").";
            case RosterResult.PartyFull full -> "Your party is full. Bench someone or name who to swap.";
            case RosterResult.LeaderCapped capped -> "The party leader sent one of yours out: that seat stays empty here.";
            case RosterResult.QuotaFull quota -> name + " could not join right now.";
            case RosterResult.BudgetExhausted budget -> name + " could not join right now.";
            case RosterResult.Busy busy -> name + " could not join right now.";
            case RosterResult.RosterFull full -> "Your roster is full (" + full.max() + "). Release someone first.";
            case RosterResult.NotOwned notOwned -> name + " is not in your roster.";
            case RosterResult.InEventInstance instance -> name + ": after the run.";
            case RosterResult.Failed failed -> failed.reason();
        };
    }
}
