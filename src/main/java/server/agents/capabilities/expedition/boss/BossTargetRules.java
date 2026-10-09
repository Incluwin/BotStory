package server.agents.capabilities.expedition.boss;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Who a companion hits in a fight against a boss made of parts (P8, Zakum first): pure rules over a snapshot of the
 * live monsters on the battle map, so they hold no state and read no config.
 *
 * <p>{@link #phase} names the stage of the fight: nothing summoned yet, arms alive, a gap with no arm and no real
 * body (the last arm fell before the body woke, or a body form fell before its revive), the real body, and the
 * clear.</p>
 *
 * <p>{@link #tiers} orders the mob ids a member should hit, first tier first. A fake part is never a target. A boss
 * part counts only while a human really drives it ({@code engaged}, AgentBossScope.engaged), so companions never
 * fight a part nobody plays against. A part immune to the member's damage type (WEAPON_IMMUNITY for a physical
 * member, MAGIC_IMMUNITY for a magic one) is dropped for that member only. Arms come before the body. A melee member
 * takes the arms that reach the floor first; a ranged or magic member takes the upper arms first. Adds, the boss's
 * summons, come first once at least the add threshold of them are alive, and last otherwise. No tier means the member
 * has nothing to hit and holds.</p>
 */
public final class BossTargetRules {

    /** The stage of a part-based boss fight. */
    public enum Phase { WAITING_FOR_SUMMON, ARMS, TRANSITION, BODY, DONE }

    /** What a live monster on the battle map is to the fight. */
    public enum Role { ARM, BODY, ADD }

    /** What one target tier holds. */
    public enum Kind { ADDS, FLOOR_ARMS, UPPER_ARMS, BODY }

    /**
     * One live monster on the battle map as the rules see it. {@code reachPx} is how far above the part's own origin
     * (the floor it was spawned on) its body box ends: an arm that reaches down to the floor has a small or negative
     * value.
     */
    public record Part(int mobId, Role role, boolean fake, boolean engaged, boolean weaponImmune,
                       boolean magicImmune, int reachPx) {
    }

    /** How a member hits: with magic or not, and in melee range or from afar. */
    public record Member(boolean magic, boolean melee) {
    }

    /** One tier of mob ids to hit, in the order the tiers are listed. */
    public record Tier(Kind kind, Set<Integer> mobIds) {
        public Tier {
            mobIds = Set.copyOf(mobIds);
        }
    }

    private BossTargetRules() {
    }

    /**
     * The fight's stage. Cleared is DONE; a live arm is ARMS; a real live body is BODY; otherwise TRANSITION once
     * the boss was summoned (a part was ever seen, or a fake part stands now) and WAITING_FOR_SUMMON before that.
     */
    public static Phase phase(List<Part> parts, boolean summoned, boolean cleared) {
        if (cleared) {
            return Phase.DONE;
        }
        if (parts.stream().anyMatch(part -> part.role() == Role.ARM)) {
            return Phase.ARMS;
        }
        if (parts.stream().anyMatch(part -> part.role() == Role.BODY && !part.fake())) {
            return Phase.BODY;
        }
        boolean bossPart = parts.stream().anyMatch(part -> part.role() != Role.ADD);
        return summoned || bossPart ? Phase.TRANSITION : Phase.WAITING_FOR_SUMMON;
    }

    /**
     * The member's target tiers, first tier first, with no empty tier. The arm tiers split at {@code floorReachPx}:
     * an arm whose body box ends at most that far above its origin reaches the floor. Adds count toward
     * {@code addClearThreshold} whatever the member's damage type.
     */
    public static List<Tier> tiers(List<Part> parts, Member member, int floorReachPx, int addClearThreshold) {
        Set<Integer> adds = new LinkedHashSet<>();
        Set<Integer> floorArms = new LinkedHashSet<>();
        Set<Integer> upperArms = new LinkedHashSet<>();
        Set<Integer> body = new LinkedHashSet<>();
        int liveAdds = 0;
        for (Part part : parts) {
            if (part.fake()) {
                continue;
            }
            if (part.role() == Role.ADD) {
                liveAdds++;
            }
            if (!hittable(part, member)) {
                continue;
            }
            switch (part.role()) {
                case ADD -> adds.add(part.mobId());
                case ARM -> (part.reachPx() <= floorReachPx ? floorArms : upperArms).add(part.mobId());
                case BODY -> body.add(part.mobId());
            }
        }
        boolean addsFirst = liveAdds >= addClearThreshold;
        List<Tier> tiers = new ArrayList<>();
        if (addsFirst) {
            add(tiers, Kind.ADDS, adds);
        }
        if (member.melee()) {
            add(tiers, Kind.FLOOR_ARMS, floorArms);
            add(tiers, Kind.UPPER_ARMS, upperArms);
        } else {
            add(tiers, Kind.UPPER_ARMS, upperArms);
            add(tiers, Kind.FLOOR_ARMS, floorArms);
        }
        add(tiers, Kind.BODY, body);
        if (!addsFirst) {
            add(tiers, Kind.ADDS, adds);
        }
        return List.copyOf(tiers);
    }

    /** Every mob id of the tiers after the first: what a member falls back to while its first tier is out of reach. */
    public static Set<Integer> fallback(List<Tier> tiers) {
        Set<Integer> rest = new LinkedHashSet<>();
        for (int index = 1; index < tiers.size(); index++) {
            rest.addAll(tiers.get(index).mobIds());
        }
        return Set.copyOf(rest);
    }

    /** A boss part needs a human driving it; an add does not. Either is dropped while immune to the member. */
    private static boolean hittable(Part part, Member member) {
        if (part.role() != Role.ADD && !part.engaged()) {
            return false;
        }
        return member.magic() ? !part.magicImmune() : !part.weaponImmune();
    }

    private static void add(List<Tier> tiers, Kind kind, Set<Integer> mobIds) {
        if (!mobIds.isEmpty()) {
            tiers.add(new Tier(kind, mobIds));
        }
    }
}
