package server.agents.integration.cosmic;

import client.BotClient;
import client.Character;
import client.Client;
import scripting.npc.NPCScriptManager;

import javax.script.ScriptEngine;

final class CosmicHeadlessNpcScriptGateway {
    private static final int MAX_SCRIPT_ADVANCES = config.AgentTuning.intValue("server.agents.integration.cosmic.CosmicHeadlessNpcScriptGateway.MAX_SCRIPT_ADVANCES");

    private CosmicHeadlessNpcScriptGateway() {
    }

    static boolean execute(Character agent, int npcId, int... selections) {
        return execute(CosmicPreparedNpcScriptEngines.INSTANCE, agent, npcId, selections);
    }

    static boolean execute(CosmicPreparedNpcScriptEngines engines, Character agent, int npcId, int... selections) {
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
                if (!scripts.start(client, npcId, agent, prepared)) {
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
}
