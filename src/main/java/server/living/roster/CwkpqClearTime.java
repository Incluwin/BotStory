package server.living.roster;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.expedition.cwkpq.AgentCwkpqPaceProfile;
import server.agents.capabilities.partyquest.pace.AgentPqMemberPower;
import server.agents.capabilities.partyquest.pace.AgentPqPaceCalibration;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimator;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfile;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfiles;
import server.integration.AgentPresence;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * CWKPQ plan C8: the clear-time estimate Jack gives over the leader's list. The profile is warmed on companion-ops at
 * boot ({@link #warm}); a leader's window reads it, every member's power and the priors from memory on his I/O thread.
 * No CWKPQ run is recorded yet, so the estimate stands on the profile's priors ({@link AgentPqPaceCalibration#prior}).
 */
final class CwkpqClearTime {
    private static final Logger log = LoggerFactory.getLogger(CwkpqClearTime.class);
    /** A failed read is logged at info at most once a minute; the window shows the list without a time. */
    private static final long FAILURE_LOG_MS = 60_000L;
    private static final AtomicLong failureLoggedAtMs = new AtomicLong();

    /** Where the estimate reads the warm profile and each member's power; a test's stub. */
    interface Pace {
        AgentPqPaceProfile profile();

        AgentPqMemberPower power(Character member, AgentPqPaceProfile profile, boolean human, boolean leader);

        Pace LIVE = new Pace() {
            @Override
            public AgentPqPaceProfile profile() {
                return AgentPqPaceProfiles.cached(AgentCwkpqPaceProfile.QUEST_KEY);
            }

            @Override
            public AgentPqMemberPower power(Character member, AgentPqPaceProfile profile, boolean human,
                                            boolean leader) {
                return AgentPqMemberPower.of(member, profile, human, leader);
            }
        };
    }

    private CwkpqClearTime() {
    }

    /** Registers and builds the profile; on companion-ops at boot, since it parses Map.wz and Mob.wz. */
    static void warm() {
        try {
            AgentPqPaceProfiles.register(AgentCwkpqPaceProfile.QUEST_KEY, AgentCwkpqPaceProfile::load);
            AgentPqPaceProfiles.warm(AgentCwkpqPaceProfile.QUEST_KEY);
        } catch (RuntimeException | LinkageError failure) {
            log.warn("CWKPQ's pace profile could not be warmed; Jack shows no clear time", failure);
        }
    }

    /**
     * Jack's words over the leader's list, lines joined for his window; null with nothing to say: no companion on the
     * list, a cold profile, a list outside 6-30, or a member's power that could not be read.
     */
    static String text(Character leader, List<Character> members, Pace pace) {
        List<String> lines = lines(leader, members, pace, AgentPresence::isAgent);
        return lines.isEmpty() ? null : String.join("\r\n", lines);
    }

    static List<String> lines(Character leader, List<Character> members, Pace pace, Predicate<Character> isAgent) {
        if (leader == null || members == null || members.stream().noneMatch(isAgent)) {
            return List.of();
        }
        AgentPqPaceProfile profile = pace.profile();
        if (profile == null || members.size() < profile.minSize() || members.size() > profile.maxSize()) {
            return List.of();
        }
        List<AgentPqMemberPower> powers = new ArrayList<>();
        try {
            // humans first, the leader first among them, as the PQ desks order a lineup
            powers.add(pace.power(leader, profile, true, true));
            for (Character member : members) {
                if (member.getId() != leader.getId() && !isAgent.test(member)) {
                    powers.add(pace.power(member, profile, true, false));
                }
            }
            for (Character member : members) {
                if (member.getId() != leader.getId() && isAgent.test(member)) {
                    powers.add(pace.power(member, profile, false, false));
                }
            }
        } catch (RuntimeException | LinkageError failure) {
            failed(leader, failure);
            return List.of();
        }
        if (powers.size() != members.size() || powers.contains(null)) {
            return List.of(); // the leader is not on his own list: nothing to weigh
        }
        AgentPqPaceEstimate estimate = AgentPqPaceEstimator.estimate(profile, powers,
                AgentPqPaceCalibration.prior(profile));
        if (estimate != null) {
            log.info("CWKPQ clear time: leader={} members={} clearable={} p10={}s p50={}s p90={}s weaknesses={}",
                    leader.getId(), powers.size(), estimate.clearable(), Math.round(estimate.p10S()),
                    Math.round(estimate.p50S()), Math.round(estimate.p90S()), estimate.weaknesses());
        }
        return JackLines.lines(estimate, powers);
    }

    private static void failed(Character leader, Throwable failure) {
        long now = System.currentTimeMillis();
        long loggedAt = failureLoggedAtMs.get();
        if (now - loggedAt >= FAILURE_LOG_MS && failureLoggedAtMs.compareAndSet(loggedAt, now)) {
            log.info("no CWKPQ clear time at {}'s list; Jack shows it without one", leader.getName(), failure);
        } else {
            log.debug("no CWKPQ clear time at {}'s list", leader.getName(), failure);
        }
    }
}
