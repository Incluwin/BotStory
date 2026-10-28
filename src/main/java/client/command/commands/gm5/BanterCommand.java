package client.command.commands.gm5;

import client.Character;
import client.Client;
import client.command.Command;
import server.living.LivingWorld;

/**
 * {@code !banter <player>} (GM 5, docs/BOT_CONCEPT_PLAN.md F6.4): a player's crew banter as it stands - the switch, the
 * mute, the party time toward the cap, a clear waiting for its moment, the map and the free companions there, and each
 * pair's tie and affinity. {@code !banter now <player>} starts one exchange at once whatever the cap and the roll (the
 * calm check and the mute still hold), for live checks and clips. On companion-ops.
 */
public final class BanterCommand extends Command {
    static final String USAGE = "Syntax: !banter <player> | !banter now <player>";

    {
        setDescription("Show a player's crew ties and banter, or start one exchange now.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character gm = c.getPlayer();
        boolean now = params.length == 2 && "now".equalsIgnoreCase(params[0]);
        if (params.length != 1 && !now) {
            gm.yellowMessage(USAGE);
            return;
        }
        LivingWorld world = LivingWorld.get();
        if (world == null || world.crew() == null) {
            gm.yellowMessage("The living world is off.");
            return;
        }
        String name = now ? params[1] : params[0];
        world.executor().submit("gm.banter " + name, () -> {
            int playerId = Character.getIdByName(name);
            if (playerId <= 0) {
                gm.yellowMessage("No character named " + name + ".");
                return;
            }
            if (now) {
                gm.yellowMessage(name + ": " + world.crew().banter().force(playerId));
                return;
            }
            for (String line : world.crew().banter().status(playerId)) {
                gm.yellowMessage(name + ": " + line);
            }
        });
    }
}
