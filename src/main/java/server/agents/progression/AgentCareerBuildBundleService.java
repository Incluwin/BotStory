package server.agents.progression;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.build.profiles.AgentApBuildProfileService;
import server.agents.capabilities.build.profiles.AgentSpBuildProfileService;
import server.agents.runtime.AgentRuntimeEntry;

import java.io.IOException;
import java.util.List;

/**
 * Restores a durable assignment, re-picking one whose bundle no longer covers the Agent's job,
 * or makes one deterministic first-time assignment.
 */
public final class AgentCareerBuildBundleService {
    private static final Logger log = LoggerFactory.getLogger(AgentCareerBuildBundleService.class);
    private static final AgentCareerAssignmentStore STORE = FileAgentCareerAssignmentStore.runtimeDefault();

    private AgentCareerBuildBundleService() {
    }

    public static AgentCareerBuildBundle restoreOrAssign(AgentRuntimeEntry entry, long nowMs) {
        Character agent = entry == null ? null : entry.bot();
        if (agent == null || agent.getId() <= 0) {
            return null;
        }
        try {
            return restoreOrAssign(entry, agent, nowMs,
                    AgentCareerBuildBundleRepository.defaultRepository(), STORE);
        } catch (IOException | RuntimeException failure) {
            log.warn("Could not restore Agent career build bundle for {} ({})",
                    agent.getName(), agent.getId(), failure);
            return null;
        }
    }

    public static AgentCareerBuildBundle assignForTest(AgentRuntimeEntry entry,
                                                        String bundleId,
                                                        long nowMs) throws IOException {
        Character agent = entry == null ? null : entry.bot();
        if (agent == null || agent.getId() <= 0) {
            throw new IllegalArgumentException("a live Agent is required");
        }
        return assignForTest(entry, agent, bundleId, nowMs,
                AgentCareerBuildBundleRepository.defaultRepository(), STORE);
    }

    /** Explicit beginner-only selection used by the World Director before first-job work starts. */
    public static AgentCareerBuildBundle assignExplicit(
            AgentRuntimeEntry entry, String bundleId, long nowMs) throws IOException {
        Character agent = entry == null ? null : entry.bot();
        if (agent == null || agent.getId() <= 0 || bundleId == null || bundleId.isBlank()) {
            throw new IllegalArgumentException("a live Agent and career bundle are required");
        }
        AgentCareerProgressionState state = entry.capabilityStates()
                .require(AgentCareerProgressionState.STATE_KEY);
        AgentCareerBuildBundle current = state.bundle();
        if (current != null && current.bundleId().equals(bundleId)) return current;
        if (agent.getJob().getId() != 0
                || state.stage() != AgentCareerProgressionState.Stage.WAITING_FOR_MAPLE_ISLAND) {
            throw new IllegalStateException(
                    "career build can only change before first-job progression begins");
        }
        AgentCareerBuildBundle selected = AgentCareerBuildBundleRepository.defaultRepository()
                .find(bundleId)
                .orElseThrow(() -> new IllegalArgumentException("unknown career bundle " + bundleId));
        AgentProgressionProfile profile = AgentProgressionProfileRepository.defaultRepository()
                .deterministicFor(agent.getId());
        STORE.save(new AgentCareerAssignment(2, agent.getId(), agent.getName(),
                selected.bundleId(), selected.bundleVersion(), profile.profileId(), nowMs));
        assignProfiles(entry, selected, profile);
        state.reset(selected, AgentCareerProgressionState.RunMode.LEVEL15,
                AgentCareerProgressionState.Stage.WAITING_FOR_MAPLE_ISLAND, nowMs);
        return selected;
    }

    static AgentCareerBuildBundle assignForTest(AgentRuntimeEntry entry,
                                                 Character agent,
                                                 String bundleId,
                                                 long nowMs,
                                                 AgentCareerBuildBundleRepository repository,
                                                 AgentCareerAssignmentStore store) throws IOException {
        AgentCareerBuildBundle bundle = repository
                .find(bundleId)
                .orElseThrow(() -> new IllegalArgumentException("unknown career bundle " + bundleId));
        AgentProgressionProfile profile = AgentProgressionProfileRepository.defaultRepository()
                .deterministicFor(agent.getId());
        store.save(new AgentCareerAssignment(2, agent.getId(), agent.getName(),
                bundle.bundleId(), bundle.bundleVersion(), profile.profileId(), nowMs));
        assignProfiles(entry, bundle, profile);
        entry.capabilityStates().require(AgentCareerProgressionState.STATE_KEY).assign(bundle);
        return bundle;
    }

    static AgentCareerBuildBundle restoreOrAssign(AgentRuntimeEntry entry,
                                                   Character agent,
                                                   long nowMs,
                                                   AgentCareerBuildBundleRepository repository,
                                                   AgentCareerAssignmentStore store) throws IOException {
        AgentCareerAssignment assignment = store.load(agent.getId()).orElse(null);
        AgentCareerBuildBundle bundle;
        if (assignment == null) {
            List<AgentCareerBuildBundle> choices = compatibleChoices(repository.all(), agent.getJob().getId());
            bundle = choices.get(Math.floorMod(agent.getId(), choices.size()));
            AgentProgressionProfile profile = AgentProgressionProfileRepository.defaultRepository()
                    .deterministicFor(agent.getId());
            assignment = new AgentCareerAssignment(2, agent.getId(), agent.getName(),
                    bundle.bundleId(), bundle.bundleVersion(), profile.profileId(), nowMs);
            store.save(assignment);
        } else {
            String assignedBundleId = assignment.bundleId();
            bundle = repository.find(assignedBundleId)
                    .orElseThrow(() -> new IOException(
                            "assigned career bundle no longer exists: " + assignedBundleId));
            if (bundle.bundleVersion() != assignment.bundleVersion()) {
                throw new IOException("assigned career bundle version changed without migration: "
                        + assignment.bundleId());
            }
            // A Beginner draws from every bundle; one that then advanced into another class or branch
            // (shaping, GM, owner chat) kept a bundle whose AP/SP profiles cannot serve its job.
            int jobId = agent.getJob().getId();
            if (jobId != 0 && !covers(bundle, jobId)) {
                List<AgentCareerBuildBundle> choices = compatibleChoices(repository.all(), jobId);
                AgentCareerBuildBundle repicked = choices.get(Math.floorMod(agent.getId(), choices.size()));
                if (covers(repicked, jobId)) {
                    log.info("Reassigning career build bundle for {} ({}): {} does not cover job {}, now {}",
                            agent.getName(), agent.getId(), bundle.bundleId(), jobId, repicked.bundleId());
                    bundle = repicked;
                    assignment = new AgentCareerAssignment(2, assignment.characterId(), assignment.characterName(),
                            bundle.bundleId(), bundle.bundleVersion(), assignment.progressionProfileId(), nowMs);
                    store.save(assignment);
                }
            }
        }
        AgentProgressionProfileRepository profiles = AgentProgressionProfileRepository.defaultRepository();
        AgentProgressionProfile profile;
        if (assignment.progressionProfileId().isBlank()) {
            profile = profiles.deterministicFor(agent.getId());
            assignment = new AgentCareerAssignment(2, assignment.characterId(), assignment.characterName(),
                    assignment.bundleId(), assignment.bundleVersion(), profile.profileId(), assignment.assignedAtMs());
            store.save(assignment);
        } else {
            String assignedProfileId = assignment.progressionProfileId();
            profile = profiles.find(assignedProfileId).orElseThrow(
                    () -> new IOException("assigned progression profile no longer exists: "
                            + assignedProfileId));
        }
        assignProfiles(entry, bundle, profile);
        entry.capabilityStates().require(AgentCareerProgressionState.STATE_KEY).assign(bundle);
        return bundle;
    }

    private static void assignProfiles(AgentRuntimeEntry entry,
                                       AgentCareerBuildBundle bundle,
                                       AgentProgressionProfile progressionProfile) {
        AgentApBuildProfileService.select(entry, bundle.apProfileId());
        AgentSpBuildProfileService.select(entry, bundle.spProfileId());
        entry.capabilityStates().require(AgentProgressionProfileState.STATE_KEY)
                .assign(progressionProfile);
    }

    private static List<AgentCareerBuildBundle> compatibleChoices(List<AgentCareerBuildBundle> all, int jobId) {
        if (jobId == 0) {
            return all;
        }
        List<AgentCareerBuildBundle> compatible = all.stream()
                .filter(bundle -> covers(bundle, jobId))
                .toList();
        return compatible.isEmpty() ? all : compatible;
    }

    /**
     * The bundle's first job matches; from 2nd job on, its first-job SP profile must also be the one
     * the Agent's branch descends from (claw vs dagger, gun vs knuckle).
     */
    private static boolean covers(AgentCareerBuildBundle bundle, int jobId) {
        int firstJobId = jobId < 1_000 ? (jobId / 100) * 100 : jobId;
        if (bundle.firstJobId() != firstJobId) {
            return false;
        }
        return jobId % 100 == 0
                || bundle.spProfileId().equals(AgentSpBuildProfileService.firstJobProfileId(jobId));
    }
}
