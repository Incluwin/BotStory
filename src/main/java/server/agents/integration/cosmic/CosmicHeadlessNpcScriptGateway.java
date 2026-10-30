package server.agents.integration.cosmic;

import client.BotClient;
import client.Character;
import client.Client;
import scripting.npc.NPCScriptManager;
import server.ShopFactory;
import server.mods.RepackMaps;

import javax.script.ScriptEngine;

final class CosmicHeadlessNpcScriptGateway {
    private static final int MAX_SCRIPT_ADVANCES = config.AgentTuning.intValue("server.agents.integration.cosmic.CosmicHeadlessNpcScriptGateway.MAX_SCRIPT_ADVANCES");

    private CosmicHeadlessNpcScriptGateway() {
    }

    static boolean execute(Character agent, int npcId, int... selections) {
        return execute(CosmicPreparedNpcScriptEngines.INSTANCE, agent, npcId, selections);
    }

    static boolean execute(CosmicPreparedNpcScriptEngines engines, Character agent, int npcId, int... selections) {
        return executeOnObject(engines, agent, npcId, -1, selections);
    }

    /**
     * Runs {@code npcId}'s script as a click on the NPC map object {@code npcObjectId} would (-1: no object, as
     * {@link #execute} does), for scripts that key their state by the clicked object.
     */
    static boolean executeOnObject(CosmicPreparedNpcScriptEngines engines, Character agent, int npcId,
                                   int npcObjectId, int... selections) {
        Client client = agent.getClient();
        if (!(client instanceof BotClient)) {
            return false;
        }
        NPCScriptManager scripts = NPCScriptManager.getInstance();
        long startedNs = System.nanoTime();
        ScriptEngine prepared = engines.take(NPCScriptManager.npcScriptPath(npcId));
        try {
            synchronized (scripts) {
                client.removeClickedNPC();
                if (!scripts.start(client, npcId, npcObjectId, agent, prepared)
                        && !startRepackTalk(scripts, client, agent, npcId, npcObjectId)) {
                    return false;
                }
                int advances = 0;
                while (scripts.getCM(client) != null && advances < MAX_SCRIPT_ADVANCES) {
                    int selection = selections != null && advances < selections.length
                            ? selections[advances] : 0;
                    scripts.action(client, (byte) 1, (byte) 0, selection);
                    advances++;
                }
                if (scripts.getCM(client) != null) {
                    scripts.dispose(client);
                    return false;
                }
            }
            return true;
        } finally {
            engines.recordRun(prepared != null, System.nanoTime() - startedNs);
        }
    }

    /**
     * As NPCTalkHandler answers a player's click: an NPC of the Community Repack's maps with no script of its own and no
     * shop talks through npc/repack_talk.js (one of its String.wz lines; a town guide also offers the way back to Three
     * Doors, server.mods.RepackGuide). Every other NPC without a script still fails as before.
     */
    private static boolean startRepackTalk(NPCScriptManager scripts, Client client, Character agent, int npcId,
                                           int npcObjectId) {
        return RepackMaps.listed(agent.getMapId())
                && ShopFactory.getInstance().getShopForNPC(npcId) == null
                && scripts.start(client, npcId, npcObjectId, "repack_talk", agent);
    }
}
