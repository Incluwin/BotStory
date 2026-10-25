package server.living.roster;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.capabilities.partyquest.apq.AgentApqDefinition;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.life.NPC;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * APQ plan A2, decision 2 (tickets earned, stock for everyone): Amos the Strong (9201043, Amos' Training Ground) takes
 * one Entrance Ticket (4031592) from each player for the way into the Entrance of the Amorian Challenge, and trades ten
 * Lip Lock Keys (4031593) for a ticket. Follow sync and a bench call never run his script, so while an owner stands on
 * the entrance each companion of theirs still at his training ground (it followed them there, or was sent for and
 * arrived there: {@link #arrivalMapId}) is held off follow. One with a ticket, or ten keys, walks up to Amos the Strong
 * and talks to him as a player does: his own script trades the keys if it must, takes the ticket and sends it in, and it
 * follows again from there. One with neither says so in its owner's party and waits there until its owner leaves the
 * entrance. On while the APQ desk is (COMPANION_PQ_ENABLED_KEYS lists apq); the follow path reads memory only.
 */
final class CompanionTicketGate {
    private static final Logger log = LoggerFactory.getLogger(CompanionTicketGate.class);
    static final int ENTRANCE_MAP = AgentApqDefinition.RECRUIT_MAP;
    static final int TRAINING_GROUND_MAP = AgentApqDefinition.GATE_MAP;
    static final int AMOS_THE_STRONG = AgentApqDefinition.GATE_NPC;
    static final int TICKET = AgentApqDefinition.ENTRANCE_TICKET;
    static final int KEY = AgentApqDefinition.LIP_LOCK_KEY;
    /** 9201043.js's menu: "Enter the Amorian Challenge!!" and "Trade 10 Keys for a Ticket!". */
    static final int ENTER = 0;
    static final int TRADE = 1;
    static final String NO_TICKET = "i don't have an entrance ticket. tell me to get my ticket and i'll farm the ten "
            + "lip lock keys in purplewood.";
    /** How often a visit looks again while it walks up and talks. */
    static final long CHECK_MS = 1_000L;
    /** The longest walk up to Amos the Strong before it talks from where it got to. */
    static final long WALK_MAX_MS = 20_000L;
    /** How long it stands with him before his script runs, as a player reads the menu. */
    static final long TALK_MS = 2_000L;
    static final int REACH_PX = 80;

    /** What runs his script on the companion's own mailbox (a test stands in for the Agent). */
    interface Hands {
        /** Talks to Amos the Strong with {@code selection} on his menu; true when the talk ran. */
        boolean talk(AgentRuntimeEntry runtime, int selection);
    }

    private final LivingWorld world;
    private final LongSupplier clock;
    private final Hands hands;
    /** Companion id -> its way through the gate while its owner is on the entrance. */
    private final Map<Integer, Visit> held = new ConcurrentHashMap<>();

    private static final class Visit {
        final int ownerId;
        final long startedAtMs;
        volatile Point spot;
        volatile long reachedAtMs;
        volatile boolean waiting;

        Visit(int ownerId, long startedAtMs) {
            this.ownerId = ownerId;
            this.startedAtMs = startedAtMs;
        }
    }

    CompanionTicketGate(LivingWorld world) {
        this(world, System::currentTimeMillis, new AgentHands());
    }

    CompanionTicketGate(LivingWorld world, LongSupplier clock, Hands hands) {
        this.world = world;
        this.clock = clock;
        this.hands = hands;
    }

    /** True while the gate is on: the APQ desk is (a companion goes in on its own ticket since then). */
    boolean on() {
        return world.config() != null && world.config().companionPqEnabled("apq");
    }

    /**
     * Where a companion sent for by an owner standing on {@code ownerMapId} arrives: Amos' Training Ground while the
     * owner is on the entrance (it comes in by the gate like any player), else the owner's own map.
     */
    int arrivalMapId(int ownerMapId) {
        return ownerMapId == ENTRANCE_MAP && on() ? TRAINING_GROUND_MAP : ownerMapId;
    }

    /**
     * The owner came into {@code mapId} (on the thread that moves them, memory only): onto the entrance, each of their
     * own companions still on Amos' Training Ground starts its way through the gate.
     */
    void ownerEntered(int ownerId, int mapId) {
        if (mapId != ENTRANCE_MAP || !on()) {
            return;
        }
        for (AgentRuntimeEntry entry : AgentRuntimeRegistry.entriesForLeader(ownerId)) {
            int id = AgentRuntimeIdentityRuntime.botId(entry);
            Character bot = id > 0 ? AgentRuntimeIdentityRuntime.bot(entry) : null;
            if (bot != null && bot.getMapId() == TRAINING_GROUND_MAP && ownersCompanion(id, ownerId)) {
                begin(id, ownerId);
            }
        }
    }

    /**
     * From memory, on whatever thread follows: true while the companion is held at Amos' Training Ground, its owner on
     * the entrance. A companion of theirs found there with no visit yet (a bench call landed it there) starts one.
     */
    boolean holds(int companionId) {
        Visit visit = held.get(companionId);
        if (visit == null) {
            int ownerId = AgentRuntimeRegistry.leaderIdForAgentCharacter(companionId);
            Character owner = ownerId > 0 ? world.roster().onlineCharacter(ownerId) : null;
            if (owner == null || owner.getMapId() != ENTRANCE_MAP || !on()) {
                return false; // the fast path: its owner is anywhere else
            }
            Character bot = loaded(companionId);
            if (bot == null || bot.getMapId() != TRAINING_GROUND_MAP || !ownersCompanion(companionId, ownerId)) {
                return false;
            }
            return begin(companionId, ownerId);
        }
        Character owner = world.roster().onlineCharacter(visit.ownerId);
        Character bot = loaded(companionId);
        if (owner == null || owner.getMapId() != ENTRANCE_MAP || bot == null || bot.getMapId() != TRAINING_GROUND_MAP) {
            held.remove(companionId, visit);
            return false;
        }
        return true;
    }

    /** Holds the companion and starts its visit on companion-ops; true when it holds it (now or already). */
    private boolean begin(int companionId, int ownerId) {
        Visit visit = new Visit(ownerId, clock.getAsLong());
        if (held.putIfAbsent(companionId, visit) == null) {
            world.executor().submit("apq.ticket_gate " + companionId, () -> start(companionId, visit));
        }
        return true;
    }

    /** On companion-ops: a companion with a ticket or ten keys walks up to Amos the Strong; one without says why. */
    private void start(int companionId, Visit visit) {
        Character bot = held.get(companionId) == visit ? loaded(companionId) : null;
        if (bot == null) {
            return;
        }
        if (bot.getItemQuantity(TICKET, false) <= 0 && bot.getItemQuantity(KEY, false) < AgentApqDefinition.KEYS_PER_TICKET) {
            visit.waiting = true;
            log.info("APQ ticket gate: companion {} has no ticket and {} keys, waits on {}", companionId,
                    bot.getItemQuantity(KEY, false), bot.getMapId());
            try {
                AgentPartyGatewayRuntime.party().sendPartyChat(bot, NO_TICKET);
            } catch (RuntimeException failure) {
                log.debug("party chat failed for {}", bot.getName(), failure);
            }
            return;
        }
        MapleMap map = bot.getMap();
        NPC amos = map == null ? null : map.getNPCById(AMOS_THE_STRONG);
        visit.spot = amos == null ? null : amos.getPosition();
        AgentRuntimeEntry runtime = AgentRuntimeRegistry.findByAgentCharacterId(companionId);
        if (visit.spot != null && runtime != null) {
            AgentMovementCommandRuntime.moveTo(runtime, visit.spot, false);
        }
        world.roster().later("apq.ticket_gate.visit " + companionId, () -> visit(companionId, visit), CHECK_MS);
    }

    /** It looks again: there yet, done reading his menu, then his script: the keys traded if it must, then in. */
    private void visit(int companionId, Visit visit) {
        Character bot = held.get(companionId) == visit ? loaded(companionId) : null;
        AgentRuntimeEntry runtime = bot == null ? null : AgentRuntimeRegistry.findByAgentCharacterId(companionId);
        if (bot == null || runtime == null) {
            return; // let go meanwhile (its owner left the entrance), or unloaded
        }
        long now = clock.getAsLong();
        Point at = bot.getPosition();
        boolean there = visit.spot == null || at != null && at.distance(visit.spot) <= REACH_PX;
        if (!there && now - visit.startedAtMs < WALK_MAX_MS) {
            world.roster().later("apq.ticket_gate.visit " + companionId, () -> visit(companionId, visit), CHECK_MS);
            return;
        }
        if (visit.reachedAtMs == 0L) {
            visit.reachedAtMs = now;
        }
        if (now - visit.reachedAtMs < TALK_MS) {
            world.roster().later("apq.ticket_gate.visit " + companionId, () -> visit(companionId, visit),
                    visit.reachedAtMs + TALK_MS - now);
            return;
        }
        boolean traded = bot.getItemQuantity(TICKET, false) > 0 || hands.talk(runtime, TRADE);
        boolean in = traded && bot.getItemQuantity(TICKET, false) > 0 && hands.talk(runtime, ENTER)
                && bot.getMapId() == ENTRANCE_MAP;
        if (in) {
            held.remove(companionId, visit);
            log.info("APQ ticket gate: companion {} gave Amos the Strong its ticket and went in", companionId);
        } else {
            visit.waiting = true; // still held: it never follows in on its owner's ticket
            log.warn("APQ ticket gate: companion {} could not go in (traded={})", companionId, traded);
        }
    }

    /** The loaded Agent with character id {@code characterId}, or null when nothing has it loaded. */
    private static Character loaded(int characterId) {
        return AgentRuntimeIdentityRuntime.bot(AgentRuntimeRegistry.findByAgentCharacterId(characterId));
    }

    /** True when the Agent following {@code ownerId} is the owner's own companion, not a citizen or a guest. */
    private boolean ownersCompanion(int agentId, int ownerId) {
        Persona persona = world.residents().personaOfCharacterId(agentId);
        return persona != null && world.roster().cachedOwnerOf(persona.id()) == ownerId;
    }

    /** Amos the Strong's script on the companion's own mailbox, as a player's click runs it. */
    private static final class AgentHands implements Hands {
        @Override
        public boolean talk(AgentRuntimeEntry runtime, int selection) {
            return Boolean.TRUE.equals(BenchDutyDispatcher.onAgent(runtime, entry -> {
                PrimitiveCapabilityGateway gateway = AgentPrimitiveCapabilityGatewayRuntime.gateway();
                return gateway.runNpcScript(entry.bot(), AMOS_THE_STRONG, selection);
            }));
        }
    }
}
