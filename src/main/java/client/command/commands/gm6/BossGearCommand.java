package client.command.commands.gm6;

import client.Character;
import client.Client;
import client.command.Command;
import server.living.LivingWorld;
import server.living.roster.CompanionGearTool;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * {@code !bossgear <me|name> [preview|remove]} (GM 6, docs/COMPANION_BOSSING_PLAN.md G13): a level-fitting kit for boss
 * tests, a piece per slot from this server's obtainable gear with every upgrade slot a passed 60% scroll, untradeable
 * and tagged "bossgear" (CompanionGearTool, BossGearPlanner). {@code me} puts it in the GM's Equip tab to put on; a name
 * is one of the GM's own companions out with them, which wears it. {@code preview} lists the kit and gives nothing.
 * {@code remove} takes every boss piece back from the GM or that companion, worn and carried, and nothing else; the
 * companion then wears its own gear again. Companions need the living world; the work runs on companion-ops.
 */
public final class BossGearCommand extends Command {
    static final String USAGE = "Syntax: !bossgear <me|name> [preview|remove]";

    {
        setDescription("Give you or your companion a level-fitting boss kit, every slot a passed 60% scroll, or take it"
                + " back.");
    }

    /** One parsed line: the target ("me" or a companion's name), whether it only previews, whether it takes the kit off. */
    record Line(String target, boolean preview, boolean remove) {
    }

    private final Function<LivingWorld, CompanionGearTool> tools;

    public BossGearCommand() {
        this(CompanionGearTool::new);
    }

    BossGearCommand(Function<LivingWorld, CompanionGearTool> tools) {
        this.tools = tools;
    }

    /** Null when the line matches no form. */
    static Line parse(String[] params) {
        if (params.length == 1) {
            return new Line(params[0], false, false);
        }
        if (params.length == 2 && params[1].equalsIgnoreCase("preview")) {
            return new Line(params[0], true, false);
        }
        if (params.length == 2 && params[1].equalsIgnoreCase("remove")) {
            return new Line(params[0], false, true);
        }
        return null;
    }

    @Override
    public void execute(Client c, String[] params) {
        Character gm = c.getPlayer();
        Line line = parse(params);
        if (line == null) {
            gm.yellowMessage(USAGE);
            return;
        }
        LivingWorld world = LivingWorld.get();
        CompanionGearTool tool = tools.apply(world);
        if (world == null) {
            run(tool, gm, line).thenAccept(gm::yellowMessage); // "me" only: it refuses a name
            return;
        }
        world.executor().submit("gm.bossgear " + line.target(),
                () -> run(tool, gm, line).thenAccept(gm::yellowMessage));
    }

    private static CompletableFuture<String> run(CompanionGearTool tool, Character gm, Line line) {
        return line.remove() ? tool.remove(gm, line.target()) : tool.use(gm, line.target(), line.preview());
    }
}
