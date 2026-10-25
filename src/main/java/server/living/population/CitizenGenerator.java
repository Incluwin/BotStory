package server.living.population;

import client.Character;
import client.Client;
import client.Job;
import client.creator.BotCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.integration.AgentAccountResolution;
import server.agents.integration.AgentBackingAccountSecurityRuntime;
import server.agents.integration.AgentClientGatewayRuntime;
import server.agents.integration.AgentIdentityGatewayRuntime;
import server.agents.integration.AgentIdentityOrigin;
import server.agents.integration.AgentPersistenceGatewayRuntime;
import server.agents.integration.cosmic.CosmicAgentOfflineLoader;
import server.agents.personality.AgentPersonalityProfileRepository;
import server.agents.social.personality.DialogueStyleProfileRepository;
import server.living.config.Content.Loadout;
import server.living.population.Citizen.State;
import server.living.population.PopulationContent.Career;
import server.living.population.PopulationContent.Cohort;
import server.living.population.PopulationContent.Region;
import server.living.population.PopulationContent.Voice;
import server.living.resident.ResidentShaper;
import server.living.resident.ResidentSpots;
import server.maps.MapleMap;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;

/**
 * Births citizens (plan 3.1): a cohort, career, voice and flags drawn by weight, a generated name
 * that passes the same checks as a player's, an Agent-only account and character with a random
 * look, then the character is shaped to the level its age says it should have and put away.
 */
public final class CitizenGenerator {
    private static final Logger log = LoggerFactory.getLogger(CitizenGenerator.class);
    private static final int[] MALE_FACES = {20000, 20001, 20002, 20003, 20004, 20005};
    private static final int[] FEMALE_FACES = {21000, 21001, 21002, 21003, 21004, 21005};
    private static final int[] MALE_HAIR = {30000, 30020, 30030, 30040, 30050, 30060};
    private static final int[] FEMALE_HAIR = {31000, 31040, 31050, 31060, 31090, 31150};
    private static final int[] MALE_TOPS = {1040002, 1040006, 1040010};
    private static final int[] FEMALE_TOPS = {1041002, 1041006, 1041010};
    private static final int[] MALE_BOTTOMS = {1060002, 1060006};
    private static final int[] FEMALE_BOTTOMS = {1061002, 1061008};
    private static final int[] SHOES = {1072001, 1072005, 1072037, 1072038};
    private static final int[] WEAPONS = {1302000, 1312004, 1322005, 1372005, 1452002, 1332007};
    private static final int NAME_ATTEMPTS = 40;
    private static final double PACE_MIN = 0.6d;
    private static final double PACE_SPAN = 0.9d;
    /** Fast-seeding draws a seed plan tries before it solves for a band those draws almost never hit. */
    private static final int SEED_TRIES = 4096;

    private final Population population;
    private final Random random;
    private int births;

    CitizenGenerator(Population population) {
        this.population = population;
        this.random = new Random(population.config().SEED * 31L + 7L);
    }

    /** Creates one citizen; {@code cohortId} null draws a cohort by share. Returns null when creation failed. */
    public Citizen birth(String cohortId, int backdateDays) {
        PopulationContent content = population.content();
        Cohort cohort = cohortId != null ? content.cohort(cohortId) : pickCohort();
        Career career = pickCareer();
        if (cohort == null || career == null) {
            log.warn("cannot birth a citizen: cohorts={} careers={}", content.cohorts().size(), content.careers().size());
            return null;
        }
        return birth(cohort, career, backdateDays, null);
    }

    /**
     * A seeded citizen homed in {@code regionId} at a level inside its band; null when no career lands in the band or creation
     * failed. Seeds draw from the same Random as births, so births after one differ from a run that never seeded.
     */
    public Citizen seed(String regionId) {
        PopulationContent content = population.content();
        Region region = content.region(regionId);
        Cohort cohort = region == null ? null : pickCohort();
        boolean lines = linesEnabled();
        SeedPlan plan = cohort == null ? null : planSeed(region, CitizenLines.drawable(content.careers().values(), lines),
                career -> CitizenLines.band(region, career, lines), population.config().SEED_BACKDATE_DAYS,
                population.config().LIFE_PACE, random);
        if (plan == null) {
            log.warn("seed for {} skipped: {}", regionId, region == null ? "no such region" : cohort == null ? "no cohorts"
                    : "no career lands in lv" + region.levelMin() + "-" + region.levelMax() + " within "
                    + population.config().SEED_BACKDATE_DAYS + " backdate days");
            return null;
        }
        return birth(cohort, plan.career(), plan.backdateDays(), plan);
    }

    /** {@code plan} null draws the pace and picks the region by level; otherwise both come from the plan. */
    private Citizen birth(Cohort cohort, Career career, int backdateDays, SeedPlan plan) {
        String name = pickName();
        if (name == null) {
            return null;
        }
        int characterId = createCharacter(name, CitizenLines.beginner(career));
        if (characterId <= 0) {
            return null;
        }
        long now = System.currentTimeMillis();
        long joined = now - backdateDays * 86_400_000L;
        int lifeDays = plan != null ? seedLifeDays(backdateDays) : population.config().LIFE_DAYS_MIN
                + random.nextInt(Math.max(1, population.config().LIFE_DAYS_MAX - population.config().LIFE_DAYS_MIN + 1));
        lifeDays = Math.max(lifeDays, backdateDays + 30);
        String personality = AgentPersonalityProfileRepository.defaultRepository().deterministicFor(characterId).profileId();
        String style = DialogueStyleProfileRepository.defaultRepository().resolve(personality).styleId();
        double pace = plan == null ? PACE_MIN + random.nextDouble() * PACE_SPAN : plan.pace();
        Citizen citizen = new Citizen(characterId, name, cohort.id(), 0, 0, "victoria", career.id(), personality,
                pickVoice(style), pace, random.nextInt(120) - 60, pickChannel(), flags(cohort), State.ACTIVE, joined,
                joined + lifeDays * 86_400_000L, 0L, now, 0L, 0, "");
        int level = LifeCurve.levelTarget(citizen.ageDays(now), pace, career.levelCap(), population.config().LIFE_PACE);
        citizen = citizen.withRegion(plan == null ? regionFor(level, citizen.characterId(), career) : plan.region());
        population.save(citizen);
        shape(citizen, career, level);
        births++;
        log.info("citizen born: {} ({}) cohort {} career {} level {} voice {}", name, characterId, cohort.id(), career.id(), level, citizen.voice());
        return citizen;
    }

    /**
     * A seed has already lived {@code backdateDays}: its life is drawn among lives at least 30 days longer rather than
     * clamped to backdate + 30, so a queued batch does not all retire in the same hour a month later.
     */
    private int seedLifeDays(int backdateDays) {
        int shortest = Math.max(population.config().LIFE_DAYS_MIN, backdateDays + 30);
        int longest = Math.max(population.config().LIFE_DAYS_MAX, shortest + 60);
        return shortest + random.nextInt(longest - shortest + 1);
    }

    /** LivingWorldConfig.CYGNUS_ARAN_CAREERS_ENABLED: Cygnus Knight and Aran citizens are born (CitizenLines). */
    private boolean linesEnabled() {
        return CitizenLines.enabled(population);
    }

    public int births() {
        return births;
    }

    /**
     * Region whose level band fits, weighted; ties broken by the character id so it is stable. A Knight or an Aran below
     * its first job lives in its line's home, and elsewhere only from its first job on (CitizenLines).
     */
    String regionFor(int level, int seed, Career career) {
        boolean lines = linesEnabled();
        Region start = CitizenLines.startingHome(population.content().regions().values(), career, level, lines);
        if (start != null) {
            return start.id();
        }
        List<Region> fitting = new ArrayList<>();
        int total = 0;
        for (Region region : population.content().regions().values()) {
            Region band = CitizenLines.band(region, career, lines);
            if (band != null && band.fits(level)) {
                fitting.add(region);
                total += Math.max(1, region.weight());
            }
        }
        if (fitting.isEmpty()) {
            return "victoria";
        }
        int roll = Math.floorMod(seed * 7919, total);
        for (Region region : fitting) {
            roll -= Math.max(1, region.weight());
            if (roll < 0) {
                return region.id();
            }
        }
        return fitting.get(0).id();
    }

    /** Career, pace and backdate that put a seeded citizen of {@code region} at {@code level}, inside its band. */
    record SeedPlan(String region, Career career, double pace, int backdateDays, int level) { }

    /**
     * Plans a seed homed in {@code region}: careers, paces and backdates drawn the way fast seeding draws them until the
     * level lands in the band, so seeds look like the citizens who settled there on their own. A band those draws almost
     * never hit falls back to a career that lands in it, a pace fast enough and a backdate inside it. Null when no career
     * lands in the band within {@code maxBackdate} days.
     */
    static SeedPlan planSeed(Region region, Collection<Career> careers, int maxBackdate, double lifePace, Random random) {
        return planSeed(region, careers, career -> region, maxBackdate, lifePace, random);
    }

    /**
     * As above, each career living in its own part of the region's band ({@code bandOf}, CitizenLines.band: a Knight
     * outside Ereve from Lv10; null when the career does not live there at all).
     */
    static SeedPlan planSeed(Region region, Collection<Career> careers, Function<Career, Region> bandOf, int maxBackdate,
                             double lifePace, Random random) {
        List<Career> able = new ArrayList<>();
        List<Career> all = new ArrayList<>();
        for (Career career : careers) {
            Region band = bandOf.apply(career);
            if (band == null) {
                continue;
            }
            all.add(career);
            if (lands(career, band, maxBackdate, lifePace)) {
                able.add(career);
            }
        }
        if (able.isEmpty()) {
            return null;
        }
        for (int i = 0; i < SEED_TRIES; i++) {
            Career career = byPopularity(all, random);
            double pace = PACE_MIN + random.nextDouble() * PACE_SPAN;
            double skew = random.nextDouble(); // squared like Churn's fast seeding
            int backdate = (int) Math.round(skew * skew * maxBackdate);
            int level = LifeCurve.levelTarget(backdate, pace, career.levelCap(), lifePace);
            if (bandOf.apply(career).fits(level)) {
                return new SeedPlan(region.id(), career, pace, backdate, level);
            }
        }
        Career career = byPopularity(able, random);
        Region band = bandOf.apply(career);
        double floor = slowestPace(career, band.levelMin(), maxBackdate, lifePace);
        double pace = floor + random.nextDouble() * (PACE_MIN + PACE_SPAN - floor);
        int[] window = window(career, band, pace, maxBackdate, lifePace);
        if (window == null) { // a band narrower than a day's levels at this pace; lands() found it at the slowest
            pace = floor;
            window = window(career, band, pace, maxBackdate, lifePace);
        }
        int backdate = window[0] + random.nextInt(window[1] - window[0] + 1);
        return new SeedPlan(region.id(), career, pace, backdate, LifeCurve.levelTarget(backdate, pace, career.levelCap(), lifePace));
    }

    /** True when some career lands in the region's band within {@code maxBackdate} days, so its seeds can be planned. */
    static boolean reachable(Region region, Collection<Career> careers, int maxBackdate, double lifePace) {
        return reachable(region, careers, career -> region, maxBackdate, lifePace);
    }

    /** As above, each career in its own part of the band ({@code bandOf}, as {@link #planSeed}). */
    static boolean reachable(Region region, Collection<Career> careers, Function<Career, Region> bandOf, int maxBackdate,
                             double lifePace) {
        for (Career career : careers) {
            Region band = bandOf.apply(career);
            if (band != null && lands(career, band, maxBackdate, lifePace)) {
                return true;
            }
        }
        return false;
    }

    /** Highest level any career reaches within {@code maxBackdate} days at the fastest pace: the top of what seeding fills. */
    static int seedCeiling(Collection<Career> careers, int maxBackdate, double lifePace) {
        int ceiling = 1;
        for (Career career : careers) {
            ceiling = Math.max(ceiling, LifeCurve.levelTarget(maxBackdate, PACE_MIN + PACE_SPAN, career.levelCap(), lifePace));
        }
        return ceiling;
    }

    /** True when this career, at the slowest pace that reaches the band's bottom, has a backdate whose level is in the band. */
    private static boolean lands(Career career, Region region, int maxBackdate, double lifePace) {
        return reaches(career, region.levelMin(), maxBackdate, PACE_MIN + PACE_SPAN, lifePace)
                && window(career, region, slowestPace(career, region.levelMin(), maxBackdate, lifePace), maxBackdate, lifePace) != null;
    }

    private static boolean reaches(Career career, int level, int days, double pace, double lifePace) {
        return LifeCurve.levelTarget(days, pace, career.levelCap(), lifePace) >= level;
    }

    /** First and last backdate whose level at this pace lies in the band, or null. */
    private static int[] window(Career career, Region region, double pace, int maxBackdate, double lifePace) {
        int first = firstDay(career, region.levelMin(), pace, maxBackdate, lifePace);
        int above = firstDay(career, region.levelMax() + 1, pace, maxBackdate, lifePace);
        int last = above < 0 ? maxBackdate : above - 1;
        return first < 0 || last < first ? null : new int[] {first, last};
    }

    /** First day in 0..maxDays on which this career reaches {@code level}, or -1; bisected, since levels never fall as days pass. */
    private static int firstDay(Career career, int level, double pace, int maxDays, double lifePace) {
        if (!reaches(career, level, maxDays, pace, lifePace)) {
            return -1;
        }
        int low = 0;
        int high = maxDays;
        while (low < high) {
            int mid = low + (high - low) / 2;
            if (reaches(career, level, mid, pace, lifePace)) {
                high = mid;
            } else {
                low = mid + 1;
            }
        }
        return low;
    }

    private static Career byPopularity(List<Career> careers, Random random) {
        int total = 0;
        for (Career career : careers) {
            total += Math.max(0, career.popularity());
        }
        int roll = total > 0 ? random.nextInt(total) : 0;
        for (Career career : careers) {
            roll -= Math.max(0, career.popularity());
            if (roll < 0) {
                return career;
            }
        }
        return careers.get(0);
    }

    /** Slowest pace, never under PACE_MIN, at which this career reaches {@code level} within {@code days} (bisected). */
    private static double slowestPace(Career career, int level, int days, double lifePace) {
        double low = PACE_MIN;
        double high = PACE_MIN + PACE_SPAN;
        if (reaches(career, level, days, low, lifePace)) {
            return low;
        }
        for (int i = 0; i < 40; i++) {
            double mid = (low + high) / 2;
            if (reaches(career, level, days, mid, lifePace)) {
                high = mid;
            } else {
                low = mid;
            }
        }
        return high;
    }

    private void shape(Citizen citizen, Career career, int level) {
        Region region = population.content().region(citizen.homeRegion());
        int townMap = region == null || region.towns().isEmpty() ? ResidentSpots.townMapId("henesys") : region.towns().get(0);
        MapleMap map = population.world().residents().map(townMap);
        if (map == null) {
            return;
        }
        try {
            Character agent = CosmicAgentOfflineLoader.loadOfflineAgent(citizen.characterId(), population.world().config().WORLD,
                    population.world().config().CHANNEL, map, ResidentSpots.fallback(map, citizen.characterId()));
            if (agent == null) {
                return;
            }
            Job job = ResidentShaper.job(career.job());
            Loadout loadout = population.world().content().loadout(career.loadoutFor(level));
            ResidentShaper.shapeTo(agent, job, level, loadout);
            CitizenGear.upgrade(agent, job, loadout);
            agent.saveCharToDB();
            if (agent.getClient() != null) {
                agent.getClient().forceDisconnect();
            }
        } catch (Exception failure) {
            log.warn("shaping citizen {} failed", citizen.name(), failure);
        }
    }

    private Cohort pickCohort() {
        int total = 0;
        for (Cohort cohort : population.content().cohorts().values()) {
            total += Math.max(0, cohort.share());
        }
        if (total <= 0) {
            return population.content().cohorts().values().stream().findFirst().orElse(null);
        }
        int roll = random.nextInt(total);
        for (Cohort cohort : population.content().cohorts().values()) {
            roll -= Math.max(0, cohort.share());
            if (roll < 0) {
                return cohort;
            }
        }
        return null;
    }

    private Career pickCareer() {
        List<Career> careers = CitizenLines.drawable(population.content().careers().values(), linesEnabled());
        int total = 0;
        for (Career career : careers) {
            total += Math.max(0, career.popularity());
        }
        if (total <= 0) {
            return careers.stream().findFirst().orElse(null);
        }
        int roll = random.nextInt(total);
        for (Career career : careers) {
            roll -= Math.max(0, career.popularity());
            if (roll < 0) {
                return career;
            }
        }
        return null;
    }

    private String pickVoice(String style) {
        List<Voice> matching = new ArrayList<>();
        int total = 0;
        for (Voice voice : population.content().voices().values()) {
            if (voice.style().equals(style)) {
                matching.add(voice);
                total += Math.max(1, voice.share());
            }
        }
        if (matching.isEmpty()) {
            matching.addAll(population.content().voices().values());
            total = 0;
            for (Voice voice : matching) {
                total += Math.max(1, voice.share());
            }
        }
        if (matching.isEmpty()) {
            return "casual-typos";
        }
        int roll = random.nextInt(total);
        for (Voice voice : matching) {
            roll -= Math.max(1, voice.share());
            if (roll < 0) {
                return voice.id();
            }
        }
        return matching.get(0).id();
    }

    private int pickChannel() {
        List<Integer> channels = population.channels();
        return channels.get(random.nextInt(channels.size()));
    }

    private Set<String> flags(Cohort cohort) {
        Set<String> flags = new LinkedHashSet<>();
        int market = cohort.activities().getOrDefault("MARKET", 0);
        int pq = cohort.activities().getOrDefault("PQ", 0);
        int afk = cohort.activities().getOrDefault("AFK", 0);
        if (random.nextInt(100) < market * 2) {
            flags.add(Citizen.FLAG_MERCHANT);
        }
        if (random.nextInt(100) < pq * 2) {
            flags.add(Citizen.FLAG_PQ_REGULAR);
        }
        if (random.nextInt(100) < afk * 2) {
            flags.add(Citizen.FLAG_AFK_TOWNIE);
        }
        if (random.nextInt(100) < 10) {
            flags.add(Citizen.FLAG_FAMER);
        }
        if (random.nextInt(100) < 15) {
            flags.add(Citizen.FLAG_RECRUITER);
        }
        return flags;
    }

    private String pickName() {
        NameGenerator names = population.names();
        for (int attempt = 0; attempt < NAME_ATTEMPTS; attempt++) {
            String candidate = names.next(random);
            if (candidate.length() < 4 || candidate.length() > 12 || !candidate.matches("[A-Za-z0-9]+")) {
                continue;
            }
            if (population.store().nameTaken(candidate) || !Character.canCreateChar(candidate)) {
                continue;
            }
            return candidate;
        }
        log.warn("no free citizen name after {} attempts", NAME_ATTEMPTS);
        return null;
    }

    /** A character starting {@code beginner}'s line: a Legend in the Aran's starting clothes and Basic Polearm. */
    private int createCharacter(String name, Job beginner) {
        try {
            AgentAccountResolution account = AgentPersistenceGatewayRuntime.persistence().resolveOrCreateAgentAccount(name);
            if (!account.isSuccess()) {
                log.warn("citizen {}: {}", name, account.failureMessage());
                return 0;
            }
            if (!AgentBackingAccountSecurityRuntime.lockInteractiveLogin(account.accountId())) {
                log.warn("citizen {}: could not lock backing account {}", name, account.accountId());
                return 0;
            }
            Client creation = AgentClientGatewayRuntime.clients().createHeadlessClient(population.world().config().WORLD,
                    population.world().config().CHANNEL);
            creation.setAccID(account.accountId());
            creation.setAccountName(name);
            boolean female = random.nextBoolean();
            int face = (female ? FEMALE_FACES : MALE_FACES)[random.nextInt(6)] + 100 * random.nextInt(8);
            int hair = (female ? FEMALE_HAIR : MALE_HAIR)[random.nextInt(6)] + random.nextInt(8);
            int skin = random.nextInt(4); // drawn in the order the arguments always were
            int top = (female ? FEMALE_TOPS : MALE_TOPS)[random.nextInt(3)];
            int bottom = (female ? FEMALE_BOTTOMS : MALE_BOTTOMS)[random.nextInt(2)];
            int shoes = SHOES[random.nextInt(SHOES.length)];
            int weapon = WEAPONS[random.nextInt(WEAPONS.length)];
            boolean legend = beginner == Job.LEGEND;
            int characterId = BotCreator.createCharacter(creation, name, face, hair, skin, female ? 1 : 0,
                    legend ? BotCreator.ARAN_TOP : top, legend ? BotCreator.ARAN_BOTTOM : bottom,
                    legend ? BotCreator.ARAN_SHOES : shoes, legend ? BotCreator.BASIC_POLEARM : weapon, beginner);
            if (characterId == -1) {
                log.warn("citizen {}: character creation failed", name);
                return 0;
            }
            AgentIdentityGatewayRuntime.identities().register(characterId, AgentIdentityOrigin.PROVISIONED, false);
            return characterId;
        } catch (SQLException failure) {
            log.warn("citizen {}: provisioning failed", name, failure);
            return 0;
        }
    }
}
