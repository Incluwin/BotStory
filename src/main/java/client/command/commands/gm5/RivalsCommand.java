package client.command.commands.gm5;

import client.Character;
import client.Client;
import client.command.Command;
import server.living.population.Population;
import server.living.population.records.Clear;
import server.living.population.records.ClearLog;

/**
 * {@code !rivals} (docs/BOT_CONCEPT_PLAN.md F7): every rival crew in one line each; {@code !rivals <player>} that
 * player's crew (members, levels, who is online or leaving, its bests beside the player's); {@code !rivals clears} the
 * last clears booked.
 */
public class RivalsCommand extends Command {
    {
        setDescription("Rival crews and clear records. Usage: !rivals [<player>|clears]");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character gm = c.getPlayer();
        Population population = Population.get();
        if (population == null) {
            gm.yellowMessage("Population is disabled (population.ENABLED in config.yaml, needs living_world.ENABLED).");
            return;
        }
        if (params.length == 0) {
            population.rivals().summary().forEach(gm::yellowMessage);
            return;
        }
        if (params[0].equalsIgnoreCase("clears")) {
            var recent = population.clears().recent();
            if (recent.isEmpty()) {
                gm.yellowMessage("No clears booked since the boot" + (population.config().CLEAR_RECORDS_ENABLED ? ""
                        : " (CLEAR_RECORDS_ENABLED is off)") + ".");
            }
            for (Clear clear : recent) {
                gm.yellowMessage(clear.quest() + " " + clear.kind() + " " + (clear.timed() ? ClearLog.time(clear.durationMs()) : "-")
                        + (clear.crewId() > 0 ? " crew " + clear.crewId() : "") + ": " + ClearLog.names(clear, 6));
            }
            return;
        }
        Character target = c.getWorldServer().getPlayerStorage().getCharacterByName(params[0]);
        int accountId = target != null ? target.getAccountID() : accountOf(params[0]);
        if (accountId <= 0) {
            gm.yellowMessage("No character named " + params[0] + ".");
            return;
        }
        population.rivals().report(accountId).forEach(gm::yellowMessage);
    }

    private static int accountOf(String name) {
        try (var con = tools.DatabaseConnection.getConnection();
             var ps = con.prepareStatement("SELECT accountid FROM characters WHERE name = ?")) {
            ps.setString(1, name);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (java.sql.SQLException failure) {
            return 0;
        }
    }
}
