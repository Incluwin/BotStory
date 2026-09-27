package client.command.commands.gm6;

import client.Client;
import client.command.Command;
import server.living.probe.CompanionProbeService;

/** GM-only harness for the P0 companion client probes; see CompanionProbeService. */
public final class CompanionProbeCommand extends Command {
    {
        setDescription("Run and observe the P0 companion client probes (party invite, hotkey item, NPC windows).");
    }

    @Override
    public void execute(Client client, String[] params) {
        CompanionProbeService.execute(client.getPlayer(), params);
    }
}
