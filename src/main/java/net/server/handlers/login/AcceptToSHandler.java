package net.server.handlers.login;

import client.Client;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import tools.PacketCreator;

/**
 * @author kevintjuh93
 */
public final class AcceptToSHandler extends AbstractPacketHandler {

    @Override
    public boolean validateState(Client c) {
        return !c.isLoggedIn();
    }

    @Override
    public final void handlePacket(InPacket p, Client c) {
        if (p.available() == 0 || p.readByte() != 1 || c.acceptToS()) {
            c.disconnect(false, false);//Client dc's but just because I am cool I do this (:
            return;
        }
        if (c.finishLogin() == 0) {
            int disconnectedBots = c.checkChar(c.getAccID());
            c.sendPacket(PacketCreator.getAuthSuccess(c));
            if (disconnectedBots > 0) {
                String suffix = disconnectedBots == 1 ? "" : "s";
                c.sendPacket(PacketCreator.serverNotice(5,
                        "Disconnected " + disconnectedBots + " active bot" + suffix + " from this account."));
            }
        } else {
            c.sendPacket(PacketCreator.getLoginFailed(9));//shouldn't happen XD
        }
    }
}
