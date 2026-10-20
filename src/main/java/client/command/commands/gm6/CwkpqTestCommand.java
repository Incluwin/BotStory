package client.command.commands.gm6;

import client.Client;
import client.command.Command;
import server.agents.capabilities.expedition.cwkpq.AgentCwkpqTestService;

/** GM-only Crimsonwood Keep PQ companion-run harness. */
public final class CwkpqTestCommand extends Command {
    { setDescription("Inspect a Crimsonwood Keep PQ companion run and skip it to a stage."); }

    @Override
    public void execute(Client client, String[] params) {
        AgentCwkpqTestService.execute(client.getPlayer(), params, System.currentTimeMillis())
                .forEach(line -> client.getPlayer().dropMessage(6, line));
    }
}
