package server.agents.capabilities.expedition.boss;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.expedition.boss.BossTargetRules.Kind;
import server.agents.capabilities.expedition.boss.BossTargetRules.Member;
import server.agents.capabilities.expedition.boss.BossTargetRules.Part;
import server.agents.capabilities.expedition.boss.BossTargetRules.Phase;
import server.agents.capabilities.expedition.boss.BossTargetRules.Role;
import server.agents.capabilities.expedition.boss.BossTargetRules.Tier;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The pure targeting rules of a part-based boss (P8 step 10), on Zakum's parts. The arm reaches are the WZ stand boxes
 * (how far above the arm's origin its box ends): 8800007, 8800009 and 8800010 reach the floor at the shipped 100 px;
 * 8800003-8800006 and 8800008 hang above it.
 */
class BossTargetRulesTest {
    private static final int FLOOR_REACH_PX = 100;
    private static final int ADD_CLEAR_THRESHOLD = 6;
    private static final int FAKE_BODY = 8_800_000;
    private static final int SECOND_BODY = 8_800_001;
    private static final int LAST_BODY = 8_800_002;
    private static final Set<Integer> FLOOR_ARMS = Set.of(8_800_007, 8_800_009, 8_800_010);
    private static final Set<Integer> UPPER_ARMS = Set.of(8_800_003, 8_800_004, 8_800_005, 8_800_006, 8_800_008);
    private static final int ADD = 6_400_003;

    private static final Member MELEE = new Member(false, true);
    private static final Member RANGED = new Member(false, false);
    private static final Member MAGIC = new Member(true, false);

    @Test
    void nothingAliveBeforeTheSummonIsWaiting() {
        assertEquals(Phase.WAITING_FOR_SUMMON, BossTargetRules.phase(List.of(), false, false));
    }

    @Test
    void aLiveArmIsTheArmsPhaseWhileTheBodyIsStillFake() {
        assertEquals(Phase.ARMS, BossTargetRules.phase(List.of(fakeBody(), arm(8_800_009, -2)), true, false));
        assertEquals(Phase.ARMS, BossTargetRules.phase(List.of(arm(8_800_003, 266)), false, false),
                "an arm alive means the summon happened");
    }

    @Test
    void aGapWithNoArmAndNoRealBodyIsTheTransition() {
        assertEquals(Phase.TRANSITION, BossTargetRules.phase(List.of(fakeBody()), false, false),
                "the last arm fell and the body has not woken yet");
        assertEquals(Phase.TRANSITION, BossTargetRules.phase(List.of(), true, false),
                "a body form fell and its revive is not out yet");
        assertEquals(Phase.TRANSITION, BossTargetRules.phase(List.of(add(), add()), true, false));
    }

    @Test
    void aRealBodyIsTheBodyPhase() {
        assertEquals(Phase.BODY, BossTargetRules.phase(List.of(body(FAKE_BODY)), true, false),
                "8800000 is made real when the last arm falls");
        assertEquals(Phase.BODY, BossTargetRules.phase(List.of(body(LAST_BODY), add()), true, false));
    }

    @Test
    void theClearIsDoneWhateverStillLives() {
        assertEquals(Phase.DONE, BossTargetRules.phase(List.of(body(LAST_BODY), arm(8_800_003, 266)), true, true));
        assertEquals(Phase.DONE, BossTargetRules.phase(List.of(), false, true));
    }

    @Test
    void aFakePartIsNeverATargetForAnyMember() {
        List<Part> summon = summon();
        for (Member member : List.of(MELEE, RANGED, MAGIC)) {
            assertFalse(ids(BossTargetRules.tiers(summon, member, FLOOR_REACH_PX, ADD_CLEAR_THRESHOLD))
                    .contains(FAKE_BODY), member.toString());
            assertEquals(List.of(),
                    BossTargetRules.tiers(List.of(fakeBody()), member, FLOOR_REACH_PX, ADD_CLEAR_THRESHOLD),
                    "the fake body alone leaves nothing to hit: " + member);
        }
        Part fakeAdd = new Part(ADD, Role.ADD, true, false, false, false, 0);
        assertEquals(List.of(), BossTargetRules.tiers(List.of(fakeAdd), MELEE, FLOOR_REACH_PX, ADD_CLEAR_THRESHOLD));
    }

    @Test
    void meleeTakesTheFloorArmsFirstAndTheUpperArmsAfter() {
        List<Tier> tiers = BossTargetRules.tiers(summon(), MELEE, FLOOR_REACH_PX, ADD_CLEAR_THRESHOLD);

        assertEquals(List.of(new Tier(Kind.FLOOR_ARMS, FLOOR_ARMS), new Tier(Kind.UPPER_ARMS, UPPER_ARMS)), tiers);
        assertEquals(UPPER_ARMS, BossTargetRules.fallback(tiers));
    }

    @Test
    void rangedAndMagicTakeTheUpperArmsFirstAndTheFloorArmsAfter() {
        for (Member member : List.of(RANGED, MAGIC)) {
            List<Tier> tiers = BossTargetRules.tiers(summon(), member, FLOOR_REACH_PX, ADD_CLEAR_THRESHOLD);

            assertEquals(List.of(new Tier(Kind.UPPER_ARMS, UPPER_ARMS), new Tier(Kind.FLOOR_ARMS, FLOOR_ARMS)), tiers,
                    member.toString());
            assertEquals(FLOOR_ARMS, BossTargetRules.fallback(tiers));
        }
    }

    @Test
    void theFloorReachIsInclusive() {
        List<Part> parts = List.of(arm(1, 100), arm(2, 101));

        assertEquals(List.of(new Tier(Kind.FLOOR_ARMS, Set.of(1)), new Tier(Kind.UPPER_ARMS, Set.of(2))),
                BossTargetRules.tiers(parts, MELEE, 100, ADD_CLEAR_THRESHOLD));
    }

    @Test
    void armsComeBeforeTheBody() {
        List<Part> parts = List.of(body(SECOND_BODY), arm(8_800_009, -2), arm(8_800_004, 256));

        for (Member member : List.of(MELEE, RANGED, MAGIC)) {
            List<Tier> tiers = BossTargetRules.tiers(parts, member, FLOOR_REACH_PX, ADD_CLEAR_THRESHOLD);
            assertEquals(Kind.BODY, tiers.getLast().kind(), member.toString());
            assertEquals(3, tiers.size(), member.toString());
        }
    }

    @Test
    void weaponImmunityDropsTheBodyForPhysicalMembersOnly() {
        Part immune = new Part(SECOND_BODY, Role.BODY, false, true, true, false, 0);

        assertEquals(List.of(), BossTargetRules.tiers(List.of(immune), MELEE, FLOOR_REACH_PX, ADD_CLEAR_THRESHOLD));
        assertEquals(List.of(), BossTargetRules.tiers(List.of(immune), RANGED, FLOOR_REACH_PX, ADD_CLEAR_THRESHOLD));
        assertEquals(List.of(new Tier(Kind.BODY, Set.of(SECOND_BODY))),
                BossTargetRules.tiers(List.of(immune), MAGIC, FLOOR_REACH_PX, ADD_CLEAR_THRESHOLD));
    }

    @Test
    void magicImmunityDropsTheBodyForMagicMembersOnly() {
        Part immune = new Part(SECOND_BODY, Role.BODY, false, true, false, true, 0);

        assertEquals(List.of(), BossTargetRules.tiers(List.of(immune), MAGIC, FLOOR_REACH_PX, ADD_CLEAR_THRESHOLD));
        for (Member physical : List.of(MELEE, RANGED)) {
            assertEquals(List.of(new Tier(Kind.BODY, Set.of(SECOND_BODY))),
                    BossTargetRules.tiers(List.of(immune), physical, FLOOR_REACH_PX, ADD_CLEAR_THRESHOLD));
        }
    }

    @Test
    void aBossPartNoHumanDrivesIsDroppedButAnAddIsNot() {
        Part idleArm = new Part(8_800_007, Role.ARM, false, false, false, false, 87);
        Part idleBody = new Part(LAST_BODY, Role.BODY, false, false, false, false, 0);
        Part add = add();

        assertEquals(List.of(new Tier(Kind.FLOOR_ARMS, Set.of(8_800_009))),
                BossTargetRules.tiers(List.of(idleArm, arm(8_800_009, -2)), MELEE, FLOOR_REACH_PX,
                        ADD_CLEAR_THRESHOLD));
        assertEquals(List.of(), BossTargetRules.tiers(List.of(idleBody), MAGIC, FLOOR_REACH_PX, ADD_CLEAR_THRESHOLD));
        assertEquals(List.of(new Tier(Kind.ADDS, Set.of(ADD))),
                BossTargetRules.tiers(List.of(idleBody, add), MELEE, FLOOR_REACH_PX, ADD_CLEAR_THRESHOLD));
    }

    @Test
    void nothingLeftToHitIsNoTier() {
        assertEquals(List.of(), BossTargetRules.tiers(List.of(), MELEE, FLOOR_REACH_PX, ADD_CLEAR_THRESHOLD));
        assertEquals(Set.of(), BossTargetRules.fallback(List.of()));
    }

    @Test
    void addsBelowTheThresholdComeLastAndAtItFirst() {
        List<Part> five = withAdds(body(LAST_BODY), 5);
        List<Part> six = withAdds(body(LAST_BODY), 6);

        assertEquals(List.of(new Tier(Kind.BODY, Set.of(LAST_BODY)), new Tier(Kind.ADDS, Set.of(ADD))),
                BossTargetRules.tiers(five, MELEE, FLOOR_REACH_PX, ADD_CLEAR_THRESHOLD));
        assertEquals(List.of(new Tier(Kind.ADDS, Set.of(ADD)), new Tier(Kind.BODY, Set.of(LAST_BODY))),
                BossTargetRules.tiers(six, MELEE, FLOOR_REACH_PX, ADD_CLEAR_THRESHOLD),
                "six live adds of one id count as six");
        assertEquals(Set.of(LAST_BODY), BossTargetRules.fallback(
                BossTargetRules.tiers(six, MAGIC, FLOOR_REACH_PX, ADD_CLEAR_THRESHOLD)));
    }

    @Test
    void aTierKeepsItsOwnCopyOfTheIds() {
        Set<Integer> ids = new java.util.HashSet<>(Set.of(1, 2));
        Tier tier = new Tier(Kind.ADDS, ids);

        ids.add(3);

        assertEquals(Set.of(1, 2), tier.mobIds());
    }

    /** Right after the Eye: the fake body and the eight arms, each driven by a human. */
    private static List<Part> summon() {
        List<Part> parts = new ArrayList<>();
        parts.add(fakeBody());
        parts.add(arm(8_800_003, 266));
        parts.add(arm(8_800_004, 256));
        parts.add(arm(8_800_005, 210));
        parts.add(arm(8_800_006, 212));
        parts.add(arm(8_800_007, 87));
        parts.add(arm(8_800_008, 160));
        parts.add(arm(8_800_009, -2));
        parts.add(arm(8_800_010, 30));
        return parts;
    }

    private static List<Part> withAdds(Part body, int adds) {
        List<Part> parts = new ArrayList<>(List.of(body));
        for (int index = 0; index < adds; index++) {
            parts.add(add());
        }
        return parts;
    }

    private static Part arm(int mobId, int reachPx) {
        return new Part(mobId, Role.ARM, false, true, false, false, reachPx);
    }

    private static Part fakeBody() {
        return new Part(FAKE_BODY, Role.BODY, true, true, false, false, 0);
    }

    private static Part body(int mobId) {
        return new Part(mobId, Role.BODY, false, true, false, false, 0);
    }

    private static Part add() {
        return new Part(ADD, Role.ADD, false, false, false, false, 0);
    }

    private static Set<Integer> ids(List<Tier> tiers) {
        Set<Integer> ids = new java.util.HashSet<>();
        tiers.forEach(tier -> ids.addAll(tier.mobIds()));
        return ids;
    }
}
