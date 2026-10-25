package server.agents.progression;

import client.Character;
import client.Job;
import client.JobProgressionPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.build.AgentBuildService;
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
    private static final FileAgentApResetStore AP_RESETS = FileAgentApResetStore.runtimeDefault();

    private AgentCareerBuildBundleService() {
    }

    public static AgentCareerBuildBundle restoreOrAssign(AgentRuntimeEntry entry, long nowMs) {
        Character agent = entry == null ? null : entry.bot();
        if (agent == null || agent.getId() <= 0) {
            return null;
        }
        try {
            return restoreOrAssign(entry, agent, nowMs,
                    AgentCareerBuildBundleRepository.defaultRepository(), STORE, AP_RESETS);
        } catch (IOException | RuntimeException failure) {
            log.warn("Could not restore Agent career build bundle for {} ({})",
                    agent.getName(), agent.getId(), failure);
            return null;
        }
    }

    /**
     * Right after a job change: a bundle that no longer covers the new job is re-picked now (restoreOrAssign), not at
     * the next login. A Noblesse drew one of five knighthoods by its id; the class it takes at its Chief Knight is the
     * one its AP and SP follow from then on. Nothing happens for an Agent with no bundle or one that still covers.
     */
    public static void followJobChange(AgentRuntimeEntry entry, long nowMs) {
        Character agent = entry == null ? null : entry.bot();
        AgentCareerBuildBundle bundle = agent == null ? null : entry.capabilityStates()
                .find(AgentCareerProgressionState.STATE_KEY).map(AgentCareerProgressionState::bundle).orElse(null);
        if (bundle != null && !covers(bundle, agent.getJob().getId())) {
            restoreOrAssign(entry, nowMs);
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
                                                   AgentCareerAssignmentStore store,
                                                   FileAgentApResetStore apResets) throws IOException {
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
            // A Beginner draws from every Explorer bundle; one that then advanced into another class or branch
            // (shaping, GM, owner chat) kept a bundle whose AP/SP profiles cannot serve its job. A Noblesse or a
            // Legend assigned before its line had bundles holds an Explorer one.
            int jobId = agent.getJob().getId();
            if (!covers(bundle, jobId)) {
                List<AgentCareerBuildBundle> choices = compatibleChoices(repository.all(), jobId);
                AgentCareerBuildBundle repicked = choices.get(Math.floorMod(agent.getId(), choices.size()));
                if (covers(repicked, jobId)) {
                    log.info("Reassigning career build bundle for {} ({}): {} does not cover job {}, now {}",
                            agent.getName(), agent.getId(), bundle.bundleId(), jobId, repicked.bundleId());
                    if (!repicked.apProfileId().equals(bundle.apProfileId())) {
                        // Saved before the new assignment, so a failure in between repeats the re-pick.
                        apResets.save(new AgentApReset(1, agent.getId(), agent.getName(),
                                bundle.bundleId(), repicked.bundleId(), nowMs, 0L));
                    }
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
        completeOwedApReset(entry, agent, apResets, nowMs);
        entry.capabilityStates().require(AgentCareerProgressionState.STATE_KEY).assign(bundle);
        return bundle;
    }

    /**
     * Runs the reset owed since a re-pick changed the Agent's AP profile, once. A reset from the job's
     * stat floors lands on the same stats every time, so a marker that failed to save only repeats it.
     */
    private static void completeOwedApReset(AgentRuntimeEntry entry,
                                            Character agent,
                                            FileAgentApResetStore apResets,
                                            long nowMs) {
        try {
            AgentApReset reset = apResets.load(agent.getId()).orElse(null);
            if (reset == null || reset.completed()) {
                return;
            }
            String before = statLine(agent);
            if (!AgentBuildService.resetApToProfile(entry, agent)) {
                log.warn("Could not reset AP for {} ({}); it stays owed", agent.getName(), agent.getId());
                return;
            }
            apResets.save(reset.completedAt(nowMs));
            log.info("Reset AP for {} ({}) after its career bundle moved {} -> {}: {} -> {}",
                    agent.getName(), agent.getId(), reset.fromBundleId(), reset.toBundleId(),
                    before, statLine(agent));
        } catch (IOException | RuntimeException failure) {
            log.warn("Could not complete the owed AP reset for {} ({})", agent.getName(), agent.getId(), failure);
        }
    }

    private static String statLine(Character agent) {
        return "STR " + agent.getStr() + " DEX " + agent.getDex() + " INT " + agent.getInt()
                + " LUK " + agent.getLuk() + " AP " + agent.getRemainingAp();
    }

    private static void assignProfiles(AgentRuntimeEntry entry,
                                       AgentCareerBuildBundle bundle,
                                       AgentProgressionProfile progressionProfile) {
        AgentApBuildProfileService.select(entry, bundle.apProfileId());
        AgentSpBuildProfileService.select(entry, bundle.spProfileId());
        entry.capabilityStates().require(AgentProgressionProfileState.STATE_KEY)
                .assign(progressionProfile);
    }

    /**
     * The bundles that cover the job: a beginner job (Beginner, Noblesse, Legend) draws from its own line's bundles.
     * None covering (a job outside every line): all of them, as before.
     */
    private static List<AgentCareerBuildBundle> compatibleChoices(List<AgentCareerBuildBundle> all, int jobId) {
        List<AgentCareerBuildBundle> compatible = all.stream()
                .filter(bundle -> covers(bundle, jobId))
                .toList();
        return compatible.isEmpty() ? all : compatible;
    }

    /**
     * A beginner job: the bundle's first job starts from it (a Beginner never draws a Cygnus Knight bundle). A first
     * job: it is the bundle's. From 2nd job on, the bundle's first-job SP profile must also be the one the Agent's
     * branch descends from (claw vs dagger, gun vs knuckle).
     */
    private static boolean covers(AgentCareerBuildBundle bundle, int jobId) {
        Job job = Job.getById(jobId);
        if (job != null && jobId % 1_000 == 0) {
            return JobProgressionPolicy.beginnerOf(Job.getById(bundle.firstJobId())) == job;
        }
        if (bundle.firstJobId() != jobId / 100 * 100) {
            return false;
        }
        return jobId % 100 == 0
                || bundle.spProfileId().equals(AgentSpBuildProfileService.firstJobProfileId(jobId));
    }
}
