package server.living;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.life.Monster;
import server.maps.MapleMap;

import java.util.Set;

/**
 * The only entry points the game engine calls. Every hook is a no-op while the living world is
 * disabled and never lets an exception escape into the engine.
 */
public final class LivingWorldHooks {
    private static final Logger log = LoggerFactory.getLogger(LivingWorldHooks.class);

    private LivingWorldHooks() {
    }

    /** A monster died; {@code attackerIds} are every character that damaged it (players and agents). */
    public static void mobKilled(Monster monster, Set<Integer> attackerIds, MapleMap map) {
        LivingWorld world = LivingWorld.get();
        if (world == null || monster == null || map == null || attackerIds == null || attackerIds.isEmpty()) {
            return;
        }
        try {
            world.quests().onMobKilled(monster.getId(), attackerIds, map);
            world.contracts().onMobKilled(monster.getId(), attackerIds, map);
        } catch (RuntimeException failure) {
            log.warn("living world mobKilled hook failed", failure);
        }
    }

    public static void itemGained(Character chr, int itemId, int quantity) {
        LivingWorld world = LivingWorld.get();
        if (world == null || chr == null || quantity <= 0) {
            return;
        }
        try {
            world.quests().onItemGained(chr, itemId);
        } catch (RuntimeException failure) {
            log.warn("living world itemGained hook failed", failure);
        }
    }

    public static void mapEntered(Character chr, MapleMap map) {
        LivingWorld world = LivingWorld.get();
        if (world == null || chr == null || map == null) {
            return;
        }
        try {
            world.quests().onMapEntered(chr, map.getId());
            world.residents().onPlayerPresence(chr, map);
        } catch (RuntimeException failure) {
            log.warn("living world mapEntered hook failed", failure);
        }
    }

    /** Map chat. Returns true when a Resident took the message (it is still broadcast normally). */
    public static boolean chat(Character speaker, String text) {
        LivingWorld world = LivingWorld.get();
        if (world == null || speaker == null || text == null || text.isBlank()) {
            return false;
        }
        try {
            return world.dialogue().onMapChat(speaker, text);
        } catch (RuntimeException failure) {
            log.warn("living world chat hook failed", failure);
            return false;
        }
    }

    /** Whisper to a character name. Returns true when the target is a Resident and the message was taken. */
    public static boolean whisper(Character speaker, String targetName, String text) {
        LivingWorld world = LivingWorld.get();
        if (world == null || speaker == null || targetName == null || text == null || text.isBlank()) {
            return false;
        }
        try {
            return world.dialogue().onWhisper(speaker, targetName, text);
        } catch (RuntimeException failure) {
            log.warn("living world whisper hook failed", failure);
            return false;
        }
    }

    public static void playerLoggedIn(Character chr) {
        LivingWorld world = LivingWorld.get();
        if (world == null || chr == null) {
            return;
        }
        try {
            world.roster().onOwnerLogin(chr);
        } catch (RuntimeException failure) {
            log.warn("living world login hook failed", failure);
        }
    }

    public static void playerLoggedOut(Character chr) {
        LivingWorld world = LivingWorld.get();
        if (world == null || chr == null) {
            return;
        }
        try {
            world.roster().onOwnerLogout(chr);
        } catch (RuntimeException failure) {
            log.warn("living world logout hook failed", failure);
        }
    }

    /** Multiplier applied to a companion's kill EXP (apprentice bonus); 1.0 for everyone else. */
    public static double expMultiplier(Character attacker) {
        LivingWorld world = LivingWorld.get();
        if (world == null || attacker == null) {
            return 1.0d;
        }
        try {
            return world.roster().apprenticeMultiplier(attacker);
        } catch (RuntimeException failure) {
            return 1.0d;
        }
    }
}
