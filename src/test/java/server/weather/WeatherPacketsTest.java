package server.weather;

import client.Character;
import client.Client;
import io.netty.buffer.Unpooled;
import net.opcodes.SendOpcode;
import net.packet.ByteBufInPacket;
import net.packet.InPacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.lamp.LampPackets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeatherPacketsTest {
    private static final int HENESYS = 100000000;

    @AfterEach
    void handBack() {
        WeatherService.clearTimeOverride();
        WeatherService.clearSkyOverride();
        WeatherService.setBareSky(false);
    }

    @Test
    void syncIsTheReleasesTwentyEightBytesUnderOurOpcode() {
        WeatherService.setTime(WeatherService.TIME_NIGHT);
        WeatherService.setSky(WeatherProfile.RAIN.id(), WeatherService.OVERRIDE_HOLD_MS);

        byte[] bytes = WeatherPackets.weatherSync(HENESYS, true).getBytes();
        InPacket in = new ByteBufInPacket(Unpooled.wrappedBuffer(bytes));

        assertEquals(28, bytes.length, "the client reads each optional tail group only when it is there");
        assertEquals(SendOpcode.WEATHER_SYNC.getValue(), in.readShort());
        assertEquals(0x17C, SendOpcode.WEATHER_SYNC.getValue(), "docs/CUSTOM_OPCODES.md and weathermod.cpp");
        assertEquals(WeatherService.TIME_NIGHT, in.readShort());
        assertEquals(WeatherService.msPerGameMinute(), in.readInt());
        assertEquals(WeatherService.skyForMap(HENESYS), in.readByte());
        assertEquals(WeatherService.FLAG_SNAP | WeatherService.FLAG_FROZEN, in.readByte());
    }

    @Test
    void lampPreviewIsSixteenBytesUnderOurOpcode() {
        byte[] bytes = LampPackets.lampPreview(LampPackets.MODE_PLACE, 6, HENESYS, -120, 215).getBytes();
        InPacket in = new ByteBufInPacket(Unpooled.wrappedBuffer(bytes));

        assertEquals(16, bytes.length);
        assertEquals(0x17D, in.readShort());
        assertEquals(LampPackets.MODE_PLACE, in.readByte());
        assertEquals(6, in.readByte());
        assertEquals(HENESYS, in.readInt());
        assertEquals(-120, in.readInt());
        assertEquals(215, in.readInt());
    }

    @Test
    void aClientThatNeverReportedTheWeatherFeatureIsNotSentIt() {
        Character chr = mock(Character.class);
        when(chr.getClient()).thenReturn(mock(Client.class)); // companions and citizens never report features
        when(chr.getMapId()).thenReturn(HENESYS);

        assertFalse(WeatherPackets.wants(chr));
        WeatherPackets.sendTo(chr);
        LampPackets.sendTo(chr, 6, HENESYS, 0, 0);

        verify(chr, never()).sendPacket(any());
    }

    @Test
    void noClientIsNotSentIt() {
        Character chr = mock(Character.class);

        assertFalse(WeatherPackets.wants(chr));
        assertFalse(WeatherPackets.wants(null));
        WeatherPackets.sendTo(chr);

        verify(chr, never()).sendPacket(any());
    }
}
