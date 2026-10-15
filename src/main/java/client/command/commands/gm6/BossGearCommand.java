package client.command.commands.gm6;

import client.Character;
import client.Client;
import client.command.Command;
import server.living.LivingWorld;
import server.living.roster.CompanionGearTool;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * {@code !bossgear <me|name> [boss] [preview|remove]} (GM 6, docs/COMPANION_BOSSING_PLAN.md G13): a level-fitting kit
 * for boss tests, a piece per slot from this server's obtainable gear with every upgrade slot a passed 10% scroll,
 * untradeable and tagged "bossgear" (CompanionGearTool, BossGearPlanner). With a boss key (boss-rules.json: showa,
 * horntail, ...) the kit also reaches the accuracy that boss's parts need, from accessories and accuracy scrolls, losing
 * as little damage as it can; the reply says how short it stays. {@code me} puts it in the GM's Equip tab to put on; a name
 * is one of the GM's own companions out with them, which wears it. {@code preview} lists the kit and gives nothing.
 * {@code remove} takes every boss piece back from the GM or that companion, worn and carried, and nothing else; the
 * companion then wears its own gear again. Companions need the living world; the work runs on companion-ops.
 */
public final class BossGearCommand extends Command {
    static final String USAGE = "Syntax: !bossgear <me|name> [boss] [preview|remove]";

    {
        setDescription("Give you or your companion a level-fitting boss kit, every slot a passed 10% scroll, or take it"
                + " back.");
    }

    /**
     * One parsed line: the target ("me" or a companion's name), whether it only previews, whether it takes the kit off,
     * and the boss whose accuracy the kit reaches (null: none).
     */
    record Line(String target, boolean preview, boolean remove, String boss) {
        Line(String target, boolean preview, boolean remove) {
            this(target, preview, remove, null);
        }
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
        if (params.length == 2) {
            return new Line(params[0], false, false, params[1]);
        }
        if (params.length == 3 && params[2].equalsIgnoreCase("preview") && !params[1].equalsIgnoreCase("remove")
                && !params[1].equalsIgnoreCase("preview")) {
            return new Line(params[0], true, false, params[1]);
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
        if (line.remove()) {
            return tool.remove(gm, line.target());
        }
        return line.boss() == null ? tool.use(gm, line.target(), line.preview())
                : tool.use(gm, line.target(), line.preview(), line.boss());
    }
}
