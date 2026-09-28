package server.living;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.life.Monster;
import server.living.roster.CompanionPartyRouter;
import server.maps.MapleMap;

import java.awt.Point;
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

    /** {@code chr} is moving into {@code map}, to stand at {@code at} (it is not there yet). */
    public static void mapEntered(Character chr, MapleMap map, Point at) {
        LivingWorld world = LivingWorld.get();
        if (world == null || chr == null || map == null) {
            return;
        }
        try {
            server.living.resident.LivingTalk.forget(chr); // the warp closes any NPC window, as stock scripts are disposed
            world.quests().onMapEntered(chr, map.getId());
            world.markers().forget(chr);
            world.markers().enteredMap(chr);
            world.residents().onPlayerPresence(chr, map);
            world.roster().ownerEnteredMap(chr, map, at); // out of a run: companions waiting outside it rejoin (companion-ops)
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
            // on companion-ops behind any logout still queued (a channel change), so the restore sees it done
            world.executor().submit("roster.login " + chr.getId(), () -> world.roster().onOwnerLogin(chr));
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
            server.living.resident.LivingTalk.forget(chr);
            scripting.event.EventInstanceManager instance = chr.getEventInstance(); // before the disconnect unregisters them
            // on companion-ops ahead of the owner's LOG_ONOFF, which the logout publishes right after this hook
            world.executor().submit("roster.logout " + chr.getId(), () -> {
                Character back = world.roster().onlineCharacter(chr.getId());
                // its companions log off with this session (a channel change's old session: they follow instead)
                world.roster().onOwnerLogout(chr, instance);
                if (back != null && back != chr) {
                    world.roster().onOwnerLogin(back); // logged in again meanwhile (a channel change): restored there
                }
            });
            world.markers().loggedOut(chr);
        } catch (RuntimeException failure) {
            log.warn("living world logout hook failed", failure);
        }
    }

    /**
     * The player is about to change channel (Client.changeChannel, before the transition) or to leave the Cash Shop,
     * whose way out is a change to the same channel: their companions follow them there instead of logging off with the
     * old session (plan flow 6). Memory only on this thread; the rest runs on companion-ops.
     */
    public static void playerChangingChannel(Character chr, int channel) {
        LivingWorld world = LivingWorld.get();
        if (world == null || chr == null) {
            return;
        }
        try {
            world.roster().ownerChangingChannel(chr, channel);
        } catch (RuntimeException failure) {
            log.warn("living world channel change hook failed", failure);
        }
    }

    /**
     * The player went into the Cash Shop: their companions idle where they stand, and log off as at a logout if the
     * visit lasts longer than {@code LEADER_OFFLINE_BENCH_MS} (plan flow 6). Memory only on this thread.
     */
    public static void playerEnteredCashShop(Character chr) {
        LivingWorld world = LivingWorld.get();
        if (world == null || chr == null) {
            return;
        }
        try {
            world.roster().ownerEnteredCashShop(chr);
        } catch (RuntimeException failure) {
            log.warn("living world cash shop hook failed", failure);
        }
    }

    /**
     * A character was deleted (Character.deleteCharFromDB, after its rows are gone). Its roster is released on
     * companion-ops (plan "Roster deletion cleanup"): its Residents go home, a loaded companion is unloaded, and its
     * seats, leases and commitments go back.
     */
    public static void characterDeleted(int characterId) {
        LivingWorld world = LivingWorld.get();
        if (world == null || characterId <= 0) {
            return;
        }
        try {
            world.executor().submit("roster.owner_deleted " + characterId, () -> world.roster().ownerDeleted(characterId));
        } catch (RuntimeException failure) {
            log.warn("living world characterDeleted hook failed", failure);
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
            // a third death releases a companion: the roster is written on companion-ops
            world.executor().submit("contract.owner_died " + chr.getId(), () -> world.contracts().onOwnerDied(chr));
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
     * of that name or null. The stock invite runs while the living world is disabled; while it is enabled a
     * failure answers "not found" (19) instead, so nothing is loaded or seated past the router.
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
            inviter.sendPacket(tools.PacketCreator.partyStatusMessage(19));
            return CompanionPartyRouter.Route.ANSWERED;
        }
    }

    /**
     * A party invite accepted (op 3) after the invite was answered. True when the living world seats the player itself
     * on companion-ops: an owner alone with their companions merging into the inviter's party (P0 probe 1), or a full
     * party making room (probe 12). False leaves the stock join.
     */
    public static boolean partyAccept(Character player, int partyId) {
        LivingWorld world = LivingWorld.get();
        if (world == null || player == null) {
            return false;
        }
        try {
            return new CompanionPartyRouter(world).accept(player, partyId);
        } catch (RuntimeException failure) {
            log.warn("living world partyAccept hook failed", failure);
            return false;
        }
    }

    /**
     * A committed party change ({@code PartyEvents} listener, on the thread that made it). It is queued for the
     * companion-ops thread and never handled here, so nothing re-enters {@code World.updateParty} or takes the roster's
     * locks under the party's. The seat caches take it first, then the roster is reconciled.
     */
    public static void partyChanged(net.server.world.PartyEvents.PartyChangeEvent event) {
        LivingWorld world = LivingWorld.get();
        if (world == null || event == null) {
            return;
        }
        try {
            world.executor().submit("party " + event.op() + " " + event.partyId(), () -> {
                world.slots().onPartyChanged(event);
                world.partySync().onPartyChanged(event);
            });
        } catch (RuntimeException failure) {
            log.warn("living world partyChanged hook failed", failure);
        }
    }

    /**
     * True for a roster companion whose kill EXP must not dilute the humans' share (decision 17,
     * {@code COMPANION_EXP_SHARE}): it earns its own split, and the humans split as if it were absent.
     */
    public static boolean expShareCompanion(Character member) {
        LivingWorld world = LivingWorld.get();
        if (world == null || member == null || !world.config().COMPANION_EXP_SHARE
                || !server.integration.AgentPresence.isAgent(member)) {
            return false;
        }
        try {
            return world.directory().ownerOfCharacter(member.getId()) != 0;
        } catch (RuntimeException failure) {
            return false;
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

    /**
     * A USE item double-clicked in the inventory (SCRIPTED_ITEM), before its item script runs. True when it is the
     * Adventurer's Roster ({@code ROSTER_BOOK_ITEM_ID}): the Crew window opens and the item is not used up.
     */
    public static boolean rosterBookUsed(Character chr, short slot, int itemId) {
        LivingWorld world = LivingWorld.get();
        if (world == null || chr == null || itemId <= 0 || itemId != world.config().ROSTER_BOOK_ITEM_ID) {
            return false;
        }
        try {
            client.inventory.Item item = chr.getInventory(constants.inventory.ItemConstants.getInventoryType(itemId)).getItem(slot);
            if (item == null || item.getItemId() != itemId || item.getQuantity() < 1) {
                chr.sendPacket(tools.PacketCreator.enableActions());
            } else {
                new server.living.roster.ui.CompanionMenus(world).rosterBook(chr);
            }
        } catch (RuntimeException failure) {
            log.warn("living world rosterBookUsed hook failed", failure);
            chr.sendPacket(tools.PacketCreator.enableActions());
        }
        return true;
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
