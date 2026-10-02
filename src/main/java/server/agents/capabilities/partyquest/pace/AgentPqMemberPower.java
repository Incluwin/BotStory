package server.agents.capabilities.partyquest.pace;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.combat.AgentExpectedDamagePolicy;
import server.agents.capabilities.combat.data.AgentDefenseDataProvider;
import server.life.LifeFactory;
import server.life.Monster;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What one party member brings to a party quest, read from its character once: per profile mob, the expected damage
 * per second of its best attack ({@link AgentExpectedDamagePolicy}), that attack's hit chance and cooldown; and how
 * many touches of the profile's danger mob it takes to go down. This is the "expected party DPS" an NPC weighs a
 * lineup by; nothing here is shown to a player as a number.
 *
 * @param touchesToDown effective maximum HP over the danger mob's expected touch damage; {@link Double#MAX_VALUE}
 *                      when there is no danger mob or the touch could not be estimated
 */
public record AgentPqMemberPower(int characterId, String name, boolean human, boolean leader, int level, int jobId,
                                 Map<Integer, Double> dpsByMob, Map<Integer, Double> hitByMob,
                                 Map<Integer, Double> attackMsByMob, double touchesToDown) {
    private static final Logger log = LoggerFactory.getLogger(AgentPqMemberPower.class);
    private static final long FAILURE_LOG_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.pace.AgentPqMemberPower.FAILURE_LOG_MS");
    private static final AtomicLong failureLoggedAtMs = new AtomicLong();

    public AgentPqMemberPower {
        name = name == null ? "" : name;
        dpsByMob = finite(dpsByMob);
        hitByMob = finite(hitByMob);
        attackMsByMob = finite(attackMsByMob);
        touchesToDown = Double.isNaN(touchesToDown) || touchesToDown < 0.0d ? Double.MAX_VALUE
                : Math.min(touchesToDown, Double.MAX_VALUE);
    }

    /**
     * The human who leads: he does the leader's talks and hand-ins, so a quota credits only part of his kills and a
     * clear may park him. A human beside him, or an Agent that leads an Agent party, fights like any member.
     */
    public boolean humanLeader() {
        return human && leader;
    }

    public double dps(int mobId) {
        return dpsByMob.getOrDefault(mobId, 0.0d);
    }

    public double hit(int mobId) {
        return hitByMob.getOrDefault(mobId, 0.0d);
    }

    public double attackMs(int mobId) {
        return attackMsByMob.getOrDefault(mobId, 0.0d);
    }

    /** The same member with every damage rate scaled (estimate checks: a party twice as strong). */
    public AgentPqMemberPower withDpsScaled(double factor) {
        Map<Integer, Double> scaled = new TreeMap<>();
        dpsByMob.forEach((mobId, dps) -> scaled.put(mobId, dps * factor));
        return new AgentPqMemberPower(characterId, name, human, leader, level, jobId, scaled, hitByMob, attackMsByMob,
                touchesToDown);
    }

    /**
     * Reads a member against a warmed profile's mobs. A damage read that fails (the policy throws, or a profile mob is
     * not loaded) leaves the member unread rather than weak: it is logged at info at most once per FAILURE_LOG_MS and
     * thrown, so the caller has no power for the member (a lineup then has no clear time, a run's pace no members) and
     * an estimate never names a member that misses, or calls a party slow, on a reading it never got. An unknown touch
     * only counts as never fragile, since the touch never enters the time. Reads only data the profile's warm-up
     * loaded.
     *
     * @throws IllegalStateException when the member's damage against a profile mob could not be read
     */
    public static AgentPqMemberPower of(Character character, AgentPqPaceProfile profile, boolean human, boolean leader) {
        return of(character, profile, human, leader, Sources.LIVE);
    }

    static AgentPqMemberPower of(Character character, AgentPqPaceProfile profile, boolean human, boolean leader,
                                 Sources sources) {
        Map<Integer, Double> dps = new TreeMap<>();
        Map<Integer, Double> hit = new TreeMap<>();
        Map<Integer, Double> attackMs = new TreeMap<>();
        for (int mobId : profile.mobIds()) {
            AgentExpectedDamagePolicy.ExpectedAttack attack;
            try {
                Monster monster = sources.monster(mobId);
                if (monster == null) throw new IllegalStateException("mob " + mobId + " is not loaded");
                attack = sources.attack(character, monster);
                if (attack == null) throw new IllegalStateException("no attack read against mob " + mobId);
            } catch (RuntimeException failure) {
                logFailure(character, "damage against mob " + mobId, failure);
                throw new IllegalStateException("no PQ pace power for " + character.getName() + ": damage against mob "
                        + mobId + " could not be read", failure);
            }
            dps.put(mobId, attack.damagePerSecond());
            hit.put(mobId, attack.hitChance());
            attackMs.put(mobId, (double) attack.cooldownMs());
        }
        double touches = Double.MAX_VALUE;
        if (profile.dangerMobId() > 0) {
            try {
                Monster danger = sources.monster(profile.dangerMobId());
                double touch = danger == null ? 0.0d : sources.touchDamage(character, danger);
                if (touch > 0.0d) touches = sources.effectiveMaxHp(character) / touch;
            } catch (RuntimeException failure) {
                logFailure(character, "touch of mob " + profile.dangerMobId(), failure);
            }
        }
        return new AgentPqMemberPower(character.getId(), character.getName(), human, leader, character.getLevel(),
                character.getJob() == null ? 0 : character.getJob().getId(), dps, hit, attackMs, touches);
    }

    /** Where {@link #of} reads a member's attacks and survival; the live one reads the game's formulas. */
    interface Sources {
        Monster monster(int mobId);

        AgentExpectedDamagePolicy.ExpectedAttack attack(Character character, Monster monster);

        double touchDamage(Character character, Monster monster);

        int effectiveMaxHp(Character character);

        Sources LIVE = new Sources() {
            @Override
            public Monster monster(int mobId) {
                return LifeFactory.getMonster(mobId);
            }

            @Override
            public AgentExpectedDamagePolicy.ExpectedAttack attack(Character character, Monster monster) {
                return AgentExpectedDamagePolicy.bestAttack(character, monster);
            }

            @Override
            public double touchDamage(Character character, Monster monster) {
                return AgentDefenseDataProvider.getInstance().expectedPhysicalTouchDamage(character, monster);
            }

            @Override
            public int effectiveMaxHp(Character character) {
                return AgentDefenseDataProvider.effectiveMaximumHp(character);
            }
        };
    }

    private static void logFailure(Character character, String what, RuntimeException failure) {
        long now = System.currentTimeMillis();
        long loggedAt = failureLoggedAtMs.get();
        if (now - loggedAt >= FAILURE_LOG_MS && failureLoggedAtMs.compareAndSet(loggedAt, now)) {
            log.info("no PQ pace estimate of {} for {} (job {}); it is left unread", what, character.getName(),
                    character.getJob(), failure);
        } else {
            log.debug("no PQ pace estimate of {} for {}", what, character.getName(), failure);
        }
    }

    private static Map<Integer, Double> finite(Map<Integer, Double> values) {
        Map<Integer, Double> copy = new TreeMap<>();
        if (values != null) {
            values.forEach((mobId, value) -> copy.put(mobId,
                    value == null || !Double.isFinite(value) || value < 0.0d ? 0.0d : value));
        }
        return Collections.unmodifiableMap(copy);
    }
}
