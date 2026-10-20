package server.living.roster;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.expedition.boss.AgentBossRunCatalog;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.store.LivingStore;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CWKPQ plan C3: only a Crimsonwood Keystone (3992041) lets a player into the Hall to Inner Sanctum, where Jack signs
 * the keep's expedition up (glpqEnter.js and Gate 9201114 check it; follow sync never runs them). As the owner comes
 * into the hall, each of their following companions without a keystone of its own stays in the Hall of Mastery it
 * stood in, says why in party chat (and how to send it for one: KeystoneErrands), and follows again once its owner
 * leaves the hall. The hall's bench allies are loaded straight onto it, so the ally picker offers only those that hold
 * one ({@link #lacks}, read ahead on companion-ops when the owner comes in) and the call reads the bag once more.
 *
 * <p>On while COMPANION_KEYSTONE_ERRANDS_ENABLED is: a companion can earn its own keystone since then, so it no longer
 * walks through the gate on its owner's. The follow path reads memory only.</p>
 */
final class CompanionKeystoneGate {
    private static final Logger log = LoggerFactory.getLogger(CompanionKeystoneGate.class);
    /** The Hall to Inner Sanctum, Jack's room (the CWKPQ run's recruit map). */
    static final int HALL_MAP_ID = AgentBossRunCatalog.CWKPQ.recruitMapId();
    static final int KEYSTONE_ITEM_ID = KeystoneErrands.KEYSTONE_ITEM_ID;

    private final LivingWorld world;
    /** Companion id -> the hall it is held off while its owner stands there. */
    private final Map<Integer, Integer> held = new ConcurrentHashMap<>();
    /** Persona id -> whether it held a keystone when last read (the ally picker's view, memory only). */
    private final Map<String, Boolean> benchHolds = new ConcurrentHashMap<>();

    CompanionKeystoneGate(LivingWorld world) {
        this.world = world;
    }

    /** True while the gate is on: the keystone errands are (a companion can earn its own keystone). */
    boolean on() {
        return world.config() != null && world.config().COMPANION_KEYSTONE_ERRANDS_ENABLED;
    }

    /**
     * The owner came into {@code mapId} (on the thread that moves them, memory only). Into the hall, each following
     * companion of theirs without a keystone is held off it, and companion-ops has it say why and reads which of the
     * owner's benched companions hold one.
     */
    void ownerEntered(int ownerId, int mapId) {
        if (mapId != HALL_MAP_ID || !on()) {
            return;
        }
        for (AgentRuntimeEntry entry : AgentRuntimeRegistry.entriesForLeader(ownerId)) {
            int id = AgentRuntimeIdentityRuntime.botId(entry);
            Character bot = id > 0 ? AgentRuntimeIdentityRuntime.bot(entry) : null;
            if (bot != null && !bot.haveItem(KEYSTONE_ITEM_ID) && ownersCompanion(id, ownerId)) {
                held.put(id, mapId);
                world.executor().submit("cwkpq.keystone_gate " + id, () -> sayHeld(id, mapId));
            }
        }
        world.executor().submit("cwkpq.keystone_bench " + ownerId, () -> readBench(ownerId));
    }

    /** True while the companion is held off the hall its owner (the human it follows) still stands in. */
    boolean holds(int companionId) {
        Integer hall = held.get(companionId);
        if (hall == null) {
            return false;
        }
        Character owner = world.roster().onlineCharacter(AgentRuntimeRegistry.leaderIdForAgentCharacter(companionId));
        if (owner == null || owner.getMapId() != hall) {
            held.remove(companionId, hall);
            return false;
        }
        return true;
    }

    /**
     * From memory, for the ally picker: true when {@code personaId} held no keystone when last read (the owner's
     * coming into the hall reads it); unknown is false, and the call reads again ({@link #holdsNow}).
     */
    boolean lacks(String personaId) {
        return Boolean.FALSE.equals(benchHolds.get(personaId));
    }

    /**
     * On companion-ops: whether the companion with character id {@code characterId} holds a keystone now, from its bag
     * when it is loaded, else from its saved inventory; a failed read answers true (the stock gate is not ours to
     * enforce harder than the picker). Remembered for the picker.
     */
    boolean holdsNow(String personaId, int characterId) {
        boolean holds;
        Character loaded = characterId == 0 ? null : loaded(characterId);
        if (loaded != null) {
            holds = loaded.haveItem(KEYSTONE_ITEM_ID);
        } else {
            try {
                holds = characterId != 0 && world.store().holdsItem(characterId, KEYSTONE_ITEM_ID);
            } catch (LivingStore.ReadFailed failure) {
                return true;
            }
        }
        benchHolds.put(personaId, holds);
        return holds;
    }

    /** On companion-ops: a still-held companion says why it waits, in its owner's party. */
    private void sayHeld(int companionId, int hallMapId) {
        Integer hall = held.get(companionId);
        Character bot = hall == null || hall != hallMapId ? null : loaded(companionId);
        if (bot != null) {
            log.info("CWKPQ keystone gate: companion {} has no keystone and waits on {}", companionId, bot.getMapId());
            BossRunLines.say(world, "cwkpq", bot, BossRunLines.KEYSTONE, null, 0);
        }
    }

    /** On companion-ops: which of the owner's benched companions hold a keystone, for the ally picker. */
    private void readBench(int ownerId) {
        for (RosterEntry row : world.store().roster(ownerId)) {
            if (row.state() != RosterState.BENCHED) {
                continue;
            }
            Persona persona = world.content().persona(row.personaId());
            if (persona != null) {
                holdsNow(persona.id(), world.residents().characterIdOf(persona.id()));
            }
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
}
