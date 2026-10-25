package client.command.commands.gm6;

import client.Character;
import client.Client;
import client.command.Command;
import server.agents.capabilities.partyquest.apq.AgentApqTestService;

/** GM-only Amorian Challenge harness: adopt a companion party, marry a test party, skip to a stage. */
public final class ApqTestCommand extends Command {
    { setDescription("Run and inspect companion Amorian Challenge (APQ) runs."); }
    @Override public void execute(Client client, String[] params) {
        Character operator = client.getPlayer();
        AgentApqTestService.execute(operator, params, System.currentTimeMillis())
                .forEach(line -> operator.dropMessage(6, line));
    }
}
