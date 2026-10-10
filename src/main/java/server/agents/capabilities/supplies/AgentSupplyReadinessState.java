package server.agents.capabilities.supplies;

import server.agents.capabilities.contracts.AgentProcurementRequest;
import server.agents.capabilities.contracts.AgentResourceCategory;
import server.agents.runtime.state.AgentCapabilityStateKey;

/**
 * The standing readiness duty's memory (AgentSupplyReadinessRuntime): when the Agent next looks at its potions, the
 * readiness trip under way, the last time it could not go (on which map, and how many mesos it lacked), the cooldown
 * that keeps it from trying again too soon, how many trips from one town in a row were cut short, the last
 * affordability check of a critical potion trip, and what it was given while it could not pay (its last look at its
 * purse, shortfall and own shop visits). Written on the Agent tick; a host only asks for an early look
 * ({@link #checkSoon}) and reads it.
 */
public final class AgentSupplyReadinessState {
    public static final AgentCapabilityStateKey<AgentSupplyReadinessState> STATE_KEY =
            new AgentCapabilityStateKey<>("supplies.readiness", AgentSupplyReadinessState.class,
                    AgentSupplyReadinessState::new);

    /** Why the last assessment did not set off. */
    public enum Verdict { CANNOT_AFFORD, NO_SPACE, NO_SUPPLIER }

    /** What keeps it from trying again, and what lifts it. */
    enum Cooldown {
        /** Until its purse grows by what it lacked, or it lacks fewer potions. */
        CANNOT_AFFORD,
        /** A trip ran out of mesos: until its purse grows by what it still lacks, or it lacks fewer potions. */
        SHORT_MESOS,
        /** Until a Use slot more is free, or it lacks fewer potions. */
        NO_SPACE,
        /** A trip ran out of Use slots: until a Use slot more is free, or it lacks fewer potions. */
        SHORT_SPACE,
        /** A trip was cut short: until its time is up. */
        INTERRUPTED,
        /** Trips from this town were cut short again and again: until it has been on another map. */
        GAVE_UP
    }

    /**
     * The last affordability check of a critical potion trip of {@code category}: allowed until {@code untilMs}, or
     * refused while the purse stays under {@code mesosAt + missingMesos} and it carries no more than {@code countAt}.
     */
    record CriticalCheck(AgentResourceCategory category, boolean allowed, long untilMs, int mesosAt,
                         int missingMesos, int countAt) { }

    private long nextCheckAtMs;
    private AgentProcurementRequest request;
    private AgentSupplyReadiness.Activity activity;
    private int hops;
    private long etaAtMs;
    private int originMapId;
    private Verdict verdict;
    private int verdictMapId;
    private int verdictMissingMesos;
    private long verdictAtMs;
    private Cooldown cooldown;
    private long cooldownUntilMs;
    private int cooldownMesosAt;
    private int cooldownMissingMesos;
    private int cooldownFreeUseSlotsAt;
    private int cooldownHpShortAt;
    private int cooldownMpShortAt;
    private int cooldownAmmoShortAt;
    private int cooldownMapId;
    private int cooldownSupplierMapId = -1;
    private int interruptedInARow;
    private int interruptedMapId;
    private CriticalCheck criticalCheck;
    private int lookMapId;
    private int lookMesos;
    private AgentSupplyReadiness.Shortfall lookShortfall;
    private long lookShopVisits;
    private boolean givenPending;
    private long stillShortToldAtMs = -1L;

    synchronized long nextCheckAtMs() {
        return nextCheckAtMs;
    }

    synchronized void scheduleNextCheck(long atMs) {
        nextCheckAtMs = atMs;
    }

    /** The next tick looks (a host nudged it). */
    synchronized void checkSoon() {
        nextCheckAtMs = 0L;
    }

    // ---- the readiness trip ----------------------------------------------------------------

    synchronized void offer(AgentProcurementRequest offered, AgentSupplyReadiness.Activity forActivity,
                            int tripHops, long etaAt, int fromMapId) {
        request = offered;
        activity = forActivity;
        hops = tripHops;
        etaAtMs = etaAt;
        originMapId = fromMapId;
    }

    /** The readiness request under way (or offered to begin), null when none. */
    synchronized AgentProcurementRequest request() {
        return request;
    }

    synchronized AgentSupplyReadiness.Activity activity() {
        return activity;
    }

    synchronized int hops() {
        return hops;
    }

    /** The town map the readiness trip under way set off from; 0 when none. */
    synchronized int originMapId() {
        return originMapId;
    }

    /** When the readiness trip {@code requestId} should be back; 0 when that is not the trip under way. */
    synchronized long etaAtMs(String requestId) {
        return request != null && request.requestId().equals(requestId) ? etaAtMs : 0L;
    }

    synchronized void clearRequest() {
        request = null;
        activity = null;
        hops = 0;
        etaAtMs = 0L;
        originMapId = 0;
    }

    // ---- the last verdict ------------------------------------------------------------------

    /** Records {@code next} on {@code mapId}; true when it differs from the last one (kind or map). */
    synchronized boolean verdict(Verdict next, int mapId, int missingMesos, long nowMs) {
        boolean changed = verdict != next || verdictMapId != mapId;
        verdict = next;
        verdictMapId = mapId;
        verdictMissingMesos = Math.max(0, missingMesos);
        verdictAtMs = nowMs;
        return changed;
    }

    /** The last verdict when it was found on {@code mapId}; else null. */
    synchronized Verdict verdictOn(int mapId) {
        return verdict != null && verdictMapId == mapId ? verdict : null;
    }

    synchronized Verdict verdict() {
        return verdict;
    }

    synchronized int verdictMissingMesos() {
        return verdictMissingMesos;
    }

    synchronized long verdictAtMs() {
        return verdictAtMs;
    }

    synchronized void clearVerdict() {
        verdict = null;
        verdictMapId = 0;
        verdictMissingMesos = 0;
        verdictAtMs = 0L;
    }

    // ---- the cooldown ----------------------------------------------------------------------

    /**
     * Sets {@code kind}: {@code shortfallAt} is what it lacked then (fewer later lifts a meso or space cooldown),
     * {@code mapId} the town a GAVE_UP cooldown holds for.
     */
    synchronized void cooldown(Cooldown kind, long untilMs, int mesosAt, int missingMesos, int freeUseSlotsAt,
                               AgentSupplyReadiness.Shortfall shortfallAt, int mapId) {
        cooldown = kind;
        cooldownUntilMs = untilMs;
        cooldownMesosAt = Math.max(0, mesosAt);
        cooldownMissingMesos = Math.max(0, missingMesos);
        cooldownFreeUseSlotsAt = Math.max(0, freeUseSlotsAt);
        cooldownHpShortAt = shortfallAt == null ? Integer.MAX_VALUE : shortfallAt.hp();
        cooldownMpShortAt = shortfallAt == null ? Integer.MAX_VALUE : shortfallAt.mp();
        cooldownAmmoShortAt = shortfallAt == null ? Integer.MAX_VALUE : shortfallAt.ammo();
        cooldownMapId = mapId;
        cooldownSupplierMapId = -1;
    }

    /** The store the trips it gave up on went to: standing there (before the follow warp lands) is not elsewhere. */
    synchronized void cooldownSupplier(int supplierMapId) {
        cooldownSupplierMapId = supplierMapId;
    }

    /**
     * The cooldown in force now, or null once lifted (and then forgotten): a meso cooldown lifts when the purse has
     * grown by what it lacked, a space cooldown when more Use slots are free than when it was set, either when it now
     * lacks fewer potions of a kind or fewer projectiles (someone gave it some); an interrupted trip's when its time is
     * up; a given-up one
     * once it stands on another map.
     */
    synchronized Cooldown cooldownInForce(long nowMs, int mesos, int freeUseSlots,
                                          AgentSupplyReadiness.Shortfall shortfall, int mapId) {
        if (cooldown != null && lifted(nowMs, mesos, freeUseSlots, shortfall, mapId)) {
            if (cooldown == Cooldown.GAVE_UP) {
                interruptedInARow = 0;
            }
            cooldown = null;
        }
        return cooldown;
    }

    /** As {@link #cooldownInForce}, without forgetting a lifted one (a host's read). */
    synchronized Cooldown cooldownShown(long nowMs, int mesos, int freeUseSlots,
                                        AgentSupplyReadiness.Shortfall shortfall, int mapId) {
        return cooldown == null || lifted(nowMs, mesos, freeUseSlots, shortfall, mapId) ? null : cooldown;
    }

    private boolean lifted(long nowMs, int mesos, int freeUseSlots, AgentSupplyReadiness.Shortfall shortfall,
                           int mapId) {
        boolean fewer = shortfall != null
                && (shortfall.hp() < cooldownHpShortAt || shortfall.mp() < cooldownMpShortAt
                || shortfall.ammo() < cooldownAmmoShortAt);
        return switch (cooldown) {
            case CANNOT_AFFORD, SHORT_MESOS -> (long) mesos >= (long) cooldownMesosAt + cooldownMissingMesos || fewer;
            case NO_SPACE, SHORT_SPACE -> freeUseSlots > cooldownFreeUseSlotsAt || fewer;
            case INTERRUPTED -> nowMs >= cooldownUntilMs;
            case GAVE_UP -> mapId != cooldownMapId && mapId != cooldownSupplierMapId;
        };
    }

    /** What a meso cooldown still lacks at {@code mesos}. */
    synchronized int cooldownMissingMesos(int mesos) {
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE,
                (long) cooldownMesosAt + cooldownMissingMesos - Math.max(0, mesos)));
    }

    /** It has all its potions: what it could not pay for or carry no longer holds it back. */
    synchronized void clearSpendCooldown() {
        if (cooldown == Cooldown.CANNOT_AFFORD || cooldown == Cooldown.SHORT_MESOS
                || cooldown == Cooldown.NO_SPACE || cooldown == Cooldown.SHORT_SPACE) {
            cooldown = null;
        }
    }

    /** A given-up town is behind it once it stands on another map (looked at on every check). */
    synchronized void noteMap(int mapId) {
        if (cooldown == Cooldown.GAVE_UP && mapId != cooldownMapId && mapId != cooldownSupplierMapId) {
            cooldown = null;
            interruptedInARow = 0;
        }
    }

    synchronized Cooldown cooldown() {
        return cooldown;
    }

    // ---- trips cut short in a row ---------------------------------------------------------------

    /** One more trip from {@code mapId} was cut short: how many in a row from that town now. */
    synchronized int interrupted(int mapId) {
        interruptedInARow = mapId == interruptedMapId ? interruptedInARow + 1 : 1;
        interruptedMapId = mapId;
        return interruptedInARow;
    }

    /** A trip came back ready. */
    synchronized void resetInterrupted() {
        interruptedInARow = 0;
        interruptedMapId = 0;
    }

    // ---- what it was given while it could not pay ------------------------------------------------

    /**
     * One check's look at its purse and its shortfall on {@code mapId}, once {@code shopVisits} shop visits of its own
     * have ended (AgentShopStateRuntime.shopVisitGeneration). While a meso cooldown holds it back (CANNOT_AFFORD,
     * SHORT_MESOS) and it stands on {@code giftGround} (a town or a monster-free map, where no monster drops a coin,
     * that it may set off from), a purse grown or fewer potions or projectiles lacking since the last look on this
     * same map is something it was given; that stays to be told until {@link #toldStillShort} or {@link #clearGiven}.
     * Another map, or a shop visit of its own that ended since (it bought or sold there itself), forgets it, as
     * {@link #forgetLook} does.
     */
    synchronized void look(int mapId, int mesos, AgentSupplyReadiness.Shortfall shortfall, boolean giftGround,
                           long shopVisits) {
        boolean sameMap = lookShortfall != null && mapId == lookMapId && shopVisits == lookShopVisits;
        if (!sameMap) {
            givenPending = false;
        } else if (giftGround && (cooldown == Cooldown.CANNOT_AFFORD || cooldown == Cooldown.SHORT_MESOS)
                && (mesos > lookMesos || shortfall.hp() < lookShortfall.hp() || shortfall.mp() < lookShortfall.mp()
                || shortfall.ammo() < lookShortfall.ammo())) {
            givenPending = true;
        }
        lookMapId = mapId;
        lookMesos = mesos;
        lookShortfall = shortfall;
        lookShopVisits = shopVisits;
    }

    /**
     * Takes what it holds now on {@code mapId} as its last look, with nothing given left to tell: what changed before
     * this was answered already (a broke line said between two looks), and only what changes after it is a gift.
     */
    synchronized void rebaseLook(int mapId, int mesos, AgentSupplyReadiness.Shortfall shortfall, long shopVisits) {
        lookMapId = mapId;
        lookMesos = mesos;
        lookShortfall = shortfall;
        lookShopVisits = shopVisits;
        givenPending = false;
    }

    /**
     * Forgets the last look and anything given not told yet: what it buys or earns on its own (a supply trip, a shop
     * visit under way, time without its duty) is no gift, and the next look starts afresh.
     */
    synchronized void forgetLook() {
        lookMapId = 0;
        lookMesos = 0;
        lookShortfall = null;
        lookShopVisits = 0L;
        givenPending = false;
    }

    /**
     * True when something it was given is still to be told and the last still-short news is at least {@code everyMs}
     * old (or none was told yet).
     */
    synchronized boolean givenToTell(long nowMs, long everyMs) {
        return givenPending && (stillShortToldAtMs < 0L || nowMs - stillShortToldAtMs >= everyMs);
    }

    /**
     * Its host heard that what it was given still does not pay for the trip; {@code said}: and said a line, from which
     * the spacing counts (news it kept quiet holds back no later gift).
     */
    synchronized void toldStillShort(long nowMs, boolean said) {
        givenPending = false;
        if (said) {
            stillShortToldAtMs = nowMs;
        }
    }

    /** What it was given needs no news: it sets off, needs nothing more, or cannot go for another reason. */
    synchronized void clearGiven() {
        givenPending = false;
    }

    // ---- the critical path's affordability check -------------------------------------------------

    synchronized CriticalCheck criticalCheck() {
        return criticalCheck;
    }

    synchronized void criticalCheck(CriticalCheck check) {
        criticalCheck = check;
    }
}
