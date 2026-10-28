package server.living.mentor;

/**
 * One leg of a mentor's lead (docs/BOT_CONCEPT_PLAN.md F4.3), as a pure state machine the mentor sweep steps once a
 * second with what it sees ({@link View}); it answers with one {@link Move}. Companions follow, never lead: this is the
 * one place a companion goes first.
 *
 * <ul>
 *   <li>A two-way portal ({@link Leg#twoWay}): the mentor walks ahead to the portal, goes through, and waits on the far
 *   side up to {@code waitMs}. The newcomer comes: done. Not: it comes back through ("you coming?") and walks ahead
 *   again, at most {@code tries} times, then it gives up leading and just follows.</li>
 *   <li>A one-way portal or an NPC (Shanks, the taxi): it walks ahead and waits beside it, saying what to do; the
 *   newcomer goes first and the mentor comes right behind by the normal follow, so it is never stranded ahead. A leg
 *   it must walk through itself after the newcomer ({@link Leg#throughAfter}, Lith Harbor's ship deck, whose way off is
 *   a hidden portal on the same map) gets an ENTER once the newcomer is across.</li>
 *   <li>The newcomer goes another way ({@link View#newcomerOffPlan}): the lead is over and the mentor follows them.</li>
 * </ul>
 */
public final class MentorLead {
    /** What the mentor does now; the sweep carries it out on the mentor's own mailbox. */
    public enum Act {
        /** Nothing new (still walking, or waiting). */
        NONE,
        /** Walk up to the leg's portal or NPC and stand there. */
        GO_DOOR,
        /** Go through the leg's portal. */
        ENTER,
        /** Go back through the far side's portal to the newcomer's side. */
        COME_BACK,
        /** The lead is over: follow the newcomer again. */
        FOLLOW
    }

    /** {@code act} and the line it says with it (a mentor.yaml key, null for none). */
    public record Move(Act act, String line) {
        static final Move NONE = new Move(Act.NONE, null);

        static Move of(Act act) {
            return new Move(act, null);
        }
    }

    /**
     * The leg: {@code twoWay}, it goes first and waits on the far side; else it waits at the door and the newcomer goes
     * first. {@code throughAfter}: the far side is on the same map (a hidden portal), so it walks through itself once
     * the newcomer is across. {@code waitLine}: what it says at a one-way door or an NPC (a mentor.yaml key).
     */
    public record Leg(boolean twoWay, boolean throughAfter, String waitLine) { }

    /**
     * What the sweep sees this second: whether the mentor stands at the door, is still walking, is across (on the far
     * side) or back on the newcomer's side; whether the newcomer is across or went somewhere off the plan.
     */
    public record View(long nowMs, boolean mentorAtDoor, boolean mentorWalking, boolean mentorAcross,
                       boolean newcomerAcross, boolean newcomerOffPlan) { }

    enum Phase { GOING, ENTERING, BEYOND, RETURNING, WAITING_NEAR, FOLLOWING_THROUGH, OVER }

    private final Leg leg;
    private final long waitMs;
    private final int maxTries;
    private Phase phase = Phase.GOING;
    private long sinceMs;
    private int tries;
    private boolean gaveUp;

    public MentorLead(Leg leg, long waitMs, int maxTries, long nowMs) {
        this.leg = leg;
        this.waitMs = waitMs;
        this.maxTries = maxTries;
        this.sinceMs = nowMs;
    }

    /** The first move: off to the door, saying so. */
    public Move start() {
        return new Move(Act.GO_DOOR, "lead.start");
    }

    public Move step(View view) {
        if (phase == Phase.OVER) {
            return Move.NONE;
        }
        if (view.newcomerOffPlan()) {
            return giveUp(); // they went their own way: follow them
        }
        return switch (phase) {
            case GOING -> going(view);
            case ENTERING -> entering(view);
            case BEYOND -> beyond(view);
            case RETURNING -> returning(view);
            case WAITING_NEAR -> waitingNear(view);
            case FOLLOWING_THROUGH -> view.mentorAcross() ? end() : view.mentorWalking() ? Move.NONE : Move.of(Act.ENTER);
            case OVER -> Move.NONE;
        };
    }

    private Move going(View view) {
        if (view.newcomerAcross()) {
            return leg.throughAfter() ? through(view) : end(); // they went ahead: right behind them
        }
        if (!view.mentorAtDoor()) {
            return view.mentorWalking() ? Move.NONE : Move.of(Act.GO_DOOR); // a stalled walk sets off again
        }
        sinceMs = view.nowMs();
        if (leg.twoWay()) {
            phase = Phase.ENTERING;
            return Move.of(Act.ENTER);
        }
        phase = Phase.WAITING_NEAR;
        return new Move(Act.NONE, leg.waitLine());
    }

    private Move entering(View view) {
        if (view.mentorAcross()) {
            phase = Phase.BEYOND;
            sinceMs = view.nowMs();
            return Move.NONE;
        }
        return view.mentorWalking() ? Move.NONE : Move.of(Act.ENTER);
    }

    private Move beyond(View view) {
        if (view.newcomerAcross()) {
            return end();
        }
        if (view.nowMs() - sinceMs < waitMs) {
            return Move.NONE;
        }
        if (++tries > maxTries) {
            return giveUp(); // the follow brings it back to them
        }
        phase = Phase.RETURNING;
        sinceMs = view.nowMs();
        return Move.of(Act.COME_BACK);
    }

    private Move returning(View view) {
        if (view.newcomerAcross()) {
            return end(); // they crossed while it came back: it follows them through
        }
        if (view.mentorAcross()) {
            return view.mentorWalking() ? Move.NONE : Move.of(Act.COME_BACK);
        }
        phase = Phase.GOING;
        return new Move(Act.GO_DOOR, "lead.come");
    }

    private Move waitingNear(View view) {
        if (view.newcomerAcross()) {
            return leg.throughAfter() ? through(view) : end();
        }
        if (view.nowMs() - sinceMs < waitMs) {
            return Move.NONE;
        }
        sinceMs = view.nowMs();
        if (++tries > maxTries) {
            return giveUp();
        }
        return new Move(Act.NONE, "lead.nudge");
    }

    private Move through(View view) {
        phase = Phase.FOLLOWING_THROUGH;
        return view.mentorAcross() ? end() : Move.of(Act.ENTER);
    }

    private Move giveUp() {
        gaveUp = true;
        return end();
    }

    private Move end() {
        phase = Phase.OVER;
        return Move.of(Act.FOLLOW);
    }

    public boolean over() {
        return phase == Phase.OVER;
    }

    /** True when it ended without the newcomer coming along (they went their own way or did not follow). */
    public boolean gaveUp() {
        return gaveUp;
    }

    Phase phase() {
        return phase;
    }

    public Leg leg() {
        return leg;
    }
}
