package server.agents.capabilities.expedition.zakum;

import client.Character;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.expedition.AgentBossFight;

import java.util.List;

/**
 * The Zakum fight for adopted companions (P8). For now an idle fight: it gives members no orders, so the boss run's
 * own steps (sign-up, adoption, the way out) are all that drive them. The phases and targeting rules come with the
 * fight itself; Zakum stays off (COMPANION_BOSS_ENABLED_TYPES) until then.
 */
public final class AgentZakumScenario implements AgentBossFight {

    private AgentZakumScenario() {
    }

    /** A fresh fight for one boss run session. */
    public static AgentZakumScenario forCompanions() {
        return new AgentZakumScenario();
    }

    @Override
    public void adopt(int characterId, int ordinal) {
    }

    @Override
    public void tick(List<Character> fighters, EventInstanceManager event, long nowMs) {
    }

    @Override
    public void end(EventInstanceManager event) {
    }
}
