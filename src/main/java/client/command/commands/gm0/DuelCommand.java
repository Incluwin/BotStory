package client.command.commands.gm0;

import client.Character;
import client.Client;
import client.command.Command;
import server.living.population.Population;

/**
 * {@code @duel yes|no} answers a rival crew's duel challenge (docs/PVP_PLAN.md V3), the same as saying yes or no in map
 * chat or whispering it to one of them.
 */
public class DuelCommand extends Command {
    {
        setDescription("Answer a rival crew's duel challenge. Usage: @duel yes|no");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        String word = params.length == 0 ? "" : params[0].toLowerCase(java.util.Locale.ROOT);
        boolean yes = word.equals("yes") || word.equals("y") || word.equals("accept");
        boolean no = word.equals("no") || word.equals("n") || word.equals("decline");
        if (!yes && !no) {
            player.dropMessage(5, "Usage: @duel yes|no");
            return;
        }
        Population population = Population.get();
        if (population == null || population.rivals() == null) {
            player.dropMessage(5, "Nobody has challenged you.");
            return;
        }
        String told = population.rivals().challenges().command(player, yes);
        if (told != null) {
            player.dropMessage(5, told);
        }
    }
}
