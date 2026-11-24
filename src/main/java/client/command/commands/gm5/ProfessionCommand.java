package client.command.commands.gm5;

import client.Character;
import client.Client;
import client.command.Command;
import server.living.population.Citizen;
import server.living.population.Population;
import server.living.population.craft.Commissions;
import server.living.population.craft.Profession;

/**
 * {@code !profession <citizen>} shows a citizen's trade and the orders it has out (docs/WORLD_SYSTEMS_PLAN.md N8);
 * {@code !profession <citizen> <smith|tailor|alchemist|scroller|none>} gives it one, {@code !profession <citizen> finish}
 * counts the order it works on as worked through (its next minute delivers it); {@code !profession} the counts. For
 * checks, without the board's windows: {@code !profession <citizen> order <recipe> [count]}, {@code !profession cancel
 * <order>}, {@code !profession buy <item> <count> <price each>}, {@code !profession down <buy order>}.
 */
public class ProfessionCommand extends Command {
    {
        setDescription("Citizen trades. Usage: !profession [<citizen> [smith|tailor|alchemist|scroller|none|finish]]");
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
            int[] counts = new int[Profession.values().length];
            for (Citizen citizen : population.citizens()) {
                Profession trade = Profession.of(citizen);
                if (trade != null && citizen.state() == Citizen.State.ACTIVE) {
                    counts[trade.ordinal()]++;
                }
            }
            StringBuilder line = new StringBuilder("trades: on=" + population.professions().on());
            for (Profession trade : Profession.values()) {
                line.append(' ').append(trade.word()).append('=').append(counts[trade.ordinal()]);
            }
            gm.yellowMessage(line + "; " + population.commissions().report() + "; " + population.buyOrders().report());
            return;
        }
        long nowMs = System.currentTimeMillis();
        if (params.length > 1 && params[0].equalsIgnoreCase("cancel")) {
            String refused = population.commissions().cancel(gm, number(params[1]), nowMs);
            gm.yellowMessage(refused == null ? "cancelled" : "refused: " + refused);
            return;
        }
        if (params.length > 3 && params[0].equalsIgnoreCase("buy")) {
            String refused = population.buyOrders().post(gm, number(params[1]), number(params[2]), number(params[3]), nowMs);
            gm.yellowMessage(refused == null ? "posted" : "refused: " + refused);
            return;
        }
        if (params.length > 1 && params[0].equalsIgnoreCase("down")) {
            String refused = population.buyOrders().takeDown(gm, number(params[1]), nowMs);
            gm.yellowMessage(refused == null ? "taken down" : "refused: " + refused);
            return;
        }
        Citizen citizen = population.citizenByName(params[0]);
        if (citizen == null) {
            gm.yellowMessage("No citizen named " + params[0] + ".");
            return;
        }
        if (params.length > 2 && params[1].equalsIgnoreCase("order")) {
            Commissions.Ordered ordered = population.commissions().order(gm, citizen.characterId(), params[2],
                    params.length > 3 ? number(params[3]) : 1, nowMs);
            gm.yellowMessage(ordered.refused() == null ? "ordered #" + ordered.order().id() : "refused: " + ordered.refused());
            return;
        }
        if (params.length > 1 && params[1].equalsIgnoreCase("finish")) {
            gm.yellowMessage(population.commissions().finish(citizen.characterId()) ? citizen.name() + "'s order is worked"
                    + " through; its next minute delivers it." : citizen.name() + " is not at work on an order.");
            return;
        }
        if (params.length > 1) {
            Profession trade = Profession.parse(params[1]);
            if (trade == null && !params[1].equalsIgnoreCase("none")) {
                gm.yellowMessage("No trade named " + params[1] + " (smith, tailor, alchemist, scroller, none).");
                return;
            }
            population.professions().set(citizen.characterId(), trade);
            citizen = population.citizen(citizen.characterId());
        }
        Profession trade = Profession.of(citizen);
        gm.yellowMessage(citizen.name() + ": " + (trade == null ? "no trade" : trade.word() + ", "
                + population.professions().book().of(trade).size() + " recipes")
                + (population.professions().on() ? "" : " (PROFESSIONS_ENABLED is off)")
                + ", " + (population.presence().session(citizen.characterId()) == null ? "away" : "online"));
        for (String line : population.commissions().describe(citizen.characterId(), nowMs)) {
            gm.yellowMessage(line);
        }
    }

    private static int number(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }
}
