package client.command.commands.gm5;

import client.Character;
import client.Client;
import client.command.Command;
import server.living.population.Citizen;
import server.living.population.Population;

import java.util.List;

/**
 * {@code !ties <citizen>} lists what a citizen is to other citizens (docs/WORLD_SYSTEMS_PLAN.md N2.1): each FRIEND or
 * RIVAL tie, strongest first, felt and stored; without a name, the tie book's one-line report. Read only.
 */
public class TiesCommand extends Command {
    {
        setDescription("Citizen ties. Usage: !ties [citizen]");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character gm = c.getPlayer();
        Population population = Population.get();
        if (population == null || population.ties() == null) {
            gm.yellowMessage("Population is disabled (population.ENABLED in config.yaml, needs living_world.ENABLED).");
            return;
        }
        gm.yellowMessage(population.ties().report());
        if (params.length < 1) {
            return;
        }
        Citizen citizen = population.citizenByName(params[0]);
        if (citizen == null) {
            gm.yellowMessage("No citizen named " + params[0]);
            return;
        }
        List<String> ties = population.ties().describe(citizen.characterId());
        gm.yellowMessage(citizen.name() + " has " + ties.size() + (ties.size() == 1 ? " tie" : " ties"));
        for (String tie : ties) {
            gm.yellowMessage("  " + tie);
        }
    }
}
