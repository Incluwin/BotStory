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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

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
        String name = pickName();
        if (name == null) {
            return null;
        }
        int characterId = createCharacter(name);
        if (characterId <= 0) {
            return null;
        }
        long now = System.currentTimeMillis();
        long joined = now - backdateDays * 86_400_000L;
        int lifeDays = population.config().LIFE_DAYS_MIN
                + random.nextInt(Math.max(1, population.config().LIFE_DAYS_MAX - population.config().LIFE_DAYS_MIN + 1));
        lifeDays = Math.max(lifeDays, backdateDays + 30);
        String personality = AgentPersonalityProfileRepository.defaultRepository().deterministicFor(characterId).profileId();
        String style = DialogueStyleProfileRepository.defaultRepository().resolve(personality).styleId();
        double pace = 0.6d + random.nextDouble() * 0.9d;
        Citizen citizen = new Citizen(characterId, name, cohort.id(), 0, 0, "victoria", career.id(), personality,
                pickVoice(style), pace, random.nextInt(120) - 60, pickChannel(), flags(cohort), State.ACTIVE, joined,
                joined + lifeDays * 86_400_000L, 0L, now, 0L, 0, "");
        int level = LifeCurve.levelTarget(citizen.ageDays(now), pace, career.levelCap(), population.config().LIFE_PACE);
        citizen = citizen.withRegion(regionFor(level, citizen.characterId()));
        population.save(citizen);
        shape(citizen, career, level);
        births++;
        log.info("citizen born: {} ({}) cohort {} career {} level {} voice {}", name, characterId, cohort.id(), career.id(), level, citizen.voice());
        return citizen;
    }

    public int births() {
        return births;
    }

    /** Region whose level band fits, weighted; ties broken by the character id so it is stable. */
    String regionFor(int level, int seed) {
        List<Region> fitting = new ArrayList<>();
        int total = 0;
        for (Region region : population.content().regions().values()) {
            if (region.fits(level)) {
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
        int total = 0;
        for (Career career : population.content().careers().values()) {
            total += Math.max(0, career.popularity());
        }
        if (total <= 0) {
            return population.content().careers().values().stream().findFirst().orElse(null);
        }
        int roll = random.nextInt(total);
        for (Career career : population.content().careers().values()) {
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

    private int createCharacter(String name) {
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
            int characterId = BotCreator.createCharacter(creation, name, face, hair, random.nextInt(4), female ? 1 : 0,
                    (female ? FEMALE_TOPS : MALE_TOPS)[random.nextInt(3)], (female ? FEMALE_BOTTOMS : MALE_BOTTOMS)[random.nextInt(2)],
                    SHOES[random.nextInt(SHOES.length)], WEAPONS[random.nextInt(WEAPONS.length)]);
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
