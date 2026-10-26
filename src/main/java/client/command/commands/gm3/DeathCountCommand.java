package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.command.Command;
import scripting.event.EventInstanceManager;
import server.expeditions.ExpeditionDeathCount;

/** {@code !deathcount [n]}: shows or sets the death count of the boss run you are in; a negative n turns it off. */
public class DeathCountCommand extends Command {
    {
        setDescription("Show or set your boss run's death count (negative: off).");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        EventInstanceManager eim = player.getEventInstance();
        if (eim == null || eim.getExpedition() == null) {
            player.dropMessage(5, "You are not in a boss run.");
            return;
        }
        ExpeditionDeathCount count = eim.getDeathCount();
        if (params.length < 1) {
            player.dropMessage(5, count.isOn() ? "Death count: " + count.left() + " left." : "The death count is off for this run.");
            return;
        }
        int deaths;
        try {
            deaths = Integer.parseInt(params[0]);
        } catch (NumberFormatException e) {
            player.yellowMessage("Syntax: !deathcount [deaths, negative turns it off]");
            return;
        }
        count.set(deaths);
        if (deaths < 0) {
            eim.sendDeathCountHidden();
        } else {
            eim.sendDeathCount();
        }
        player.dropMessage(5, deaths < 0 ? "The death count is off for this run." : "Death count set to " + deaths + ".");
    }
}
