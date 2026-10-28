package server.living.population;

import client.Character;
import config.YamlConfig;
import net.server.Server;
import net.server.coordinator.world.InviteCoordinator;
import net.server.coordinator.world.InviteCoordinator.InviteType;
import net.server.guild.Guild;
import net.server.guild.GuildCharacter;
import net.server.guild.GuildPackets;
import net.server.world.Party;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.party.AgentPartyLifecycleService;
import server.agents.personality.AgentPersonalityProfileRepository;
import server.economy.ledger.MesoAccount;
import server.economy.ledger.MesoFlows;
import server.economy.ledger.MesoHolder;
import server.integration.AgentPresence;
import server.knowledge.PersonalKnowledge;
import server.living.LivingWorld;
import server.living.config.LivingContent;
import server.living.population.CitizenSession.Activity;
import server.living.population.CitizenStore.Acquaintance;
import server.living.population.CitizenStore.Circle;
import server.living.population.CitizenStore.GuildMeta;
import server.living.population.PopulationContent.GuildNames;
import server.living.population.PopulationContent.Lobby;
import server.living.roster.CompanionLeases;
import tools.PacketCreator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;

/**
 * Guilds, buddies, circles that party up, fame (plan 4). Bot guilds are real guilds with a bot
 * leader; membership is written to the characters table for offline citizens and through the
 * normal member path for loaded ones. Humans get one buddy request and one guild invite after
 * enough shared time, never spam.
 */
public final class SocialFabric {
    private static final Logger log = LoggerFactory.getLogger(SocialFabric.class);
    private static final int GUILD_CAPACITY = 30;
    private static final int CIRCLE_MIN = 3;
    private static final int CIRCLE_MAX = 5;
    private static final int GUILD_LEVEL_SPREAD = 15;
    private static final long FAME_COOLDOWN_MS = 24 * 3_600_000L;
    private static final long ACQUAINTANCE_STEP_MS = 60_000L;
    /** Gossip (plan 7.5): per pair and tick, this chance times their tie times their mean sociability. */
    private static final double GOSSIP_CHANCE = 0.05d;
    private static final int GOSSIP_PER_TICK = 24;
    private static final double TIE_PARTY = 1.0d;
    private static final double TIE_CIRCLE = 0.7d;
    private static final double TIE_GUILD = 0.4d;

    private final Population population;
    private final Random random;
    private final Map<Integer, GuildMeta> guilds = new ConcurrentHashMap<>();
    private final Map<Integer, Long> lastFameAtMs = new ConcurrentHashMap<>();
    private final Map<String, Long> acquaintanceTouchedAtMs = new ConcurrentHashMap<>();
    private final CitizenAlliances alliances;
    private long lastPartyFormingMs;
    private boolean emblemsChecked;

    SocialFabric(Population population) {
        this.population = population;
        this.random = new Random(population.config().SEED ^ 0x50c1a1L);
        for (GuildMeta meta : population.store().guilds()) {
            guilds.put(meta.guildId(), meta);
        }
        this.alliances = new CitizenAlliances(population);
    }

    /** Bot guild alliances (docs/BOT_GUILD_ALLIANCES_PLAN.md), checked on this tick. */
    public CitizenAlliances alliances() {
        return alliances;
    }

    /** True for a bot guild (a living_guild row), one a citizen founded; a rival crew's guild too (F7, not a row here). */
    public boolean isBotGuild(int guildId) {
        return guilds.containsKey(guildId) || population.rivals() != null && population.rivals().isCrewGuild(guildId);
    }

    /** The bot guilds' meta rows, by guild id (a live view). */
    Map<Integer, GuildMeta> guildMetas() {
        return Collections.unmodifiableMap(guilds);
    }

    /**
     * Citizens on a PQ session become eligible for the engine's background KPQ/HPQ parties, unless some owner has
     * them (a hire) or the living world has them loaded for anything but the population (a companion, a live bench
     * slice): a background party would otherwise pull them out from under their owner. A quest citizen parties run
     * themselves (CitizenPartyQuests, PQ_RUN_QUESTS) gets none: one system owns a citizen's party quests (live, the
     * background KPQ director took citizens off the roofs of Kerning City, and none of its 291 runs cleared).
     */
    void installSeams() {
        java.util.function.Supplier<java.util.Set<Integer>> pqCitizens = () -> {
            java.util.Set<Integer> ids = new java.util.HashSet<>();
            for (CitizenSession session : population.presence().sessions()) {
                if (!session.virtual && session.activity == Activity.PQ && !spokenFor(session.characterId)) {
                    ids.add(session.characterId);
                }
            }
            return ids;
        };
        server.agents.capabilities.partyquest.kpq.AgentKpqPopulationRuntime.installHostManaged(() -> population.config().ENABLED,
                () -> CitizenPartyQuests.quests(population.config()).contains("kpq") ? java.util.Set.of() : pqCitizens.get());
        server.agents.capabilities.partyquest.hpq.AgentHpqPopulationRuntime.installHostManaged(() -> population.config().ENABLED,
                () -> CitizenPartyQuests.quests(population.config()).contains("hpq") ? java.util.Set.of() : pqCitizens.get());
    }

    /**
     * From memory only, since the background directors ask on the Agent scheduler. A citizen can only be owned as a
     * hire, so the {@code hire:} row is the whole ownership check. A citizen session holds no lease (PresenceScheduler
     * never loads a leased character), so a lease means something else took the character; only the population's own
     * POPULATION kind is let through.
     */
    private boolean spokenFor(int characterId) {
        LivingWorld world = population.world();
        CompanionLeases.Lease lease = world.leases().holder(characterId);
        return (lease != null && lease.kind() != CompanionLeases.Kind.POPULATION)
                || world.roster().cachedOwnerOf(LivingContent.HIRE_PREFIX + characterId) != 0;
    }

    public int sociability(Citizen citizen) {
        if (citizen == null) {
            return 50;
        }
        return AgentPersonalityProfileRepository.defaultRepository().find(citizen.personality())
                .map(profile -> profile.traits().sociability()).orElse(50);
    }

    public String motto(int guildId) {
        GuildMeta meta = guilds.get(guildId);
        return meta == null || meta.motto().isBlank() ? "active and friendly" : meta.motto();
    }

    public int guildCount() {
        return guilds.size();
    }

    // ---- lifecycle -----------------------------------------------------------------------

    /**
     * Its guild entry is bound to the loaded character, as the stock login binds a player's: the bot guilds are cached at
     * boot (emblemOldGuilds), before any citizen loads, so without this a loaded master's entry has no character and a
     * player's alliance invite reads it as "not online".
     */
    public void onLogin(CitizenSession session) {
        Character agent = session.agent();
        if (agent == null || agent.getGuildId() <= 0) {
            return;
        }
        Guild guild = Server.getInstance().getGuild(agent.getGuildId(), population.world().config().WORLD, agent);
        if (guild == null) {
            return;
        }
        GuildCharacter member = guild.getMGC(agent.getId());
        if (member == null) {
            guild.addGuildMember(new GuildCharacter(agent), agent);
        } else {
            member.setCharacter(agent);
            agent.setMGC(member);
        }
    }

    /**
     * Party membership is dropped with the character; its memories of people are written and leave the cache; its
     * guild entry lets go of the unloaded character.
     */
    public void onLogout(CitizenSession session) {
        PersonMemories memories = population.memories();
        if (memories != null) {
            memories.release(session.characterId);
        }
        Character agent = session.agent();
        Guild guild = agent == null || agent.getGuildId() <= 0 ? null : Server.getInstance().getGuild(agent.getGuildId());
        GuildCharacter member = guild == null ? null : guild.getMGC(agent.getId());
        if (member != null && member.getCharacter() == agent) {
            member.setCharacter(null);
        }
    }

    public void onRetire(Citizen citizen) {
        if (citizen.guildId() > 0) {
            population.store().setGuild(citizen.characterId(), 0, 5);
        }
        PersonMemories memories = population.memories();
        if (memories != null) {
            memories.forgetCitizen(citizen.characterId());
        }
    }

    /** Every minute: guilds exist and recruit, circles form and party up, acquaintances, fame. */
    public void tick() {
        long now = System.currentTimeMillis();
        try {
            if (population.config().GUILDS_ENABLED) {
                try {
                    emblemOldGuilds(guildId -> Server.getInstance().getGuild(guildId,
                            population.world().config().WORLD));
                } catch (RuntimeException failure) {
                    // the emblems wait for the next boot; the guilds, circles and the rest of the tick go on
                    log.warn("bot guild emblems could not be checked this boot", failure);
                }
                ensureGuilds();
                recruitCitizens();
                try {
                    alliances.tick(guilds, now);
                } catch (RuntimeException failure) {
                    // the alliances wait for the next check; circles, parties and the rest of the tick go on
                    log.warn("bot guild alliance check failed", failure);
                }
            }
            formCircles();
            partyUp(now);
            acquaintances(now);
            fame(now);
            gossip();
            PersonMemories memories = population.memories();
            if (memories != null) {
                memories.flush(now); // what happened between citizens and players this minute, in one batch
            }
        } catch (RuntimeException failure) {
            log.warn("social tick failed", failure);
        }
    }

    // ---- guilds --------------------------------------------------------------------------

    private void ensureGuilds() {
        while (guilds.size() < population.config().GUILD_TARGET) {
            Citizen leader = null;
            int leaderLevel = 0;
            for (Citizen citizen : population.citizens()) {
                if (citizen.state() != Citizen.State.ACTIVE || citizen.hired() || citizen.guildId() > 0) {
                    continue;
                }
                int level = population.store().characterProgress(citizen.characterId())[0];
                if (level >= 10 && (leader == null || random.nextInt(3) == 0)) {
                    leader = citizen;
                    leaderLevel = level;
                }
            }
            if (leader == null) {
                return;
            }
            GuildNames names = population.content().guildNames();
            NameGenerator generator = new NameGenerator(names.patterns(), names.lists(), List.of(), List.of(), List.of());
            String name = null;
            for (int attempt = 0; attempt < 20 && name == null; attempt++) {
                String candidate = generator.next(random);
                if (candidate.length() >= 3 && candidate.length() <= 12 && Server.getInstance().getGuildByName(candidate) == null) {
                    name = candidate;
                }
            }
            Citizen founder = population.citizen(leader.characterId());
            if (name == null || founder == null || founder.hired()) {
                return; // hired since it was picked: a companion founds no guild
            }
            if (!payFoundingFee(leader.characterId())) {
                return; // it cannot afford a guild yet: another sweep, another founder
            }
            int guildId = Guild.createGuild(leader.characterId(), name);
            if (guildId <= 0) {
                refundFoundingFee(leader.characterId());
                return;
            }
            population.store().setGuild(leader.characterId(), guildId, 1);
            setCapacity(guildId);
            Guild guild = Server.getInstance().getGuild(guildId, population.world().config().WORLD);
            if (guild != null) {
                wearEmblem(guild, names);
            }
            String motto = names.mottos().isEmpty() ? "" : names.mottos().get(random.nextInt(names.mottos().size()));
            GuildMeta meta = new GuildMeta(guildId, "open", Math.max(1, leaderLevel - GUILD_LEVEL_SPREAD),
                    leaderLevel + GUILD_LEVEL_SPREAD, motto, leader.cohort());
            population.store().saveGuild(meta);
            guilds.put(guildId, meta);
            population.update(leader.characterId(), current -> current.withGuild(guildId).withFlag(Citizen.FLAG_RECRUITER));
            log.info("bot guild '{}' ({}) founded by {} lv{}", name, guildId, leader.name(), leaderLevel);
        }
    }

    /**
     * A founder pays what a player pays the guild NPC (server.CREATE_GUILD_COST, economy.CITIZEN_GUILD_FEE): a loaded one
     * from its purse, an offline one in SQL. False when it has less.
     */
    private boolean payFoundingFee(int characterId) {
        int cost = foundingFee();
        if (cost <= 0) {
            return true;
        }
        Character online = onlineCharacter(characterId);
        if (online != null) {
            try (var ledgerScope = MesoFlows.as(MesoAccount.GUILD)) {
                return online.tryPayMeso(cost, false);
            }
        }
        if (!population.store().chargeMeso(characterId, cost)) {
            return false;
        }
        MesoFlows.record(MesoAccount.GUILD, MesoHolder.CITIZEN, -cost);
        return true;
    }

    private void refundFoundingFee(int characterId) {
        int cost = foundingFee();
        if (cost <= 0) {
            return;
        }
        Character online = onlineCharacter(characterId);
        if (online != null) {
            try (var ledgerScope = MesoFlows.as(MesoAccount.GUILD)) {
                online.gainMeso(cost, false);
            }
        } else if (population.store().chargeMeso(characterId, -cost)) {
            MesoFlows.record(MesoAccount.GUILD, MesoHolder.CITIZEN, cost);
        }
    }

    private static int foundingFee() {
        return YamlConfig.config.economy.CITIZEN_GUILD_FEE ? YamlConfig.config.server.CREATE_GUILD_COST : 0;
    }

    private Character onlineCharacter(int characterId) {
        net.server.world.World world = population.gameWorld();
        return world == null ? null : world.getPlayerStorage().getCharacterById(characterId);
    }

    /**
     * Once per boot, before any founding: a bot guild whose emblem the client cannot draw (founded before emblems named
     * real GuildMark.img art: backgrounds 1-18, marks 1-25) wears the emblem its name gives it. A guild already wearing
     * real art keeps it.
     */
    void emblemOldGuilds(IntFunction<Guild> guildById) {
        if (emblemsChecked) {
            return;
        }
        emblemsChecked = true;
        GuildNames names = population.content().guildNames();
        int fixed = 0;
        for (GuildMeta meta : guilds.values()) {
            Guild guild = guildById.apply(meta.guildId());
            if (guild == null || GuildEmblems.isClientArt(guild.getLogoBG(), guild.getLogoBGColor(), guild.getLogo(),
                    guild.getLogoColor())) {
                continue;
            }
            wearEmblem(guild, names);
            fixed++;
        }
        if (fixed > 0) {
            log.info("bot guild emblems: {} of {} bot guilds now wear the emblem their name gives them", fixed, guilds.size());
        }
    }

    private void wearEmblem(Guild guild, GuildNames names) {
        GuildEmblems.Emblem emblem = GuildEmblems.pick(guild.getName(), names.emblems());
        guild.setGuildEmblem((short) emblem.background(), (byte) emblem.backgroundColor(), (short) emblem.mark(),
                (byte) emblem.markColor());
        log.info("bot guild '{}' ({}) emblem: mark {} colour {} on background {} colour {}", guild.getName(), guild.getId(),
                emblem.mark(), emblem.markColor(), emblem.background(), emblem.backgroundColor());
    }

    private void setCapacity(int guildId) {
        try (java.sql.Connection con = tools.DatabaseConnection.getConnection();
             java.sql.PreparedStatement ps = con.prepareStatement("UPDATE guilds SET capacity = ? WHERE guildid = ?")) {
            ps.setInt(1, GUILD_CAPACITY);
            ps.setInt(2, guildId);
            ps.executeUpdate();
        } catch (java.sql.SQLException failure) {
            log.debug("guild capacity update failed for {}", guildId, failure);
        }
    }

    private void recruitCitizens() {
        if (guilds.isEmpty()) {
            return;
        }
        int joined = 0;
        for (Citizen citizen : population.citizens()) {
            if (joined >= 3 || citizen.state() != Citizen.State.ACTIVE || citizen.hired() || citizen.guildId() > 0
                    || random.nextInt(100) >= 20) {
                continue;
            }
            int level = population.store().characterProgress(citizen.characterId())[0];
            List<GuildMeta> fitting = new ArrayList<>();
            for (GuildMeta meta : guilds.values()) {
                if ("closed".equals(meta.stance()) || level < meta.levelMin() || level > meta.levelMax()) {
                    continue;
                }
                if (memberCount(meta.guildId()) >= GUILD_CAPACITY - 2) {
                    continue;
                }
                fitting.add(meta);
                if (meta.cohort().equals(citizen.cohort())) {
                    fitting.add(meta); // same-cohort guilds weigh double
                }
            }
            if (fitting.isEmpty()) {
                continue;
            }
            GuildMeta meta = fitting.get(random.nextInt(fitting.size()));
            join(citizen, meta.guildId());
            joined++;
        }
    }

    private void join(Citizen citizen, int guildId) {
        Citizen current = population.citizen(citizen.characterId());
        if (current == null || current.hired()) {
            return; // hired since the list was made: its guild is its own business now
        }
        population.store().setGuild(citizen.characterId(), guildId, 5);
        population.update(citizen.characterId(), latest -> latest.withGuild(guildId));
        Guild guild = Server.getInstance().getGuild(guildId);
        if (guild != null && guild.getMGC(citizen.characterId()) == null) {
            int[] progress = population.store().characterProgress(citizen.characterId());
            CitizenSession session = population.presence().session(citizen.characterId());
            Character agent = session == null ? null : session.agent();
            if (agent != null) {
                agent.setGuildId(guildId);
                agent.setGuildRank(5);
                agent.setMGC(new GuildCharacter(agent));
                guild.addGuildMember(agent.getMGC(), agent);
            } else {
                guild.addGuildMember(new GuildCharacter(null, citizen.characterId(), progress[0], citizen.name(), (byte) -1,
                        population.world().config().WORLD, progress[2], 5, guildId, false, 5), null);
            }
        }
        log.info("citizen {} joined guild {}", citizen.name(), guildId);
    }

    private int memberCount(int guildId) {
        int count = 0;
        for (Citizen citizen : population.citizens()) {
            if (citizen.guildId() == guildId && citizen.state() == Citizen.State.ACTIVE) {
                count++;
            }
        }
        return count;
    }

    /**
     * GPQ plan decision 8: the master of a citizen guild promotes a human member it knows well to Jr. Master, once, so
     * they can register the guild for the Guild Quest (only a master or Jr. Master may): a human of its own guild below
     * Jr. Master, after GUILD_PROMOTE_AFTER_MINUTES shared minutes, counted as the guild invite counts them.
     */
    void maybePromoteHuman(CitizenSession session, Character human, Acquaintance acquaintance) {
        Character agent = session.agent();
        int minutes = population.config().GUILD_PROMOTE_AFTER_MINUTES;
        if (agent == null || human == null || minutes <= 0 || !population.config().GUILDS_ENABLED
                || agent.getGuildId() <= 0 || agent.getGuildRank() != 1 || human.getGuildId() != agent.getGuildId()
                || human.getGuildRank() <= 2
                || acquaintance.mapMinutes() + acquaintance.partyMinutes() * 2 < minutes) {
            return;
        }
        Server.getInstance().changeRank(agent.getGuildId(), human.getId(), 2);
        population.chatter().whisper(session, human, "you've done a lot for the guild. made you a jr. master, so you "
                + "can sign us up for the guild quest with shuang");
        session.journal.add("social", "made " + human.getName() + " a jr. master");
        log.info("citizen guild master {} promoted {} to Jr. Master in guild {}", session.characterId, human.getId(),
                agent.getGuildId());
    }

    /** A recruiter in a guild invites a human it has been around for a while (once). */
    public void maybeInviteHuman(CitizenSession session, Character human) {
        Character agent = session.agent();
        if (agent == null || human == null || human.getGuildId() > 0 || agent.getGuildId() <= 0 || !population.config().GUILDS_ENABLED) {
            return;
        }
        Acquaintance acquaintance = population.store().acquaintance(session.characterId, human.getId());
        if (acquaintance.guildAsked() || acquaintance.mapMinutes() + acquaintance.partyMinutes() * 2 < population.config().BUDDY_REQUEST_AFTER_MINUTES) {
            return;
        }
        GuildMeta meta = guilds.get(agent.getGuildId());
        if (meta == null || human.getLevel() < meta.levelMin() || human.getLevel() > meta.levelMax()) {
            return;
        }
        if (agent.getClient() != null && InviteCoordinator.createInvite(InviteType.GUILD, agent, agent.getGuildId(), human.getId())) {
            human.sendPacket(GuildPackets.guildInvite(agent.getGuildId(), agent.getName()));
            population.chatter().whisper(session, human, "we could use you in the guild, sent an invite");
            population.store().saveAcquaintance(new Acquaintance(acquaintance.characterId(), acquaintance.playerId(),
                    acquaintance.partyMinutes(), acquaintance.mapMinutes(), acquaintance.buddyAsked(), true));
            session.journal.add("social", "invited " + human.getName() + " to the guild");
        }
    }

    // ---- buddies -------------------------------------------------------------------------

    public int[] onlineBuddyIds(Character agent) {
        World world = population.gameWorld();
        if (world == null || agent == null) {
            return new int[0];
        }
        List<Integer> online = new ArrayList<>();
        for (int buddyId : agent.getBuddylist().getBuddyIds()) {
            Character buddy = world.getPlayerStorage().getCharacterById(buddyId);
            if (buddy != null && !AgentPresence.isAgent(buddy)) {
                online.add(buddyId);
            }
        }
        int[] out = new int[online.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = online.get(i);
        }
        return out;
    }

    /** Buddy greetings on login go to human buddies who are online ("ur on!"). */
    void greetBuddies(CitizenSession session) {
        Character agent = session.agent();
        if (agent == null || !population.config().BUDDIES_ENABLED) {
            return;
        }
        World world = population.gameWorld();
        if (world == null) {
            return;
        }
        for (int buddyId : onlineBuddyIds(agent)) {
            Character buddy = world.getPlayerStorage().getCharacterById(buddyId);
            if (buddy != null && random.nextInt(100) < 60) {
                population.chatter().event(ChatterEngine.Event.of("login.buddy", session.mapId, session.channel).self(session).subject(buddy));
                break;
            }
        }
    }

    void maybeBuddyRequest(CitizenSession session, Character human, Acquaintance acquaintance) {
        Character agent = session.agent();
        if (agent == null || acquaintance.buddyAsked() || !population.config().BUDDIES_ENABLED) {
            return;
        }
        if (acquaintance.partyMinutes() < population.config().BUDDY_REQUEST_AFTER_MINUTES
                && acquaintance.mapMinutes() < population.config().BUDDY_REQUEST_AFTER_MINUTES * 3) {
            return;
        }
        if (human.getBuddylist().containsVisible(agent.getId()) || !server.living.BuddyRequests.ask(agent, human)) {
            return;
        }
        population.store().saveAcquaintance(new Acquaintance(acquaintance.characterId(), acquaintance.playerId(),
                acquaintance.partyMinutes(), acquaintance.mapMinutes(), true, acquaintance.guildAsked()));
        session.journal.add("social", "asked " + human.getName() + " to be buddies");
    }

    // ---- circles and parties -------------------------------------------------------------

    private void formCircles() {
        Map<String, List<Citizen>> loose = new HashMap<>();
        for (Citizen citizen : population.citizens()) {
            if (citizen.state() == Citizen.State.ACTIVE && !citizen.hired() && citizen.circleId() == 0) {
                loose.computeIfAbsent(citizen.cohort(), k -> new ArrayList<>()).add(citizen);
            }
        }
        for (Map.Entry<String, List<Citizen>> entry : loose.entrySet()) {
            List<Citizen> pool = entry.getValue();
            while (pool.size() >= CIRCLE_MIN) {
                int size = Math.min(pool.size(), CIRCLE_MIN + random.nextInt(CIRCLE_MAX - CIRCLE_MIN + 1));
                List<Citizen> members = new ArrayList<>(pool.subList(0, size));
                pool.subList(0, size).clear();
                int circleId = population.store().createCircle(members.get(0).name() + "'s crew", entry.getKey());
                if (circleId <= 0) {
                    return;
                }
                List<Citizen> joined = new ArrayList<>();
                for (Citizen member : members) {
                    Citizen now = population.update(member.characterId(), current -> current.hired() ? current
                            : current.withCircle(circleId));
                    if (now != null && now.circleId() == circleId) {
                        joined.add(now); // one hired since the pool was made keeps out of circles
                    }
                }
                for (Citizen member : joined) {
                    for (Citizen other : joined) {
                        if (other.characterId() < member.characterId()) {
                            population.store().addBuddyPair(member.characterId(), other.characterId());
                        }
                    }
                }
                log.info("circle {} formed: {}", circleId, joined.stream().map(Citizen::name).toList());
            }
        }
    }

    /**
     * Circle-mates loaded on the same map with no party form one and train or PQ together. A lobby whose quest citizens
     * run themselves is CitizenPartyQuests' own, and so is a citizen in a run or back from one.
     */
    private void partyUp(long now) {
        if (now - lastPartyFormingMs < 120_000L) {
            return;
        }
        lastPartyFormingMs = now;
        CitizenPartyQuests partyQuests = population.partyQuests();
        Map<String, List<CitizenSession>> groups = new LinkedHashMap<>();
        for (CitizenSession session : population.presence().sessions()) {
            Character agent = session.agent();
            if (session.virtual || agent == null || session.citizen.circleId() == 0 || agent.getParty() != null
                    || partyQuests != null && partyQuests.manages(session)) {
                continue;
            }
            groups.computeIfAbsent(session.citizen.circleId() + ":" + session.channel + ":" + session.mapId, k -> new ArrayList<>()).add(session);
        }
        for (List<CitizenSession> group : groups.values()) {
            if (group.size() < 2) {
                continue;
            }
            CitizenSession leader = group.get(0);
            Character leaderChr = leader.agent();
            for (int i = 1; i < group.size() && i < 6; i++) {
                Character member = group.get(i).agent();
                if (member != null) {
                    AgentPartyLifecycleService.joinAgentToLeaderParty(leaderChr, member);
                }
            }
            Party party = leaderChr.getParty();
            if (party == null) {
                continue;
            }
            for (CitizenSession session : group) {
                session.partyId = party.getId();
                session.journal.add("party", "partied with " + leader.name() + "'s crew");
            }
            population.chatter().event(ChatterEngine.Event.of("party.forming", leader.mapId, leader.channel).self(leader));
            Lobby lobby = population.fields().lobbyAt(leader.mapId);
            if (lobby != null) {
                inviteHumanToParty(leader, party, lobby);
            } else if (leader.activity != Activity.TRAIN) {
                int mapId = population.presence().mapFor(leader, Activity.TRAIN);
                for (CitizenSession session : group) {
                    population.presence().travel(session, Activity.TRAIN, mapId, now);
                }
            }
        }
    }

    private void inviteHumanToParty(CitizenSession leader, Party party, Lobby lobby) {
        Character leaderChr = leader.agent();
        if (leaderChr == null || leaderChr.getMap() == null || party.getMembers().size() >= 6) {
            return;
        }
        for (Character chr : leaderChr.getMap().getAllPlayers()) {
            if (AgentPresence.isAgent(chr) || chr.getParty() != null || chr.getLevel() < lobby.levelMin() || chr.getLevel() > lobby.levelMax()) {
                continue;
            }
            if (InviteCoordinator.createInvite(InviteType.PARTY, leaderChr, party.getId(), chr.getId())) {
                chr.sendPacket(PacketCreator.partyInvite(leaderChr));
                population.chatter().event(ChatterEngine.Event.of("party.invite", leader.mapId, leader.channel).self(leader).subject(chr));
                leader.journal.add("social", "invited " + chr.getName() + " to " + lobby.name());
            }
            return;
        }
    }

    // ---- acquaintances -------------------------------------------------------------------

    /**
     * Shared minutes with humans on the same map / in the same party drive buddy requests and guild invites. Each
     * minute together is also a sighting for the citizen's memory of them (before the minutes move {@code last_at}: a
     * player back after hours apart is greeted), and a minute in one party a minute of their party stint.
     */
    void acquaintances(long now) {
        PersonMemories memories = population.memories();
        for (CitizenSession session : population.presence().sessions()) {
            Character agent = session.agent();
            if (session.virtual || agent == null || agent.getMap() == null) {
                continue;
            }
            for (Character chr : agent.getMap().getAllPlayers()) {
                if (AgentPresence.isAgent(chr)) {
                    continue;
                }
                String key = session.characterId + ":" + chr.getId();
                Long touched = acquaintanceTouchedAtMs.get(key);
                if (touched != null && now - touched < ACQUAINTANCE_STEP_MS) {
                    continue;
                }
                acquaintanceTouchedAtMs.put(key, now);
                if (memories != null) {
                    memories.seen(session, chr, now);
                }
                Acquaintance current = population.store().acquaintance(session.characterId, chr.getId());
                boolean sameParty = agent.getParty() != null && chr.getParty() != null && agent.getParty().getId() == chr.getParty().getId();
                if (sameParty && memories != null) {
                    memories.record(session.characterId, chr.getId(), PersonMemory.Kind.PARTY, agent.getMapId(), "", 1);
                }
                Acquaintance updated = new Acquaintance(current.characterId(), current.playerId(),
                        current.partyMinutes() + (sameParty ? 1 : 0), current.mapMinutes() + 1, current.buddyAsked(), current.guildAsked());
                population.store().saveAcquaintance(updated);
                maybeBuddyRequest(session, chr, updated);
                if (session.citizen.has(Citizen.FLAG_RECRUITER)) {
                    maybeInviteHuman(session, chr);
                }
                maybePromoteHuman(session, chr, updated);
            }
        }
    }

    // ---- fame ----------------------------------------------------------------------------

    private void fame(long now) {
        for (CitizenSession session : population.presence().sessions()) {
            Character agent = session.agent();
            if (session.virtual || agent == null || agent.getLevel() < 15 || agent.getMap() == null) {
                continue;
            }
            Long last = lastFameAtMs.get(session.characterId);
            if (last != null && now - last < FAME_COOLDOWN_MS || random.nextInt(100) >= 3) {
                continue;
            }
            Character target = null;
            for (Character chr : agent.getMap().getAllPlayers()) {
                if (chr.getId() == agent.getId() || chr.getLevel() < 15) {
                    continue;
                }
                boolean buddy = agent.getBuddylist().containsVisible(chr.getId());
                boolean guildmate = agent.getGuildId() > 0 && agent.getGuildId() == chr.getGuildId();
                if (buddy || guildmate || session.citizen.has(Citizen.FLAG_FAMER) && !AgentPresence.isAgent(chr)) {
                    target = chr;
                    break;
                }
            }
            if (target == null) {
                continue;
            }
            target.gainFame(1);
            lastFameAtMs.put(session.characterId, now);
            session.journal.add("social", "famed " + target.getName());
            if (!AgentPresence.isAgent(target)) {
                target.dropMessage(5, agent.getName() + " has raised your fame.");
                if (session.citizen.has(Citizen.FLAG_FAMER)) {
                    population.chatter().whisper(session, target, "famed u, fame back?");
                }
            }
        }
    }

    // ---- gossip --------------------------------------------------------------------------

    /** Who a citizen is to the others online: its live party, circle, guild, and where it stands. */
    record Ties(int id, int party, int circle, int guild, int channel, int map, int sociability) { }

    /**
     * Party, circle and guild mates online together pass first-hand facts silently (docs/SERVER_KNOWLEDGE_PLAN.md 7.5): a
     * training spot and its mob, a mob and what it drops, an item and who drops it. A party is the closest tie, a circle on
     * the same map and channel next, a guild (whose chat crosses maps) the loosest; at most {@value #GOSSIP_PER_TICK} a tick.
     */
    private void gossip() {
        PersonalKnowledge personal = PersonalKnowledge.shared();
        if (!personal.on()) {
            return;
        }
        Map<String, List<Ties>> groups = new HashMap<>();
        for (CitizenSession session : population.presence().sessions()) {
            Character agent = session.agent();
            if (session.virtual || agent == null || agent.getMap() == null || session.activity == Activity.AFK) {
                continue;
            }
            Ties ties = new Ties(session.characterId, agent.getParty() == null ? 0 : agent.getParty().getId(),
                    session.citizen.circleId(), agent.getGuildId(), session.channel, agent.getMapId(), sociability(session.citizen));
            if (ties.party() != 0) {
                groups.computeIfAbsent("p" + ties.party(), key -> new ArrayList<>()).add(ties);
            }
            if (ties.circle() != 0) {
                groups.computeIfAbsent("c" + ties.circle() + ":" + ties.channel() + ":" + ties.map(), key -> new ArrayList<>()).add(ties);
            }
            if (ties.guild() != 0) {
                groups.computeIfAbsent("g" + ties.guild(), key -> new ArrayList<>()).add(ties);
            }
        }
        Set<Long> paired = new HashSet<>();
        int transfers = 0;
        for (List<Ties> group : groups.values()) {
            Collections.shuffle(group, random);
            for (int i = 0; i < group.size(); i++) {
                for (int j = i + 1; j < group.size(); j++) {
                    Ties a = group.get(i);
                    Ties b = group.get(j);
                    if (!paired.add((long) Math.min(a.id(), b.id()) << 32 | Math.max(a.id(), b.id()))) {
                        continue; // a pair in two groups is drawn once, at its closest tie
                    }
                    if (random.nextDouble() >= GOSSIP_CHANCE * tie(a, b) * (a.sociability() + b.sociability()) / 200d) {
                        continue;
                    }
                    boolean aTells = random.nextBoolean();
                    if (personal.gossip(aTells ? a.id() : b.id(), aTells ? b.id() : a.id(), random.nextLong())
                            && ++transfers >= GOSSIP_PER_TICK) {
                        return;
                    }
                }
            }
        }
    }

    /** The closest tie two citizens share: party 1.0, circle on the same map and channel 0.7, guild 0.4, else 0. */
    static double tie(Ties a, Ties b) {
        if (a.party() != 0 && a.party() == b.party()) {
            return TIE_PARTY;
        }
        if (a.circle() != 0 && a.circle() == b.circle() && a.channel() == b.channel() && a.map() == b.map()) {
            return TIE_CIRCLE;
        }
        return a.guild() != 0 && a.guild() == b.guild() ? TIE_GUILD : 0d;
    }

    public List<Circle> circles() {
        return population.store().circles();
    }
}
