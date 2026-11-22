package client.command.commands.gm5;

import client.Character;
import client.Client;
import client.command.Command;
import server.living.LivingWorld;
import server.living.config.Content.Persona;

/**
 * {@code !traits <companion>} (GM 5, docs/WORLD_SYSTEMS_PLAN.md N9): a companion's traits with its owner - the switch,
 * the traits it holds (living_bond.flags), what a fresh read of its runs would give, what that read is made of (its
 * runs, deaths and close calls over a year, its bench sales over thirty days) and its card lines. Reads only; on
 * companion-ops.
 */
public final class TraitsCommand extends Command {
    static final String USAGE = "Syntax: !traits <companion>";

    {
        setDescription("Show a companion's traits and what they are read from.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character gm = c.getPlayer();
        if (params.length != 1) {
            gm.yellowMessage(USAGE);
            return;
        }
        LivingWorld world = LivingWorld.get();
        if (world == null || world.traits() == null) {
            gm.yellowMessage("The living world is off.");
            return;
        }
        String name = params[0];
        world.executor().submit("gm.traits " + name, () -> {
            int characterId = Character.getIdByName(name);
            Persona persona = characterId <= 0 ? null : world.residents().personaOfCharacterId(characterId);
            int ownerId = persona == null ? 0 : world.roster().ownerOf(persona.id());
            if (ownerId == 0) {
                gm.yellowMessage(name + " is nobody's companion.");
                return;
            }
            for (String line : world.traits().status(ownerId, persona.id())) {
                gm.yellowMessage(name + ": " + line);
            }
        });
    }
}
