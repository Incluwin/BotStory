package client.command.commands.gm0;

import client.Character;
import client.Client;
import client.command.Command;
import server.living.population.Population;
import server.living.population.records.RecordsView;

/**
 * {@code @records} is the records page in chat (docs/BOT_CONCEPT_PLAN.md F7.1, decision D13): each party quest and boss
 * with a clear, who cleared it first and fastest, and your best; {@code @records <quest>} (kpq, balrog, zakum, ...) for
 * one. Open while population.RIVALS_ENABLED is on.
 */
public class RecordsCommand extends Command {
    {
        setDescription("Party quest and boss records. Usage: @records [quest]");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        Population population = Population.get();
        if (population == null || population.clears() == null || population.rivals() == null || !population.rivals().on()) {
            player.dropMessage(5, "Nobody keeps records here yet.");
            return;
        }
        String typed = params.length == 0 ? null : String.join(" ", params);
        for (String line : new RecordsView(population.clears()).chat(player, typed)) {
            player.dropMessage(5, line);
        }
    }
}
