package server.agents.capabilities.expedition.balrog;

import server.agents.capabilities.expedition.boss.AgentBossRules;
import server.expeditions.ExpeditionType;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * The maps and mob ids of one Balrog fight that AgentEasyBalrogScenario drives: Easy Balrog's (AgentBalrogDefinition)
 * and Normal Balrog's (P11). The two arenas are WZ twins (105100400/105100401 and 105100300/105100301 have the same
 * footholds, ropes and portals), so the scenario's stations and walls serve both. Normal's mob ids come from the boss
 * rules catalog (AgentBossRules, key balrog_normal), not from constants.
 *
 * @param clawPriority the claws in the order members hit them while both are up; empty: both at once (Easy)
 * @param createSelections Mu Young's window choices that create the expedition (the GM lobby's)
 * @param joinSelections   her choices that add a member to a registering expedition
 * @param startSelections  her choices that start it
 */
public record AgentBalrogFight(String label, ExpeditionType type, int recruitMapId, int battleMapId, int clearMapId,
                               int bodyMob, int releasedClawMob, int initialClawMob, int releaseSealMob,
                               Set<Integer> adds, List<Integer> clawPriority, int rewardReactorId,
                               List<Integer> createSelections, List<Integer> joinSelections,
                               List<Integer> startSelections) {
    /** Easy Balrog, as the scenario always fought it. */
    public static final AgentBalrogFight EASY = new AgentBalrogFight("Easy Balrog", ExpeditionType.BALROG_EASY,
            AgentBalrogDefinition.RECRUIT_MAP, AgentBalrogDefinition.BATTLE_MAP, AgentBalrogDefinition.CLEAR_MAP,
            AgentBalrogDefinition.BODY_MOB, AgentBalrogDefinition.RELEASED_CLAW_MOB,
            AgentBalrogDefinition.INITIAL_CLAW_MOB, AgentBalrogDefinition.RELEASE_SEAL_MOB,
            AgentBalrogDefinition.SUMMONED_ADDS, List.of(), AgentBalrogDefinition.REWARD_REACTOR_ID,
            List.of(1, 1), List.of(1), List.of(1, 2, 0));
    /** Normal Balrog (P11): Mu Young's second choice; the claws in the catalog's target order. */
    public static final AgentBalrogFight NORMAL = normal(AgentBossRules.of(AgentBalrogDefinition.NORMAL_KEY));

    public AgentBalrogFight {
        adds = Set.copyOf(adds);
        clawPriority = List.copyOf(clawPriority);
        createSelections = List.copyOf(createSelections);
        joinSelections = List.copyOf(joinSelections);
        startSelections = List.copyOf(startSelections);
    }

    static AgentBalrogFight normal(AgentBossRules.Boss rules) {
        if (rules == null) {
            throw new IllegalStateException("the boss rules catalog has no " + AgentBalrogDefinition.NORMAL_KEY);
        }
        int released = rules.role("releasedClaw");
        int initial = rules.role("initialClaw");
        List<Integer> clawOrder = rules.targetPriority().stream()
                .filter(id -> id == released || id == initial).toList();
        return new AgentBalrogFight(rules.label(), ExpeditionType.BALROG_NORMAL, AgentBalrogDefinition.RECRUIT_MAP,
                AgentBalrogDefinition.NORMAL_BATTLE_MAP_ID, AgentBalrogDefinition.NORMAL_CLEAR_MAP_ID,
                rules.role("body"), released, initial, rules.role("releaseSeal"), Set.copyOf(rules.adds()), clawOrder,
                AgentBalrogDefinition.NORMAL_REWARD_REACTOR_ID, List.of(2, 1), List.of(2), List.of(2, 2, 0));
    }

    /** Both claws. */
    public Set<Integer> claws() {
        return Set.of(releasedClawMob, initialClawMob);
    }

    /** The body, both claws and the adds: what the battle status lists. */
    public Set<Integer> combatMobs() {
        Set<Integer> mobs = new LinkedHashSet<>(claws());
        mobs.add(bodyMob);
        mobs.addAll(adds);
        return Set.copyOf(mobs);
    }

    /**
     * The claws a member hits now, {@code alive} telling which are up: the first live claw of {@link #clawPriority},
     * or both claws with no priority (Easy) or none of them up.
     */
    public Set<Integer> clawTargets(IntPredicate alive) {
        for (int claw : clawPriority) {
            if (alive.test(claw)) {
                return Set.of(claw);
            }
        }
        return claws();
    }
}
