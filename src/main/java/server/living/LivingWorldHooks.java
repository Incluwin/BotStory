package server.living;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.life.Monster;
import server.living.roster.CompanionPartyRouter;
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
            server.living.population.PopulationHooks.mobKilled(monster, attackerIds, map);
        } catch (RuntimeException failure) {
            log.warn("living world mobKilled hook failed", failure);
        }
    }

    /** Any character (human or bot) reached a new level; citizens react and journal it. */
    public static void levelUp(Character chr) {
        LivingWorld world = LivingWorld.get();
        if (world == null) {
            return;
        }
        world.markers().forget(chr);
        server.living.population.PopulationHooks.levelUp(chr);
    }

    /** Any character advanced a job. */
    public static void jobAdvanced(Character chr) {
        if (LivingWorld.get() == null) {
            return;
        }
        server.living.population.PopulationHooks.jobAdvanced(chr);
    }

    public static void itemGained(Character chr, int itemId, int quantity) {
        LivingWorld world = LivingWorld.get();
        if (world == null || chr == null || quantity <= 0) {
            return;
        }
        try {
            world.quests().onItemGained(chr, itemId);
            world.markers().forget(chr);
            server.living.population.PopulationHooks.itemGained(chr, itemId, quantity);
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
            world.markers().forget(chr);
            world.markers().enteredMap(chr);
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
            if (world.dialogue().onMapChat(speaker, text)) {
                return true;
            }
            return server.living.population.PopulationHooks.chat(speaker, text);
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
            world.questLog().reconcile(chr);
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
            world.markers().loggedOut(chr);
        } catch (RuntimeException failure) {
            log.warn("living world logout hook failed", failure);
        }
    }

    /** A party quest instance was cleared by these players. */
    public static void partyQuestCleared(java.util.List<Character> players, String eventName) {
        LivingWorld world = LivingWorld.get();
        if (world == null || players == null) {
            return;
        }
        try {
            for (Character chr : players) {
                if (chr != null) {
                    world.quests().onPartyQuestCleared(chr, eventName == null ? "" : eventName);
                }
            }
        } catch (RuntimeException failure) {
            log.warn("living world partyQuestCleared hook failed", failure);
        }
    }

    public static void playerDied(Character chr) {
        LivingWorld world = LivingWorld.get();
        if (world == null || chr == null) {
            return;
        }
        try {
            world.contracts().onOwnerDied(chr);
            server.living.population.PopulationHooks.died(chr);
        } catch (RuntimeException failure) {
            log.warn("living world playerDied hook failed", failure);
        }
    }

    /** A player double-clicked a character. True when it was a Resident and its NPC window opened instead of character info. */
    public static boolean characterClicked(Character clicker, Character target) {
        LivingWorld world = LivingWorld.get();
        if (world == null || clicker == null || target == null) {
            return false;
        }
        try {
            return world.dialogue().onCharacterClicked(clicker, target);
        } catch (RuntimeException failure) {
            log.warn("living world characterClicked hook failed", failure);
            return false;
        }
    }

    /**
     * A party invite by name (op 4), answered before anything is spawned. {@code invited} is the online character
     * of that name or null. The stock invite runs while the living world is disabled.
     */
    public static CompanionPartyRouter.Route partyInvite(Character inviter, Character invited, String name) {
        LivingWorld world = LivingWorld.get();
        if (world == null || inviter == null) {
            return CompanionPartyRouter.Route.STOCK;
        }
        try {
            return new CompanionPartyRouter(world).invite(inviter, invited, name);
        } catch (RuntimeException failure) {
            log.warn("living world partyInvite hook failed", failure);
            return CompanionPartyRouter.Route.STOCK;
        }
    }

    /** True when {@code target} is one of {@code owner}'s roster companions. */
    public static boolean ownsCompanion(Character owner, Character target) {
        LivingWorld world = LivingWorld.get();
        if (world == null || owner == null || target == null) {
            return false;
        }
        try {
            return world.directory().ownerOfCharacter(target.getId()) == owner.getId();
        } catch (RuntimeException failure) {
            log.warn("living world ownsCompanion hook failed", failure);
            return false;
        }
    }

    /** An NPC click on a quest marker's hitbox; true when it was one (the Resident's menu opens instead). */
    public static boolean markerClicked(Character clicker, int objectId) {
        LivingWorld world = LivingWorld.get();
        if (world == null || clicker == null) {
            return false;
        }
        try {
            if (!world.markers().isHitbox(clicker, objectId)) {
                return false;
            }
            Character resident = world.markers().hitboxOwner(clicker, objectId);
            if (resident == null || !world.dialogue().onCharacterClicked(clicker, resident)) {
                clicker.sendPacket(tools.PacketCreator.enableActions());
            }
            return true;
        } catch (RuntimeException failure) {
            log.warn("living world markerClicked hook failed", failure);
            return false;
        }
    }

    /** A response to a Resident's NPC-window dialog; true when one was open for this client. */
    public static boolean npcTalkResponse(client.Client client, byte lastMsg, byte action, net.packet.InPacket packet) {
        if (LivingWorld.get() == null) {
            return false;
        }
        try {
            return server.living.resident.LivingTalk.respond(client, lastMsg, action, packet);
        } catch (RuntimeException failure) {
            log.warn("living world npc talk hook failed", failure);
            return false;
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
