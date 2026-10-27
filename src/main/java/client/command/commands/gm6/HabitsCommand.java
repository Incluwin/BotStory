package client.command.commands.gm6;

import client.Character;
import client.Client;
import client.command.Command;
import server.living.LivingWorld;

import java.util.List;

/**
 * {@code !habits <player>} (GM 6, docs/BOT_CONCEPT_PLAN.md F2.3): what companions learned of an online owner's habits
 * and what it rests on (OwnerHabitBook.report): how many drinks, drops, sales, kills and skill uses were seen, the
 * potion point, the loot categories skipped, the items sold, the best maps per level band and the skills that land
 * their damage. Read on companion-ops; an owner is watched only while online with owner habits on.
 */
public final class HabitsCommand extends Command {
    static final String USAGE = "Syntax: !habits <player>";

    {
        setDescription("Show what companions learned of a player's habits.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character gm = c.getPlayer();
        if (params.length != 1) {
            gm.yellowMessage(USAGE);
            return;
        }
        LivingWorld world = LivingWorld.get();
        if (world == null || world.ownerHabits() == null || !world.config().OWNER_HABITS_ENABLED) {
            gm.yellowMessage("Owner habits are off (living_world.OWNER_HABITS_ENABLED).");
            return;
        }
        Character target = c.getWorldServer().getPlayerStorage().getCharacterByName(params[0]);
        if (target == null) {
            gm.yellowMessage(params[0] + " is not online.");
            return;
        }
        world.executor().submit("gm.habits " + target.getId(), () -> {
            List<String> report = world.ownerHabits().report(target.getId());
            if (report == null) {
                gm.yellowMessage(target.getName() + " is not watched: they own no companion, or their habits are still"
                        + " being read.");
                return;
            }
            gm.yellowMessage(target.getName() + "'s habits:");
            report.forEach(gm::yellowMessage);
        });
    }
}
