package server.agents.capabilities.expedition.cwkpq;

/** The CWKPQ pace profile from Map.wz and Mob.wz, for tests outside its package (no LifeFactory load). */
public final class CwkpqPaceTestProfiles {
    private CwkpqPaceTestProfiles() {
    }

    public static AgentCwkpqPaceProfile keep() {
        return AgentCwkpqPaceProfile.load(AgentCwkpqPaceProfile.Census.WZ);
    }
}
