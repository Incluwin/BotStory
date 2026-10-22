package server.living.roster;

import client.Character;
import config.LivingWorldConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.expedition.boss.AgentBossField;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.maps.MapleMap;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P13 G14 (decision 18): a companion that dies beside a field group's boss whose key is on rests in town before it
 * follows its owner again, since follow would warp it straight back at 50 HP (AgentFollowMapSyncService). The follow
 * guard keeps it off follow (CompanionFollowGuard) while it is dead, and once revived until its HP and MP are back to
 * COMPANION_FIELD_BOSS_REST_PERCENT of their maximum or COMPANION_FIELD_BOSS_REST_MAX_MS has passed since it died.
 *
 * <p>Sitting a fight out (2026-10-19, for the field groups on by default): one that went down
 * COMPANION_FIELD_BOSS_SIT_OUT_DEATHS times beside a covered boss on one map stops walking back into that fight. It
 * says so once in party chat and stays in town while a covered boss still stands on that map and its owner stays there,
 * at most COMPANION_FIELD_BOSS_SIT_OUT_MAX_MS from its first death there. Its count starts again on another map, once
 * the boss is gone, or once its owner has left the map. Memory only, any thread.</p>
 */
final class CompanionFieldBossRest {
    private static final Logger log = LoggerFactory.getLogger(CompanionFieldBossRest.class);
    /** What a companion says once as it sits a field boss's fight out; one per companion, by its id. */
    static final List<String> SIT_OUT_LINES = List.of(
            "that thing's way too strong for me. i'll wait in town till it's down.",
            "ok i keep getting flattened. sitting this one out, i'll be in town.",
            "nope, not going back in there. ping me when it's dead.",
            "i'm just feeding it at this point. waiting in town for this one.");

    private final LivingWorld world;
    /** Companion character id -> when it died beside a field boss. */
    private final Map<Integer, Long> diedAtMs = new ConcurrentHashMap<>();
    /** Companion character id -> its deaths beside a covered field boss on one map, since the first. */
    private final Map<Integer, Losses> losses = new ConcurrentHashMap<>();

    /** Deaths beside a covered boss on {@code map} since {@code sinceMs}; {@code said}: its sit-out line is out. */
    private record Losses(MapleMap map, int deaths, long sinceMs, boolean said) {
        Losses another() {
            return new Losses(map, deaths + 1, sinceMs, said);
        }

        Losses spoken() {
            return new Losses(map, deaths, sinceMs, true);
        }
    }

    CompanionFieldBossRest(LivingWorld world) {
        this.world = world;
    }

    /**
     * On companion-ops: the character died on {@code map} (LivingWorldHooks.playerDied). A companion that died beside
     * a covered field boss starts to rest, and the death counts toward sitting that fight out.
     */
    void died(int characterId, MapleMap map, long nowMs) {
        if (AgentBossField.near(map) && companion(characterId)) {
            diedAtMs.put(characterId, nowMs);
            Losses first = new Losses(map, 1, nowMs, false);
            losses.merge(characterId, first, (old, fresh) -> old.map() == map ? old.another() : fresh);
        }
    }

    /**
     * True while the companion rests: dead, or revived and not yet recovered, within the rest's time limit; or while it
     * sits a field boss's fight out.
     */
    boolean resting(int characterId, long nowMs) {
        if (sittingOut(characterId, nowMs)) {
            return true;
        }
        Long died = diedAtMs.get(characterId);
        if (died == null) {
            return false;
        }
        LivingWorldConfig config = world.config();
        Character chr = bot(characterId);
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

    /**
     * True while the companion sits out the fight it lost on one map: enough deaths there, a covered boss still standing
     * there, its owner online on that map, within the time limit. A count that can no longer sit anything out (the boss
     * gone, the owner elsewhere or offline, the time up) is dropped, so the next fight starts from no deaths.
     */
    private boolean sittingOut(int characterId, long nowMs) {
        Losses lost = losses.get(characterId);
        LivingWorldConfig config = world.config();
        if (lost == null || config == null) {
            return false;
        }
        Character owner = owner(characterId);
        if (nowMs - lost.sinceMs() >= config.COMPANION_FIELD_BOSS_SIT_OUT_MAX_MS || owner == null
                || owner.getMap() != lost.map() || !AgentBossField.near(lost.map())) {
            losses.remove(characterId, lost);
            return false;
        }
        int needed = config.COMPANION_FIELD_BOSS_SIT_OUT_DEATHS;
        if (needed <= 0 || lost.deaths() < needed) {
            return false;
        }
        if (!lost.said() && losses.replace(characterId, lost, lost.spoken())) {
            say(characterId);
        }
        return true;
    }

    /** Its sit-out line, once, in party chat. */
    private void say(int characterId) {
        Character chr = bot(characterId);
        if (chr == null) {
            return;
        }
        String line = SIT_OUT_LINES.get(Math.floorMod(characterId, SIT_OUT_LINES.size()));
        log.info("Companion sits a field boss out: companion={} map={} line={}", chr.getName(), chr.getMapId(), line);
        try {
            AgentPartyGatewayRuntime.party().sendPartyChat(chr, line);
        } catch (RuntimeException failure) {
            log.debug("party chat failed for {}", chr.getName(), failure);
        }
    }

    private static Character bot(int characterId) {
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(characterId);
        return entry == null ? null : entry.bot();
    }

    /** The companion's owner, online; null when it has none or they are offline. */
    private Character owner(int characterId) {
        Persona persona = world.residents().personaOfCharacterId(characterId);
        int ownerId = persona == null ? 0 : world.roster().cachedOwnerOf(persona.id());
        return ownerId <= 0 ? null : world.roster().onlineCharacter(ownerId);
    }

    /** A companion: a persona whose owner it follows. */
    private boolean companion(int characterId) {
        Persona persona = world.residents().personaOfCharacterId(characterId);
        int ownerId = persona == null ? 0 : world.roster().cachedOwnerOf(persona.id());
        return ownerId > 0 && AgentRuntimeRegistry.leaderIdForAgentCharacter(characterId) == ownerId;
    }
}
