package server.living.imperfections;

import client.Character;
import config.LivingWorldConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.behavior.AgentFumbles;
import server.agents.capabilities.behavior.AgentImperfectionPolicy;
import server.agents.capabilities.behavior.AgentImperfections;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.integration.AgentPresence;

import java.awt.Point;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

/**
 * The one gate every small imperfection goes through (docs/BOT_CONCEPT_PLAN.md F8; user, 2026-10-26: no severe
 * mistakes, so no frequent deaths and no long inactivity). Only citizens and companions show any; never in party
 * content (a citizen's party quest or expedition, a companion's run), and the Agent engine adds its own exclusions
 * (event instances, boss maps, party quests: AgentImperfections.excluded). A slip also needs the bot's HP over
 * IMPERFECTIONS_HP_GUARD and no human of its party (or its owner) dead or under half HP, its chance, and room in the
 * bot's hourly budget (IMPERFECTIONS_SLIPS_PER_HOUR, and each kind's own cap). Every answer is counted for
 * GET /runtime/imperfections and an hourly log line. Off unless living_world.IMPERFECTIONS_ENABLED. Any thread.
 */
public final class Imperfections implements AgentImperfections.Rule {
    private static final Logger log = LoggerFactory.getLogger(Imperfections.class);
    static final long HOUR_MS = 3_600_000L;
    /** A companion's AFK never runs longer than this, whatever the config says. */
    static final long COMPANION_AFK_HARD_MAX_MS = 90_000L;
    static final long COMPANION_AFK_MIN_MS = 30_000L;
    /** The owner idles while it stays this close to where it stood; the companion's AFK ends past the far mark. */
    static final int OWNER_STILL_PX = 40;
    static final int OWNER_LEFT_PX = 200;
    /** A drop decision is kept for this many drops (the same answer for the same drop). */
    static final int DROP_DECISIONS_MAX = 4096;

    /** What can slip, with each kind's own hourly cap per bot. */
    public enum Kind {
        LATE_POTION(6), BUFF_LAPSE(2), ROPE_MISS(2), NPC_OVERSHOOT(2), LOOT_SLIP(4), REZ_WAIT(2), TYPO_FIX(3),
        COMPANION_AFK(1);

        final int perHour;

        Kind(int perHour) {
            this.perHour = perHour;
        }
    }

    /** What the gate asks the living world (LivingWorld in play; tests hand their own). */
    interface Host {
        boolean citizen(int characterId);

        boolean companion(int characterId);

        /** A citizen's party quest or expedition, a companion's run (its commitment). */
        boolean partyContent(int characterId);

        /** True when the item has a price-index row at or under the index median (Market.atOrUnderMedian). */
        boolean cheap(int itemId);

        /** The bot says one of its lines for {@code moment} ("rez.ask.self", "rez.thanks.self", "afk.brb", "afk.back"). */
        void say(Character bot, String moment);
    }

    private static volatile Imperfections active;

    private final LivingWorldConfig config;
    private final Host host;
    private final DoubleSupplier draw;
    private final LongSupplier clock;
    private final SlipBudget budget = new SlipBudget();
    private final Map<Kind, AtomicLong> fired = new EnumMap<>(Kind.class);
    private final Map<String, AtomicLong> blocked = new ConcurrentHashMap<>();
    private final AtomicLong calibrations = new AtomicLong();
    private final Map<Long, Boolean> dropDecisions = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
            return size() > DROP_DECISIONS_MAX;
        }
    };
    /** Drops some bot left by a loot slip (object ids, guarded by dropDecisions). */
    private final Map<Integer, Boolean> slippedDrops = new LinkedHashMap<>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Boolean> eldest) {
            return size() > DROP_DECISIONS_MAX;
        }
    };
    private final Map<Integer, CompanionAfk> afks = new ConcurrentHashMap<>();
    private final Map<Integer, Long> lastAfkAt = new ConcurrentHashMap<>();
    private final Map<Integer, OwnerStill> ownersStill = new ConcurrentHashMap<>();
    private final long startedAtMs;
    private volatile long loggedAtMs;

    Imperfections(LivingWorldConfig config, Host host, DoubleSupplier draw, LongSupplier clock) {
        this.config = config;
        this.host = host;
        this.draw = draw;
        this.clock = clock;
        for (Kind kind : Kind.values()) {
            fired.put(kind, new AtomicLong());
        }
        this.startedAtMs = clock.getAsLong();
        this.loggedAtMs = startedAtMs;
    }

    // ---- lifecycle -------------------------------------------------------------------------

    /** LivingWorld.start: the gate goes in when living_world.IMPERFECTIONS_ENABLED, else nothing slips. */
    public static void install(server.living.LivingWorld world) {
        install(world.config(), new ImperfectionsHost(world));
    }

    static void install(LivingWorldConfig config, Host host) {
        uninstall();
        if (config.IMPERFECTIONS_ENABLED) {
            start(new Imperfections(config, host, () -> ThreadLocalRandom.current().nextDouble(),
                    System::currentTimeMillis));
            log.info("Imperfections on ({} slips an hour per bot at most)", config.IMPERFECTIONS_SLIPS_PER_HOUR);
        }
    }

    static void start(Imperfections gate) {
        active = gate;
        AgentImperfections.install(gate);
    }

    public static void uninstall() {
        active = null;
        AgentImperfections.install(null);
    }

    /** The running gate, or null while it is off. */
    public static Imperfections active() {
        return active;
    }

    // ---- the engine's rule -------------------------------------------------------------------

    @Override
    public boolean calibrated(Character agent) {
        int id = agent.getId();
        if (!host.citizen(id) && !host.companion(id)) {
            return false;
        }
        if (host.partyContent(id)) {
            block("party-content");
            return false;
        }
        calibrations.incrementAndGet();
        return true;
    }

    @Override
    public boolean slips(Character agent, AgentImperfections.Slip slip) {
        return slip(agent, kindOf(slip));
    }

    @Override
    public boolean leavesDrop(Character agent, int itemId, int dropObjectId) {
        long key = ((long) agent.getId() << 32) | (dropObjectId & 0xffffffffL);
        synchronized (dropDecisions) {
            Boolean decided = dropDecisions.get(key);
            if (decided != null) {
                return decided;
            }
        }
        boolean leaves = host.cheap(itemId) && slip(agent, Kind.LOOT_SLIP);
        synchronized (dropDecisions) {
            dropDecisions.put(key, leaves);
            if (leaves) {
                slippedDrops.put(dropObjectId, Boolean.TRUE);
            }
        }
        return leaves;
    }

    /**
     * True when a bot left this drop by a loot slip: OwnerHabitBook.dropsExpired does not count it as left by the owner
     * standing near, so a companion's slip never teaches it its owner's habit.
     */
    public boolean leftBySlip(int dropObjectId) {
        synchronized (dropDecisions) {
            return slippedDrops.containsKey(dropObjectId);
        }
    }

    @Override
    public float personalHpAlert(Character agent, float base) {
        return AgentImperfectionPolicy.personalHpAlert(agent.getId(), base, (float) config.IMPERFECTIONS_POTION_FLOOR);
    }

    @Override
    public void waitingForRez(Character agent) {
        host.say(agent, "rez.ask.self");
    }

    @Override
    public void revived(Character agent) {
        host.say(agent, "rez.thanks.self");
    }

    // ---- the gate ------------------------------------------------------------------------------

    /**
     * True when {@code agent} slips this once ({@code kind}): one of the gate's own bots outside party content, its HP
     * over the guard and no human of its party in danger (a Resurrection wait asks neither: the bot is already dead),
     * its chance came up and its budget has room. A true is spent and counted.
     */
    public boolean slip(Character agent, Kind kind) {
        if (agent == null || !calibrated(agent)) {
            return false;
        }
        if (kind != Kind.REZ_WAIT) {
            int maxHp = agent.getCurrentMaxHp();
            if (maxHp <= 0 || agent.getHp() < config.IMPERFECTIONS_HP_GUARD * maxHp) {
                return block("hp");
            }
            if (partyInDanger(agent)) {
                return block("owner-danger");
            }
        }
        if (draw.getAsDouble() >= chance(kind)) {
            return false;
        }
        long now = clock.getAsLong();
        if (!budget.spend(agent.getId(), kind, now, Math.max(0, config.IMPERFECTIONS_SLIPS_PER_HOUR))) {
            return block("budget");
        }
        fired.get(kind).incrementAndGet();
        logHourly(now);
        return true;
    }

    double chance(Kind kind) {
        return switch (kind) {
            case LATE_POTION -> config.IMPERFECTIONS_LATE_POTION_CHANCE;
            case BUFF_LAPSE -> config.IMPERFECTIONS_BUFF_LAPSE_CHANCE;
            case LOOT_SLIP -> config.IMPERFECTIONS_LOOT_SLIP_CHANCE;
            case ROPE_MISS, NPC_OVERSHOOT -> config.IMPERFECTIONS_FUMBLE_CHANCE;
            case TYPO_FIX -> config.IMPERFECTIONS_TYPO_FIX_CHANCE;
            case COMPANION_AFK -> config.IMPERFECTIONS_COMPANION_AFK_CHANCE;
            case REZ_WAIT -> 1.0d;
        };
    }

    /** True when its owner or a human of its party on its map is dead or under half HP, or its owner is in an event. */
    static boolean partyInDanger(Character agent) {
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(agent.getId());
        Character owner = entry == null ? null : entry.owner();
        if (owner != null && !AgentPresence.isAgent(owner)
                && (owner.getEventInstance() != null || owner.getMapId() == agent.getMapId() && inDanger(owner))) {
            return true;
        }
        for (Character member : agent.getPartyMembersOnSameMap()) {
            if (member != null && member.getId() != agent.getId() && !AgentPresence.isAgent(member) && inDanger(member)) {
                return true;
            }
        }
        return false;
    }

    private static boolean inDanger(Character human) {
        return !human.isAlive() || human.getHp() * 2L < human.getCurrentMaxHp();
    }

    private static Kind kindOf(AgentImperfections.Slip slip) {
        return switch (slip) {
            case LATE_POTION -> Kind.LATE_POTION;
            case BUFF_LAPSE -> Kind.BUFF_LAPSE;
            case ROPE_MISS -> Kind.ROPE_MISS;
            case NPC_OVERSHOOT -> Kind.NPC_OVERSHOOT;
            case LOOT_SLIP -> Kind.LOOT_SLIP;
            case REZ_WAIT -> Kind.REZ_WAIT;
        };
    }

    private boolean block(String reason) {
        blocked.computeIfAbsent(reason, key -> new AtomicLong()).incrementAndGet();
        return false;
    }

    // ---- citizens' chat: a typo, then "*word" (F8.5) ---------------------------------------------

    /**
     * ChatterEngine.speakSelf, a citizen's line on a town, Free Market or lobby map ({@code where}) with no hostile mob
     * near: now and then (TYPO_FIX) one word of four letters or more gets two letters swapped and a second line
     * "*word" follows. Returns {line with the typo, the fix}, or null for the line as it is.
     */
    public String[] typoFix(Character agent, String line, String where) {
        if (agent == null || line == null || !("town".equals(where) || "fm".equals(where) || "lobby".equals(where))
                || !AgentFumbles.calmTown(agent)) {
            return null;
        }
        String[] typo = typo(line, new Random(clock.getAsLong() ^ agent.getId()));
        return typo != null && slip(agent, Kind.TYPO_FIX) ? typo : null;
    }

    /** {line with two neighbouring letters of one word swapped, "*word"}; null when no word fits. Pure. */
    static String[] typo(String line, Random random) {
        String[] words = line.split(" ", -1);
        int[] fits = new int[words.length];
        int count = 0;
        for (int i = 0; i < words.length; i++) {
            if (words[i].length() >= 4 && words[i].chars().allMatch(java.lang.Character::isLetter)) {
                fits[count++] = i;
            }
        }
        for (int tries = 0; tries < count; tries++) {
            int index = fits[(random.nextInt(count) + tries) % count];
            String word = words[index];
            int at = 1 + random.nextInt(word.length() - 2); // swap at, at+1: never the first letter
            if (word.charAt(at) == word.charAt(at + 1)) {
                continue;
            }
            char[] letters = word.toCharArray();
            letters[at] = word.charAt(at + 1);
            letters[at + 1] = word.charAt(at);
            words[index] = new String(letters);
            return new String[]{String.join(" ", words), "*" + word};
        }
        return null;
    }

    // ---- companions: a short AFK while the owner idles in town (F8.4) ----------------------------

    /**
     * CompanionFollowGuard.waitsAt, on the companion's tick: the spot this companion stands at while it is AFK, else
     * null (it follows as usual). Its owner idling in town for IMPERFECTIONS_OWNER_IDLE_MS (within 40 px), both on one
     * calm town map, gives each companion one roll per idle spell (COMPANION_AFK: its chance, the gate, the budget, at
     * most once in IMPERFECTIONS_COMPANION_AFK_GAP_MS); the AFK lasts 30 s to IMPERFECTIONS_COMPANION_AFK_MAX_MS (never
     * over 90 s) and ends at once when the owner moves off 200 px or leaves the map.
     */
    public Point companionAfk(Character companion, Character owner) {
        if (companion == null || owner == null || owner.getPosition() == null || companion.getPosition() == null) {
            return null;
        }
        long now = clock.getAsLong();
        CompanionAfk afk = afks.get(companion.getId());
        if (afk != null) {
            if (now < afk.untilMs && owner.getMapId() == afk.mapId && companion.getMapId() == afk.mapId
                    && owner.getPosition().distance(afk.ownerAt) <= OWNER_LEFT_PX && companion.isAlive()) {
                return new Point(afk.spot);
            }
            afks.remove(companion.getId());
            if (afk.said) {
                host.say(companion, "afk.back");
            }
            return null;
        }
        OwnerStill still = ownersStill.compute(owner.getId(), (id, was) -> was != null && was.mapId == owner.getMapId()
                && was.at.distance(owner.getPosition()) <= OWNER_STILL_PX ? was
                : new OwnerStill(owner.getMapId(), new Point(owner.getPosition()), now));
        if (now - still.sinceMs < config.IMPERFECTIONS_OWNER_IDLE_MS || !still.rolled.add(companion.getId())
                || owner.getMapId() != companion.getMapId() || !owner.getMap().isTown()
                || !AgentFumbles.calmTown(companion)
                || now - lastAfkAt.getOrDefault(companion.getId(), Long.MIN_VALUE / 2) < config.IMPERFECTIONS_COMPANION_AFK_GAP_MS
                || !slip(companion, Kind.COMPANION_AFK)) {
            return null;
        }
        long max = Math.min(COMPANION_AFK_HARD_MAX_MS, Math.max(COMPANION_AFK_MIN_MS, config.IMPERFECTIONS_COMPANION_AFK_MAX_MS));
        long lasts = COMPANION_AFK_MIN_MS + (long) (draw.getAsDouble() * (max - COMPANION_AFK_MIN_MS));
        boolean say = draw.getAsDouble() < 0.5d;
        afks.put(companion.getId(), new CompanionAfk(companion.getMapId(), new Point(companion.getPosition()),
                new Point(owner.getPosition()), now + lasts, say));
        lastAfkAt.put(companion.getId(), now);
        if (say) {
            host.say(companion, "afk.brb");
        }
        return new Point(companion.getPosition());
    }

    private record CompanionAfk(int mapId, Point spot, Point ownerAt, long untilMs, boolean said) {
    }

    private static final class OwnerStill {
        final int mapId;
        final Point at;
        final long sinceMs;
        final java.util.Set<Integer> rolled = ConcurrentHashMap.newKeySet();

        OwnerStill(int mapId, Point at, long sinceMs) {
            this.mapId = mapId;
            this.at = at;
            this.sinceMs = sinceMs;
        }
    }

    // ---- metrics -------------------------------------------------------------------------------

    /** What fired and what was held back since the gate started (GET /runtime/imperfections). */
    public Map<String, Long> counts() {
        Map<String, Long> out = new LinkedHashMap<>();
        out.put("calibrated", calibrations.get());
        for (Kind kind : Kind.values()) {
            out.put("fired." + kind.name().toLowerCase(java.util.Locale.ROOT), fired.get(kind).get());
        }
        new TreeMap<>(blocked).forEach((reason, count) -> out.put("blocked." + reason, count.get()));
        return out;
    }

    public long startedAtMs() {
        return startedAtMs;
    }

    private void logHourly(long now) {
        long last = loggedAtMs;
        if (now - last >= HOUR_MS) {
            loggedAtMs = now;
            log.info("Imperfections, last {} h: {}", (now - startedAtMs) / HOUR_MS, counts());
        }
    }
}
