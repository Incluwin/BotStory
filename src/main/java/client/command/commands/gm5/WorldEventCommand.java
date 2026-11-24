package client.command.commands.gm5;

import client.Character;
import client.Client;
import client.command.Command;
import server.living.population.Population;

import java.util.Locale;

/**
 * {@code !worldevent list|start <id> [site]|stop <id>} (docs/WORLD_SYSTEMS_PLAN.md N4): the calendar, its rows and the
 * busiest hours it plans from; an event started now (an occurrence of its own); a running one ended with no result.
 */
public class WorldEventCommand extends Command {
    {
        setDescription("World events. Usage: !worldevent list|start <id> [site]|stop <id>");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character gm = c.getPlayer();
        Population population = Population.get();
        if (population == null || population.worldEvents() == null) {
            gm.yellowMessage("Population is disabled (population.ENABLED in config.yaml, needs living_world.ENABLED).");
            return;
        }
        long now = System.currentTimeMillis();
        String verb = params.length == 0 ? "list" : params[0].toLowerCase(Locale.ROOT);
        if (verb.equals("list")) {
            population.worldEvents().list(now).forEach(gm::yellowMessage);
        } else if (verb.equals("start") && params.length > 1) {
            gm.yellowMessage(population.worldEvents().start(params[1], params.length > 2 ? params[2] : null, now));
        } else if (verb.equals("stop") && params.length > 1) {
            gm.yellowMessage(population.worldEvents().stop(params[1]));
        } else {
            gm.yellowMessage("Usage: !worldevent list|start <id> [site]|stop <id>");
        }
    }
}
