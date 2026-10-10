package client.command.commands.gm4;

import client.Character;
import client.Client;
import client.command.Command;
import server.expeditions.ExpeditionBossLog;

public class ResetBossCommand extends Command {
    {
        setDescription("Reset a player's daily boss entry counter.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (params.length < 2) {
            player.yellowMessage("Syntax: !resetboss <playername> <boss>");
            player.yellowMessage("Bosses: ZAKUM, HORNTAIL, PINKBEAN, SCARGA, PAPULATUS (CHAOS_ZAKUM / CHAOS_HORNTAIL also accepted).");
            return;
        }

        Character victim = c.getWorldServer().getPlayerStorage().getCharacterByName(params[0]);
        if (victim == null) {
            player.message("Player '" + params[0] + "' is not online. Only online players can be reset.");
            return;
        }

        String boss = params[1].toUpperCase();
        if (!ExpeditionBossLog.resetPlayerEntries(victim.getId(), boss)) {
            player.message("Unknown boss '" + params[1] + "'. Try ZAKUM, HORNTAIL, PINKBEAN, SCARGA, or PAPULATUS.");
            return;
        }

        player.message("Reset " + victim.getName() + "'s daily entry counter for " + boss + ".");
    }
}
