package net.server.channel.handlers;

import client.Character;
import client.Client;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.living.LivingWorld;
import server.living.roster.ui.NativeCrewPackets;
import server.living.roster.ui.NativeCrewWindow;
import server.monitoring.ThrottledLogger;

/**
 * The native Crew window's packets (P10, docs/BOT_MANAGEMENT_P10_PLAN.md; Recv opcode CREW_WINDOW 0x117 from a client
 * with the P10 DLL): decoded here and answered by the living world's {@link NativeCrewWindow}. A packet that does not
 * decode is dropped and logged (throttled), and nothing is ever sent to a connection that did not say HELLO.
 */
public final class CrewWindowHandler extends AbstractPacketHandler {
    private static final Logger log = LoggerFactory.getLogger(CrewWindowHandler.class);

    @Override
    public void handlePacket(InPacket p, Client c) {
        Character player = c.getPlayer();
        if (player == null) {
            return;
        }
        NativeCrewPackets.Op op = NativeCrewPackets.decode(p);
        if (op == null) {
            ThrottledLogger.warn("crew-window:malformed", log, "Dropped a malformed CREW_WINDOW packet from {}", null,
                    player.getName());
            return;
        }
        NativeCrewWindow.receiveFrom(c, player, op, LivingWorld::get);
    }
}
