package server.agents.capabilities.expedition.boss;

import client.Character;
import server.life.Monster;
import server.maps.MapleMap;

/**
 * The boss rules' field scope (P12 G1): a field boss fights on a map with no event (Mano, Pianus, the Neo City field
 * bosses). A boss the boss rules catalog lists under a field group (AgentBossRules.fieldKeyOf) is in scope while the
 * host's kill switch lists that group's key (AgentBossRunHost.Rule.bossEnabled). Then the attack mirror hits every
 * Agent in its attacks' shape there, citizens too (decision 19), and the client-load floor spaces the attacks of the
 * Agents beside it. Memory only, any thread.
 */
public final class AgentBossField {
    private AgentBossField() {
    }

    /** True for a field group's boss whose key is on, standing on a map with no event. */
    public static boolean covers(Monster monster) {
        MapleMap map = monster == null ? null : monster.getMap();
        if (map == null || map.getEventInstance() != null) {
            return false;
        }
        String key = AgentBossRules.fieldKeyOf(monster.getId());
        return key != null && AgentBossRunHost.installed().bossEnabled(key);
    }

    /** True while a live boss {@link #covers} stands on the Agent's map, a map with no event. */
    public static boolean near(Character bot) {
        MapleMap map = bot == null ? null : bot.getMap();
        if (map == null || map.getEventInstance() != null) {
            return false;
        }
        for (Monster monster : map.getAllMonsters()) {
            if (monster.isAlive() && covers(monster)) {
                return true;
            }
        }
        return false;
    }
}
