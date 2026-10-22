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
 * boot ({@link #warm}); a leader's window reads it, every member's power and what the CWKPQ records taught
 * ({@link PqPaceLedger#calibration}, the profile's priors until runs are recorded) from memory on his I/O thread.
 */
final class CwkpqClearTime {
    private static final Logger log = LoggerFactory.getLogger(CwkpqClearTime.class);
    /** A failed read is logged at info at most once a minute; the window shows the list without a time. */
    private static final long FAILURE_LOG_MS = 60_000L;
    private static final AtomicLong failureLoggedAtMs = new AtomicLong();

    /** Where the estimate reads the warm profile, each member's power and what the records taught; a test's stub. */
    interface Pace {
        AgentPqPaceProfile profile();

        AgentPqMemberPower power(Character member, AgentPqPaceProfile profile, boolean human, boolean leader);

        /** What the clear-time records taught for this leader; the profile's priors while none were learned. */
        default AgentPqPaceCalibration calibration(AgentPqPaceProfile profile, int leaderId) {
            return AgentPqPaceCalibration.prior(profile);
        }

        /**
         * The warm profile, the characters' own attacks, and the CWKPQ records {@code ledger} holds (null: none): what
         * they taught for the leader, or the priors while none were read or they were read under another epoch.
         */
        static Pace live(PqPaceLedger ledger) {
            return new Pace() {
                @Override
                public AgentPqPaceProfile profile() {
                    return AgentPqPaceProfiles.cached(AgentCwkpqPaceProfile.QUEST_KEY);
                }

                @Override
                public AgentPqMemberPower power(Character member, AgentPqPaceProfile profile, boolean human,
                                                boolean leader) {
                    return AgentPqMemberPower.of(member, profile, human, leader);
                }

                @Override
                public AgentPqPaceCalibration calibration(AgentPqPaceProfile profile, int leaderId) {
                    AgentPqPaceCalibration learned = ledger == null ? null
                            : ledger.calibration(profile.questKey(), leaderId);
                    return learned != null && learned.epoch() == profile.epoch() ? learned
                            : AgentPqPaceCalibration.prior(profile);
                }
            };
        }
    }

    /** Jack's estimate and his words over it; the estimate is null when he shows no time. */
    record Shown(AgentPqPaceEstimate estimate, List<String> lines) {
        static final Shown NOTHING = new Shown(null, List.of());

        Shown {
            lines = List.copyOf(lines);
        }

        /** The lines joined for his window; null with nothing to say. */
        String text() {
            return lines.isEmpty() ? null : String.join("\r\n", lines);
        }
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
        return show(leader, members, pace, AgentPresence::isAgent).text();
    }

    static List<String> lines(Character leader, List<Character> members, Pace pace, Predicate<Character> isAgent) {
        return show(leader, members, pace, isAgent).lines();
    }

    /** {@link #text}'s estimate and lines; {@link Shown#NOTHING} when Jack has nothing to say. */
    static Shown show(Character leader, List<Character> members, Pace pace, Predicate<Character> isAgent) {
        if (leader == null || members == null || members.stream().noneMatch(isAgent)) {
            return Shown.NOTHING;
        }
        AgentPqPaceProfile profile = pace.profile();
        if (profile == null || members.size() < profile.minSize() || members.size() > profile.maxSize()) {
            return Shown.NOTHING;
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
            return Shown.NOTHING;
        }
        if (powers.size() != members.size() || powers.contains(null)) {
            return Shown.NOTHING; // the leader is not on his own list: nothing to weigh
        }
        AgentPqPaceEstimate estimate = AgentPqPaceEstimator.estimate(profile, powers,
                pace.calibration(profile, leader.getId()));
        if (estimate != null) {
            log.info("CWKPQ clear time: leader={} members={} clearable={} p10={}s p50={}s p90={}s records={}"
                            + " weaknesses={}", leader.getId(), powers.size(), estimate.clearable(),
                    Math.round(estimate.p10S()), Math.round(estimate.p50S()), Math.round(estimate.p90S()),
                    estimate.recordsUsed(), estimate.weaknesses());
        }
        return new Shown(estimate, JackLines.lines(estimate, powers));
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
