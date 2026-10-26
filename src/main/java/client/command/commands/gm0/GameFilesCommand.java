package client.command.commands.gm0;

import client.Character;
import client.Client;
import client.command.Command;
import server.mods.RepackMaps;

/**
 * {@code @gamefiles [repack|cosmic]}: which game files the player's client has, for the Community Repack's maps
 * (server.mods.RepackMaps, docs/REPACK_IMPORT_PLAN.md). With no word it says what the server knows.
 */
public class GameFilesCommand extends Command {
    {
        setDescription("Tell the server which game files you installed (Repack or Cosmic). Usage: @gamefiles [repack|cosmic]");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        RepackMaps maps = RepackMaps.get();
        if (params.length == 0) {
            player.yellowMessage(switch (maps.files(c)) {
                case REPACK -> "Your game files: the full Community Repack set. Its extra towns are open to you.";
                case COSMIC -> "Your game files: the smaller Cosmic set. The Repack's extra towns stay closed to you.";
                case UNKNOWN -> "The server does not know your game files yet. Type @gamefiles repack or @gamefiles"
                        + " cosmic (a base=cosmic line in your game folder's config.ini means Cosmic).";
            });
            return;
        }
        RepackMaps.Files files = RepackMaps.Files.of(params[0]);
        if (files == null) {
            player.yellowMessage("Usage: @gamefiles [repack|cosmic]");
            return;
        }
        maps.record(c.getAccID(), files, RepackMaps.Source.PLAYER);
        player.yellowMessage(files == RepackMaps.Files.REPACK
                ? "Noted: the full Community Repack set. If your game closes when you enter one of its towns, the server"
                + " notes the Cosmic set instead."
                : "Noted: the smaller Cosmic set. The Repack's extra towns stay closed, so your game never closes there.");
    }
}
