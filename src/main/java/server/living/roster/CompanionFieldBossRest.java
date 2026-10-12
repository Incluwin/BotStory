package server.living.roster;

import client.Character;
import config.LivingWorldConfig;
import server.agents.capabilities.expedition.boss.AgentBossField;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.maps.MapleMap;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P13 G14 (decision 18): a companion that dies beside a field group's boss whose key is on rests in town before it
 * follows its owner again, since follow would warp it straight back at 50 HP (AgentFollowMapSyncService). The follow
 * guard keeps it off follow (CompanionFollowGuard) while it is dead, and once revived until its HP and MP are back to
 * COMPANION_FIELD_BOSS_REST_PERCENT of their maximum or COMPANION_FIELD_BOSS_REST_MAX_MS has passed since it died.
 * Memory only, any thread.
 */
final class CompanionFieldBossRest {
    private final LivingWorld world;
    /** Companion character id -> when it died beside a field boss. */
    private final Map<Integer, Long> diedAtMs = new ConcurrentHashMap<>();

    CompanionFieldBossRest(LivingWorld world) {
        this.world = world;
    }

    /**
     * On companion-ops: the character died on {@code map} (LivingWorldHooks.playerDied). A companion that died beside
     * a covered field boss starts to rest.
     */
    void died(int characterId, MapleMap map, long nowMs) {
        if (AgentBossField.near(map) && companion(characterId)) {
            diedAtMs.put(characterId, nowMs);
        }
    }

    /** True while the companion rests: dead, or revived and not yet recovered, within the rest's time limit. */
    boolean resting(int characterId, long nowMs) {
        Long died = diedAtMs.get(characterId);
        if (died == null) {
            return false;
        }
        LivingWorldConfig config = world.config();
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(characterId);
        Character chr = entry == null ? null : entry.bot();
        if (config == null || chr == null || nowMs - died >= config.COMPANION_FIELD_BOSS_REST_MAX_MS) {
            diedAtMs.remove(characterId);
            return false;
        }
        if (!chr.isAlive()) {
            return true;
        }
        int percent = config.COMPANION_FIELD_BOSS_REST_PERCENT;
        boolean recovered = (long) chr.getHp() * 100 >= (long) chr.getCurrentMaxHp() * percent
                && (long) chr.getMp() * 100 >= (long) chr.getCurrentMaxMp() * percent;
        if (recovered) {
            diedAtMs.remove(characterId);
        }
        return !recovered;
    }

    /** A companion: a persona whose owner it follows. */
    private boolean companion(int characterId) {
        Persona persona = world.residents().personaOfCharacterId(characterId);
        int ownerId = persona == null ? 0 : world.roster().cachedOwnerOf(persona.id());
        return ownerId > 0 && AgentRuntimeRegistry.leaderIdForAgentCharacter(characterId) == ownerId;
    }
}
