package client.command.commands.gm6;

import client.Client;
import client.command.Command;
import server.agents.capabilities.partyquest.rjpq.AgentRjpqTestService;

/** GM-only Romeo and Juliet PQ companion-run harness. */
public final class RjpqTestCommand extends Command {
    { setDescription("Adopt and inspect a Romeo and Juliet PQ companion run."); }

    @Override
    public void execute(Client client, String[] params) {
        AgentRjpqTestService.execute(client.getPlayer(), params, System.currentTimeMillis())
                .forEach(line -> client.getPlayer().dropMessage(6, line));
    }
}
