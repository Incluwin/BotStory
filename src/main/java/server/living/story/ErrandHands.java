package server.living.story;

import client.Character;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.capabilities.navigation.AgentMapWalk;
import server.agents.integration.AgentDialogueTransportRuntime;
import server.agents.progression.AgentVictoriaRouteRuntime;
import server.agents.runtime.AgentMailboxRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.life.NPC;
import server.living.mentor.MentorLead;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;
import java.util.List;

/**
 * What an attended errand (F6.3) does to the world: the way through the instructor's town, the companion's walks and
 * its lines. {@link #LIVE} is the game's; a test gives its own, so {@link ErrandAttend} runs without a channel. The walk
 * is the mentor's (F4.3): AgentMapWalk on the companion's own mailbox, off follow while it walks.
 */
interface ErrandHands {
    /** Each hop's give-up time, as the mentor's lead has it. */
    long WALK_HOP_TIMEOUT_MS = 60_000L;

    /** The first walking hop from {@code fromMapId} toward {@code goalMapId} (never a ride); 0 for none. */
    int nextHop(Character bot, int fromMapId, int goalMapId);

    /** The leg through the visible portal on {@code here} to {@code toMapId}, two-way when one leads back; or null. */
    ErrandAttend.Leg portalLeg(Character owner, MapleMap here, int toMapId);

    /** The leg through the named script portal (Leafre's {@code in00}): its owner goes first, it follows; or null. */
    ErrandAttend.Leg gateLeg(MapleMap here, String portalName, int toMapId);

    /** Where the NPC stands on {@code map}; null when it is not there. */
    Point npcAt(MapleMap map, int npcId);

    /** Off follow, walking to {@code target}, on its own mailbox. */
    void walk(Character bot, AgentMapWalk.Target target);

    boolean walking(int botId);

    /** Its walk ends and it follows its owner again. */
    void follow(Character bot);

    /** A line in map chat, said in front of whoever is there. */
    void say(Character bot, String line);

    ErrandHands LIVE = new ErrandHands() {
        @Override
        public int nextHop(Character bot, int fromMapId, int goalMapId) {
            AgentRuntimeEntry runtime = AgentRuntimeRegistry.findByAgentCharacterId(bot.getId());
            if (runtime == null) {
                return 0;
            }
            List<AgentVictoriaRouteRuntime.PlannedStep> route = AgentVictoriaRouteRuntime.plannedRoute(runtime, fromMapId,
                    goalMapId, System.currentTimeMillis());
            AgentVictoriaRouteRuntime.PlannedStep first = route.isEmpty() ? null : route.get(0);
            return first == null || first.fromMapId() != fromMapId || first.ride() ? 0 : first.toMapId();
        }

        @Override
        public ErrandAttend.Leg portalLeg(Character owner, MapleMap here, int toMapId) {
            Portal door = portalTo(here, toMapId);
            if (door == null) {
                return null;
            }
            MapleMap there = owner.getClient() == null || owner.getClient().getChannelServer() == null ? null
                    : owner.getClient().getChannelServer().getMapFactory().getMap(toMapId);
            Portal back = there == null ? null : portalTo(there, here.getId());
            return new ErrandAttend.Leg(here.getId(), door.getId(), toMapId, back == null ? -1 : back.getId(),
                    door.getPosition(), new MentorLead.Leg(back != null, false, "lead.oneway"));
        }

        @Override
        public ErrandAttend.Leg gateLeg(MapleMap here, String portalName, int toMapId) {
            Portal gate = here.getPortal(portalName);
            return gate == null || gate.getPosition() == null ? null : new ErrandAttend.Leg(here.getId(), gate.getId(),
                    toMapId, -1, gate.getPosition(), new MentorLead.Leg(false, false, "lead.gate"));
        }

        @Override
        public Point npcAt(MapleMap map, int npcId) {
            NPC npc = map == null ? null : map.getNPCById(npcId);
            return npc == null ? null : npc.getPosition();
        }

        @Override
        public void walk(Character bot, AgentMapWalk.Target target) {
            AgentRuntimeEntry runtime = AgentRuntimeRegistry.findByAgentCharacterId(bot.getId());
            if (runtime == null) {
                return;
            }
            long now = System.currentTimeMillis();
            AgentMailboxRuntime.dispatch(runtime, entry -> {
                Character agent = entry.bot();
                if (agent != null && agent.getId() == bot.getId()) {
                    AgentMovementCommandRuntime.stop(runtime);
                    AgentMapWalk.start(runtime, agent, target, WALK_HOP_TIMEOUT_MS, now);
                }
                return null;
            });
        }

        @Override
        public boolean walking(int botId) {
            return AgentMapWalk.walking(botId);
        }

        @Override
        public void follow(Character bot) {
            AgentRuntimeEntry runtime = AgentRuntimeRegistry.findByAgentCharacterId(bot.getId());
            if (runtime == null) {
                return;
            }
            AgentMailboxRuntime.dispatch(runtime, entry -> {
                AgentMapWalk.stopAll(bot.getId());
                AgentMovementCommandRuntime.followConfiguredTarget(runtime);
                return null;
            });
        }

        @Override
        public void say(Character bot, String line) {
            AgentDialogueTransportRuntime.sayMapNow(bot, line);
        }
    };

    /** The visible portal on {@code map} straight to {@code toMapId} (a walking portal, not a script's), or null. */
    static Portal portalTo(MapleMap map, int toMapId) {
        for (Portal portal : map.getPortals()) {
            if (portal.getTargetMapId() == toMapId && portal.getPosition() != null) {
                return portal;
            }
        }
        return null;
    }
}
