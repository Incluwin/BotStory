package client.command.commands.gm0;

import client.Character;
import client.Client;
import client.command.Command;
import server.living.LivingWorld;
import server.living.config.Content.BondStage;
import server.living.config.Content.Persona;
import server.living.roster.ui.CompanionCardScreens;
import server.living.store.LivingStore.Bond;
import server.living.store.LivingStore.ContractState;

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
            player.dropMessage(5, "There are no residents on this server.");
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
        player.dropMessage(6, bondLine(persona.name(), bond.stage(), bond.points(), bond.contractState())
                + (bond.flags().isEmpty() || !player.isGM() ? "" : ", knows: " + String.join(", ", bond.flags())));
        player.dropMessage(6, "Friendship " + world.friendship().describe(bond.points()));
        for (String line : world.roster().haulSummary(player, persona)) {
            if (!line.endsWith("is not in your roster.")) {
                player.dropMessage(6, line);
            }
        }
    }

    /** "Lia: Trusted (35 points), hired": the bond in the companion card's words. */
    static String bondLine(String name, BondStage stage, int points, ContractState contract) {
        String terms = switch (contract == null ? ContractState.NONE : contract) {
            case NONE -> "no contract";
            case OFFERED -> "contract offered";
            case ACTIVE -> "hired";
            case ENDED -> "contract ended";
        };
        return name + ": " + CompanionCardScreens.bondName(stage) + " (" + points + " points), " + terms;
    }
}
