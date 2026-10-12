package server.agents.capabilities.expedition.boss;

import client.Character;
import client.status.MonsterStatus;
import scripting.event.EventInstanceManager;
import server.life.Monster;
import server.maps.MapleMap;

/**
 * The immunity and reflect stop for every boss (P12 G4), in companion boss scope only. A member never takes, as a
 * primary or a secondary target, a monster that is immune to its damage type (WEAPON_IMMUNITY for a physical member,
 * MAGIC_IMMUNITY for a magic one) or that reflects it (WEAPON_REFLECT, MAGIC_REFLECT: mob skills 143, 144 and 145).
 * The member switches to another target, or holds while nothing else is left. USE_ANTI_IMMUNITY_CRASH stays off
 * (decision 12).
 *
 * <p>Companion boss scope here: an INSIDE or LEAVING member of an open run hitting a monster in its own event, or any
 * Agent hitting a field boss whose field key is on (AgentBossField). Everything else keeps today's path: the EPQ stone
 * bug while it is immune, every party quest, every field monster no field group lists. Memory only, any thread; the
 * scope is checked first, so an Agent outside it reads no monster status.</p>
 */
public final class AgentBossTargetGate {
    private AgentBossTargetGate() {
    }

    /** True when {@code agent} must not hit {@code monster} now: in scope, and immune to or reflecting its damage. */
    public static boolean refuses(Character agent, Monster monster) {
        if (agent == null || monster == null || !inScope(agent, monster)) {
            return false;
        }
        boolean magic = agent.getJob() != null && agent.getJob().getJobNiche() == 2;
        return monster.isBuffed(magic ? MonsterStatus.MAGIC_IMMUNITY : MonsterStatus.WEAPON_IMMUNITY)
                || monster.isBuffed(magic ? MonsterStatus.MAGIC_REFLECT : MonsterStatus.WEAPON_REFLECT);
    }

    /** An inside member hitting a monster of its own event, or any Agent hitting a field boss whose key is on. */
    static boolean inScope(Character agent, Monster monster) {
        MapleMap map = monster.getMap();
        if (map == null) {
            return false;
        }
        EventInstanceManager event = map.getEventInstance();
        if (event != null) {
            return agent.getEventInstance() == event && AgentBossRunRegistry.inside(agent.getId());
        }
        return AgentBossField.covers(monster);
    }
}
