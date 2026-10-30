package client;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import net.server.Server;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A connection accepted before the server is online (the restart script's port probe) is closed in channelActive and
 * never gets an ioChannel; its channelInactive then closes the session, which threw a NullPointerException at boot
 * (twice: once more from exceptionCaught). Closing or disconnecting a session without a connection does nothing.
 */
class ClientSessionCloseTest {
    @Test
    void aConnectionRefusedBeforeTheServerIsOnlineClosesItsSessionWithoutAnError() {
        Client client = Client.createMock();
        EmbeddedChannel channel = new EmbeddedChannel();

        try (MockedStatic<Server> server = online(false)) {
            client.channelActive(context(channel));
        }

        assertFalse(channel.isOpen(), "refused in channelActive");
        assertDoesNotThrow(client::closeSession);
        assertDoesNotThrow(client::disconnectSession);
    }

    @Test
    void aConnectedSessionStillClosesAndDisconnects() {
        Client client = Client.createMock();
        Channel channel = mock(Channel.class);
        when(channel.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 8484));

        try (MockedStatic<Server> server = online(true)) {
            client.channelActive(context(channel));
        }
        verify(channel, never()).close();

        client.closeSession();
        client.disconnectSession();
        verify(channel).close();
        verify(channel).disconnect();
    }

    private static MockedStatic<Server> online(boolean online) {
        Server instance = mock(Server.class);
        when(instance.isOnline()).thenReturn(online);
        MockedStatic<Server> server = mockStatic(Server.class);
        server.when(Server::getInstance).thenReturn(instance);
        return server;
    }

    private static ChannelHandlerContext context(Channel channel) {
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        when(ctx.channel()).thenReturn(channel);
        return ctx;
    }
}
