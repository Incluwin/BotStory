package server.life.autonomy.balrog;

import client.Character;
import server.life.MobSkillType;
import server.life.Monster;
import server.life.autonomy.BossAction;
import server.life.autonomy.BossActionGeometry;
import server.life.autonomy.BossActorBehavior;
import server.life.autonomy.ServerMobActionCatalog;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;

/** Shared WZ selection policy for the stationary combat components of Easy and Normal Balrog. */
abstract class EasyBalrogBehavior implements BossActorBehavior {
    private final boolean autoStart;

    /** Easy Balrog's parts: the server takes them up as they spawn, as before. */
    EasyBalrogBehavior() {
        this(true);
    }

    /**
     * {@code autoStart} false: only EasyBalrogEncounterService registers the part. Normal Balrog's parts (P11) are
     * spawned by the stock BalrogBattle.js in every run without a companion, and that fight must stay exactly as it
     * was (decision 4).
     */
    EasyBalrogBehavior(boolean autoStart) {
        this.autoStart = autoStart;
    }

    @Override
    public final boolean autoStartOnSpawn() {
        return autoStart;
    }

    @Override
    public final boolean allowServerTakeoverForOrdinarySummons() {
        // Adds begin under ordinary client control and are promoted only by Agent aggro.
        return true;
    }

    @Override
    public final Optional<SelectedAction> select(
            Monster monster, List<Character> targets,
            ServerMobActionCatalog.MonsterActions actions, RandomGenerator random) {
        if (monster.isFake() || monster.getPosition() == null) {
            return Optional.empty();
        }
        Point origin = monster.getPosition();
        List<Character> ordered = targets.stream()
                .filter(target -> target.getPosition() != null)
                .sorted(Comparator.comparingDouble(target ->
                        target.getPosition().distanceSq(origin)))
                .toList();
        List<SelectedAction> eligibleAttacks = new ArrayList<>();
        for (BossAction.OrdinaryAttack attack : actions.attacks()) {
            addAttack(monster, ordered, origin, attack, eligibleAttacks);
        }

        List<SelectedAction> eligibleSkills = new ArrayList<>();
        int hpPercent = (int) Math.ceil(monster.getHp() * 100.0 / monster.getMaxHp());
        for (BossAction.Skill action : actions.skills()) {
            var skill = action.mobSkill();
            if (skill.getHP() < hpPercent || !monster.canUseSkill(skill, false)) {
                continue;
            }
            if (skill.getType() == MobSkillType.PHYSICAL_AND_MAGIC_COUNTER
                    || skill.getType() == MobSkillType.UNDEAD
                    || skill.getType() == MobSkillType.SUMMON) {
                eligibleSkills.add(new SelectedAction(action,
                        ordered.isEmpty() ? null : ordered.getFirst()));
            }
        }
        List<SelectedAction> eligible = eligibleSkills.isEmpty()
                ? eligibleAttacks : eligibleSkills;
        return eligible.isEmpty()
                ? Optional.empty()
                : Optional.of(eligible.get(random.nextInt(eligible.size())));
    }

    private static void addAttack(Monster monster, List<Character> targets, Point origin,
                                  BossAction.OrdinaryAttack attack,
                                  List<SelectedAction> eligible) {
        if (monster.getMp() < attack.mpCost()) {
            return;
        }
        if (attack.hasDistributedRegions()) {
            if (!targets.isEmpty()) {
                eligible.add(new SelectedAction(attack, targets.getFirst(),
                        (monster.getStance() & 1) != 0));
            }
            return;
        }
        for (Character target : targets) {
            if (BossActionGeometry.contains(attack, origin, target.getPosition(), true)) {
                eligible.add(new SelectedAction(attack, target, true));
                return;
            }
            if (BossActionGeometry.contains(attack, origin, target.getPosition(), false)) {
                eligible.add(new SelectedAction(attack, target, false));
                return;
            }
        }
    }
}
