package server.living.population;

import java.util.LinkedHashSet;
import java.util.Set;

/** One generated population bot with a life (plan 3.1). Row of {@code living_citizen}. */
public record Citizen(int characterId,
                      String name,
                      String cohort,
                      int circleId,
                      int guildId,
                      String homeRegion,
                      String career,
                      String personality,
                      String voice,
                      double pace,
                      int offsetMinutes,
                      int channel,
                      Set<String> flags,
                      State state,
                      long joinedAtMs,
                      long retiresAtMs,
                      long lastOnlineAtMs,
                      long lastLedgerAtMs,
                      long accruedExp,
                      int scheduledMinutes,
                      String lifeSummary) {

    public enum State { ACTIVE, RETIRED }

    public static final String FLAG_MERCHANT = "merchant";
    public static final String FLAG_FAMER = "famer";
    public static final String FLAG_PQ_REGULAR = "pq-regular";
    public static final String FLAG_AFK_TOWNIE = "afk-townie";
    public static final String FLAG_RECRUITER = "recruiter";
    /**
     * A player hired it (P4, HiredHands): the roster owns its character, so the population leaves it alone (no
     * sessions, ledger, churn count, circles or guild moves) until it is let go.
     */
    public static final String FLAG_HIRED = "hired";
    /**
     * Already old enough for its third (or fourth) job when citizens started taking them (2026-10-06): that job comes
     * off-screen with its next catch-up, then the flag is gone. Every later one is an errand (CitizenJobPath).
     */
    public static final String FLAG_JOB_BACKLOG = "job-backlog";

    public boolean has(String flag) {
        return flags.contains(flag);
    }

    /** True while a player employs it as a hired hand. */
    public boolean hired() {
        return has(FLAG_HIRED);
    }

    /** Age in whole days since it joined the server. */
    public int ageDays(long nowMs) {
        return (int) Math.max(0L, (nowMs - joinedAtMs) / 86_400_000L);
    }

    public Citizen withState(State newState) {
        return new Citizen(characterId, name, cohort, circleId, guildId, homeRegion, career, personality, voice, pace,
                offsetMinutes, channel, flags, newState, joinedAtMs, retiresAtMs, lastOnlineAtMs, lastLedgerAtMs, accruedExp,
                scheduledMinutes, lifeSummary);
    }

    public Citizen withGuild(int newGuildId) {
        return new Citizen(characterId, name, cohort, circleId, newGuildId, homeRegion, career, personality, voice, pace,
                offsetMinutes, channel, flags, state, joinedAtMs, retiresAtMs, lastOnlineAtMs, lastLedgerAtMs, accruedExp,
                scheduledMinutes, lifeSummary);
    }

    public Citizen withCircle(int newCircleId) {
        return new Citizen(characterId, name, cohort, newCircleId, guildId, homeRegion, career, personality, voice, pace,
                offsetMinutes, channel, flags, state, joinedAtMs, retiresAtMs, lastOnlineAtMs, lastLedgerAtMs, accruedExp,
                scheduledMinutes, lifeSummary);
    }

    public Citizen withRegion(String region) {
        return new Citizen(characterId, name, cohort, circleId, guildId, region, career, personality, voice, pace,
                offsetMinutes, channel, flags, state, joinedAtMs, retiresAtMs, lastOnlineAtMs, lastLedgerAtMs, accruedExp,
                scheduledMinutes, lifeSummary);
    }

    public Citizen withChannel(int newChannel) {
        return new Citizen(characterId, name, cohort, circleId, guildId, homeRegion, career, personality, voice, pace,
                offsetMinutes, newChannel, flags, state, joinedAtMs, retiresAtMs, lastOnlineAtMs, lastLedgerAtMs, accruedExp,
                scheduledMinutes, lifeSummary);
    }

    public Citizen withLedger(long ledgerAtMs, long exp, int minutes) {
        return new Citizen(characterId, name, cohort, circleId, guildId, homeRegion, career, personality, voice, pace,
                offsetMinutes, channel, flags, state, joinedAtMs, retiresAtMs, lastOnlineAtMs, ledgerAtMs, exp, minutes, lifeSummary);
    }

    public Citizen withOnline(long onlineAtMs) {
        return new Citizen(characterId, name, cohort, circleId, guildId, homeRegion, career, personality, voice, pace,
                offsetMinutes, channel, flags, state, joinedAtMs, retiresAtMs, onlineAtMs, lastLedgerAtMs, accruedExp,
                scheduledMinutes, lifeSummary);
    }

    public Citizen withSummary(String summary) {
        return new Citizen(characterId, name, cohort, circleId, guildId, homeRegion, career, personality, voice, pace,
                offsetMinutes, channel, flags, state, joinedAtMs, retiresAtMs, lastOnlineAtMs, lastLedgerAtMs, accruedExp,
                scheduledMinutes, summary);
    }

    public Citizen withFlag(String flag) {
        Set<String> next = new LinkedHashSet<>(flags);
        next.add(flag);
        return new Citizen(characterId, name, cohort, circleId, guildId, homeRegion, career, personality, voice, pace,
                offsetMinutes, channel, next, state, joinedAtMs, retiresAtMs, lastOnlineAtMs, lastLedgerAtMs, accruedExp,
                scheduledMinutes, lifeSummary);
    }

    public Citizen withoutFlag(String flag) {
        Set<String> next = new LinkedHashSet<>(flags);
        next.remove(flag);
        return new Citizen(characterId, name, cohort, circleId, guildId, homeRegion, career, personality, voice, pace,
                offsetMinutes, channel, next, state, joinedAtMs, retiresAtMs, lastOnlineAtMs, lastLedgerAtMs, accruedExp,
                scheduledMinutes, lifeSummary);
    }
}
