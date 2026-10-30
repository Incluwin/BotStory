package client.command.commands.gm5;

import client.Character;
import client.Client;
import client.command.Command;
import server.living.LivingWorld;

/**
 * {@code !story [player]} (GM 5, docs/BOT_CONCEPT_PLAN.md F6): where the player (you by default) stands in each
 * companion's personal arc (each chapter's stage, the race), a scene they are in and an attended job errand
 * (StoryArcs.report). Read-only, on companion-ops.
 */
public final class StoryCommand extends Command {
    static final String USAGE = "Syntax: !story [player]";

    {
        setDescription("Show a player's personal story arcs, scene and attended errand.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character gm = c.getPlayer();
        if (params.length > 1) {
            gm.yellowMessage(USAGE);
            return;
        }
        LivingWorld world = LivingWorld.get();
        if (world == null || world.stories() == null) {
            gm.yellowMessage("The living world is off.");
            return;
        }
        String playerName = params.length == 1 ? params[0] : gm.getName();
        world.executor().submit("gm.story " + playerName, () -> {
            int playerId = params.length == 1 ? Character.getIdByName(playerName) : gm.getId();
            if (playerId <= 0) {
                gm.yellowMessage("No character named " + playerName + ".");
                return;
            }
            world.stories().report(playerId).forEach(gm::yellowMessage);
        });
    }
}
