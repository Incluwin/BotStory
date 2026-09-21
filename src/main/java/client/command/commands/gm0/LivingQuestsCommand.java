package client.command.commands.gm0;

import client.Character;
import client.Client;
import client.command.Command;
import server.living.LivingWorld;

/** {@code @quests} lists the player's active resident quests and their current step. */
public class LivingQuestsCommand extends Command {
    {
        setDescription("Show your resident quests.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        LivingWorld world = LivingWorld.get();
        if (world == null) {
            player.dropMessage(5, "The living world is not enabled on this server.");
            return;
        }
        world.quests().showLog(player);
    }
}
