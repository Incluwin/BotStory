package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.command.Command;
import server.agents.integration.AgentPartyGatewayRuntime;

/** Leaves the operator's party (disbanding it when the operator leads); ends a filmed two-person party. */
public class LeavePartyCommand extends Command {
    {
        setDescription("Leave your party (disbands it if you lead it).");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (player.getParty() == null) {
            player.yellowMessage("You are not in a party.");
            return;
        }
        AgentPartyGatewayRuntime.party().leaveCurrentParty(player);
        player.yellowMessage(player.getParty() == null ? "Left the party." : "Could not leave the party.");
    }
}
