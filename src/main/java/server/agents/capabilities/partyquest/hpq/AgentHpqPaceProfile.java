package server.agents.capabilities.partyquest.hpq;

import server.agents.capabilities.partyquest.AgentPartyQuestCatalog;
import server.agents.capabilities.partyquest.AgentPartyQuestDefinition;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfile;
import server.agents.capabilities.partyquest.pace.AgentPqPaceSegment;

import java.util.List;
import java.util.Map;

/**
 * What HPQ asks of a party, as the clear-time estimate sees it. A host registers it by {@link #QUEST_KEY} with
 * {@link #load} (on companion-ops). Its segments, mobs and priors arrive with HPQ's pace work; until then the profile
 * does not warm, so AgentPqPaceProfiles keeps HPQ unestimated rather than estimating it from nothing.
 */
public final class AgentHpqPaceProfile implements AgentPqPaceProfile {
    public static final String QUEST_KEY = "hpq";

    private final AgentPartyQuestDefinition definition;

    private AgentHpqPaceProfile(AgentPartyQuestDefinition definition) {
        this.definition = definition;
    }

    public static AgentHpqPaceProfile load() {
        return new AgentHpqPaceProfile(AgentPartyQuestCatalog.require(QUEST_KEY));
    }

    @Override
    public String questKey() {
        return QUEST_KEY;
    }

    @Override
    public long timerMs() {
        return definition.eventTimerMs();
    }

    @Override
    public int minSize() {
        return definition.minimumPartySize();
    }

    @Override
    public int maxSize() {
        return definition.maximumPartySize();
    }

    @Override
    public List<Integer> mobIds() {
        return List.of();
    }

    @Override
    public int dangerMobId() {
        return 0;
    }

    @Override
    public boolean warmed() {
        return false;
    }

    @Override
    public List<AgentPqPaceSegment> segments(int partySize) {
        return List.of();
    }

    @Override
    public Map<String, Double> priors() {
        return Map.of();
    }

    @Override
    public int epoch() {
        return 0;
    }

    @Override
    public Thresholds thresholds() {
        return new Thresholds(0d, 0d, Map.of());
    }
}
