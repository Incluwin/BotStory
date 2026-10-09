package server.agents.capabilities.expedition.boss;

/**
 * One adopted companion in a boss run. Its session changes it under the session's lock; the phase and the anchor are
 * volatile, so other threads (the host, the Agent tick) read them without the lock.
 */
public final class AgentBossRunMember {
    /** Where the member stands in the run. */
    public enum Phase {
        /** Enlisted; walking up to the entry NPC, or still at the store. */
        WAITING,
        /** On the entry NPC's list. */
        REGISTERED,
        /** Never got onto the list, or fell off it; no longer part of the fight. */
        REFUSED,
        /** In the expedition's event on the battle map. */
        INSIDE,
        /** On its way out of the event. */
        LEAVING,
        /** Out of the run. */
        OUT
    }

    /** Who leads the member outside the fight. */
    public enum Kind {
        /** Follows its owner (a party companion): it walks up with them and follows them again when handed back. */
        FOLLOWER,
        /**
         * Leads itself (its runtime leader is its own id): it walks to the spot the host names beside the entry NPC,
         * and when handed back it is stopped where it stands, never set following.
         */
        SELF_LED
    }

    private final int agentId;
    private final int enlistedBy;
    private final int ordinal;
    private final long enlistedAtMs;
    private final Kind kind;
    private volatile int anchorId;
    /** Since when its logged-out anchor has been gone from the event; -1 while the anchor is there. */
    private volatile long anchorGoneSinceMs = -1L;
    private volatile Phase phase = Phase.WAITING;
    /** Its host took it back before Start; set from any thread, acted on by the session's next tick. */
    private volatile boolean withdrawn;
    private long onMapSinceMs;
    private AgentBossRunHost.Refusal refusal;
    private boolean wentIn;
    private boolean inAtClear;
    private boolean seenDead;
    private boolean aliveOnceSinceDeath;
    private int deaths;
    private AgentBossRunHost.Out leavingFor;
    private long leavingSinceMs;
    private AgentBossRunHost.Out out;

    AgentBossRunMember(int agentId, int enlistedBy, int ordinal, long enlistedAtMs) {
        this(agentId, enlistedBy, ordinal, enlistedAtMs, Kind.FOLLOWER);
    }

    AgentBossRunMember(int agentId, int enlistedBy, int ordinal, long enlistedAtMs, Kind kind) {
        this.agentId = agentId;
        this.enlistedBy = enlistedBy;
        this.anchorId = enlistedBy;
        this.ordinal = ordinal;
        this.enlistedAtMs = enlistedAtMs;
        this.kind = kind == null ? Kind.FOLLOWER : kind;
    }

    public int agentId() {
        return agentId;
    }

    public Kind kind() {
        return kind;
    }

    /** The human whose enlist brought the member in. */
    public int enlistedBy() {
        return enlistedBy;
    }

    /** The human the member stays with: its enlister, or the heir of a logout handoff. */
    public int anchorId() {
        return anchorId;
    }

    /** Enlist order in the run (0 first); the fight's formation slot. */
    public int ordinal() {
        return ordinal;
    }

    public long enlistedAtMs() {
        return enlistedAtMs;
    }

    public Phase phase() {
        return phase;
    }

    /** Its host took it back before Start (see AgentBossRunService.withdraw). */
    public boolean withdrawn() {
        return withdrawn;
    }

    /** Why it was refused; null unless {@link Phase#REFUSED}. */
    public AgentBossRunHost.Refusal refusal() {
        return refusal;
    }

    public boolean wentIn() {
        return wentIn;
    }

    public boolean inAtClear() {
        return inAtClear;
    }

    /**
     * Seen dead and not yet seen alive in the fight on two ticks in a row; alive outside the event next means it died
     * out.
     */
    public boolean seenDead() {
        return seenDead;
    }

    public int deaths() {
        return deaths;
    }

    /** How it left the run; null while it is still part of it. */
    public AgentBossRunHost.Out out() {
        return out;
    }

    AgentBossRunHost.Seat seat(String bossKey, long sessionId) {
        return new AgentBossRunHost.Seat(bossKey, sessionId, agentId, enlistedBy);
    }

    AgentBossRunHost.MemberResult result() {
        return new AgentBossRunHost.MemberResult(agentId, enlistedBy, wentIn, inAtClear, deaths, out);
    }

    /** When it was first seen on the recruit map since it last stood elsewhere; 0 while it is off the map. */
    long onMapSinceMs() {
        return onMapSinceMs;
    }

    void onMapSince(long nowMs) {
        onMapSinceMs = nowMs;
    }

    void registered() {
        phase = Phase.REGISTERED;
    }

    /** Taken back by its host; the flag only, so any thread may set it without the session's lock. */
    void withdraw() {
        withdrawn = true;
    }

    void refused(AgentBossRunHost.Refusal reason) {
        refusal = reason;
        phase = Phase.REFUSED;
    }

    /** Adopted on the battle map: it fights with the run from now on. */
    void adopted() {
        wentIn = true;
        phase = Phase.INSIDE;
    }

    /** Was still in the fight when the boss fell. */
    void clearedInside() {
        inAtClear = true;
    }

    /**
     * Seen dead; true (and one more death) only for the first sighting of this death. Dead again after a sighting
     * alive in the fight is a new death: it was raised and fell again.
     */
    boolean died() {
        if (seenDead && !aliveOnceSinceDeath) {
            return false;
        }
        seenDead = true;
        aliveOnceSinceDeath = false;
        deaths++;
        return true;
    }

    /**
     * Seen alive in the fight. After a death, the first such sighting may be the stock respawn between its HP restore
     * and its map change, so only the second in a row means it was raised where it fell; its next fall is then
     * another death.
     */
    void upAgain() {
        if (!seenDead) {
            return;
        }
        if (aliveOnceSinceDeath) {
            seenDead = false;
            aliveOnceSinceDeath = false;
        } else {
            aliveOnceSinceDeath = true;
        }
    }

    /** Why it is on its way out (NO_HUMAN_LEFT, OWNER_LEFT or EXITED); null unless {@link Phase#LEAVING}. */
    AgentBossRunHost.Out leavingFor() {
        return leavingFor;
    }

    long leavingSinceMs() {
        return leavingSinceMs;
    }

    /** Heads out of the event for {@code reason}; its way out is timed from {@code nowMs}. */
    void leaving(AgentBossRunHost.Out reason, long nowMs) {
        leavingFor = reason;
        leavingSinceMs = nowMs;
        phase = Phase.LEAVING;
    }

    /** Stays with {@code humanId} from now on (the heir of its owner's logout); any wait for the old anchor ends. */
    void anchorTo(int humanId) {
        anchorId = humanId;
        anchorGoneSinceMs = -1L;
    }

    /** Since when its logged-out anchor has been gone, starting the wait at {@code nowMs} on the first sighting. */
    long anchorGoneSince(long nowMs) {
        long since = anchorGoneSinceMs;
        if (since < 0L) {
            since = nowMs;
            anchorGoneSinceMs = since;
        }
        return since;
    }

    /** Its anchor is in the event again: the wait is over. */
    void anchorHere() {
        anchorGoneSinceMs = -1L;
    }

    void leftRun(AgentBossRunHost.Out reason) {
        out = reason;
        phase = Phase.OUT;
    }
}
