package client.command.commands.gm5;

import client.Character;
import client.Client;
import client.command.Command;
import server.living.LivingWorld;

/**
 * {@code !mentor <player>} (GM 5, docs/BOT_CONCEPT_PLAN.md F4): a new character's mentorship as it stands (its mentor
 * and level, lead, voice, branch pick, tips told and muted, the leg it leads), or the newest mentor row and why there
 * is none. {@code !mentor offer <player>} opens the once-only offer again at that character's next login (test
 * characters whose first offer was missed); it still needs the rest of the gate (Lv 1 Beginner on Maple Island).
 * On companion-ops.
 */
public final class MentorCommand extends Command {
    static final String USAGE = "Syntax: !mentor <player> | !mentor offer <player>";

    {
        setDescription("Show a new character's mentor, or open its mentor offer again.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character gm = c.getPlayer();
        boolean rearm = params.length == 2 && "offer".equalsIgnoreCase(params[0]);
        if (params.length != 1 && !rearm) {
            gm.yellowMessage(USAGE);
            return;
        }
        LivingWorld world = LivingWorld.get();
        if (world == null || world.mentors() == null) {
            gm.yellowMessage("The living world is off.");
            return;
        }
        String name = rearm ? params[1] : params[0];
        world.executor().submit("gm.mentor " + name, () -> {
            int playerId = Character.getIdByName(name);
            if (playerId <= 0) {
                gm.yellowMessage("No character named " + name + ".");
                return;
            }
            if (rearm) {
                world.mentors().rearm(playerId);
                gm.yellowMessage(name + ": the mentor offer opens again at their next login.");
                return;
            }
            gm.yellowMessage(world.mentors().status(playerId, name));
        });
    }
}
