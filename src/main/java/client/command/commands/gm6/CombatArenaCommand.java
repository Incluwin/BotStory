package client.command.commands.gm6;

import client.Character;
import client.Client;
import client.command.Command;
import server.agents.diagnostics.arena.AgentCombatArenaService;

/** GM-only combat arena: an Agent measures its best rotation per scenario; see AgentCombatArenaService. */
public final class CombatArenaCommand extends Command {
    {
        setDescription("Let an Agent find its best combat loop per scenario by measurement and save the profile.");
    }

    @Override
    public void execute(Client client, String[] params) {
        Character operator = client.getPlayer();
        AgentCombatArenaService.execute(operator, params)
                .forEach(line -> operator.dropMessage(6, line));
    }
}
