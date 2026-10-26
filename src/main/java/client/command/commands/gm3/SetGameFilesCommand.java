package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.command.Command;
import server.mods.RepackMaps;

/**
 * {@code !setgamefiles <player> [repack|cosmic|forget]}: what the server knows of an online player's game files, for the
 * Community Repack's maps (server.mods.RepackMaps), or the GM's answer for them.
 */
public class SetGameFilesCommand extends Command {
    {
        setDescription("Show or set a player's game files. Usage: !setgamefiles <player> [repack|cosmic|forget]");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character gm = c.getPlayer();
        if (params.length < 1) {
            gm.yellowMessage("Usage: !setgamefiles <player> [repack|cosmic|forget]");
            return;
        }
        Character target = c.getWorldServer().getPlayerStorage().getCharacterByName(params[0]);
        if (target == null || target.getClient() == null) {
            gm.yellowMessage("Player '" + params[0] + "' could not be found on this world.");
            return;
        }
        RepackMaps maps = RepackMaps.get();
        int account = target.getClient().getAccID();
        if (params.length == 1) {
            gm.yellowMessage(target.getName() + ": " + maps.files(target.getClient()).word()
                    + maps.answer(account).map(answer -> " (answered by " + answer.source().word() + ")").orElse(""));
            return;
        }
        if (params[1].equalsIgnoreCase("forget")) {
            maps.forget(account);
            gm.yellowMessage(target.getName() + "'s game files are forgotten; the entry NPCs ask again.");
            return;
        }
        RepackMaps.Files files = RepackMaps.Files.of(params[1]);
        if (files == null) {
            gm.yellowMessage("Usage: !setgamefiles <player> [repack|cosmic|forget]");
            return;
        }
        maps.record(account, files, RepackMaps.Source.GM);
        gm.yellowMessage(target.getName() + "'s game files: " + files.word() + ".");
    }
}
