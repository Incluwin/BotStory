package server.agents.capabilities.partyquest.pace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.partyquest.AgentPartyQuestDefinition;
import server.agents.capabilities.partyquest.kpq.AgentKpqPaceProfile;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The party quests' pace profiles. {@link #warm} builds one from game data on the calling thread, which must be a
 * background executor (the living world's companion-ops); {@link #cached} only reads what a warm-up built and returns
 * null while the profile is cold, so an NPC's lineup on a leader's I/O thread never parses WZ data.
 */
public final class AgentPqPaceProfiles {
    private static final Logger log = LoggerFactory.getLogger(AgentPqPaceProfiles.class);
    private static final Registry REGISTRY = new Registry();

    static {
        REGISTRY.register(AgentKpqPaceProfile.QUEST_KEY, AgentKpqPaceProfile::load);
    }

    private AgentPqPaceProfiles() {
    }

    public static void register(String questKey, Supplier<? extends AgentPqPaceProfile> loader) {
        REGISTRY.register(questKey, loader);
    }

    /** Builds the profile on this thread if it is cold; the warmed profile, or null when it could not be built. */
    public static AgentPqPaceProfile warm(String questKey) {
        return REGISTRY.warm(questKey);
    }

    /** The warmed profile, or null while cold. Never loads anything. */
    public static AgentPqPaceProfile cached(String questKey) {
        return REGISTRY.cached(questKey);
    }

    /** One set of loaders and warmed profiles (the static one above; tests build their own). */
    static final class Registry {
        private final Map<String, Supplier<? extends AgentPqPaceProfile>> loaders = new ConcurrentHashMap<>();
        private final Map<String, AgentPqPaceProfile> warmed = new ConcurrentHashMap<>();

        void register(String questKey, Supplier<? extends AgentPqPaceProfile> loader) {
            if (loader == null) throw new IllegalArgumentException("a profile loader is required");
            loaders.put(AgentPartyQuestDefinition.normalize(questKey), loader);
        }

        AgentPqPaceProfile warm(String questKey) {
            String key = AgentPartyQuestDefinition.normalize(questKey);
            AgentPqPaceProfile ready = warmed.get(key);
            if (ready != null) return ready;
            Supplier<? extends AgentPqPaceProfile> loader = loaders.get(key);
            if (loader == null) {
                log.warn("no PQ pace profile for {}; its clear time stays unestimated", key);
                return null;
            }
            try {
                AgentPqPaceProfile profile = loader.get();
                if (profile == null || !profile.warmed()) {
                    log.warn("PQ pace profile {} did not warm; its clear time stays unestimated", key);
                    return null;
                }
                AgentPqPaceProfile raced = warmed.putIfAbsent(key, profile);
                return raced != null ? raced : profile;
            } catch (RuntimeException failure) {
                log.warn("PQ pace profile {} could not be built; its clear time stays unestimated", key, failure);
                return null;
            }
        }

        AgentPqPaceProfile cached(String questKey) {
            if (questKey == null || questKey.isBlank()) return null;
            return warmed.get(AgentPartyQuestDefinition.normalize(questKey));
        }
    }
}
