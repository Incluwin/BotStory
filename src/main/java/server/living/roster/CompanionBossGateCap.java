package server.living.roster;

import client.Character;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.LivingWorld;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;

/**
 * P15 G8: a companion with no daily entry of a party boss left (Papulatus: 2 a day, ExpeditionBossLog) stays off that
 * boss's gate while its owner stands there. The stock party check (Populatus00.js firstPartyMemberOverBossCap) then
 * never names it, and the owner's party goes in without it. As the owner comes onto the gate, each of their following
 * companions is held for a moment; companion-ops reads its entries, lets a free one follow, and keeps a capped one on
 * the map before with one line, until its owner leaves the gate. A boss with no entry log holds nobody past that read.
 * The follow path reads memory only.
 */
final class CompanionBossGateCap {
    private final LivingWorld world;
    /** May the companion enter boss log {@code name} today (ExpeditionBossLog.attemptBossByName, a store read)? */
    private final BiPredicate<Integer, String> mayEnter;
    /** Companion id -> the gate it is held off while its owner stands there. */
    private final Map<Integer, Integer> held = new ConcurrentHashMap<>();

    CompanionBossGateCap(LivingWorld world, BiPredicate<Integer, String> mayEnter) {
        this.world = world;
        this.mayEnter = mayEnter;
    }

    /**
     * The owner came onto party boss {@code bossKey}'s gate {@code gateMapId} (on the thread that moves them, memory
     * only): their following companions are held, and companion-ops reads each one's entries.
     */
    void ownerEntered(int ownerId, int gateMapId, String bossKey) {
        for (AgentRuntimeEntry entry : AgentRuntimeRegistry.entriesForLeader(ownerId)) {
            int id = AgentRuntimeIdentityRuntime.botId(entry);
            if (id > 0) {
                held.put(id, gateMapId);
                world.executor().submit("boss.gate_cap " + id, () -> check(id, gateMapId, bossKey));
            }
        }
    }

    /** On companion-ops: a free companion follows on; a capped one stays held and says the quota line once. */
    void check(int companionId, int gateMapId, String bossKey) {
        Integer gate = held.get(companionId);
        if (gate == null || gate != gateMapId) {
            return;
        }
        if (mayEnter.test(companionId, bossKey.toUpperCase(Locale.ROOT))) {
            held.remove(companionId, gateMapId);
            return;
        }
        Character bot = world.roster().onlineCharacter(companionId);
        if (bot != null) {
            BossRunLines.say(world, bossKey, bot, BossRunLines.QUOTA, null, 0);
        }
    }

    /** True while the companion is held off the gate its owner (the human it follows) still stands on. */
    boolean holds(int companionId) {
        Integer gate = held.get(companionId);
        if (gate == null) {
            return false;
        }
        Character owner = world.roster().onlineCharacter(AgentRuntimeRegistry.leaderIdForAgentCharacter(companionId));
        if (owner == null || owner.getMapId() != gate) {
            held.remove(companionId, gate);
            return false;
        }
        return true;
    }
}
