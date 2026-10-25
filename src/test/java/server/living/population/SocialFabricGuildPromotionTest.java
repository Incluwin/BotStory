package server.living.population;

import client.Character;
import config.PopulationConfig;
import net.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.living.population.CitizenStore.Acquaintance;

import java.util.LinkedHashSet;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GPQ plan decision 8: the master of a citizen guild promotes a human member it has shared three hours with (map
 * minutes plus party minutes twice) to Jr. Master, once, and whispers why, so the human can register the guild at
 * Shuang. Never before the time, never someone of another guild or already a Jr. Master, never by a plain member.
 */
class SocialFabricGuildPromotionTest {
    private static final int CITIZEN = 963;
    private static final int ANN = 10;
    private static final int GUILD = 31;

    private final Population population = mock(Population.class);
    private final PopulationConfig config = new PopulationConfig();
    private final ChatterEngine chatter = mock(ChatterEngine.class);
    private final Server server = mock(Server.class);
    private final Character agent = mock(Character.class);
    private final Character ann = mock(Character.class);
    private CitizenSession session;
    private MockedStatic<AgentRuntimeIdentityRuntime> identity;
    private MockedStatic<Server> servers;

    @BeforeEach
    void pipLeadsTheGuildAnnJoined() {
        when(population.config()).thenReturn(config);
        when(population.chatter()).thenReturn(chatter);
        when(population.store()).thenReturn(mock(CitizenStore.class));
        Citizen citizen = new Citizen(CITIZEN, "Pip", "evening", 0, 0, "victoria", "hunter", "relaxed-v1", "default", 1.0d,
                0, 1, new LinkedHashSet<>(), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
        session = new CitizenSession(citizen, 0L, 60_000L, false);
        session.entry = mock(AgentRuntimeEntry.class);
        when(agent.getId()).thenReturn(CITIZEN);
        when(agent.getGuildId()).thenReturn(GUILD);
        when(agent.getGuildRank()).thenReturn(1);
        when(ann.getId()).thenReturn(ANN);
        when(ann.getName()).thenReturn("Ann");
        when(ann.getGuildId()).thenReturn(GUILD);
        when(ann.getGuildRank()).thenReturn(5);
        identity = mockStatic(AgentRuntimeIdentityRuntime.class);
        identity.when(() -> AgentRuntimeIdentityRuntime.bot(any())).thenReturn(agent);
        servers = mockStatic(Server.class);
        servers.when(Server::getInstance).thenReturn(server);
    }

    @AfterEach
    void close() {
        servers.close();
        identity.close();
    }

    @Test
    void afterThreeHoursTogetherTheMasterMakesHerAJrMasterAndSaysWhy() {
        promote(new Acquaintance(CITIZEN, ANN, 40, 100, true, true)); // 100 + 2 x 40 = 180

        verify(server).changeRank(GUILD, ANN, 2);
        verify(chatter).whisper(eq(session), eq(ann), anyString());
    }

    @Test
    void notBeforeTheTimeNorTwiceNorForAnotherGuildNorByAPlainMember() {
        promote(new Acquaintance(CITIZEN, ANN, 40, 99, true, true));
        when(ann.getGuildRank()).thenReturn(2);
        promote(new Acquaintance(CITIZEN, ANN, 90, 300, true, true));
        when(ann.getGuildRank()).thenReturn(5);
        when(ann.getGuildId()).thenReturn(32);
        promote(new Acquaintance(CITIZEN, ANN, 90, 300, true, true));
        when(ann.getGuildId()).thenReturn(GUILD);
        when(agent.getGuildRank()).thenReturn(3);
        promote(new Acquaintance(CITIZEN, ANN, 90, 300, true, true));
        config.GUILD_PROMOTE_AFTER_MINUTES = 0;
        when(agent.getGuildRank()).thenReturn(1);
        promote(new Acquaintance(CITIZEN, ANN, 90, 300, true, true));

        verify(server, never()).changeRank(anyInt(), anyInt(), anyInt());
    }

    private void promote(Acquaintance acquaintance) {
        new SocialFabric(population).maybePromoteHuman(session, ann, acquaintance);
    }
}
