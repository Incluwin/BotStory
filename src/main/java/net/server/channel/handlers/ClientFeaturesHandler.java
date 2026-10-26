package net.server.channel.handlers;

import client.Character;
import client.Client;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.mods.features.FeatureHandshake;
import server.mods.features.FeaturePackets;
import server.monitoring.ThrottledLogger;
import server.set.SetItemSync;
import server.weather.WeatherPackets;

/**
 * The kaentake client's feature list (Recv opcode CLIENT_FEATURES 0x118, docs/CLIENT_FEATURE_HANDSHAKE.md): decoded
 * here and answered by {@link FeatureHandshake}. A packet that does not decode is dropped and logged (throttled).
 */
public final class ClientFeaturesHandler extends AbstractPacketHandler {
    private static final Logger log = LoggerFactory.getLogger(ClientFeaturesHandler.class);

    @Override
    public void handlePacket(InPacket p, Client c) {
        Character player = c.getPlayer();
        if (player == null) {
            return;
        }
        FeaturePackets.Report report = FeaturePackets.decode(p);
        if (report == null) {
            ThrottledLogger.warn("client-features:malformed", log, "Dropped a malformed CLIENT_FEATURES packet from {}",
                    null, player.getName());
            return;
        }
        FeatureHandshake.get().receive(c, player, report);
        if (report.applied()) {
            SetItemSync.send(player); // the set tooltips need the worn sets before the first equip change
            WeatherPackets.sendTo(player); // the first map's sky: its map entry came before the handshake
        }
    }
}
