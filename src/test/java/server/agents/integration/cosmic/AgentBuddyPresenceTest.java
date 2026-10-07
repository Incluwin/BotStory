package server.agents.integration.cosmic;

import client.BotClient;
import client.BuddyList;
import client.BuddylistEntry;
import client.Character;
import net.server.Server;
import net.server.channel.Channel;
import net.server.world.World;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.runtime.AgentRuntimeCleanupService;

import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A bot's buddies see it come and go like a player (P9 buddy friends; probe 8 found a benched companion still shown
 * online): an Agent entering the world tells its buddies it is online on its channel, and an Agent leaving the world
 * tells them it went offline. A real logout tells them in the stock path; a bot is unloaded with forceDisconnect, which
 * skips that, so it is told here.
 */
class AgentBuddyPresenceTest {
    private static final int WORLD = 0;
    private static final int CHANNEL = 1;
    private static final int CASSE = 333;
    private static final int ANN = 10;

    private static Character bot(BuddyList buddies) {
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(CASSE);
        when(bot.getName()).thenReturn("Casse");
        when(bot.getBuddylist()).thenReturn(buddies);
        return bot;
    }

    private static BuddyList annOnTheList() {
        BuddyList buddies = new BuddyList(20);
        buddies.put(new BuddylistEntry("Ann", "Default Group", ANN, CHANNEL, true));
        return buddies;
    }

    @Test
    void anAgentEnteringTheWorldTellsItsBuddiesItIsOnline() {
        World world = mock(World.class);
        Character agent = bot(annOnTheList());
        try (MockedStatic<Server> server = mockStatic(Server.class)) {
            Server instance = mock(Server.class);
            Channel channel = mock(Channel.class);
            server.when(Server::getInstance).thenReturn(instance);
            when(instance.getChannel(WORLD, CHANNEL)).thenReturn(channel);
            when(channel.getWorldServer()).thenReturn(world);

            CosmicMapGateway.INSTANCE.addWorldPlayer(WORLD, CHANNEL, agent);
        }

        verify(world).addPlayer(agent);
        verify(world).loggedOn(org.mockito.ArgumentMatchers.eq("Casse"), org.mockito.ArgumentMatchers.eq(CASSE),
                org.mockito.ArgumentMatchers.eq(CHANNEL), aryEq(new int[] {ANN}));
    }

    @Test
    void aBotLeavingTheWorldTellsItsBuddiesItWentOffline() {
        World world = mock(World.class);
        BotClient client = new BotClient(WORLD, CHANNEL);
        Character agent = bot(annOnTheList());
        when(agent.isLoggedin()).thenReturn(true);
        when(agent.getClient()).thenReturn(client);
        client.setPlayer(agent);
        try (MockedStatic<Server> server = mockStatic(Server.class);
             MockedStatic<AgentRuntimeCleanupService> cleanup = mockStatic(AgentRuntimeCleanupService.class)) {
            Server instance = mock(Server.class);
            server.when(Server::getInstance).thenReturn(instance);
            when(instance.getWorld(WORLD)).thenReturn(world);
            when(instance.getChannel(anyInt(), anyInt())).thenReturn(mock(Channel.class));

            client.forceDisconnect();
        }

        verify(world, times(1)).loggedOff(org.mockito.ArgumentMatchers.eq("Casse"), org.mockito.ArgumentMatchers.eq(CASSE),
                org.mockito.ArgumentMatchers.eq(CHANNEL), aryEq(new int[] {ANN}));
    }
}
