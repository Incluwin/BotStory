package server.pvp;

import client.Character;
import client.Skill;
import client.SkillFactory;
import client.inventory.WeaponType;
import client.processor.action.PetAutopotProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.StatEffect;
import server.agents.capabilities.combat.AgentAttackExecutionProvider;
import server.agents.capabilities.combat.AgentAttackPlan;
import server.agents.capabilities.combat.AgentAttackRoute;
import server.agents.capabilities.combat.AgentAttackTransactionResult;
import server.agents.capabilities.combat.AgentCombatBuffRuntime;
import server.agents.capabilities.combat.AgentCombatAttackRuntime;
import server.agents.capabilities.combat.AgentCombatCooldownStateRuntime;
import server.agents.capabilities.combat.AgentCombatHitCounter;
import server.agents.capabilities.combat.AgentCombatSkillCacheStateRuntime;
import server.agents.capabilities.combat.AgentCombatSkillClassifier;
import server.agents.capabilities.combat.AgentCombatWeaponPolicy;
import server.agents.capabilities.combat.AgentSkillAttackPlanRuntime;
import server.agents.capabilities.townlife.AgentTownLifeRuntime;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.cosmic.CosmicAgentPotionCheckRequestBridge;
import server.agents.runtime.field.AgentFieldActivityRuntime;
import server.agents.runtime.townlife.AgentTownLifeVisitLeaseRuntime;
import server.agents.runtime.AgentMailboxRuntime;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A bot's side of a duel (docs/PVP_PLAN.md sections 4-5): the priors, and for a fighter with a learner's seat
 * ({@link DuelLearner}: self-play, or a job whose learned brain beat this one) the learner's choice. While the duel
 * holds it, its own following and grinding are off; it thinks every 300-600 ms (a person's reaction, not a tick's),
 * swings its strongest attack skill whose box reaches the opponent, and otherwise walks to that skill's reach; in reach
 * it steps or hops every few seconds instead of a swing. Every action runs on the Agent's own thread through its mailbox.
 */
final class DuelBrain {
    private static final Logger log = LoggerFactory.getLogger(DuelBrain.class);
    /**
     * How many of its attack skills a bot tries, strongest first. Live 2026-11-09 a Crusader with four tried landed
     * nothing: its strongest need combo orbs the planner refuses until it has them.
     */
    static final int SKILLS_TRIED = 10;
    /** Buffs that hide the caster: a bot hiding in a duel cannot be hit (DuelField's Dark Sight rule), so it never casts them. */
    static final java.util.Set<Integer> HIDING_BUFFS = java.util.Set.of(4001003, 14001003, 13101006);
    /** A buff is cast again this long before it would run out; a cast that failed is tried again after the retry. */
    static final long BUFF_EARLY_MS = 10_000L;
    static final long BUFF_RETRY_MS = 3_000L;
    /** The share of its reach a bot stands off at (docs/PVP_PLAN.md V2 priors: ranged holds 60-80% of its range). */
    static final double HOLD_SHARE = 0.7d;
    static final int MIN_STAND_PX = 30;
    /** A stand spot off the target's level walks in by this much at a time. */
    static final int STAND_STEP_PX = 40;
    /** How far above or below the target's feet a stand spot may be and still count as its level. */
    static final int LEVEL_TOLERANCE_PX = 25;
    /**
     * A held attack key: after a swing, or a wait on its attack lock, a bot thinks again this long after the lock ends
     * (plus up to this spread), not a whole reaction later. Practice 2026-11-09: thinking every 300-600 ms against a
     * ~700 ms animation, every other think landed mid-swing and bots swung 0.8-0.95 a second against a player's ~1.3.
     */
    static final int KEY_REPEAT_MIN_MS = 30;
    static final int KEY_REPEAT_SPREAD_MS = 60;
    /**
     * Footwork: every 1.8-4.2 s a fighter in reach steps to another spot inside its reach, or hops, instead of its next
     * swing. Filmed 2026-11-10: once in range neither fighter moved again, and two fighters traded hits standing still
     * for 29 s, which no player does.
     */
    static final int FOOTWORK_EVERY_MIN_MS = 1_800;
    static final int FOOTWORK_EVERY_SPREAD_MS = 2_400;
    /** A step's spot lies this share of its last swing's reach from the target, near to far. */
    static final double FOOTWORK_NEAR_SHARE = 0.35d;
    static final double FOOTWORK_FAR_SHARE = 0.85d;
    /**
     * A smaller move than this does not read as a step; a short reach's band is narrower, so its fighter takes half
     * the band, down to {@link #FOOTWORK_SHORT_STEP_PX} (an 80 px spear reach has 30-68 px to step in).
     */
    static final int FOOTWORK_MIN_STEP_PX = 40;
    static final int FOOTWORK_SHORT_STEP_PX = 20;
    /** A step walks this long (plus up to the spread) before the next think, which stops it to swing. */
    static final int STEP_MIN_MS = 350;
    static final int STEP_SPREAD_MS = 250;
    /** One footwork in this many is a hop: a ground jump of about {@link #HOP_PX}, thinking again as it lands. */
    static final int HOP_ONE_IN = 3;
    static final int HOP_PX = 90;
    static final int HOP_MS = 600;
    /**
     * A bot drinks once its HP is at or under this share, as a player spams potions mid-fight; the duel takes back
     * whatever passes PVP_HEAL_PERCENT a window. Check runs 14-15 (2026-11-10/11): the autopot drank only under its
     * own alert, so a knocked-out warrior had absorbed 1.11x its HP against a magician's 1.29x (Heal, and a potion is a
     * big share of a small pool), and magicians won 68-77% cross-class. Tried about once a second: the duel refuses a
     * drink while the allowance is under half full, without using the potion.
     */
    static final double DRINK_AT_HP_SHARE = 0.9d;
    static final long DRINK_RETRY_MS = 1_000L;

    private DuelBrain() {
    }

    /** The next think, when the attack lock of {@code lockMs} from {@code now} ends. */
    static void swingAgainAt(Mind mind, long now, int lockMs) {
        mind.nextThinkMs = now + Math.max(0, lockMs) + KEY_REPEAT_MIN_MS
                + ThreadLocalRandom.current().nextInt(KEY_REPEAT_SPREAD_MS);
    }

    /** What the duel remembers of a bot: the modes to give back, its attack skills, when it thinks next. */
    static final class Mind {
        volatile boolean wasFollowing;
        volatile boolean wasGrinding;
        volatile List<Integer> skills = List.of();
        volatile long nextThinkMs;
        volatile int thinkMs = 450;
        /** Whether the duel paused the Agent's field session, so only that one is resumed after. */
        volatile boolean pausedField;
        /** Whether something else (a boss run, a party quest) already held the Agent at full rate, so that hold stays. */
        volatile boolean fullRateWasRequired;
        /** Its own buffs (the ones it casts while grinding, less the hiding ones) and when each is due again. */
        volatile List<Integer> buffs = List.of();
        final java.util.Map<Integer, Long> buffDueMs = new java.util.concurrent.ConcurrentHashMap<>();
        volatile long nextPrepareMs;
        /** Whether its last think swung (it is in reach), how far that swing reached, and when it next moves its feet. */
        volatile boolean inReach;
        volatile int lastReach;
        volatile long nextFootworkMs;
        /** When it last reached for a potion. */
        volatile long lastDrinkTryMs;
        /** Its learner's seat, or null: the prior brain alone. */
        volatile DuelLearner.Seat seat;
    }

    static void enter(DuelField.Fighter fighter) {
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(fighter.chr.getId());
        if (entry == null) {
            return;
        }
        Mind mind = fighter.mind;
        mind.thinkMs = 300 + ThreadLocalRandom.current().nextInt(300);
        Character bot = fighter.chr;
        AgentMailboxRuntime.dispatch(entry, e -> {
            long now = System.currentTimeMillis();
            // a citizen's town life walks it about on its own tick (live 2026-11-08: 2,000 px away mid-duel). Its pause
            // does not stop that tick (only the interaction lease's per-tick stop does), so it stops, as when the
            // population or a Resident takes a citizen over; its scheduler starts a new one at its next plan. A field
            // session's suspend is honoured, so that one only pauses.
            if (AgentTownLifeRuntime.active(e)) {
                AgentTownLifeRuntime.forceStop(e, bot, "pvp duel");
                AgentTownLifeVisitLeaseRuntime.clear(e, bot); // as PresenceScheduler does, so the lease cannot restart it
            }
            mind.pausedField = AgentFieldActivityRuntime.suspend(e, bot, "pvp duel", now);
            // full rate, as a boss run or party quest holds its members: on a map no player sees, an Agent ticks every
            // 250 ms but its attack lock counts down one 50 ms movement tick per tick, so every swing waited five
            // times its animation (practice 2026-11-09: 0.3 swings a second against a player's ~1.3)
            mind.fullRateWasRequired = e.simulationState().fullRateSimulationRequired();
            e.simulationState().requireFullRateSimulation();
            mind.wasFollowing = AgentModeStateRuntime.following(e);
            mind.wasGrinding = AgentModeStateRuntime.grinding(e);
            AgentModeStateRuntime.setFollowing(e, false);
            AgentModeStateRuntime.setGrinding(e, false);
            AgentPrimitiveCapabilityGatewayRuntime.gateway().stop(e);
            mind.skills = attackSkills(fighter.chr);
            // its buffs, which it casts itself in a duel: the stock buff tick waits while it neither follows nor
            // grinds, and skips a map with no live monster (every town), so a Crusader never had Combo Attack
            List<Integer> buffs = new ArrayList<>();
            for (int skillId : AgentCombatSkillCacheStateRuntime.buffSkillIds(e)) {
                StatEffect effect = effectOf(bot, skillId);
                if (!HIDING_BUFFS.contains(skillId) && effect != null && effect.getDuration() > 0) {
                    buffs.add(skillId);
                }
            }
            mind.buffs = buffs;
            return null;
        });
    }

    /** The countdown: a bot buffs up, as a player would before the bell. */
    static void prepare(DuelField.Fighter self, long now) {
        Mind mind = self.mind;
        if (now < mind.nextPrepareMs) {
            return;
        }
        mind.nextPrepareMs = now + mind.thinkMs;
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(self.chr.getId());
        if (entry == null) {
            return;
        }
        Character bot = self.chr;
        AgentMailboxRuntime.dispatch(entry, e -> {
            try {
                buffUp(e, bot, mind, self.stats, System.currentTimeMillis());
            } catch (RuntimeException failure) {
                log.warn("PvP duel brain {} could not buff", bot.getName(), failure);
            }
            return null;
        });
    }

    /** Casts one buff that is due; true when it cast (one action a think). */
    static boolean buffUp(AgentRuntimeEntry entry, Character bot, Mind mind, DuelStats stats, long now) {
        for (int skillId : mind.buffs) {
            if (now < mind.buffDueMs.getOrDefault(skillId, 0L)) {
                continue;
            }
            StatEffect effect = effectOf(bot, skillId);
            if (effect != null && AgentCombatBuffRuntime.tryCastExplicitUtilityBuff(entry, bot, skillId)) {
                stats.buffsCast.incrementAndGet();
                mind.buffDueMs.put(skillId, now + Math.max(BUFF_EARLY_MS, effect.getDuration() - BUFF_EARLY_MS));
                return true;
            }
            mind.buffDueMs.put(skillId, now + BUFF_RETRY_MS);
        }
        return false;
    }

    static void leave(DuelField.Fighter fighter) {
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(fighter.chr.getId());
        if (entry == null) {
            return;
        }
        Mind mind = fighter.mind;
        Character bot = fighter.chr;
        AgentMailboxRuntime.dispatch(entry, e -> {
            long now = System.currentTimeMillis();
            AgentPrimitiveCapabilityGatewayRuntime.gateway().stop(e);
            AgentModeStateRuntime.setFollowing(e, mind.wasFollowing);
            AgentModeStateRuntime.setGrinding(e, mind.wasGrinding);
            if (mind.pausedField) {
                AgentFieldActivityRuntime.resume(e, bot, "pvp duel over", now);
            }
            if (!mind.fullRateWasRequired) {
                e.simulationState().clearFullRateSimulationRequirement();
            }
            return null;
        });
    }

    static void tick(DuelField.Fighter self, DuelField.Fighter foe, long now) {
        Mind mind = self.mind;
        if (now < mind.nextThinkMs) {
            return;
        }
        mind.nextThinkMs = now + mind.thinkMs + ThreadLocalRandom.current().nextInt(150);
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(self.chr.getId());
        if (entry == null) {
            return;
        }
        AgentMailboxRuntime.dispatch(entry, e -> {
            try {
                act(e, self, foe);
            } catch (RuntimeException failure) {
                log.warn("PvP duel brain {} against {} failed", self.chr.getName(), foe.chr.getName(), failure);
            }
            return null;
        });
    }

    private static void act(AgentRuntimeEntry entry, DuelField.Fighter self, DuelField.Fighter foe) {
        Character bot = self.chr;
        Character target = foe.chr;
        Mind mind = self.mind;
        DuelStats stats = self.stats;
        Point aim = target.getPosition();
        Point at = bot.getPosition();
        if (aim == null || at == null || bot.getMap() != target.getMap()) {
            return;
        }
        stats.thinks.incrementAndGet();
        long now = System.currentTimeMillis();
        boolean learned = mind.seat != null;
        if (!learned || !tonicsOnly(bot)) { // a seat decides on arena tonics and on buffs; own potions stay the prior's
            drinkIfDue(bot, mind, stats, now);   // a potion costs no action: it still swings this think
        }
        if (!learned && buffUp(entry, bot, mind, stats, now)) {
            return;
        }
        var gateway = AgentPrimitiveCapabilityGatewayRuntime.gateway();
        Rectangle body = PvpHitResolver.hurtbox(aim, 0);
        int reach = 0;
        boolean anyPlan = false;
        // a player cannot attack on a rope or ladder: it finishes the climb (filmed 2026-11-10, an assassin threw stars
        // from a ladder for six seconds)
        boolean mayAttack = mayAttackFrom(bot.getStance());
        if (mind.inReach && now >= mind.nextFootworkMs && mayAttack && gateway.grounded(bot)
                && footwork(entry, bot, at, aim, mind, stats, now)) {
            return;
        }
        List<Integer> skills = learned ? DuelLearnedBrain.decide(entry, self, foe, now) : mind.skills;
        if (skills == null) {
            return;   // the learner did something other than swing
        }
        boolean orbsFirst = orbsFirst(bot, skills);
        for (int rank = 0; rank < skills.size(); rank++) {
            int skillId = skills.get(rank);
            if (holdsShout(skillId, orbsFirst)) {
                stats.refused("hold:shout-for-orbs");
                continue;   // its walk and footwork follow the swings that build orbs, so it closes in
            }
            AgentAttackPlan plan = AgentSkillAttackPlanRuntime.planSkillAttackAt(bot, aim, skillId);
            if (plan == null) {
                stats.refused("plan:" + AgentSkillAttackPlanRuntime.lastRejection());
                continue;
            }
            anyPlan = true;
            // the box the duel will judge this swing by (the planner's own is a 1x1 point at the aim for a skill with
            // no lt/rb, such as a projectile: live 2026-11-08 a Priest stood its ground 2,000 px away)
            Rectangle box = PvpHitResolver.attackBox(bot, skillId, effectOf(bot, skillId),
                    plan.route == AgentAttackRoute.RANGED, plan.route == AgentAttackRoute.MAGIC,
                    (plan.stance & 0x80) != 0);
            if (box == null) {
                continue;
            }
            if (rank == 0) {
                stats.topReachPx.addAndGet(reachToward(box, at, aim));
                stats.topReachLooks.incrementAndGet();
            }
            if (mayAttack && box.intersects(body)) {
                gateway.stop(entry);
                gateway.facePosition(bot, aim);
                var result = AgentCombatAttackRuntime.attackAtNothing(entry, bot, plan);
                if (result.committed()) {
                    stats.swingsSent.incrementAndGet();
                    stats.swung(rank, Math.abs(at.x - aim.x));
                    swingAgainAt(mind, now, plan.cooldownMs);
                    if (!mind.inReach) {
                        mind.nextFootworkMs = now + footworkGapMs();
                    }
                    mind.inReach = true;
                    mind.lastReach = reachToward(box, at, aim);
                    return;
                }
                if (result.status() == AgentAttackTransactionResult.Status.DEFERRED) {
                    stats.deferred(result.reason().name());
                    if (result.reason() == AgentAttackTransactionResult.Reason.ATTACK_COOLDOWN) {
                        swingAgainAt(mind, now, AgentCombatCooldownStateRuntime.attackCooldownMs(entry));
                        return;   // its attack lock is running: it stays put and swings when it ends
                    }
                    if (result.reason() != AgentAttackTransactionResult.Reason.CANNOT_USE_SKILL) {
                        return;   // it is in the air: it stays put
                    }
                    continue;     // this skill's cost or cooldown: a player presses the next key
                }
                stats.swingsRefused.incrementAndGet();
                stats.refused("swing:" + result.reason());
                continue;
            }
            reach = Math.max(reach, reachToward(box, at, aim));
        }
        if (!anyPlan) {
            stats.noSkillThinks.incrementAndGet();
        }
        stats.walks.incrementAndGet();
        mind.inReach = false;
        var map = bot.getMap();
        gateway.navigate(entry, standPoint(at, aim, reach, p -> gateway.groundPoint(map, p)), true);
    }

    /** Whether a fighter at {@code hp} of {@code maxHp} reaches for a potion, {@code sinceTryMs} after its last try. */
    static boolean wantsDrink(int hp, int maxHp, long sinceTryMs) {
        return maxHp > 0 && hp > 0 && hp <= maxHp * DRINK_AT_HP_SHARE && sinceTryMs >= DRINK_RETRY_MS;
    }

    /**
     * Coliseum: a bot drinks an arena tonic at or under this share of its HP. Three tonics of 25% a round, 8 s apart,
     * spent at 90% would waste most of each one; a player saves them for when they are down.
     */
    static final double TONIC_AT_HP_SHARE = 0.6d;

    /** Whether a coliseum fighter at {@code hp} of {@code maxHp} drinks a tonic now (one being ready). */
    static boolean wantsTonic(int hp, int maxHp) {
        return maxHp > 0 && hp > 0 && hp <= maxHp * TONIC_AT_HP_SHARE;
    }

    private static boolean tonicsOnly(Character bot) {
        DuelField field = DuelRegistry.of(bot.getId());
        return field != null && field.rules().tonicsOnly();
    }

    private static void drinkIfDue(Character bot, Mind mind, DuelStats stats, long now) {
        DuelField field = DuelRegistry.of(bot.getId());
        if (field != null && field.rules().tonicsOnly()) {
            if (wantsTonic(bot.getHp(), bot.getCurrentMaxHp()) && field.tonicReady(bot, now)) {
                stats.drinkTries.incrementAndGet();
                field.drinkTonic(bot, now); // counts the drink itself
            }
            return;
        }
        if (!wantsDrink(bot.getHp(), bot.getCurrentMaxHp(), now - mind.lastDrinkTryMs)) {
            return;
        }
        mind.lastDrinkTryMs = now;
        stats.drinkTries.incrementAndGet();
        var choice = CosmicAgentPotionCheckRequestBridge.selectAutopotForCurrentDeficit(bot, true);
        if (choice == null) {
            stats.drinkNoPotion.incrementAndGet();
            return;
        }
        int before = bot.getHp();
        boolean refused = PvpHooks.refusesDrink(bot, choice.itemId());   // the gate keeps no state: asking twice is safe
        PetAutopotProcessor.runAutopotAction(bot.getClient(), choice.position(), choice.itemId());
        if (bot.getHp() > before) {
            stats.drinks.incrementAndGet();
        } else {
            stats.drinkNoGain.incrementAndGet();
            stats.lastNoGain = choice.itemId() + (refused ? "/refused" : "/" + before + "of" + bot.getCurrentMaxHp());
        }
    }

    private static int footworkGapMs() {
        return FOOTWORK_EVERY_MIN_MS + ThreadLocalRandom.current().nextInt(FOOTWORK_EVERY_SPREAD_MS);
    }

    /** A step or a hop in place of the next swing; true when it moved. */
    private static boolean footwork(AgentRuntimeEntry entry, Character bot, Point at, Point aim, Mind mind,
                                    DuelStats stats, long now) {
        var gateway = AgentPrimitiveCapabilityGatewayRuntime.gateway();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        mind.nextFootworkMs = now + footworkGapMs();
        var map = bot.getMap();
        java.util.function.Function<Point, Point> ground = p -> gateway.groundPoint(map, p);
        if (random.nextInt(HOP_ONE_IN) == 0) {
            int direction = hopDirection(at, aim, mind.lastReach, ground);
            if (direction != 0 && gateway.jump(entry, direction)) {
                stats.hops.incrementAndGet();
                mind.nextThinkMs = now + HOP_MS;
                return true;
            }
        }
        Point spot = footworkSpot(at, aim, mind.lastReach, random.nextDouble(), ground);
        if (spot == null) {
            return false;
        }
        gateway.navigate(entry, spot, true);
        stats.steps.incrementAndGet();
        mind.nextThinkMs = now + STEP_MIN_MS + random.nextInt(STEP_SPREAD_MS);
        return true;
    }

    /**
     * Where a fighter in reach steps to: on its own side of the target and its own level, {@link #FOOTWORK_NEAR_SHARE}
     * to {@link #FOOTWORK_FAR_SHARE} of {@code reach} away ({@code pick} in [0, 1) chooses where), and a visible step
     * from where it stands, so a ranged fighter crowded by a melee one steps back. Null when there is no such spot.
     */
    static Point footworkSpot(Point at, Point aim, int reach, double pick,
                              java.util.function.Function<Point, Point> ground) {
        int near = Math.max(MIN_STAND_PX, (int) Math.round(reach * FOOTWORK_NEAR_SHARE));
        int far = Math.max(near, (int) Math.round(reach * FOOTWORK_FAR_SHARE));
        int minStep = Math.min(FOOTWORK_MIN_STEP_PX, Math.max(FOOTWORK_SHORT_STEP_PX, (far - near) / 2));
        int side = at.x >= aim.x ? 1 : -1;
        int now = Math.abs(at.x - aim.x);
        int want = near + (int) Math.round(pick * (far - near));
        if (Math.abs(want - now) < minStep) {
            want = now - near > far - now ? near : far;   // too small to read as a step: the band's other end
            if (Math.abs(want - now) < minStep) {
                return null;
            }
        }
        Point floor = ground.apply(new Point(aim.x + side * want, at.y));
        return floor != null && Math.abs(floor.y - at.y) <= LEVEL_TOLERANCE_PX ? floor : null;
    }

    /**
     * Which way a fighter in reach hops (+1 right, -1 left, 0 not at all): toward the target from the far half of its
     * footwork band, away from the near half, else the other way; only a hop that lands on its own level and still in
     * {@code reach}. A short reach's hop in carries it over the target, as players jump over each other; it turns to
     * swing.
     */
    static int hopDirection(Point at, Point aim, int reach, java.util.function.Function<Point, Point> ground) {
        int side = at.x >= aim.x ? 1 : -1;
        int middle = (int) Math.round(reach * (FOOTWORK_NEAR_SHARE + FOOTWORK_FAR_SHARE) / 2);
        int first = Math.abs(at.x - aim.x) > middle ? -side : side;
        for (int direction : new int[] {first, -first}) {
            int landX = at.x + direction * HOP_PX;
            int after = Math.abs(landX - aim.x);
            if (after < MIN_STAND_PX || after > reach) {
                continue;
            }
            Point floor = ground.apply(new Point(landX, at.y));
            if (floor != null && Math.abs(floor.y - at.y) <= LEVEL_TOLERANCE_PX) {
                return direction;
            }
        }
        return 0;
    }

    /**
     * Whether a combo fighter holds Crusader's Shout: Shout earns no combo orb (CloseRangeDamageHandler.gainOnHit
     * skips 1111008), so while a finisher waits on orbs a Crusader builds them with its other swings, as a player
     * does. Counters 2026-11-12: against bowmen keeping 160-380 px away, Lv 110s Crusaders swung from 120 px with only
     * Shout in reach, never built an orb and never used Panic or Coma (0 of 57 swings; 53% against magicians, whom
     * they reached at 81 px), so equal-gear warriors went 24-66 against bowmen.
     */
    static boolean holdsShout(int skillId, boolean orbsFirst) {
        return skillId == constants.skills.Crusader.SHOUT && orbsFirst;
    }

    /** Orbs come first while Combo Attack is up, a finisher waits on orbs, and a swing other than Shout builds them. */
    static boolean orbsFirst(boolean comboUp, boolean finisherWaitsOnOrbs, boolean hasOrbBuilder) {
        return comboUp && finisherWaitsOnOrbs && hasOrbBuilder;
    }

    private static boolean orbsFirst(Character bot, List<Integer> skills) {
        if (!skills.contains(constants.skills.Crusader.SHOUT)) {
            return false;
        }
        boolean finisherWaits = false;
        boolean builder = false;
        for (int skillId : skills) {
            if (constants.game.GameConstants.isFinisherSkill(skillId)) {
                finisherWaits |= !server.agents.capabilities.combat.AgentComboFinisherPolicy.canPlan(bot, skillId);
            } else if (skillId != constants.skills.Crusader.SHOUT) {
                builder = true;
            }
        }
        return orbsFirst(bot.getBuffedValue(client.BuffStat.COMBO) != null, finisherWaits, builder);
    }

    /** Whether a fighter in {@code stance} can swing: not while climbing a rope or ladder, as no player can. */
    static boolean mayAttackFrom(int stance) {
        return !constants.game.CharacterStance.isClimbing(stance);
    }

    /**
     * Where to stand to swing at a target at {@code aim}: {@link #HOLD_SHARE} of the reach away (the plan's prior, a
     * ranged fighter holds 60-80% of its range), on the target's own level. Live 2026-11-09 casters with a 400 px
     * projectile aimed for 380 px out, past the platform's edge, landed on the platform below where no box meets the
     * target, and walked back to the same spot all duel; so the spot walks in toward the target until it is level,
     * then the other side is tried, then the target's own spot.
     */
    static Point standPoint(Point at, Point aim, int reach, java.util.function.Function<Point, Point> ground) {
        int want = Math.max(MIN_STAND_PX, reach > 0 ? (int) Math.round(reach * HOLD_SHARE) : MIN_STAND_PX);
        int first = at.x >= aim.x ? 1 : -1;
        for (int side : new int[] {first, -first}) {
            for (int stand = want; ; stand = Math.max(MIN_STAND_PX, stand - STAND_STEP_PX)) {
                Point floor = ground.apply(new Point(aim.x + side * stand, aim.y));
                if (floor != null && Math.abs(floor.y - aim.y) <= LEVEL_TOLERANCE_PX) {
                    return floor;
                }
                if (stand == MIN_STAND_PX) {
                    break;
                }
            }
        }
        return aim;
    }

    static StatEffect effectOf(Character bot, int skillId) {
        Skill skill = SkillFactory.getSkill(skillId);
        int level = skill == null ? 0 : bot.getSkillLevel(skill);
        return level <= 0 ? null : skill.getEffect(level);
    }

    /** How far from the bot, toward the target, a swing's box reaches. */
    static int reachToward(Rectangle box, Point from, Point target) {
        return target.x >= from.x ? Math.max(0, box.x + box.width - from.x) : Math.max(0, from.x - box.x);
    }

    /** The bot's attack skills its weapon can use, strongest per swing first (the attack cache's own filter). */
    static List<Integer> attackSkills(Character bot) {
        WeaponType weapon = AgentAttackExecutionProvider.getEquippedWeaponType(bot);
        List<int[]> scored = new ArrayList<>();
        for (Skill skill : bot.getSkills().keySet()) {
            int level = bot.getSkillLevel(skill);
            if (level <= 0) {
                continue;
            }
            StatEffect effect = skill.getEffect(level);
            if (AgentCombatSkillClassifier.classifySkillCacheBucket(skill, effect)
                    != AgentCombatSkillClassifier.SkillCacheBucket.ACTIVE_ATTACK
                    || !AgentCombatWeaponPolicy.canUseSkillWithWeapon(skill.getId(), weapon)
                    || AgentCombatSkillClassifier.requiresPreAction(skill.getId())) {
                continue;
            }
            int perSwing = Math.max(effect.getDamage(), effect.getMatk())
                    * Math.max(1, AgentCombatHitCounter.effectiveHitCount(effect));
            scored.add(new int[] {skill.getId(), perSwing});
        }
        scored.sort(Comparator.comparingInt((int[] s) -> s[1]).reversed());
        List<Integer> skills = new ArrayList<>();
        for (int i = 0; i < Math.min(SKILLS_TRIED, scored.size()); i++) {
            skills.add(scored.get(i)[0]);
        }
        return skills;
    }
}
