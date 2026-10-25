package client.command.commands.gm6;

import client.Client;
import client.command.Command;
import server.agents.capabilities.expedition.gpq.AgentGpqTestService;

/** GM-only Guild Quest (Sharenian) companion-run harness. */
public final class GpqTestCommand extends Command {
    { setDescription("Inspect a Guild Quest companion run and skip it to a stage."); }

    @Override
    public void execute(Client client, String[] params) {
        AgentGpqTestService.execute(client.getPlayer(), params, System.currentTimeMillis())
                .forEach(line -> client.getPlayer().dropMessage(6, line));
    }
}
