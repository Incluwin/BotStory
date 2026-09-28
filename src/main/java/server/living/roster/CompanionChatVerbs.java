package server.living.roster;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.auth.AgentClaimGuard;
import server.living.LivingWorld;
import server.living.config.Content.Persona;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * The one door from chat into the roster (docs/BOT_MANAGEMENT_PLAN.md, "CompanionChatVerbs"): every leader-level chat
 * verb that touches a rostered companion runs its RosterService verb on companion-ops, never on the chat thread, and
 * the owner reads the result as pink text. The verbs:
 * <ul>
 *   <li>the roster's own chat commands, in map chat and in a whisper to the companion (ResidentDialogue): roster,
 *   call, bench, dismiss (bench), park (bench until P2b adds PARKED), swap, release, everyone bench or park, call the
 *   usual, camp here, assignments and errands, the haul, hand-over, Duey, bank and wages;</li>
 *   <li>the Agent chat routes a companion also answers, taken over through {@link AgentClaimGuard.VerbRoute}:
 *   "dismiss", "disown" or "release" &lt;name&gt; benches it instead of unregistering it and leaving it online, and a
 *   confirmed "logout" benches it instead of disconnecting it. A plain GM Agent keeps the Agent route.</li>
 * </ul>
 * Recruit, transfer, "follow &lt;other player&gt;" and whispers ask the claim guard themselves. ChatVerbBoundaryTest lists
 * every leader-level intent and where it goes.
 */
public final class CompanionChatVerbs implements AgentClaimGuard.VerbRoute {
    private static final Logger log = LoggerFactory.getLogger(CompanionChatVerbs.class);

    private final LivingWorld world;

    public CompanionChatVerbs(LivingWorld world) {
        this.world = world;
    }

    /**
     * Runs a roster verb for {@code owner} on companion-ops and shows its line; a verb for an owner who has logged off
     * by then is dropped.
     */
    public CompletableFuture<Void> say(Character owner, String name, Supplier<String> verb) {
        return show(owner, name, 5, () -> List.of(verb.get()));
    }

    /** Like {@link #say} for several lines of message {@code type} (a listing, or one line per companion). */
    public CompletableFuture<Void> show(Character owner, String name, int type, Supplier<List<String>> lines) {
        return world.executor().submit(name, () -> {
            if (!owner.isLoggedin()) {
                return;
            }
            for (String line : lines.get()) {
                owner.dropMessage(type, line);
            }
        });
    }

    /** "park &lt;name&gt;": until P2b's PARKED state it is a bench. */
    public CompletableFuture<Void> park(Character owner, Persona persona) {
        return say(owner, "roster.park", () -> RosterVoice.line(persona, world.roster().bench(owner, persona)));
    }

    /**
     * The Agent routes' dismiss and confirmed logout of a rostered companion: its owner benches it (P2b turns the
     * logout into PARKED). The verb is taken, so the Agent route does nothing, even when the owner is offline and
     * the bench cannot run; a plain Agent or a Resident nobody owns keeps the Agent route.
     */
    @Override
    public boolean take(Character actor, int agentCharacterId, AgentClaimGuard.Verb verb) {
        Persona persona = world.residents().personaOfCharacterId(agentCharacterId);
        int ownerId = persona == null ? 0 : world.roster().cachedOwnerOf(persona.id());
        if (ownerId == 0) {
            return false;
        }
        Character owner = world.roster().onlineCharacter(ownerId);
        if (owner == null) {
            log.info("{} of {} kept: its owner {} is offline", verb, persona.id(), ownerId);
            return true;
        }
        say(owner, "chat." + verb.name().toLowerCase(Locale.ROOT),
                () -> RosterVoice.line(persona, world.roster().bench(owner, persona)));
        return true;
    }
}
