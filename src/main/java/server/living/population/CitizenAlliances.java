package server.living.population;

import client.Character;
import config.PopulationConfig;
import net.server.Server;
import net.server.coordinator.world.InviteCoordinator;
import net.server.coordinator.world.InviteCoordinator.InviteType;
import net.server.guild.Alliance;
import net.server.guild.Guild;
import net.server.guild.GuildCharacter;
import net.server.guild.GuildPackets;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;
import server.integration.AgentPresence;
import server.living.population.CitizenStore.GuildMeta;
import server.living.population.GuildTies.Side;
import server.living.population.PopulationContent.Alliances;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bot guild alliances (docs/BOT_GUILD_ALLIANCES_PLAN.md): citizen guilds found, join and leave stock alliances by how
 * close they are ({@link GuildTies}), written through the stock alliance code server-side as their guilds were founded,
 * with Lenario's fees from the paying master's purse; a citizen alliance invites a player's guild its citizens know; a
 * loaded citizen master answers a player's invite like a player. What they decide shows in the stock alliance window, in
 * the stock system lines and in the citizens' own lines in alliance chat. Citizens never expel a player's guild and never
 * leave an alliance a player leads. Runs on the social tick, every ALLIANCE_CHECK_MINUTES; a player's invite is answered
 * on the low-priority timer.
 */
public final class CitizenAlliances {
    private static final Logger log = LoggerFactory.getLogger(CitizenAlliances.class);
    /** Lenario's fees and limit (scripts/npc/2010009.js: allianceCost, increaseCost, allianceLimit). */
    static final long FOUND_FEE = 2_000_000L;
    static final long SLOT_FEE = 1_000_000L;
    static final int MAX_GUILDS = 5;
    static final String MARK_LOW = "low";
    static final String MARK_ASKED = "asked";
    static final long DAY_MS = 86_400_000L;
    /** A player's invite is answered after this moment, as a player reads it. */
    static final long ANSWER_DELAY_MS = 4_000L;
    /** A leaving guild leaves this long after its citizen's goodbye reached alliance chat. */
    static final long LEAVE_AFTER_LINE_MS = 1_500L;

    private final Population population;
    private final Random random;
    /** Per alliance id, the guilds it held at the last check, so a guild new in it is welcomed once. */
    private final Map<Integer, Set<Integer>> knownGuilds = new ConcurrentHashMap<>();
    private long lastCheckMs;
    private boolean reported;

    CitizenAlliances(Population population) {
        this.population = population;
        this.random = new Random(population.config().SEED ^ 0xa11a1L);
    }

    /** What the check sees of one alliance: its citizen guilds, its other guilds, and which guild leads. */
    record Union(Alliance alliance, int leaderGuildId, List<Integer> citizenGuilds, List<Integer> otherGuilds) {
        boolean citizenLed() {
            return citizenGuilds.contains(leaderGuildId);
        }

        int size() {
            return citizenGuilds.size() + otherGuilds.size();
        }
    }

    // ---- the check -----------------------------------------------------------------------

    /** On the social tick (GUILDS_ENABLED): the alliance check every ALLIANCE_CHECK_MINUTES. */
    void tick(Map<Integer, GuildMeta> guilds, long now) {
        PopulationConfig config = population.config();
        if (!config.ALLIANCES_ENABLED || now - lastCheckMs < Math.max(1, config.ALLIANCE_CHECK_MINUTES) * 60_000L) {
            return;
        }
        lastCheckMs = now;
        Map<Integer, Side> sides = sides(guilds);
        Map<Integer, Union> unions = unions(sides.keySet());
        welcome(unions, sides);
        leave(unions, sides, now);
        unions = unions(sides.keySet());
        boolean joined = join(unions, sides);
        if (!joined) {
            found(unions, sides);
        }
        invitePlayerGuild(unions(sides.keySet()), sides, now);
        if (!reported) {
            reported = true;
            log.info("bot guild alliances: {} citizen-led alliances, {} of {} citizen guilds allied",
                    unions(sides.keySet()).values().stream().filter(Union::citizenLed).count(),
                    sides.keySet().stream().filter(id -> allianceOf(id) > 0).count(), sides.size());
        }
    }

    /**
     * Every citizen guild that takes part (its master an active citizen nobody hired, with a guild entry the stock
     * alliance code can rank), with its active members.
     */
    private Map<Integer, Side> sides(Map<Integer, GuildMeta> guilds) {
        Map<Integer, List<Citizen>> members = new HashMap<>();
        for (Citizen citizen : population.citizens()) {
            if (citizen.state() == Citizen.State.ACTIVE && citizen.guildId() > 0) {
                members.computeIfAbsent(citizen.guildId(), id -> new ArrayList<>()).add(citizen);
            }
        }
        Map<Integer, Side> sides = new LinkedHashMap<>();
        for (GuildMeta meta : guilds.values()) {
            Guild guild = guild(meta.guildId());
            Citizen master = guild == null || guild.getMGC(guild.getLeaderId()) == null ? null
                    : population.citizen(guild.getLeaderId());
            if (master != null && master.state() == Citizen.State.ACTIVE && !master.hired()) {
                sides.put(meta.guildId(), new Side(meta, members.getOrDefault(meta.guildId(), List.of())));
            }
        }
        return sides;
    }

    /** The alliances citizen guilds are in, by alliance id, each loaded as a member's login would load it. */
    private Map<Integer, Union> unions(Set<Integer> citizenGuildIds) {
        Map<Integer, Union> unions = new LinkedHashMap<>();
        for (int guildId : citizenGuildIds) {
            int allianceId = allianceOf(guildId);
            if (allianceId <= 0 || unions.containsKey(allianceId)) {
                continue;
            }
            Alliance alliance = loadAlliance(allianceId, guildId);
            if (alliance == null) {
                continue;
            }
            List<Integer> citizens = new ArrayList<>();
            List<Integer> others = new ArrayList<>();
            int leader = 0;
            for (int member : alliance.getGuilds()) {
                Guild guild = guild(member);
                if (guild == null) {
                    continue;
                }
                GuildCharacter master = guild.getMGC(guild.getLeaderId());
                if (master != null && master.getAllianceRank() == 1) {
                    leader = member;
                }
                (citizenGuildIds.contains(member) ? citizens : others).add(member);
            }
            unions.put(allianceId, new Union(alliance, leader, citizens, others));
        }
        return unions;
    }

    /** A guild new in an alliance with citizens in it hears a citizen's welcome (none on the first check of a boot). */
    private void welcome(Map<Integer, Union> unions, Map<Integer, Side> sides) {
        for (Union union : unions.values()) {
            Set<Integer> before = remember(union.alliance());
            if (before == null) {
                continue;
            }
            for (int guildId : union.alliance().getGuilds()) {
                if (!before.contains(guildId)) {
                    sayWelcome(union, guildId);
                }
            }
        }
    }

    /** Records the guilds the alliance holds now; returns what it held at the last record, or null when never seen. */
    private Set<Integer> remember(Alliance alliance) {
        Set<Integer> now = ConcurrentHashMap.newKeySet();
        now.addAll(alliance.getGuilds());
        return knownGuilds.put(alliance.getId(), now);
    }

    /**
     * In a citizen-led alliance, a citizen guild (not the leader's) whose mean tie to its other citizen guilds stays
     * under ALLIANCE_LEAVE_TIE for ALLIANCE_LEAVE_AFTER_DAYS says so and leaves; one guild left breaks it up. A tie back
     * over the floor ends the low spell.
     */
    private void leave(Map<Integer, Union> unions, Map<Integer, Side> sides, long now) {
        PopulationConfig config = population.config();
        for (Union union : unions.values()) {
            if (!union.citizenLed()) {
                continue; // a player leads it: citizens stay
            }
            for (int guildId : union.citizenGuilds()) {
                if (guildId == union.leaderGuildId()) {
                    continue;
                }
                double tie = meanTie(sides, guildId, union.citizenGuilds());
                long since = population.store().allianceMark(guildId, MARK_LOW);
                if (tie >= config.ALLIANCE_LEAVE_TIE) {
                    if (since > 0) {
                        population.store().setAllianceMark(guildId, MARK_LOW, 0);
                    }
                } else if (since <= 0) {
                    population.store().setAllianceMark(guildId, MARK_LOW, now);
                } else if (now - since >= config.ALLIANCE_LEAVE_AFTER_DAYS * DAY_MS) {
                    population.store().setAllianceMark(guildId, MARK_LOW, 0);
                    leaveAlliance(union, guildId, tie);
                    return; // one move a check; the next check reads the alliance afresh
                }
            }
        }
    }

    /**
     * Its citizen says so in alliance chat first; the guild leaves once the line is out (the stock "[guild] guild has
     * left the union." follows it), or at once when none of its citizens is loaded.
     */
    private void leaveAlliance(Union union, int guildId, double tie) {
        Guild guild = guild(guildId);
        if (guild == null) {
            return;
        }
        CitizenSession speaker = speaker(List.of(guildId));
        String line = speaker == null ? null : line("leave", union, guild.getName(), null);
        if (line == null) {
            completeLeave(union.alliance(), guild, tie);
            return;
        }
        long delayMs = population.chatter().allianceSay(speaker, union.alliance().getId(), line);
        TimerManager.getInstance().schedule(TimerManager.SchedulerLane.LOW_PRIORITY, () -> {
            try {
                completeLeave(union.alliance(), guild, tie);
            } catch (RuntimeException failure) {
                log.warn("bot guild '{}' could not leave its alliance", guild.getName(), failure);
            }
        }, delayMs + LEAVE_AFTER_LINE_MS);
    }

    private void completeLeave(Alliance alliance, Guild guild, double tie) {
        int guildId = guild.getId();
        if (guild.getAllianceId() != alliance.getId()) {
            return; // it left (or was expelled) while its line went out
        }
        int world = population.world().config().WORLD;
        if (!Alliance.removeGuildFromAlliance(alliance.getId(), guildId, world)) {
            return;
        }
        alliance.saveToDB();
        Set<Integer> known = knownGuilds.get(alliance.getId());
        if (known != null) {
            known.remove(guildId);
        }
        log.info("bot guild alliances: '{}' ({}) left '{}' ({}), its tie to the rest {} under {} for {} days",
                guild.getName(), guildId, alliance.getName(), alliance.getId(), String.format("%.2f", tie),
                population.config().ALLIANCE_LEAVE_TIE, population.config().ALLIANCE_LEAVE_AFTER_DAYS);
        if (alliance.getGuilds().size() <= 1) {
            log.info("bot guild alliances: '{}' ({}) broke up, one guild left", alliance.getName(), alliance.getId());
            Alliance.disbandAlliance(alliance.getId());
            knownGuilds.remove(alliance.getId());
        }
    }

    /**
     * An unallied citizen guild whose mean tie to a citizen-led alliance's citizen guilds reaches ALLIANCE_FOUND_TIE
     * joins the closest such alliance (one a check); a full alliance under five buys a slot first, from its leader's
     * master. True when one joined.
     */
    private boolean join(Map<Integer, Union> unions, Map<Integer, Side> sides) {
        double floor = population.config().ALLIANCE_FOUND_TIE;
        Union best = null;
        int bestGuild = 0;
        double bestTie = floor;
        for (int guildId : sides.keySet()) {
            if (allianceOf(guildId) > 0) {
                continue;
            }
            for (Union union : unions.values()) {
                if (!union.citizenLed() || union.size() >= MAX_GUILDS) {
                    continue;
                }
                double tie = meanTie(sides, guildId, union.citizenGuilds());
                if (tie >= bestTie) {
                    best = union;
                    bestGuild = guildId;
                    bestTie = tie;
                }
            }
        }
        if (best == null || !roomFor(best)) {
            return false;
        }
        Guild guild = guild(bestGuild);
        if (guild == null || !addGuild(best.alliance(), guild)) {
            return false;
        }
        log.info("bot guild alliances: '{}' ({}) joined '{}' ({}), tie {}", guild.getName(), bestGuild,
                best.alliance().getName(), best.alliance().getId(), String.format("%.2f", bestTie));
        remember(best.alliance());
        sayWelcome(best, bestGuild);
        return true;
    }

    /**
     * While fewer than ALLIANCE_TARGET citizen-led alliances stand, the closest pair of unallied citizen guilds at
     * ALLIANCE_FOUND_TIE or more founds one (one a check). The bigger guild leads and its master pays Lenario's fee; when
     * it cannot, the other leads if its master can; a pair neither can pay for waits, and the next closest pair is tried.
     */
    private void found(Map<Integer, Union> unions, Map<Integer, Side> sides) {
        long led = unions.values().stream().filter(Union::citizenLed).count();
        if (led >= population.config().ALLIANCE_TARGET) {
            return;
        }
        List<Integer> free = new ArrayList<>();
        for (int guildId : sides.keySet()) {
            if (allianceOf(guildId) <= 0) {
                free.add(guildId);
            }
        }
        for (Pair pair : foundingPairs(sides, free, population.config().ALLIANCE_FOUND_TIE)) {
            Guild lead = guild(pair.lead());
            Guild other = guild(pair.other());
            if (lead == null || other == null) {
                continue;
            }
            if (!pay(lead.getLeaderId(), FOUND_FEE)) {
                if (!pay(other.getLeaderId(), FOUND_FEE)) {
                    continue; // neither master has Lenario's fee yet: this pair waits
                }
                Guild swap = lead;
                lead = other;
                other = swap;
            }
            Alliance alliance = foundAlliance(lead, other);
            if (alliance == null) {
                refund(lead.getLeaderId(), FOUND_FEE);
                return;
            }
            log.info("bot guild alliances: '{}' founded by '{}' ({}) and '{}' ({}), tie {}", alliance.getName(),
                    lead.getName(), lead.getId(), other.getName(), other.getId(), String.format("%.2f", pair.tie()));
            remember(alliance);
            Union union = new Union(alliance, lead.getId(), List.of(lead.getId(), other.getId()), List.of());
            CitizenSession speaker = speaker(List.of(lead.getId(), other.getId()));
            if (speaker != null) {
                Guild own = guild(speaker.citizen.guildId());
                say(speaker, union, "founded", own == null ? lead.getName() : own.getName(),
                        own != null && own.getId() == other.getId() ? lead.getName() : other.getName());
            }
            return;
        }
    }

    /**
     * A citizen-led alliance with a free (or buyable) slot invites the unallied player guild whose players spent
     * ALLIANCE_PLAYER_MINUTES or more with its citizens, through the stock invite from one of its loaded citizen masters
     * to that guild's online master, once per ALLIANCE_REASK_DAYS (one invite a check).
     */
    private void invitePlayerGuild(Map<Integer, Union> unions, Map<Integer, Side> sides, long now) {
        PopulationConfig config = population.config();
        for (Union union : unions.values()) {
            if (!union.citizenLed() || union.size() >= MAX_GUILDS) {
                continue;
            }
            CitizenSession inviter = invitingMaster(union);
            if (inviter == null) {
                continue;
            }
            Map<Integer, Integer> minutes = population.store().playerGuildMinutes(union.citizenGuilds());
            for (Map.Entry<Integer, Integer> entry : minutes.entrySet()) {
                int guildId = entry.getKey();
                if (entry.getValue() < config.ALLIANCE_PLAYER_MINUTES || population.social().isBotGuild(guildId)) {
                    continue;
                }
                Guild target = Server.getInstance().getGuild(guildId);
                Character master = target == null ? null : onlineCharacter(target.getLeaderId());
                if (target == null || target.getAllianceId() > 0 || master == null || AgentPresence.isAgent(master)) {
                    continue;
                }
                long asked = population.store().allianceMark(guildId, MARK_ASKED);
                if (asked > 0 && now - asked < config.ALLIANCE_REASK_DAYS * DAY_MS) {
                    continue;
                }
                if (!roomFor(union)) {
                    return;
                }
                Character agent = inviter.agent();
                if (agent == null || !InviteCoordinator.createInvite(InviteType.ALLIANCE, agent, union.alliance().getId(),
                        master.getId())) {
                    continue; // its master is managing another invite: next check
                }
                master.sendPacket(GuildPackets.allianceInvite(union.alliance().getId(), agent));
                population.store().setAllianceMark(guildId, MARK_ASKED, now);
                Guild own = guild(inviter.citizen.guildId());
                String line = line("invite", union, own == null ? "" : own.getName(), target.getName());
                if (line != null) {
                    population.chatter().whisper(inviter, master, line);
                }
                inviter.journal.add("social", "invited " + target.getName() + " to " + union.alliance().getName());
                log.info("bot guild alliances: '{}' ({}) invited player guild '{}' ({}) after {} shared minutes",
                        union.alliance().getName(), union.alliance().getId(), target.getName(), guildId, entry.getValue());
                return;
            }
        }
    }

    // ---- a player's invite ---------------------------------------------------------------

    /**
     * The stock invite reached a loaded citizen guild master ({@code master}, PopulationHooks.allianceInvited): after a
     * moment it takes it when its guild knows the inviting alliance well enough (its citizens spent
     * ALLIANCE_PLAYER_MINUTES with the inviting guild's players, or its tie to a citizen guild in the alliance reaches
     * ALLIANCE_FOUND_TIE), else it declines the stock way and whispers why. A master that is not a citizen, a hired one,
     * or the kill switch leaves the invite to expire as stock.
     */
    public void invited(Character inviter, Character master, int allianceId) {
        CitizenSession session = population.presence().sessionOf(master);
        if (session == null || session.virtual || session.citizen.hired() || !population.config().GUILDS_ENABLED
                || !population.config().ALLIANCES_ENABLED || !population.social().isBotGuild(master.getGuildId())) {
            return;
        }
        TimerManager.getInstance().schedule(TimerManager.SchedulerLane.LOW_PRIORITY, () -> {
            try {
                answer(session, inviter, master, allianceId);
            } catch (RuntimeException failure) {
                log.warn("bot guild alliance answer failed for {}", master.getName(), failure);
            }
        }, ANSWER_DELAY_MS);
    }

    /**
     * A guild took a stock invite into an alliance with a bot guild in it (most often a player's guild taking a citizen
     * alliance's invite): one of those citizens welcomes them now, and the next check does not welcome them again.
     */
    public void joined(int allianceId, int guildId) {
        Alliance alliance = Server.getInstance().getAlliance(allianceId);
        if (alliance == null || !population.config().ALLIANCES_ENABLED) {
            return;
        }
        List<Integer> citizenGuilds = new ArrayList<>();
        for (int member : alliance.getGuilds()) {
            if (member != guildId && population.social().isBotGuild(member)) {
                citizenGuilds.add(member);
            }
        }
        if (citizenGuilds.isEmpty()) {
            return;
        }
        remember(alliance);
        sayWelcome(new Union(alliance, 0, citizenGuilds, List.of(guildId)), guildId);
    }

    private void answer(CitizenSession session, Character inviter, Character master, int allianceId) {
        Guild guild = guild(master.getGuildId());
        Alliance alliance = Server.getInstance().getAlliance(allianceId);
        if (guild == null || alliance == null || guild.getAllianceId() > 0 || population.presence().session(session.characterId) == null) {
            return; // the invite moved on: it expires as stock
        }
        Map<Integer, Side> sides = sides(population.social().guildMetas());
        if (!sides.containsKey(guild.getId())) {
            return; // its master is hired or no longer active: the invite expires as stock
        }
        List<Integer> citizenGuilds = new ArrayList<>();
        for (int member : alliance.getGuilds()) {
            if (sides.containsKey(member)) {
                citizenGuilds.add(member);
            }
        }
        int inviterGuild = inviter.getGuildId();
        int minutes = population.store().playerGuildMinutes(List.of(guild.getId())).getOrDefault(inviterGuild, 0);
        double tie = sides.containsKey(guild.getId()) && !citizenGuilds.isEmpty() ? maxTie(sides, guild.getId(), citizenGuilds) : 0d;
        Guild inviting = guild(inviterGuild);
        String inviterGuildName = inviting == null ? alliance.getName() : inviting.getName();
        Union union = new Union(alliance, inviterGuild, citizenGuilds, List.of());
        boolean welcome = alliance.getGuilds().size() < alliance.getCapacity()
                && (minutes >= population.config().ALLIANCE_PLAYER_MINUTES || tie >= population.config().ALLIANCE_FOUND_TIE);
        if (!welcome) {
            Alliance.answerInvitation(master.getId(), guild.getName(), allianceId, false);
            String line = line("decline", union, guild.getName(), inviterGuildName);
            if (line != null) {
                population.chatter().whisper(session, inviter, line);
            }
            log.info("bot guild alliances: '{}' ({}) declined '{}' ({}) from {}: {} shared minutes, tie {}", guild.getName(),
                    guild.getId(), alliance.getName(), allianceId, inviter.getName(), minutes, String.format("%.2f", tie));
            return;
        }
        if (!Alliance.answerInvitation(master.getId(), guild.getName(), allianceId, true) || !addGuild(alliance, guild)) {
            return;
        }
        remember(alliance); // its guilds now, so the next check welcomes nobody twice
        String line = line("accept", union, guild.getName(), inviterGuildName);
        if (line != null) {
            population.chatter().whisper(session, inviter, line);
        }
        session.journal.add("social", "took " + guild.getName() + " into " + alliance.getName());
        log.info("bot guild alliances: '{}' ({}) joined '{}' ({}) on {}'s invite: {} shared minutes, tie {}", guild.getName(),
                guild.getId(), alliance.getName(), allianceId, inviter.getName(), minutes, String.format("%.2f", tie));
    }

    // ---- stock writes --------------------------------------------------------------------

    /** Lenario's union for two guilds, server-side: the stock createAlliance without a party (lead master rank 1). */
    private Alliance foundAlliance(Guild lead, Guild other) {
        String name = allianceName();
        if (name == null) {
            return null;
        }
        Alliance alliance = Alliance.createAllianceOnDb(List.of(lead.getId(), other.getId()), name);
        if (alliance == null) {
            return null;
        }
        Server server = Server.getInstance();
        alliance.setCapacity(2);
        alliance.addGuild(lead.getId());
        alliance.addGuild(other.getId());
        alliance.setNotice(population.social().motto(lead.getId()));
        server.addAlliance(alliance.getId(), alliance);
        for (Guild guild : List.of(lead, other)) {
            server.setGuildAllianceId(guild.getId(), alliance.getId());
            server.resetAllianceGuildPlayersRank(guild.getId());
            setAllianceRank(guild, guild == lead ? 1 : 2);
            population.store().setAllianceMark(guild.getId(), MARK_LOW, 0);
        }
        alliance.saveToDB();
        refresh(alliance);
        alliance.dropMessage("[" + lead.getName() + "] and [" + other.getName() + "] formed the [" + name + "] union.");
        return alliance;
    }

    /** The stock accept (op 4) for a citizen guild, its master rank 2; false when the alliance is full. */
    private boolean addGuild(Alliance alliance, Guild guild) {
        if (alliance.getGuilds().size() >= alliance.getCapacity()
                || !Server.getInstance().addGuildtoAlliance(alliance.getId(), guild.getId())) {
            return false;
        }
        Server.getInstance().resetAllianceGuildPlayersRank(guild.getId());
        setAllianceRank(guild, 2);
        population.store().setAllianceMark(guild.getId(), MARK_LOW, 0); // a low spell from an alliance it was in before
        alliance.saveToDB();
        refresh(alliance);
        guild.dropMessage("Your guild has joined the [" + alliance.getName() + "] union.");
        return true;
    }

    /** A full alliance under five buys a slot from its leader's master (Lenario's fee); true when there is room. */
    private boolean roomFor(Union union) {
        Alliance alliance = union.alliance();
        if (alliance.getGuilds().size() < alliance.getCapacity()) {
            return true;
        }
        Guild leader = guild(union.leaderGuildId());
        if (alliance.getCapacity() >= MAX_GUILDS || leader == null || !pay(leader.getLeaderId(), SLOT_FEE)) {
            return false;
        }
        Server.getInstance().increaseAllianceCapacity(alliance.getId(), 1);
        alliance.saveToDB();
        refresh(alliance);
        log.info("bot guild alliances: '{}' ({}) bought a slot, {} now", alliance.getName(), alliance.getId(),
                alliance.getCapacity());
        return true;
    }

    private void refresh(Alliance alliance) {
        int world = population.world().config().WORLD;
        Server server = Server.getInstance();
        server.allianceMessage(alliance.getId(), GuildPackets.updateAllianceInfo(alliance, world), -1, -1);
        server.allianceMessage(alliance.getId(), GuildPackets.getGuildAlliances(alliance, world), -1, -1);
        server.allianceMessage(alliance.getId(), GuildPackets.allianceNotice(alliance.getId(), alliance.getNotice()), -1, -1);
    }

    /** The guild master's alliance rank, in memory and saved: a loaded master saves its own, an offline one in SQL. */
    private void setAllianceRank(Guild guild, int rank) {
        int masterId = guild.getLeaderId();
        GuildCharacter mgc = guild.getMGC(masterId);
        if (mgc != null) {
            mgc.setAllianceRank(rank);
        }
        Character online = onlineCharacter(masterId);
        if (online != null) {
            online.setAllianceRank(rank);
            online.saveGuildStatus();
        } else {
            population.store().setAllianceRank(masterId, rank);
        }
    }

    /** Takes a fee from a master's purse: a loaded one's meso in memory, an offline one's in SQL. */
    private boolean pay(int masterId, long fee) {
        Character online = onlineCharacter(masterId);
        if (online != null) {
            if (online.getMeso() < fee) {
                return false;
            }
            online.gainMeso((int) -fee, false);
            return true;
        }
        return population.store().chargeMeso(masterId, fee);
    }

    private void refund(int masterId, long fee) {
        Character online = onlineCharacter(masterId);
        if (online != null) {
            online.gainMeso((int) fee, false);
        } else {
            population.store().chargeMeso(masterId, -fee);
        }
    }

    // ---- lines ---------------------------------------------------------------------------

    private void sayWelcome(Union union, int newGuildId) {
        List<Integer> hosts = new ArrayList<>(union.citizenGuilds());
        hosts.remove((Integer) newGuildId);
        CitizenSession speaker = speaker(hosts);
        Guild joined = guild(newGuildId);
        if (speaker == null || joined == null) {
            return;
        }
        Guild own = guild(speaker.citizen.guildId());
        say(speaker, union, "welcome", own == null ? "" : own.getName(), joined.getName());
    }

    private void say(CitizenSession speaker, Union union, String moment, String guildName, String otherName) {
        String line = line(moment, union, guildName, otherName);
        if (line != null) {
            population.chatter().allianceSay(speaker, union.alliance().getId(), line);
        }
    }

    /** One of the content's lines for the moment, its slots filled; null when the content has none. */
    String line(String moment, Union union, String guildName, String otherName) {
        Alliances content = population.content().guildNames().alliances();
        List<String> lines = content.lines(moment);
        if (lines.isEmpty()) {
            return null;
        }
        return lines.get(random.nextInt(lines.size()))
                .replace("{alliance}", union.alliance().getName())
                .replace("{guild}", guildName == null ? "" : guildName)
                .replace("{other}", otherName == null ? "" : otherName);
    }

    /** A loaded citizen of these guilds to speak for them: a master first, else any member. */
    private CitizenSession speaker(List<Integer> guildIds) {
        CitizenSession any = null;
        for (CitizenSession session : population.presence().sessions()) {
            Character agent = session.agent();
            if (session.virtual || agent == null || !guildIds.contains(agent.getGuildId())) {
                continue;
            }
            if (agent.getGuildRank() == 1) {
                return session;
            }
            any = any == null ? session : any;
        }
        return any;
    }

    /** A loaded citizen master of the alliance (stock: only alliance ranks 1-2 invite), the leader's first. */
    private CitizenSession invitingMaster(Union union) {
        CitizenSession other = null;
        for (int guildId : union.citizenGuilds()) {
            Guild guild = guild(guildId);
            CitizenSession session = guild == null ? null : population.presence().session(guild.getLeaderId());
            if (session == null || session.virtual || session.agent() == null || session.agent().getClient() == null) {
                continue;
            }
            if (guildId == union.leaderGuildId()) {
                return session;
            }
            other = other == null ? session : other;
        }
        return other;
    }

    // ---- helpers -------------------------------------------------------------------------

    private String allianceName() {
        Alliances content = population.content().guildNames().alliances();
        if (content.patterns().isEmpty()) {
            return null;
        }
        NameGenerator generator = new NameGenerator(content.patterns(), population.content().guildNames().lists(),
                List.of(), List.of(), List.of());
        for (int attempt = 0; attempt < 30; attempt++) {
            String candidate = generator.next(random);
            if (candidate != null && candidate.length() >= 3 && candidate.length() <= 12 && !candidate.contains(" ")
                    && Alliance.canBeUsedAllianceName(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** The mean tie of a guild to the other citizen guilds listed; 1 when there are none to judge it by. */
    static double meanTie(Map<Integer, Side> sides, int guildId, List<Integer> citizenGuilds) {
        Side self = sides.get(guildId);
        double sum = 0d;
        int counted = 0;
        for (int other : citizenGuilds) {
            Side side = sides.get(other);
            if (other != guildId && self != null && side != null) {
                sum += GuildTies.tie(self, side);
                counted++;
            }
        }
        return counted == 0 ? 1d : sum / counted;
    }

    private static double maxTie(Map<Integer, Side> sides, int guildId, List<Integer> citizenGuilds) {
        double best = 0d;
        for (int other : citizenGuilds) {
            if (other != guildId && sides.containsKey(other)) {
                best = Math.max(best, GuildTies.tie(sides.get(guildId), sides.get(other)));
            }
        }
        return best;
    }

    /** Two unallied citizen guilds that could found an alliance: the bigger one leads (the first listed on a tie). */
    record Pair(int lead, int other, double tie) { }

    /** Every pair of these guilds at {@code floor} or more, closest first (pairs as close keep the guilds' order). */
    static List<Pair> foundingPairs(Map<Integer, Side> sides, List<Integer> free, double floor) {
        List<Pair> pairs = new ArrayList<>();
        for (int i = 0; i < free.size(); i++) {
            for (int j = i + 1; j < free.size(); j++) {
                Side a = sides.get(free.get(i));
                Side b = sides.get(free.get(j));
                double tie = GuildTies.tie(a, b);
                if (tie >= floor) {
                    boolean aLeads = a.members().size() >= b.members().size();
                    pairs.add(new Pair(aLeads ? free.get(i) : free.get(j), aLeads ? free.get(j) : free.get(i), tie));
                }
            }
        }
        pairs.sort((x, y) -> Double.compare(y.tie(), x.tie()));
        return pairs;
    }

    private Guild guild(int guildId) {
        return guildId <= 0 ? null : Server.getInstance().getGuild(guildId, population.world().config().WORLD);
    }

    private int allianceOf(int guildId) {
        Guild guild = guild(guildId);
        return guild == null ? 0 : guild.getAllianceId();
    }

    /** The server's alliance, loaded from the database as a member's login loads it; a missing one clears the guild's id. */
    private Alliance loadAlliance(int allianceId, int guildId) {
        Server server = Server.getInstance();
        Alliance alliance = server.getAlliance(allianceId);
        if (alliance == null) {
            alliance = Alliance.loadAlliance(allianceId);
            if (alliance == null) {
                Guild guild = guild(guildId);
                if (guild != null) {
                    guild.setAllianceId(0);
                }
                return null;
            }
            server.addAlliance(allianceId, alliance);
            alliance = server.getAlliance(allianceId);
        }
        for (int member : alliance.getGuilds()) {
            guild(member); // loaded, so the stock alliance packets and getLeader see every guild in it
        }
        return alliance;
    }

    private Character onlineCharacter(int characterId) {
        World world = population.gameWorld();
        return world == null ? null : world.getPlayerStorage().getCharacterById(characterId);
    }

    /** One line per alliance citizen guilds are in, for !citizens alliances. */
    public List<String> report() {
        Map<Integer, Side> sides = sides(population.social().guildMetas());
        List<String> out = new ArrayList<>();
        for (Union union : unions(sides.keySet()).values()) {
            StringBuilder line = new StringBuilder(union.alliance().getName()).append(" (").append(union.alliance().getId())
                    .append(", ").append(union.size()).append('/').append(union.alliance().getCapacity())
                    .append(union.citizenLed() ? ", citizen-led" : ", player-led").append("):");
            for (int guildId : union.alliance().getGuilds()) {
                Guild guild = guild(guildId);
                line.append(' ').append(guild == null ? String.valueOf(guildId) : guild.getName());
                if (union.citizenGuilds().contains(guildId)) {
                    line.append(String.format(" tie %.2f", meanTie(sides, guildId, union.citizenGuilds())));
                }
                if (guildId == union.leaderGuildId()) {
                    line.append(" (leads)");
                }
                line.append(';');
            }
            out.add(line.toString());
        }
        if (out.isEmpty()) {
            out.add("No citizen guild is in an alliance.");
        }
        return out;
    }
}
