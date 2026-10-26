package server.living.roster;

import client.Character;
import client.Client;
import net.server.world.Party;
import server.Trade;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.capabilities.party.AgentPartyLifecycleService;
import server.agents.capabilities.party.JoinOutcome;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentInteractionRuntime;
import server.agents.runtime.AgentMailboxRuntime;
import server.agents.runtime.AgentRuntimeCleanupService;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.resident.LivingTalk;
import server.living.resident.ResidentDialogue;
import server.maps.MapleMap;

import java.awt.Point;

/** CompanionLoans' hands in the live game: the agent runtime, the party and the friend's window. */
final class LiveLoanHands implements CompanionLoans.Hands {
    /** The Bulletin Board, the portrait policy's fallback (CompanionMenus.BOARD_NPC). */
    private static final int BOARD_NPC = 9040011;

    private final LivingWorld world;

    LiveLoanHands(LivingWorld world) {
        this.world = world;
    }

    @Override
    public Character online(int characterId) {
        return world.roster().onlineCharacter(characterId);
    }

    @Override
    public Character loaded(int characterId) {
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(characterId);
        return entry == null ? null : AgentRuntimeIdentityRuntime.bot(entry);
    }

    @Override
    public int leaderOf(int agentCharacterId) {
        return AgentRuntimeRegistry.leaderIdForAgentCharacter(agentCharacterId);
    }

    @Override
    public boolean together(Character a, Character b) {
        return a != null && b != null && a.getMap() != null && a.getMap() == b.getMap();
    }

    @Override
    public boolean inEventInstance(Character chr) {
        MapleMap map = chr == null ? null : chr.getMap();
        return chr != null && (chr.getEventInstance() != null || map != null && map.getEventInstance() != null);
    }

    @Override
    public boolean buddies(Character a, Character b) {
        return a.getBuddylist().containsVisible(b.getId()) && b.getBuddylist().containsVisible(a.getId());
    }

    @Override
    public int partyOf(Character chr) {
        Party party = chr == null ? null : chr.getParty();
        return party == null ? 0 : party.getId();
    }

    @Override
    public void leaveParty(Character bot) {
        if (bot.getParty() != null) {
            AgentPartyLifecycleService.leaveAgentParty(bot);
        }
    }

    @Override
    public boolean joinParty(Character leader, Character bot) {
        if (leader.getParty() == null && !Party.createParty(leader, true, true)) {
            return false;
        }
        JoinOutcome joined = AgentPartyLifecycleService.joinAgentToLeaderParty(leader, bot);
        return joined == JoinOutcome.JOINED || joined == JoinOutcome.ALREADY_MEMBER;
    }

    @Override
    public boolean handOver(Character bot, int fromLeaderId, Character to) {
        return AgentInteractionRuntime.handOverAgent(bot, fromLeaderId, to);
    }

    @Override
    public void order(Character bot, Character leader, CompanionLoans.Order order) {
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(bot.getId());
        if (entry == null) {
            return;
        }
        AgentMailboxRuntime.dispatch(entry, ignored -> {
            switch (order) {
                case FOLLOW -> AgentMovementCommandRuntime.follow(entry, leader);
                case WAIT -> AgentMovementCommandRuntime.stop(entry);
                case HUNT -> AgentMovementCommandRuntime.grind(entry);
            }
            return null;
        });
    }

    /** As RosterService.walkOut: to the nearest portal on its map, then gone; a corpse or a map with none, at once. */
    @Override
    public void walkOut(Character bot, Runnable then) {
        MapleMap map = bot.getMap();
        boolean dead = bot.getHp() <= 0 && bot.getCurrentMaxHp() > 0;
        Point portal = map == null || dead ? null : ArrivalPlacement.nearestPortal(map, bot.getPosition());
        AgentRuntimeEntry entry = portal == null ? null : AgentRuntimeRegistry.findByAgentCharacterId(bot.getId());
        if (entry == null) {
            then.run();
            return;
        }
        AgentMovementCommandRuntime.moveTo(entry, portal, false);
        watchWalkOut(bot, portal, then, System.currentTimeMillis() + ArrivalPlacement.WALK_OUT_MAX_MS);
    }

    private void watchWalkOut(Character bot, Point portal, Runnable then, long deadlineMs) {
        later("loan.walk_out " + bot.getName(), () -> {
            Point at = bot.getPosition();
            if (at != null && at.distance(portal) > ArrivalPlacement.WALK_OUT_REACH_PX
                    && System.currentTimeMillis() < deadlineMs && loaded(bot.getId()) == bot) {
                watchWalkOut(bot, portal, then, deadlineMs);
            } else {
                then.run();
            }
        }, ArrivalPlacement.WALK_OUT_CHECK_MS);
    }

    /** As RosterService.unload: saved, its Agent removed, and off the server as a player logging off. */
    @Override
    public void unload(Character bot) {
        if (loaded(bot.getId()) == bot) {
            bot.saveCharToDB();
        }
        AgentRuntimeCleanupService.removeAgentByCharacterId(bot.getId());
        Client client = bot.getClient();
        if (client != null) {
            client.forceDisconnect(); // the unload saves it in full (Client.disconnectInternal)
        }
    }

    @Override
    public void cancelTrade(Character bot) {
        if (bot.getTrade() != null) {
            Trade.cancelTrade(bot, Trade.TradeResult.PARTNER_CANCEL);
        }
    }

    @Override
    public boolean ask(Character friend, Persona persona, String text, Runnable yes, Runnable no) {
        Client client = friend.getClient();
        if (client == null || !LivingTalk.mayShow(friend)) {
            return false;
        }
        int own = ResidentDialogue.renderedPortraitOf(persona);
        int portrait = own > 0 ? own : BOARD_NPC;
        client.runOnIoThread(() -> LivingTalk.open(friend, portrait).yesNo(text, yes, no).show());
        return true;
    }

    @Override
    public void later(String name, Runnable task, long delayMs) {
        world.roster().later(name, task, delayMs);
    }
}
