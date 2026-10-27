package client.command.commands.gm5;

import client.Character;
import client.Client;
import client.command.Command;
import server.living.LivingWorld;
import server.living.standing.StandingReport;

/**
 * {@code !standing <bot> [player]} (GM 5, docs/BOT_CONCEPT_PLAN.md C1.1): the rung (STRANGER, REGULAR, FRIEND, CLOSE)
 * the player (you by default) has with a Resident, a citizen or a hired citizen, and the raw numbers behind it
 * (StandingReport). Read-only, on companion-ops.
 */
public final class StandingCommand extends Command {
    static final String USAGE = "Syntax: !standing <bot> [player]";

    {
        setDescription("Show a player's standing with a bot and the numbers behind it.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character gm = c.getPlayer();
        if (params.length < 1 || params.length > 2) {
            gm.yellowMessage(USAGE);
            return;
        }
        LivingWorld world = LivingWorld.get();
        if (world == null) {
            gm.yellowMessage("The living world is off.");
            return;
        }
        String bot = params[0];
        String playerName = params.length == 2 ? params[1] : gm.getName();
        world.executor().submit("gm.standing " + bot, () -> {
            int playerId = params.length == 2 ? Character.getIdByName(playerName) : gm.getId();
            if (playerId <= 0) {
                gm.yellowMessage("No character named " + playerName + ".");
                return;
            }
            StandingReport.Report report = StandingReport.of(world, bot, playerId, playerName, Character::getNameById);
            if (report == null) {
                gm.yellowMessage("No Resident or citizen named " + bot + ".");
                return;
            }
            report.lines().forEach(gm::yellowMessage);
        });
    }
}
