package server.living;

import client.Character;
import config.LivingWorldConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.integration.AgentPresence;
import server.knowledge.PersonalKnowledge;
import server.life.Monster;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.agents.runtime.AgentRuntimeRegistry;
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

    /**
     * A monster died; {@code attackerIds} are every character that damaged it (players and agents). This runs on the
     * thread that landed the kill (a player's IO thread or the central Agent tick) inside the monster's monitor, so it
     * stays in memory and takes no map or living lock: kill credit (quest kill steps, contract proofs, quest-only drops),
     * which looks characters up and reads the store, goes to the living-kills lane, and only when a player or a Resident
     * took part.
     */
    public static void mobKilled(Monster monster, Set<Integer> attackerIds, MapleMap map) {
        LivingWorld world = LivingWorld.get();
        if (world == null || monster == null || map == null || attackerIds == null || attackerIds.isEmpty()) {
            return;
        }
        try {
            int mobId = monster.getId();
            if (creditable(world, attackerIds)) {
                Set<Integer> attackers = Set.copyOf(attackerIds);
                world.killLane().execute(() -> credit(world, mobId, attackers, map));
            }
            if (monster.isBoss()) {
                java.util.Set<Integer> attackers = new java.util.HashSet<>(attackerIds);
                world.executor().submit("friendship.boss " + monster.getId(),
                        () -> world.friendship().onBossKilled(monster.getId(), attackers, map));
            }
            server.living.population.PopulationHooks.mobKilled(monster, attackerIds, map);
            PersonalKnowledge personal = PersonalKnowledge.shared();
            if (personal.on()) {
                for (int attackerId : attackerIds) { // every bot that fought it lived it (plan 7.4)
                    if (AgentRuntimeRegistry.findByAgentCharacterId(attackerId) != null) {
                        personal.killed(attackerId, mobId, map.getId());
                    }
                }
            }
        } catch (RuntimeException failure) {
            log.warn("living world mobKilled hook failed", failure);
        }
    }

    /** A player (no Agent runtime) or a Resident (its character known to the runtime, e.g. a companion) took part. */
    static boolean creditable(LivingWorld world, Set<Integer> attackerIds) {
        for (int attackerId : attackerIds) {
            if (AgentRuntimeRegistry.findByAgentCharacterId(attackerId) == null
                    || world.residents().isPersonaCharacter(attackerId)) {
                return true;
            }
        }
        return false;
    }

    private static void credit(LivingWorld world, int mobId, Set<Integer> attackers, MapleMap map) {
        try {
            world.quests().onMobKilled(mobId, attackers, map);
            world.contracts().onMobKilled(mobId, attackers, map);
        } catch (RuntimeException failure) {
            log.warn("living world kill credit for mob {} failed", mobId, failure);
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
        if (AgentPresence.isAgent(chr) && !server.living.resident.ResidentShaper.shaping()) {
            Persona persona = world.residents().personaOf(chr);
            if (persona != null && !LivingContent.isHire(persona.id())) {
                // the quest gear its new level allows, on companion-ops (inventory work off the game thread)
                world.executor().submit("resident.kit " + persona.id(), () -> world.residents().refreshKit(chr, persona));
            }
        }
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

    /**
     * {@code chr} picked a drop up off the map: {@code quantity} of {@code itemId}, or {@code meso} (its own share of a
     * party's). A companion on a live bench slice counts it as kill loot for its harvest (P5, HarvestDiff).
     */
    public static void looted(Character chr, int itemId, int quantity, int meso) {
        LivingWorld world = LivingWorld.get();
        if (world == null || chr == null) {
            return;
        }
        world.benchDuty().looted(chr.getId(), itemId, quantity, meso);
        try {
            if (itemId > 0 && AgentPresence.isAgent(chr)) {
                PersonalKnowledge.shared().gained(chr.getId(), itemId);
            }
        } catch (RuntimeException failure) {
            log.warn("living world looted hook failed", failure);
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
            if (AgentPresence.isAgent(chr)) {
                PersonalKnowledge.shared().visited(chr.getId(), map.getId());
            }
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
            PersonalKnowledge.shared().forget(characterId);
            world.executor().submit("roster.owner_deleted " + characterId, () -> world.roster().ownerDeleted(characterId));
        } catch (RuntimeException failure) {
            log.warn("living world characterDeleted hook failed", failure);
        }
    }

    /** A party quest instance was cleared by these players; its event script is not known. */
    public static void partyQuestCleared(java.util.List<Character> players, String eventName) {
        partyQuestCleared(players, null, eventName);
    }

    /**
     * Event instance {@code eventName} of event script {@code scriptName} (EventInstanceManager.clearPQ) was cleared by
     * these players. A script that living_world.BOSS_EVENT_SCRIPTS lists is a boss kill, not a party quest clear
     * (2026-10-08): it is logged and nothing else; its friendship boss points come with the boss's death (mobKilled).
     */
    public static void partyQuestCleared(java.util.List<Character> players, String scriptName, String eventName) {
        LivingWorld world = LivingWorld.get();
        if (world == null || players == null) {
            return;
        }
        try {
            LivingWorldConfig settings = world.config();
            if (settings != null && settings.bossEventScript(scriptName)) {
                log.info("Boss event cleared: script={} instance={} players={}", scriptName, eventName, names(players));
                return;
            }
            // first, and memory only: a companion run's stage-5 clear is on its companions' records as cleared, and a
            // quest that fails to count it must not keep it off them. Tap-outs stop because the coordinator ignores
            // downed members once it reads the clear itself, not because of this mark
            world.partyQuests().cleared(players);
            for (Character chr : players) {
                if (chr != null) {
                    world.quests().onPartyQuestCleared(chr, eventName == null ? "" : eventName);
                }
            }
            java.util.List<Character> cleared = new java.util.ArrayList<>(players);
            world.executor().submit("friendship.pq " + eventName, () -> world.friendship().onPartyQuestCleared(cleared));
        } catch (RuntimeException failure) {
            log.warn("living world partyQuestCleared hook failed", failure);
        }
    }

    private static String names(java.util.List<Character> players) {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (Character chr : players) {
            if (chr != null) {
                names.add(chr.getName());
            }
        }
        return String.join(",", names);
    }

    /** {@code giver} famed ({@code delta} 1) or defamed (-1) {@code target}; a Resident remembers it (friendship). */
    public static void famed(Character giver, Character target, int delta) {
        LivingWorld world = LivingWorld.get();
        if (world == null || giver == null || target == null) {
            return;
        }
        try {
            world.friendship().onFame(giver, target, delta);
        } catch (RuntimeException failure) {
            log.warn("living world famed hook failed", failure);
        }
    }

    /** The player forfeited quest {@code questId} in the client's quest window: a mirrored living quest is abandoned. */
    public static void questForfeited(Character chr, int questId) {
        LivingWorld world = LivingWorld.get();
        if (world == null || chr == null || questId < 30000) {
            return;
        }
        try {
            for (var entry : world.content().questLogIds().entrySet()) {
                if (entry.getValue() == questId) {
                    world.quests().abandon(chr, entry.getKey());
                    return;
                }
            }
        } catch (RuntimeException failure) {
            log.warn("living world quest-forfeit hook failed", failure);
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
            world.benchDuty().died(chr.getId()); // a companion on a live bench slice ends it once revived (flow 12)
            world.crewRotation().died(chr); // Keep my crew fresh swaps one that went down, even once revived
            server.living.population.PopulationHooks.died(chr);
            world.partyQuests().died(chr); // a death inside a companion run counts for its record
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
     * A round of the leader's stock Party Search that no human answered (PartySearchCoordinator, on its timer). True
     * when one of the leader's own bench companions answers it instead (P9 flow 11); the call itself runs on
     * companion-ops.
     */
    public static boolean partySearchUnanswered(Character leader, net.server.coordinator.partysearch.PartySearchWant want) {
        LivingWorld world = LivingWorld.get();
        if (world == null || leader == null) {
            return false;
        }
        try {
            return world.manager().fillFromBench(leader, want);
        } catch (RuntimeException failure) {
            log.warn("living world partySearchUnanswered hook failed", failure);
            return false;
        }
    }

    /**
     * The group a player's accept of a bot's buddy request puts it in (BuddylistModifyHandler): "Crew" for their own
     * companion while companions ask (P9), else the stock "Default Group".
     */
    public static String buddyGroup(Character player, int otherCharacterId) {
        LivingWorld world = LivingWorld.get();
        if (world == null || player == null) {
            return "Default Group";
        }
        try {
            return world.companionBuddies().groupFor(player, otherCharacterId);
        } catch (RuntimeException failure) {
            log.warn("living world buddyGroup hook failed", failure);
            return "Default Group";
        }
    }

    /**
     * A committed party change ({@code PartyEvents} listener, on the thread that made it). It is queued for the
     * companion-ops thread and never handled here, so nothing re-enters {@code World.updateParty} or takes the roster's
     * locks under the party's. The seat and view caches take it first, then the roster is reconciled.
     */
    public static void partyChanged(net.server.world.PartyEvents.PartyChangeEvent event) {
        LivingWorld world = LivingWorld.get();
        if (world == null || event == null) {
            return;
        }
        try {
            world.executor().submit("party " + event.op() + " " + event.partyId(), () -> {
                server.living.roster.CompanionViews views = world.views();
                if (views != null) {
                    views.partyChanged(event); // the Crew windows of everyone in it render afresh
                }
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

    /**
     * True for the character of a companion on a live bench slice (P5: its stall's prices come from the market's price
     * index, so its sales never move the index; Market).
     */
    public static boolean benchCompanion(int characterId) {
        LivingWorld world = LivingWorld.get();
        if (world == null || world.benchDuty() == null) {
            return false;
        }
        try {
            return world.benchDuty().sliceOf(characterId) != null;
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

    /**
     * An inventory item dropped on the ground (ITEM_MOVE to slot 0). True when it is the Adventurer's Roster: the drop
     * is refused with a pink line, since its tradeBlock would erase it on the ground, and the caller re-enables the client.
     */
    public static boolean rosterBookDropped(Character chr, client.inventory.InventoryType type, short slot) {
        LivingWorld world = LivingWorld.get();
        if (world == null || chr == null || type == null) {
            return false;
        }
        int book = world.config().ROSTER_BOOK_ITEM_ID;
        if (book <= 0 || type != constants.inventory.ItemConstants.getInventoryType(book)) {
            return false;
        }
        try {
            client.inventory.Item item = chr.getInventory(type).getItem(slot);
            if (item == null || item.getItemId() != book) {
                return false;
            }
            chr.dropMessage(5, "The Adventurer's Roster stays with you.");
            return true;
        } catch (RuntimeException failure) {
            log.warn("living world rosterBookDropped hook failed", failure);
            return false;
        }
    }

    /**
     * A party leader asked a party quest's entry NPC to start it ({@code pqKey}; Lakelis's "kpq" branch in
     * 9020000.js, on their I/O thread). True when the living world answered the click with its own window, and the
     * script must return; false leaves the stock start to the script, with nothing sent or changed.
     */
    public static boolean partyQuestEntry(scripting.npc.NPCConversationManager cm, String pqKey) {
        LivingWorld world = LivingWorld.get();
        if (world == null || cm == null || cm.getPlayer() == null) {
            return false;
        }
        try {
            return world.partyQuests().offerAtDesk(cm.getPlayer(), pqKey);
        } catch (RuntimeException failure) {
            log.warn("living world partyQuestEntry hook failed", failure);
            return false;
        }
    }

    /**
     * Mu Young's Easy window ({@code bossKey} "easy_balrog" in 1061014.js, on the clicker's I/O thread): true when she
     * offers the owner "My party will register too." (living_world.COMPANION_BOSS_ENABLED_TYPES). False keeps her
     * stock window: no living world, an Agent clicking (a companion's own headless sign-up), or any failure.
     */
    public static boolean bossPartyOffered(scripting.npc.NPCConversationManager cm, String bossKey) {
        LivingWorld world = LivingWorld.get();
        Character clicker = cm == null ? null : cm.getPlayer();
        if (world == null || clicker == null || world.bossRuns() == null || AgentPresence.isAgent(clicker)) {
            return false;
        }
        try {
            return world.bossRuns().offers(clicker, bossKey);
        } catch (RuntimeException failure) {
            log.warn("living world bossPartyOffered hook failed", failure);
            return false;
        }
    }

    /**
     * The owner picked "My party will register too." in Mu Young's window: her answer, the companions' sign-up handed
     * to companion-ops; her "none of your companions" line with no living world, for an Agent, or on any failure.
     */
    public static String bossPartyRegister(scripting.npc.NPCConversationManager cm, String bossKey) {
        LivingWorld world = LivingWorld.get();
        Character clicker = cm == null ? null : cm.getPlayer();
        if (world == null || clicker == null || world.bossRuns() == null || AgentPresence.isAgent(clicker)) {
            return server.living.roster.CompanionBossRuns.NONE_CAN_SIGN_UP;
        }
        try {
            return world.bossRuns().registerFromNpc(clicker, bossKey);
        } catch (RuntimeException failure) {
            log.warn("living world bossPartyRegister hook failed", failure);
            return server.living.roster.CompanionBossRuns.NONE_CAN_SIGN_UP;
        }
    }

    /**
     * Mu Young's Easy window (P7 Part B, on the clicker's I/O thread): true when she offers the owner "Call allies
     * from my bench..." (living_world.COMPANION_BOSS_ALLY_TYPES). False keeps her window as it was: no living world, an
     * Agent clicking, or any failure.
     */
    public static boolean bossAlliesOffered(scripting.npc.NPCConversationManager cm, String bossKey) {
        LivingWorld world = LivingWorld.get();
        Character clicker = cm == null ? null : cm.getPlayer();
        if (world == null || clicker == null || world.bossRuns() == null || AgentPresence.isAgent(clicker)) {
            return false;
        }
        try {
            return world.bossRuns().alliesOffered(clicker, bossKey);
        } catch (RuntimeException failure) {
            log.warn("living world bossAlliesOffered hook failed", failure);
            return false;
        }
    }

    /**
     * The owner picked "Call allies from my bench..." in Mu Young's window: her bench picker's text (its choices loop
     * in her script); her "none of your companions on the bench" line when there is none, with no living world, for an
     * Agent, or on any failure.
     */
    public static String bossAllyPicker(scripting.npc.NPCConversationManager cm, String bossKey) {
        LivingWorld world = LivingWorld.get();
        Character clicker = cm == null ? null : cm.getPlayer();
        if (world == null || clicker == null || world.bossRuns() == null || AgentPresence.isAgent(clicker)) {
            return server.living.roster.CompanionBossRuns.NO_ALLY_CAN_COME;
        }
        try {
            String text = world.bossRuns().allyPicker(clicker, bossKey);
            return text == null ? server.living.roster.CompanionBossRuns.NO_ALLY_CAN_COME : text;
        } catch (RuntimeException failure) {
            log.warn("living world bossAllyPicker hook failed", failure);
            return server.living.roster.CompanionBossRuns.NO_ALLY_CAN_COME;
        }
    }

    /**
     * The owner picked {@code selection} in Mu Young's bench picker: her answer (whom she sent word to, then the picker
     * again), the calls handed to companion-ops; her "none of your companions on the bench" line as above.
     */
    public static String bossAllyCall(scripting.npc.NPCConversationManager cm, String bossKey, int selection) {
        LivingWorld world = LivingWorld.get();
        Character clicker = cm == null ? null : cm.getPlayer();
        if (world == null || clicker == null || world.bossRuns() == null || AgentPresence.isAgent(clicker)) {
            return server.living.roster.CompanionBossRuns.NO_ALLY_CAN_COME;
        }
        try {
            String text = world.bossRuns().allyCall(clicker, bossKey, selection);
            return text == null ? server.living.roster.CompanionBossRuns.NO_ALLY_CAN_COME : text;
        } catch (RuntimeException failure) {
            log.warn("living world bossAllyCall hook failed", failure);
            return server.living.roster.CompanionBossRuns.NO_ALLY_CAN_COME;
        }
    }

    /**
     * A monster died (Monster.killBy, on the thread that landed the kill, from memory): who its drops and mesos go to,
     * {@code candidate} being its stock owner (its top damager on the map, else its killer). P7 Part B: a bench ally
     * that topped the damage in a companion boss run hands them to the human it fights for
     * (CompanionBossRuns.dropOwner). {@code candidate} at once when it is no Agent (every kill a player tops) or no
     * living world runs, and on any failure.
     */
    public static Character bossDropOwner(Monster monster, Character candidate) {
        if (candidate == null || !AgentPresence.isAgent(candidate)) {
            return candidate;
        }
        LivingWorld world = LivingWorld.get();
        if (world == null) {
            return candidate;
        }
        try {
            server.living.roster.CompanionBossRuns bossRuns = world.bossRuns();
            Character owner = bossRuns == null ? null : bossRuns.dropOwner(monster, candidate);
            return owner == null ? candidate : owner;
        } catch (RuntimeException failure) {
            log.warn("living world bossDropOwner hook failed", failure);
            return candidate;
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

    /**
     * A boss's daily entry count (ExpeditionBossLog.attemptBoss, after its switch, boss and channel checks, from
     * memory, on the asking thread: the entry NPC's script, the Start, companion-ops). TRUE for a companion its boss
     * run's pre-check on companion-ops allowed for {@code expedition} (CompanionBossQuota): its sign-up inside the NPC
     * script runs no JDBC, and the Start logs nothing for it ({@code logEntry}), as its entry is logged when it enters
     * the fight. Null keeps the stock count and log: every player, a companion no pre-check allowed, no living world,
     * and any failure. Easy Balrog keeps no entry log, so it never gets here.
     */
    public static Boolean bossQuota(int characterId, server.expeditions.Expedition expedition, boolean logEntry) {
        LivingWorld world = LivingWorld.get();
        if (world == null) {
            return null;
        }
        try {
            server.living.roster.CompanionBossRuns bossRuns = world.bossRuns();
            return bossRuns == null ? null : bossRuns.bossQuota(characterId, expedition, logEntry);
        } catch (RuntimeException failure) {
            log.warn("living world bossQuota hook failed", failure);
            return null;
        }
    }
}
