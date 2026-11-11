package server.pvp;

import client.BotClient;
import client.BuffStat;
import client.Character;
import client.Skill;
import client.SkillFactory;
import config.PvpConfig;
import config.YamlConfig;
import net.server.channel.handlers.AbstractDealDamageHandler.AttackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.StatEffect;
import server.TimerManager;
import server.combat.CharacterHitApplier;
import server.combat.CombatFormulaProvider;
import server.maps.MapleMap;
import server.pvp.probe.PvpCalibration;
import tools.PacketCreator;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One duel (docs/PVP_PLAN.md section 4, V1.3): two sides on one map, a countdown, one round, knock-outs instead of
 * deaths, and a heal allowance. Every PvP hit goes through {@link #attack}: the server picks the victims, rolls the
 * lines and applies them, and other clients draw the numbers (V0.2: source -4, MISS as -2). When it ends, everyone
 * gets back the HP and MP they started with.
 */
public final class DuelField {
    private static final Logger log = LoggerFactory.getLogger(DuelField.class);
    static final long TICK_MS = 100;
    /** Skills that fire many attack packets a second; their PvP hits may come five times as often. */
    private static final Set<Integer> RAPID_SKILLS = Set.of(3121004, 5221004, 13111002);
    /** v83 face 4 (cry) on a knock-out. */
    private static final int KNOCKED_OUT_FACE = 4;

    public enum State { COUNTDOWN, FIGHTING, ENDED }

    /** A fighter and everything the duel keeps about it. Mutable fields are guarded by the field's lock. */
    public static final class Fighter {
        final Character chr;
        final int side;
        final boolean human;
        final PositionTrack track;
        final int hpBefore;
        final int mpBefore;
        boolean knockedOut;
        boolean forfeited;
        long lastAttackMs;
        int lastHp;
        int lastMp;
        double hpAllowance;
        double mpAllowance;
        long lastHpDrinkMs = Long.MIN_VALUE / 2;
        long lastMpDrinkMs = Long.MIN_VALUE / 2;
        long dealt;
        long taken;
        final DuelBrain.Mind mind = new DuelBrain.Mind();
        final DuelStats stats = new DuelStats();

        Fighter(Character chr, int side, long keepMs) {
            this.chr = chr;
            this.side = side;
            this.human = !(chr.getClient() instanceof BotClient);
            this.track = new PositionTrack(keepMs);
            this.hpBefore = chr.getHp();
            this.mpBefore = chr.getMp();
        }

        public Character character() {
            return chr;
        }

        public int side() {
            return side;
        }

        public boolean human() {
            return human;
        }

        public boolean knockedOut() {
            return knockedOut;
        }
    }

    private final long id;
    private final MapleMap map;
    private final PvpConfig config;
    private final Map<Integer, Fighter> fighters = new LinkedHashMap<>();
    private volatile State state = State.COUNTDOWN;
    private long startedAtMs;
    private long fightStartsAtMs;
    private long roundEndsAtMs;
    private long lastTickMs;
    private int lastCountdownShown = -1;
    private ScheduledFuture<?> ticker;

    DuelField(long id, MapleMap map, List<Character> sideA, List<Character> sideB) {
        this.id = id;
        this.map = map;
        this.config = YamlConfig.config.pvp;
        long keepMs = Math.max(1000L, config.PVP_LAG_WINDOW_MS * 2L);
        sideA.forEach(chr -> fighters.put(chr.getId(), new Fighter(chr, 0, keepMs)));
        sideB.forEach(chr -> fighters.put(chr.getId(), new Fighter(chr, 1, keepMs)));
    }

    public long id() {
        return id;
    }

    public State state() {
        return state;
    }

    public MapleMap map() {
        return map;
    }

    public List<Character> characters() {
        return fighters.values().stream().map(f -> f.chr).toList();
    }

    public synchronized List<Fighter> fighters() {
        return new ArrayList<>(fighters.values());
    }

    void begin() {
        long now = System.currentTimeMillis();
        startedAtMs = now;
        lastTickMs = now;
        fightStartsAtMs = now + config.PVP_COUNTDOWN_SECONDS * 1000L;
        roundEndsAtMs = fightStartsAtMs + config.PVP_ROUND_SECONDS * 1000L;
        tell("Duel: " + names(0) + " against " + names(1) + ". No deaths: a knock-out ends it, and HP and MP come"
                + " back after.");
        for (Fighter fighter : fighters.values()) {
            fighter.track.record(fighter.chr.getPosition(), now);
            if (!fighter.human) {
                DuelBrain.enter(fighter);
            }
        }
        log.info("PvP duel {} on map {}: {} against {}", id, map.getId(), names(0), names(1));
        ticker = TimerManager.getInstance().register(this::tickSafely, TICK_MS);
    }

    private void tickSafely() {
        try {
            tick(System.currentTimeMillis());
        } catch (Throwable failure) {
            log.warn("PvP duel {} tick failed", id, failure);
        }
    }

    void tick(long now) {
        List<Fighter[]> thinking = new ArrayList<>();
        synchronized (this) {
            if (state == State.ENDED) {
                return;
            }
            double dt = Math.max(0, now - lastTickMs);
            lastTickMs = now;
            for (Fighter fighter : fighters.values()) {
                Point at = fighter.chr.getPosition();
                fighter.track.record(at, now);
                if (!fighter.knockedOut && !present(fighter)) {
                    fighter.knockedOut = true;
                    fighter.forfeited = true;
                    tell(fighter.chr.getName() + " left the duel.");
                }
                if (state == State.FIGHTING && !fighter.knockedOut && present(fighter)) {
                    accountHealing(fighter, dt);
                }
            }
            if (decided()) {
                // a side left (a forfeit) or went down between attacks: only attack() ended it before, so a fighter
                // walking off left the duel holding the other one forever (live 2026-11-09, duel 2: 523 s)
                endBySides();
                return;
            }
            if (state == State.COUNTDOWN) {
                long leftMs = fightStartsAtMs - now;
                if (leftMs <= 0) {
                    state = State.FIGHTING;
                    for (Fighter fighter : fighters.values()) {
                        // every fighter starts whole: practice 2026-11-09 a citizen entered nearly dead eight duels
                        // running (each gives back what it started with) and lost to its first hit. Its own HP and
                        // MP still come back when the duel ends, so this heals no one.
                        fighter.chr.updateHpMp(fighter.chr.getCurrentMaxHp(), fighter.chr.getCurrentMaxMp());
                        fighter.lastHp = fighter.chr.getHp();
                        fighter.lastMp = fighter.chr.getMp();
                        fighter.hpAllowance = healCap(fighter.chr.getCurrentMaxHp());
                        fighter.mpAllowance = healCap(fighter.chr.getCurrentMaxMp());
                        if (fighter.human) {
                            fighter.chr.sendPacket(PacketCreator.getClock(config.PVP_ROUND_SECONDS));
                        }
                    }
                    tell("Fight!");
                } else {
                    int seconds = (int) Math.ceil(leftMs / 1000d);
                    if (seconds != lastCountdownShown) {
                        lastCountdownShown = seconds;
                        tell(seconds + "...");
                    }
                    for (Fighter fighter : fighters.values()) {
                        if (!fighter.human && !fighter.knockedOut) {
                            thinking.add(new Fighter[] {fighter, null}); // buffs up while a player would
                        }
                    }
                }
            } else if (state == State.FIGHTING) {
                if (now >= roundEndsAtMs) {
                    endByTime();
                    return;
                }
                for (Fighter fighter : fighters.values()) {
                    if (!fighter.human && !fighter.knockedOut) {
                        Fighter foe = nearestFoe(fighter);
                        if (foe != null) {
                            thinking.add(new Fighter[] {fighter, foe});
                        }
                    }
                }
            }
        }
        for (Fighter[] pair : thinking) {
            if (pair[1] == null) {
                DuelBrain.prepare(pair[0], now);
            } else {
                DuelBrain.tick(pair[0], pair[1], now);
            }
        }
    }

    private boolean present(Fighter fighter) {
        Character chr = fighter.chr;
        return chr.getMap() == map && chr.isAlive() && map.getCharacterById(chr.getId()) != null;
    }

    private double healCap(int max) {
        return max * config.PVP_HEAL_PERCENT / 100d;
    }

    /** HP or MP gained since the last look, beyond the allowance, is taken back (any source: potions, Heal, regen). */
    /**
     * After a hit's loss: the baseline is what the loss left, then the allowance check. A bot's loss goes through
     * {@code addMPHPAndTriggerAutopot}, so its autopot can drink inside the hit; taking the HP after that as the
     * baseline let the drink pass whole, an Elixir included (heal-fixed run 2026-11-10: knocked-out magicians had
     * absorbed 1.59x their own max HP, warriors 1.02x).
     */
    void settleAfterLoss(Fighter victim, int hpBefore, int mpBefore, CharacterHitApplier.Applied applied) {
        victim.lastHp = Math.max(0, hpBefore - applied.hpLoss());
        victim.lastMp = Math.max(0, mpBefore - applied.mpLoss());
        accountHealing(victim, 0d);
    }

    void accountHealing(Fighter fighter, double dtMs) {
        Character chr = fighter.chr;
        double window = Math.max(1, config.PVP_HEAL_WINDOW_MS);
        double hpCap = healCap(chr.getCurrentMaxHp());
        double mpCap = healCap(chr.getCurrentMaxMp());
        fighter.hpAllowance = Math.min(hpCap, fighter.hpAllowance + hpCap * dtMs / window);
        fighter.mpAllowance = Math.min(mpCap, fighter.mpAllowance + mpCap * dtMs / window);
        int hp = chr.getHp();
        if (hp > fighter.lastHp) {
            int gain = hp - fighter.lastHp;
            int allowed = (int) Math.min(gain, Math.floor(fighter.hpAllowance));
            fighter.hpAllowance -= allowed;
            if (allowed < gain) {
                hp = fighter.lastHp + allowed;
                chr.updateHp(hp);
            }
        }
        fighter.lastHp = hp;
        int mp = chr.getMp();
        if (mp > fighter.lastMp) {
            int gain = mp - fighter.lastMp;
            int allowed = (int) Math.min(gain, Math.floor(fighter.mpAllowance));
            fighter.mpAllowance -= allowed;
            if (allowed < gain) {
                mp = fighter.lastMp + allowed;
                chr.updateMp(mp);
            }
        }
        fighter.lastMp = mp;
    }

    /**
     * A potion is refused within the heal window of the last one (the allowance would take it back anyway). HP and MP
     * each have their own window, as a player has a key for each: check run 16 (2026-11-11) shared one, so a warrior's
     * MP autopot, drinking every few skills, kept it running and 59% of warriors never got an HP drink in. A potion is
     * refused only while every resource it restores is inside its window; one that goes through starts those windows.
     */
    synchronized boolean refusesDrink(Character chr, boolean restoresHp, boolean restoresMp) {
        Fighter fighter = fighters.get(chr.getId());
        if (fighter == null || state == State.ENDED) {
            return false;
        }
        long now = System.currentTimeMillis();
        boolean hpOpen = restoresHp && now - fighter.lastHpDrinkMs >= config.PVP_HEAL_WINDOW_MS;
        boolean mpOpen = restoresMp && now - fighter.lastMpDrinkMs >= config.PVP_HEAL_WINDOW_MS;
        if (!hpOpen && !mpOpen) {
            return true;
        }
        if (hpOpen) {
            fighter.lastHpDrinkMs = now;
        }
        if (mpOpen) {
            fighter.lastMpDrinkMs = now;
        }
        return false;
    }

    private Fighter nearestFoe(Fighter self) {
        Point at = self.chr.getPosition();
        Fighter best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Fighter other : fighters.values()) {
            if (other.side == self.side || other.knockedOut || other.chr.getPosition() == null || at == null) {
                continue;
            }
            double distance = other.chr.getPosition().distanceSq(at);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = other;
            }
        }
        return best;
    }

    /**
     * The PvP half of an attack ({@code AbstractDealDamageHandler.applyAttack} via {@link PvpHooks}): how many
     * opponents it hit (not dodged), which {@link PvpHooks} turns into combo orbs and Aran combo as a monster hit would.
     */
    synchronized int attack(Character attacker, AttackInfo attack) {
        Fighter self = fighters.get(attacker.getId());
        if (self == null || self.knockedOut || state != State.FIGHTING) {
            return 0;
        }
        long now = System.currentTimeMillis();
        int gap = RAPID_SKILLS.contains(attack.skill) ? config.PVP_MIN_ATTACK_GAP_MS / 5 : config.PVP_MIN_ATTACK_GAP_MS;
        if (now - self.lastAttackMs < gap) {
            return 0;
        }
        self.lastAttackMs = now;
        self.stats.packet(now);
        StatEffect effect = effectOf(attacker, attack.skill);
        Rectangle box = PvpHitResolver.attackBox(attacker, attack, effect);
        List<PvpHitResolver.Candidate> foes = new ArrayList<>();
        for (Fighter other : fighters.values()) {
            if (other.side != self.side && !other.knockedOut && present(other)) {
                foes.add(new PvpHitResolver.Candidate(other.chr, other.human, other.track));
            }
        }
        int maxTargets = Math.min(config.PVP_MAX_TARGETS, effect == null ? 1 : Math.max(1, effect.getMobCount()));
        List<PvpHitResolver.Candidate> met = PvpHitResolver.victims(box, attacker.getPosition(), foes, maxTargets,
                self.human, now, config.PVP_LAG_WINDOW_MS, config.PVP_HUMAN_HURTBOX_INSET_PX);
        if (met.isEmpty()) {
            self.stats.whiffs.incrementAndGet();
            return 0;
        }
        int skillLevel = attack.skill == 0 ? 0 : attacker.getSkillLevel(attack.skill);
        CombatFormulaProvider formulas = CombatFormulaProvider.getInstance();
        CombatFormulaProvider.DamageProfile profile =
                formulas.resolveDamageProfile(attacker, attack.skill, skillLevel, attack.magic);
        PvpCalibration.Sample sample = PvpCalibration.of(attacker);
        double coefficient = PvpDamageModel.coefficient(new PvpDamageModel.Attacker(attacker.getLevel(),
                attacker.getJob().getId(), sample.gearBase(), sample.jobClass() == 2), PvpReference.get(), config);
        double share = PvpDamageModel.areaShare(met.size(), config);
        int lines = lineCount(attack, effect);
        int landed = 0;
        for (PvpHitResolver.Candidate candidate : met) {
            if (hit(self, fighters.get(candidate.character().getId()), attack, profile, lines, coefficient * share)) {
                landed++;
            }
        }
        if (decided()) {
            endBySides();
        }
        return landed;
    }

    private static StatEffect effectOf(Character attacker, int skillId) {
        if (skillId == 0) {
            return null;
        }
        Skill skill = SkillFactory.getSkill(skillId);
        int level = skill == null ? 0 : attacker.getSkillLevel(skill);
        return skill == null || level <= 0 ? null : skill.getEffect(level);
    }

    /** The attack's lines as sent (Shadow Partner included), never more than twice what the skill has. */
    static int lineCount(AttackInfo attack, StatEffect effect) {
        int skillLines = effect == null ? 1 : Math.max(1, effect.getAttackCount());
        return Math.max(1, Math.min(attack.numDamage, skillLines * 2));
    }

    /** One victim's share of an attack; true when it landed (not dodged). */
    private boolean hit(Fighter attacker, Fighter victim, AttackInfo attack, CombatFormulaProvider.DamageProfile profile,
                        int lines, double factor) {
        Character from = attacker.chr;
        Character to = victim.chr;
        CombatFormulaProvider formulas = CombatFormulaProvider.getInstance();
        int direction = to.getPosition().x < from.getPosition().x ? 0 : 1;
        int accuracy = attack.magic ? formulas.getTotalMagicAccuracy(from) : formulas.getTotalAccuracy(from);
        int avoid = formulas.getTotalAvoidability(to);
        PvpHitBalance balance = config.PVP_EVEN_CLASSES ? PvpHitBalance.current() : null;
        if (balance != null) {
            // even classes (PD17): each side's accuracy / avoid against its own class's median at its band
            accuracy = (int) Math.round(accuracy * balance.accuracyFactor(from.getJob().getId(), from.getLevel()));
            avoid = (int) Math.round(avoid * balance.avoidFactor(to.getJob().getId(), to.getLevel()));
        }
        double chance = PvpDamageModel.hitChance(attack.magic, accuracy, from.getLevel(), to.getLevel(), avoid, config);
        // rolled before the dodge, so the measured rate (V2) is the attack's throughput and avoid stays a modifier
        List<Integer> raw = formulas.rollPvpLines(from, lines, attack.skill, profile);
        attacker.stats.rawRolled.addAndGet(raw.stream().mapToLong(Integer::longValue).sum());
        if (to.getBuffedValue(BuffStat.DARKSIGHT) != null || ThreadLocalRandom.current().nextDouble() > chance) {
            attacker.stats.dodged.incrementAndGet();
            map.broadcastMessage(PacketCreator.damagePlayer(-2, 0, to.getId(), 0, 0, direction, false, 0, false,
                    0, 0, 0));
            return false;
        }
        attacker.stats.hits.incrementAndGet();
        int defense = attack.magic ? to.getTotalMdef() : to.getTotalWdef();
        if (balance != null) {
            // even classes: a median victim meets spells and weapon hits with one common defense (check run 14: spells
            // met far less MDEF than weapons met WDEF, and magicians won 68% cross-class)
            int jobId = to.getJob().getId();
            defense = (int) Math.round(defense * (attack.magic ? balance.mdefFactor(jobId, to.getLevel())
                    : balance.wdefFactor(jobId, to.getLevel())));
        }
        boolean even = config.PVP_EVEN_CLASSES;
        double scale = factor * PvpDamageModel.levelFactor(from.getLevel(), to.getLevel(), config)
                * PvpDamageModel.levelHpShare(from.getLevel(), to.getLevel(), PvpReference.get())
                * PvpDamageModel.defenseFactor(defense, from.getLevel(), config)
                * (even ? PvpReference.get().classHpRatio(to.getJob().getId()) : 1d);
        List<Integer> scaled = PvpDamageModel.scaleLines(raw, scale, to.getCurrentMaxHp(), config);
        long total = scaled.stream().mapToLong(Integer::longValue).sum();
        // even classes: the class defense skills sit out, the class HP ratio above stands in for them
        int damage = even ? (int) total : CharacterHitApplier.reduce(to, (int) total);
        Integer powerGuard = to.getBuffedValue(BuffStat.POWERGUARD);
        if (!even && powerGuard != null && !attack.ranged && !attack.magic) {
            damage = damage * Math.max(0, 100 - powerGuard) / 100;
        }
        CharacterHitApplier.Guards guards = even ? CharacterHitApplier.Guards.NONE
                : new CharacterHitApplier.Guards(config.PVP_MAGIC_GUARD_FACTOR, config.PVP_MESO_GUARD_HP_SHARE);
        // a heal since the last look must meet the allowance before this hit moves the baseline: without it, a potion
        // or Heal between a tick and a hit passed whole (long run 2026-11-09: knocked-out magicians had absorbed 1.66x
        // their typical HP, warriors 1.07x, beyond what the allowance permits)
        accountHealing(victim, 0d);
        int hpBefore = to.getHp();
        int mpBefore = to.getMp();
        CharacterHitApplier.Applied applied = CharacterHitApplier.applyLoss(to, damage, 0, guards, true);
        settleAfterLoss(victim, hpBefore, mpBefore, applied);
        attacker.dealt += applied.hpLoss() + applied.mpLoss();
        victim.taken += applied.hpLoss() + applied.mpLoss();
        for (int line : shown(scaled, total, damage)) {
            map.broadcastMessage(PacketCreator.damagePlayer(-4, 0, to.getId(), line, 0, direction, false, 0, false,
                    0, 0, 0));
        }
        if (applied.floored()) {
            knockOut(victim, from);
        }
        return true;
    }

    /** The lines other clients draw: as rolled, shrunk together when a guard or Achilles took part of the hit. */
    static List<Integer> shown(List<Integer> lines, long rolled, int landed) {
        if (rolled <= 0 || landed >= rolled) {
            return lines;
        }
        List<Integer> shown = new ArrayList<>(lines.size());
        for (int line : lines) {
            shown.add((int) Math.max(line > 0 ? 1 : 0, Math.round(line * (landed / (double) rolled))));
        }
        return shown;
    }

    private void knockOut(Fighter victim, Character by) {
        victim.knockedOut = true;
        map.broadcastMessage(victim.chr, PacketCreator.facialExpression(victim.chr, KNOCKED_OUT_FACE), true);
        tell(victim.chr.getName() + " is knocked out by " + by.getName() + ".");
    }

    private boolean decided() {
        return standing(0) == 0 || standing(1) == 0;
    }

    private int standing(int side) {
        int count = 0;
        for (Fighter fighter : fighters.values()) {
            if (fighter.side == side && !fighter.knockedOut) {
                count++;
            }
        }
        return count;
    }

    private void endBySides() {
        int winner = standing(0) > 0 ? 0 : standing(1) > 0 ? 1 : -1;
        boolean leftOnly = winner >= 0 && fighters.values().stream()
                .filter(f -> f.side != winner).allMatch(f -> f.forfeited);
        end(winner, winner < 0 ? "both sides are down" : leftOnly ? "the other side left" : "the other side is down");
    }

    private void endByTime() {
        double a = hpShare(0);
        double b = hpShare(1);
        int winner = Math.abs(a - b) < 1e-9 ? -1 : a > b ? 0 : 1;
        end(winner, "time ran out");
    }

    private double hpShare(int side) {
        double share = 0;
        for (Fighter fighter : fighters.values()) {
            if (fighter.side == side && !fighter.knockedOut) {
                share += fighter.chr.getHp() / (double) Math.max(1, fighter.chr.getCurrentMaxHp());
            }
        }
        return share;
    }

    /** A GM's {@code !duel stop}: nobody wins. */
    public synchronized void stop() {
        end(-1, "it was stopped");
    }

    private synchronized void end(int winnerSide, String why) {
        if (state == State.ENDED) {
            return;
        }
        state = State.ENDED;
        if (ticker != null) {
            ticker.cancel(false);
        }
        long seconds = Math.max(0, System.currentTimeMillis() - Math.max(startedAtMs, fightStartsAtMs)) / 1000;
        String result = winnerSide < 0 ? "The duel is a draw (" + why + ")."
                : names(winnerSide) + " won the duel in " + seconds + " s (" + why + ").";
        tell(result);
        StringBuilder tally = new StringBuilder();
        for (Fighter fighter : fighters.values()) {
            Character chr = fighter.chr;
            if (present(fighter)) {
                int hp = Math.min(fighter.hpBefore, chr.getCurrentMaxHp());
                int mp = Math.min(fighter.mpBefore, chr.getCurrentMaxMp());
                chr.updateHpMp(Math.max(1, hp), mp);
            }
            if (!fighter.human) {
                DuelBrain.leave(fighter);
            }
            tally.append(' ').append(chr.getName()).append(" dealt=").append(fighter.dealt)
                    .append(" taken=").append(fighter.taken).append(fighter.knockedOut ? " KO" : "")
                    .append(fighter.forfeited ? " left" : "");
        }
        log.info("PvP duel {} ended after {} s: winner side {} ({}).{}", id, seconds, winnerSide, why, tally);
        long endMs = System.currentTimeMillis();
        List<DuelLog.Row> rows = new ArrayList<>();
        for (Fighter fighter : fighters.values()) {
            Character chr = fighter.chr;
            log.info("PvP duel {} {}: {}", id, chr.getName(), fighter.stats.summary());
            rows.add(new DuelLog.Row(endMs, id, map.getId(), seconds, why, winnerSide, chr.getName(), chr.getId(),
                    chr.getJob() == null ? 0 : chr.getJob().getId(), chr.getLevel(), fighter.human, fighter.side,
                    gearBase(chr), fighter.knockedOut, fighter.forfeited, fighter.stats, fighter.dealt, fighter.taken,
                    fighter.stats.engagedSeconds(endMs)));
        }
        rowSink.accept(rows);
        DuelRegistry.release(this);
    }

    /** Where finished duels' rows go: {@code logs/pvp/duels.csv}, then the learned rates; a test swaps it out. */
    static volatile java.util.function.Consumer<List<DuelLog.Row>> rowSink = DuelField::record;

    /** A finished duel's rows: the CSV, then (PVP_LEARN_RATES, PD16) the learned rates. */
    static void record(List<DuelLog.Row> rows) {
        DuelLog.append(rows);
        var pvp = YamlConfig.config.pvp;
        if (!pvp.PVP_LEARN_RATES) {
            return;
        }
        try {
            int counted = PvpRateLearner.get().observeAll(rows, PvpReference.get(), pvp);
            if (counted > 0) {
                log.info("PvP learned rates: {} fighter(s) of duel {} counted", counted, rows.getFirst().duel());
            }
        } catch (RuntimeException failure) {
            log.warn("PvP: learning from a duel failed", failure);
        }
    }

    private static long gearBase(Character chr) {
        try {
            return PvpCalibration.of(chr).gearBase();
        } catch (RuntimeException unknown) {
            return 0L;
        }
    }

    private String names(int side) {
        List<String> names = new ArrayList<>();
        for (Fighter fighter : fighters.values()) {
            if (fighter.side == side) {
                names.add(fighter.chr.getName());
            }
        }
        return String.join(" & ", names);
    }

    private void tell(String line) {
        for (Fighter fighter : fighters.values()) {
            if (fighter.human) {
                fighter.chr.dropMessage(5, line);
            }
        }
    }
}
