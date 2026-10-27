package client.command.commands.gm0;

import client.Character;
import client.Client;
import client.command.Command;
import server.living.population.Population;
import server.living.population.needs.NeedLedger;

/**
 * {@code @requests} is the needs board in chat (docs/BOT_CONCEPT_PLAN.md F1): the requests you hold and the nearest open
 * ones, each with its number; {@code @requests claim <n>} holds one for you, {@code @requests drop <n>} lets it go. The
 * hand-over itself is in person (double-click the citizen) or at a Bulletin Board.
 */
public class RequestsCommand extends Command {
    {
        setDescription("Townsfolk's requests. Usage: @requests [claim <n>|drop <n>]");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        Population population = Population.get();
        NeedLedger needs = population == null ? null : population.needs();
        if (needs == null || !needs.on()) {
            player.dropMessage(5, "Nobody has pinned up any requests.");
            return;
        }
        long nowMs = System.currentTimeMillis();
        if (params.length >= 2 && (params[0].equalsIgnoreCase("claim") || params[0].equalsIgnoreCase("drop"))) {
            int id = number(params[1]);
            if (params[0].equalsIgnoreCase("drop")) {
                player.dropMessage(5, needs.drop(player.getId(), id, nowMs) ? "You let request #" + id + " go."
                        : "You don't hold request #" + id + ".");
                return;
            }
            String answer = switch (needs.claim(player.getId(), id, nowMs)) {
                case OK -> "Request #" + id + " is yours for " + population.config().NEEDS_CLAIM_MINUTES + " minutes.";
                case HELD -> "Someone else is already bringing that.";
                case LIMIT -> "You already hold " + population.config().NEEDS_CLAIMS_PER_PLAYER + " requests.";
                case GONE -> "There is no request #" + id + ".";
            };
            player.dropMessage(5, answer);
            return;
        }
        var lines = needs.boards().screens().chatList(player, nowMs);
        if (lines.isEmpty()) {
            player.dropMessage(5, "Nobody has pinned up any requests.");
            return;
        }
        player.dropMessage(5, "Requests (\"@requests claim <n>\" to hold one; bring it in person or to a Bulletin Board):");
        for (String line : lines) {
            player.dropMessage(5, line);
        }
    }

    private static int number(String text) {
        try {
            return Integer.parseInt(text.startsWith("#") ? text.substring(1) : text);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
