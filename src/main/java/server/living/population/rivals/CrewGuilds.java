package server.living.population.rivals;

import client.Character;
import net.server.Server;
import net.server.guild.Guild;
import net.server.guild.GuildCharacter;
import net.server.guild.GuildPackets;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.integration.AgentPresence;
import server.living.population.Citizen;
import server.living.population.CitizenSession;
import server.living.population.GuildEmblems;
import server.living.population.NameGenerator;
import server.living.population.Population;
import server.living.population.PopulationContent;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The server's side of the rival crews ({@link RivalCrews.Ports}): players online, and the crews' own guilds and circles.
 * A citizen joins, leaves and hands over a guild as a player would (the stock guild packets, its name tag on its map
 * when it is loaded, its characters row when it is away), and its living_citizen row follows. Crew guilds are not
 * living_guild rows, so SocialFabric never counts, recruits into or allies them.
 */
final class CrewGuilds implements RivalCrews.Ports {
    private static final Logger log = LoggerFactory.getLogger(CrewGuilds.class);
    private static final int MASTER = 1;
    private static final int JR_MASTER = 2;
    private static final int MEMBER = 5;

    private final Population population;

    CrewGuilds(Population population) {
        this.population = population;
    }

    private int worldId() {
        return population.world().config().WORLD;
    }

    @Override
    public List<Character> onlineHumans() {
        List<Character> out = new ArrayList<>();
        World world = population.gameWorld();
        if (world == null) {
            return out;
        }
        for (Character chr : world.getPlayerStorage().getAllCharacters()) {
            if (chr != null && !AgentPresence.isAgent(chr) && chr.getClient() != null) {
                out.add(chr);
            }
        }
        return out;
    }

    @Override
    public String freeName(Random random) {
        PopulationContent.GuildNames names = population.content().guildNames();
        NameGenerator generator = new NameGenerator(names.patterns(), names.lists(), List.of(), List.of(), List.of());
        for (int attempt = 0; attempt < 20; attempt++) {
            String candidate = generator.next(random);
            if (candidate != null && candidate.length() >= 3 && candidate.length() <= 12
                    && Server.getInstance().getGuildByName(candidate) == null) {
                return candidate;
            }
        }
        return null;
    }

    /** A master or jr. master of another guild stays where it is: a bot guild never loses its master to a crew. */
    private boolean leadsAnother(Citizen citizen, int crewGuildId) {
        if (citizen.guildId() <= 0 || citizen.guildId() == crewGuildId) {
            return false;
        }
        Guild guild = Server.getInstance().getGuild(citizen.guildId());
        GuildCharacter entry = guild == null ? null : guild.getMGC(citizen.characterId());
        return entry != null && entry.getGuildRank() <= JR_MASTER;
    }

    @Override
    public int found(Citizen leader, String name) {
        Citizen current = population.citizen(leader.characterId());
        if (current == null || current.hired() || leadsAnother(current, 0)) {
            return 0;
        }
        if (current.guildId() > 0) {
            leave(current, current.guildId());
        }
        int guildId = Guild.createGuild(leader.characterId(), name);
        if (guildId <= 0) {
            return 0;
        }
        population.store().setGuild(leader.characterId(), guildId, MASTER);
        population.update(leader.characterId(), latest -> latest.withGuild(guildId));
        Character agent = agent(leader.characterId());
        Guild guild = Server.getInstance().getGuild(guildId, worldId(), agent);
        if (guild == null) {
            return guildId;
        }
        GuildEmblems.Emblem emblem = GuildEmblems.pick(name, population.content().guildNames().emblems());
        guild.setGuildEmblem((short) emblem.background(), (byte) emblem.backgroundColor(), (short) emblem.mark(),
                (byte) emblem.markColor());
        if (agent != null) {
            GuildCharacter member = guild.getMGC(agent.getId());
            if (member != null) {
                member.setCharacter(agent);
                agent.setMGC(member);
            }
            agent.setGuildId(guildId);
            agent.setGuildRank(MASTER);
            nameTag(agent, guild);
        }
        log.info("rival crew guild '{}' ({}) founded by {}", name, guildId, leader.name());
        return guildId;
    }

    @Override
    public void join(Citizen member, int guildId) {
        Citizen current = population.citizen(member.characterId());
        if (current == null || current.hired() || leadsAnother(current, guildId)) {
            return;
        }
        if (current.guildId() > 0 && current.guildId() != guildId) {
            leave(current, current.guildId());
        }
        Guild guild = Server.getInstance().getGuild(guildId);
        if (guild == null) {
            return;
        }
        population.store().setGuild(member.characterId(), guildId, MEMBER);
        population.update(member.characterId(), latest -> latest.withGuild(guildId));
        if (guild.getMGC(member.characterId()) != null) {
            return;
        }
        Character agent = agent(member.characterId());
        if (agent != null) {
            agent.setGuildId(guildId);
            agent.setGuildRank(MEMBER);
            agent.setMGC(new GuildCharacter(agent));
            guild.addGuildMember(agent.getMGC(), agent);
            nameTag(agent, guild);
        } else {
            int[] progress = population.store().characterProgress(member.characterId());
            guild.addGuildMember(new GuildCharacter(null, member.characterId(), progress[0], member.name(), (byte) -1,
                    worldId(), progress[2], MEMBER, guildId, false, 5), null);
        }
    }

    @Override
    public void leave(Citizen member, int guildId) {
        Guild guild = Server.getInstance().getGuild(guildId);
        GuildCharacter entry = guild == null ? null : guild.getMGC(member.characterId());
        if (entry != null) {
            Server.getInstance().leaveGuild(entry);
        }
        Character agent = agent(member.characterId());
        if (agent != null && agent.getGuildId() == guildId) {
            GuildCharacter own = agent.getMGC();
            if (own != null && own.getCharacter() == agent) {
                own.setGuildId(0);
                own.setGuildRank(MEMBER);
            } else {
                agent.setGuildId(0);
                agent.setGuildRank(MEMBER);
            }
            if (agent.getMap() != null) {
                agent.getMap().broadcastPacket(agent, GuildPackets.guildNameChanged(agent.getId(), ""));
            }
        }
        population.store().setGuild(member.characterId(), 0, MEMBER);
        population.update(member.characterId(), latest -> latest.guildId() == guildId ? latest.withGuild(0) : latest);
    }

    @Override
    public void handOver(int guildId, int fromId, int toId) {
        Guild guild = Server.getInstance().getGuild(guildId);
        if (guild == null) {
            return;
        }
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("UPDATE guilds SET leader = ? WHERE guildid = ?")) {
            ps.setInt(1, toId);
            ps.setInt(2, guildId);
            ps.executeUpdate();
        } catch (SQLException failure) {
            log.warn("crew guild {} hand-over to {} failed", guildId, toId, failure);
            return;
        }
        guild.setLeaderId(toId);
        guild.changeRank(toId, MASTER);
        guild.changeRank(fromId, JR_MASTER);
    }

    @Override
    public void disband(int guildId) {
        if (Server.getInstance().getGuild(guildId) == null) {
            return;
        }
        Server.getInstance().disbandGuild(guildId);
        for (Citizen citizen : population.citizens()) {
            if (citizen.guildId() == guildId) {
                Character agent = agent(citizen.characterId());
                if (agent != null) {
                    agent.setGuildId(0);
                    agent.setGuildRank(MEMBER);
                    agent.setMGC(null);
                }
                population.update(citizen.characterId(), latest -> latest.withGuild(0));
            }
        }
    }

    @Override
    public boolean guildExists(int guildId) {
        return Server.getInstance().getGuild(guildId, worldId(), null) != null;
    }

    @Override
    public int circle(String name, String cohort) {
        return population.store().createCircle(name, cohort);
    }

    private Character agent(int citizenId) {
        CitizenSession session = population.presence().session(citizenId);
        return session == null || session.virtual ? null : session.agent();
    }

    /** Its new guild over its head for whoever stands on its map. */
    private static void nameTag(Character agent, Guild guild) {
        if (agent.getMap() != null) {
            agent.getMap().broadcastPacket(agent, GuildPackets.guildNameChanged(agent.getId(), guild.getName()));
            agent.getMap().broadcastPacket(agent, GuildPackets.guildMarkChanged(agent.getId(), guild));
        }
    }
}
