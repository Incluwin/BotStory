package client.command.commands.gm0;

import client.Character;
import client.Client;
import client.command.Command;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.Bond;

/** {@code @roster} lists companions; {@code @bond <name>} shows the relationship with a resident. */
public class LivingRosterCommand extends Command {
    {
        setDescription("Show your companions, or @roster <name> for your bond with a resident.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        LivingWorld world = LivingWorld.get();
        if (world == null) {
            player.dropMessage(5, "The living world is not enabled on this server.");
            return;
        }
        if (params.length == 0) {
            for (String line : world.roster().summary(player)) {
                player.dropMessage(6, line);
            }
            return;
        }
        Persona persona = world.content().personaByName(params[0]);
        if (persona == null) {
            player.dropMessage(5, "No resident named " + params[0] + ".");
            return;
        }
        Bond bond = world.bonds().peek(player.getId(), persona.id());
        if (bond == null) {
            player.dropMessage(6, persona.name() + " does not know you yet.");
            return;
        }
        player.dropMessage(6, persona.name() + ": " + bond.stage().name().toLowerCase() + " (" + bond.points() + " points), contract "
                + bond.contractState().name().toLowerCase() + (bond.flags().isEmpty() ? "" : ", knows: " + String.join(", ", bond.flags())));
        for (String line : world.roster().haulSummary(player, persona)) {
            if (!line.endsWith("is not in your roster.")) {
                player.dropMessage(6, line);
            }
        }
    }
}
