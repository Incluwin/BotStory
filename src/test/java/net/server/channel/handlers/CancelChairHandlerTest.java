package net.server.channel.handlers;

import net.packet.InPacket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import server.maps.MapleMap;
import testutil.HandlerTest;
import testutil.Packets;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CancelChairHandlerTest extends HandlerTest {
    private final CancelChairHandler handler = new CancelChairHandler();

    @Mock
    private MapleMap map;

    private InPacket cancelChairPacket(int chairId) {
        return Packets.buildInPacket(out -> out.writeShort(chairId));
    }

    @Test
    void doesNothingWhenChairIdExceedsMapSeats() {
        when(chr.getMap()).thenReturn(map);
        when(map.getSeats()).thenReturn(3);

        handler.handlePacket(cancelChairPacket(5), client);

        verify(chr, never()).sitChair(anyInt());
        verify(client, never()).tryacquireClient();
    }

    @Test
    void sitsAndReleasesClientLockWhenIdIsValid() {
        when(chr.getMap()).thenReturn(map);
        when(map.getSeats()).thenReturn(3);
        when(client.tryacquireClient()).thenReturn(true);

        handler.handlePacket(cancelChairPacket(2), client);

        verify(chr, times(1)).sitChair(2);
        verify(client, times(1)).releaseClient();
    }

    @Test
    void skipsSitWhenClientLockUnavailable() {
        when(chr.getMap()).thenReturn(map);
        when(map.getSeats()).thenReturn(3);
        when(client.tryacquireClient()).thenReturn(false);

        handler.handlePacket(cancelChairPacket(1), client);

        verify(chr, never()).sitChair(anyInt());
        verify(client, never()).releaseClient();
    }
}
