package server.living.roster;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.auth.AgentAuthorityService;
import server.agents.auth.AgentClaimGuard;
import server.agents.capabilities.dialogue.AgentChatCommandClassifier;
import server.agents.capabilities.dialogue.AgentChatRouteCoordinator;
import server.agents.capabilities.dialogue.AgentChatSessionRequestFlow;
import server.agents.capabilities.dialogue.AgentPendingChatActionFlow;
import server.agents.capabilities.movement.AgentFormationRuntime;
import server.agents.capabilities.movement.AgentMovementPhysicsConfig;
import server.agents.commands.AgentReplyChannel;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeConfig;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The one door from chat into the roster (docs/BOT_MANAGEMENT_PLAN.md, "CompanionChatVerbs"): every leader-level chat
 * verb that touches a rostered companion runs its RosterService verb on companion-ops, never on the chat thread, and
 * the owner reads the result as pink text. The verbs:
 * <ul>
 *   <li>the roster's own chat commands, in map chat and in a whisper to the companion (ResidentDialogue): roster,
 *   call, bench, dismiss (bench), park (bench until P2b adds PARKED), swap, release, everyone bench or park, call the
 *   usual, camp here, assignments and errands, the haul, hand-over, Duey, bank and wages, and an untargeted
 *   "logout" + "yes" ({@link #logoutTurn}), which parks everyone out;</li>
 *   <li>the Agent chat routes a companion also answers, taken over through {@link AgentClaimGuard.VerbRoute}:
 *   "dismiss", "disown" or "release" &lt;name&gt; benches it instead of unregistering it and leaving it online, and a
 *   confirmed "logout" benches it instead of disconnecting it. A plain GM Agent keeps the Agent route.</li>
 *   <li>"sell junk" to one companion out with its owner in map chat ({@link #sellNow}): the Agent's own sell-now
 *   command, given to that one companion's Agent; any other Agent command with its name last too ({@link #toAgent}).
 *   </li>
 * </ul>
 * Recruit, transfer, "follow &lt;other player&gt;" and whispers ask the claim guard themselves. ChatVerbBoundaryTest lists
 * every leader-level intent and where it goes.
 */
public final class CompanionChatVerbs implements AgentClaimGuard.VerbRoute {
    private static final Logger log = LoggerFactory.getLogger(CompanionChatVerbs.class);
    /** How long an untargeted "logout" waits for its "yes". */
    public static final long LOGOUT_CONFIRM_MS = 30_000L;
    /** The Agent's own sell-now command (AgentChatUtilityFlow), which {@link #sellNow} gives one companion. */
    static final String SELL_NOW = "sell trash";

    /** What an owner's map-chat line did to their untargeted "logout" question. */
    public enum LogoutTurn {
        /** Not a logout matter: the line goes on to the other routes. */
        NONE,
        /** "logout" with a companion out: one of them asks, and the line goes no further. */
        ASKED,
        /** Its confirm, in time: the caller parks everyone out, and the line goes no further. */
        CONFIRMED,
        /** A "no" to it, in time: the companion that asked says so, and the line goes no further. */
        CANCELLED,
        /**
         * The owner also leads a plain Agent, which asks for itself: "logout" and its answer go straight to the Agent
         * routes (the caller hands them over), so no Resident or citizen answers the "yes" first.
         */
        TO_AGENTS
    }

    /** An untargeted "logout" asked at {@code atMs} by {@code asker}; null when the owner's plain Agent asks. */
    private record LogoutAsked(long atMs, Persona asker) { }

    private final LivingWorld world;
    private final LongSupplier clock;
    /** owner id -> their untargeted "logout" question; written from the chat threads. */
    private final Map<Integer, LogoutAsked> logoutAsked = new ConcurrentHashMap<>();

    public CompanionChatVerbs(LivingWorld world) {
        this(world, System::currentTimeMillis);
    }

    CompanionChatVerbs(LivingWorld world, LongSupplier clock) {
        this.world = world;
        this.clock = clock;
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

    /**
     * An owner's untargeted "logout" + "yes" in map chat (park for companions), asked ahead of every other route: no
     * Resident, citizen or Agent offer can answer the "yes" first, and no companion is left holding an Agent route's
     * logout prompt that never expires. {@code line} is the raw line, lower-cased (normalizing drops "yeah" and "ok"
     * as fillers). "logout" asks when a companion is out (one of them says it), and a newer "logout" asks again;
     * within {@link #LOGOUT_CONFIRM_MS} a "no" is CANCELLED (tested first, so "yes nvm" is a no) and a confirm is
     * CONFIRMED; any other line drops the question. An owner who also leads a plain Agent is left to the Agent
     * routes, which log that Agent off as before and hand each companion to {@link #take}: TO_AGENTS.
     */
    public LogoutTurn logoutTurn(Character owner, String line) {
        LogoutAsked asked = logoutAsked.remove(owner.getId()); // this line answers the question or drops it
        long now = clock.getAsLong();
        if (AgentChatCommandClassifier.isLogoutRequest(line)) {
            Persona asker = firstOut(owner);
            if (asker == null) {
                return LogoutTurn.NONE;
            }
            if (leadsPlainAgent(owner)) {
                logoutAsked.put(owner.getId(), new LogoutAsked(now, null));
                return LogoutTurn.TO_AGENTS;
            }
            logoutAsked.put(owner.getId(), new LogoutAsked(now, asker));
            world.residents().reply(asker, owner, AgentChatSessionRequestFlow.logoutConfirmPrompt());
            return LogoutTurn.ASKED;
        }
        if (asked == null || now - asked.atMs() > LOGOUT_CONFIRM_MS) {
            return LogoutTurn.NONE;
        }
        boolean no = AgentChatCommandClassifier.isNegativeConfirm(line);
        if (!no && !AgentChatCommandClassifier.isLogoutConfirm(line)) {
            return LogoutTurn.NONE;
        }
        if (asked.asker() == null) {
            return LogoutTurn.TO_AGENTS;
        }
        if (no) {
            world.residents().reply(asked.asker(), owner, AgentPendingChatActionFlow.pendingActionCancelReply(false));
            return LogoutTurn.CANCELLED;
        }
        return LogoutTurn.CONFIRMED;
    }

    /** The owner's first companion out (ACTIVE), which asks; null when none is. */
    private Persona firstOut(Character owner) {
        for (RosterEntry entry : world.roster().roster(owner)) {
            Persona persona = entry.state() == RosterState.ACTIVE ? world.content().persona(entry.personaId()) : null;
            if (persona != null) {
                return persona;
            }
        }
        return null;
    }

    /** True when the owner leads a loaded Agent the living world does not claim (a GM's own Agent). */
    private boolean leadsPlainAgent(Character owner) {
        for (AgentRuntimeEntry entry : AgentRuntimeRegistry.entriesForLeader(owner.getId())) {
            if (!world.directory().isClaimed(AgentRuntimeIdentityRuntime.botId(entry))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The owner's map line "sell junk" to one companion out with them, its name first or last (plan 12.2 "Supplies and
     * loot", deploy review 2026-10-10): "&lt;name&gt; sell trash", the Agent's own sell-now command, as the owner's map
     * line over exactly that companion (CompanionManager.crewOrder's door), so the targeted route gives it to that
     * Agent alone and it answers in map chat. Nothing happens while its Agent is not out with the owner yet, or the
     * owner may not command it (AgentAuthorityService.mayOperate, AgentClaimGuard.mayControl), as on the Agent route.
     */
    public void sellNow(Character owner, Persona persona) {
        toAgent(owner, persona, SELL_NOW);
    }

    /**
     * The owner's map line {@code command} to one companion out with them, its name last (second deploy review
     * 2026-10-10: "stop tamsin", "sell your junk tamsin" and "heals off tamsin" reached every companion out through
     * the phrase packs, which allow two unknown trailing words): "&lt;name&gt; &lt;command&gt;", the line with the
     * name first, over exactly that companion, as {@link #sellNow} does. Nothing happens while its Agent is not out
     * with the owner yet, or the owner may not command it.
     */
    public void toAgent(Character owner, Persona persona, String command) {
        AgentRuntimeEntry runtime = null;
        for (AgentRuntimeEntry entry : AgentRuntimeRegistry.entriesForLeader(owner.getId())) {
            if (AgentRuntimeIdentityRuntime.botNameEquals(entry, persona.name())) {
                runtime = entry;
                break;
            }
        }
        if (runtime == null || !AgentAuthorityService.mayOperate(owner)
                || !AgentClaimGuard.mayControl(owner, AgentRuntimeIdentityRuntime.botId(runtime))) {
            return;
        }
        AgentChatRouteCoordinator.handleChat(owner, AgentRuntimeIdentityRuntime.botName(runtime) + " " + command,
                AgentReplyChannel.MAP, Map.of(owner.getId(), List.of(runtime)),
                (leaderId, leader, name) -> null, (leaderId, leader, name, target) -> null, (leaderId, name) -> false,
                AgentFormationRuntime.defaultFormationState(), AgentRuntimeConfig.cfg.FOLLOW_STAGGER,
                AgentMovementPhysicsConfig.configuredFollowYCap());
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
