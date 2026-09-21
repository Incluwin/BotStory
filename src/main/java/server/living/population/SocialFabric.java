package server.living.population;

import client.Character;
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
import server.integration.AgentPresence;
import server.living.population.CitizenSession.Activity;
import server.living.population.CitizenStore.Acquaintance;
import server.living.population.CitizenStore.Circle;
import server.living.population.CitizenStore.GuildMeta;
import server.living.population.PopulationContent.GuildNames;
import server.living.population.PopulationContent.Lobby;
import tools.PacketCreator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

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

    private final Population population;
    private final Random random;
    private final Map<Integer, GuildMeta> guilds = new ConcurrentHashMap<>();
    private final Map<Integer, Long> lastFameAtMs = new ConcurrentHashMap<>();
    private final Map<String, Long> acquaintanceTouchedAtMs = new ConcurrentHashMap<>();
    private long lastPartyFormingMs;

    SocialFabric(Population population) {
        this.population = population;
        this.random = new Random(population.config().SEED ^ 0x50c1a1L);
        for (GuildMeta meta : population.store().guilds()) {
            guilds.put(meta.guildId(), meta);
        }
    }

    void installSeams() {
        // nothing to install yet; the operator grant for companions stays with the roster
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

    public void onLogin(CitizenSession session) {
        Character agent = session.agent();
        if (agent == null || agent.getGuildId() <= 0) {
            return;
        }
        Guild guild = Server.getInstance().getGuild(agent.getGuildId(), population.world().config().WORLD, agent);
        if (guild != null && guild.getMGC(agent.getId()) == null) {
            guild.addGuildMember(new GuildCharacter(agent), agent);
        }
    }

    public void onLogout(CitizenSession session) {
        // party membership is dropped with the character; nothing else to do
    }

    public void onRetire(Citizen citizen) {
        if (citizen.guildId() > 0) {
            population.store().setGuild(citizen.characterId(), 0, 5);
        }
    }

    /** Every minute: guilds exist and recruit, circles form and party up, acquaintances, fame. */
    public void tick() {
        long now = System.currentTimeMillis();
        try {
            if (population.config().GUILDS_ENABLED) {
                ensureGuilds();
                recruitCitizens();
            }
            formCircles();
            partyUp(now);
            acquaintances(now);
            fame(now);
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
                if (citizen.state() != Citizen.State.ACTIVE || citizen.guildId() > 0) {
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
            if (name == null) {
                return;
            }
            int guildId = Guild.createGuild(leader.characterId(), name);
            if (guildId <= 0) {
                return;
            }
            population.store().setGuild(leader.characterId(), guildId, 1);
            setCapacity(guildId);
            if (!names.backgrounds().isEmpty() && !names.logos().isEmpty() && !names.colors().isEmpty()) {
                Guild guild = Server.getInstance().getGuild(guildId, population.world().config().WORLD);
                if (guild != null) {
                    guild.setGuildEmblem((short) pick(names.backgrounds()), (byte) pick(names.colors()),
                            (short) pick(names.logos()), (byte) pick(names.colors()));
                }
            }
            String motto = names.mottos().isEmpty() ? "" : names.mottos().get(random.nextInt(names.mottos().size()));
            GuildMeta meta = new GuildMeta(guildId, "open", Math.max(1, leaderLevel - GUILD_LEVEL_SPREAD),
                    leaderLevel + GUILD_LEVEL_SPREAD, motto, leader.cohort());
            population.store().saveGuild(meta);
            guilds.put(guildId, meta);
            population.save(leader.withGuild(guildId).withFlag(Citizen.FLAG_RECRUITER));
            log.info("bot guild '{}' ({}) founded by {} lv{}", name, guildId, leader.name(), leaderLevel);
        }
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
            if (joined >= 3 || citizen.state() != Citizen.State.ACTIVE || citizen.guildId() > 0 || random.nextInt(100) >= 20) {
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
        population.store().setGuild(citizen.characterId(), guildId, 5);
        population.save(citizen.withGuild(guildId));
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

    private void maybeBuddyRequest(CitizenSession session, Character human, Acquaintance acquaintance) {
        Character agent = session.agent();
        if (agent == null || acquaintance.buddyAsked() || !population.config().BUDDIES_ENABLED) {
            return;
        }
        if (acquaintance.partyMinutes() < population.config().BUDDY_REQUEST_AFTER_MINUTES
                && acquaintance.mapMinutes() < population.config().BUDDY_REQUEST_AFTER_MINUTES * 3) {
            return;
        }
        if (human.getBuddylist().containsVisible(agent.getId()) || human.getClient() == null) {
            return;
        }
        human.getBuddylist().addBuddyRequest(human.getClient(), agent.getId(), agent.getName(), session.channel);
        population.store().saveAcquaintance(new Acquaintance(acquaintance.characterId(), acquaintance.playerId(),
                acquaintance.partyMinutes(), acquaintance.mapMinutes(), true, acquaintance.guildAsked()));
        session.journal.add("social", "asked " + human.getName() + " to be buddies");
    }

    // ---- circles and parties -------------------------------------------------------------

    private void formCircles() {
        Map<String, List<Citizen>> loose = new HashMap<>();
        for (Citizen citizen : population.citizens()) {
            if (citizen.state() == Citizen.State.ACTIVE && citizen.circleId() == 0) {
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
                for (Citizen member : members) {
                    population.save(member.withCircle(circleId));
                    for (Citizen other : members) {
                        if (other.characterId() < member.characterId()) {
                            population.store().addBuddyPair(member.characterId(), other.characterId());
                        }
                    }
                }
                log.info("circle {} formed: {}", circleId, members.stream().map(Citizen::name).toList());
            }
        }
    }

    /** Circle-mates loaded on the same map with no party form one and train or PQ together. */
    private void partyUp(long now) {
        if (now - lastPartyFormingMs < 120_000L) {
            return;
        }
        lastPartyFormingMs = now;
        Map<String, List<CitizenSession>> groups = new LinkedHashMap<>();
        for (CitizenSession session : population.presence().sessions()) {
            Character agent = session.agent();
            if (session.virtual || agent == null || session.citizen.circleId() == 0 || agent.getParty() != null) {
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

    /** Shared minutes with humans on the same map / in the same party drive buddy requests and guild invites. */
    private void acquaintances(long now) {
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
                Acquaintance current = population.store().acquaintance(session.characterId, chr.getId());
                boolean sameParty = agent.getParty() != null && chr.getParty() != null && agent.getParty().getId() == chr.getParty().getId();
                Acquaintance updated = new Acquaintance(current.characterId(), current.playerId(),
                        current.partyMinutes() + (sameParty ? 1 : 0), current.mapMinutes() + 1, current.buddyAsked(), current.guildAsked());
                population.store().saveAcquaintance(updated);
                maybeBuddyRequest(session, chr, updated);
                if (session.citizen.has(Citizen.FLAG_RECRUITER)) {
                    maybeInviteHuman(session, chr);
                }
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

    private int pick(List<Integer> values) {
        return values.get(random.nextInt(values.size()));
    }

    public List<Circle> circles() {
        return population.store().circles();
    }
}
