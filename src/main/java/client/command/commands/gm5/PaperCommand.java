package client.command.commands.gm5;

import client.Character;
import client.Client;
import client.command.Command;
import server.living.population.Population;
import server.living.population.paper.PaperDesk;
import server.living.population.paper.PaperIssue;
import server.living.population.paper.PaperView;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * {@code !paper [week]} (docs/WORLD_SYSTEMS_PLAN.md N1): composes the town paper of a week from the tables and filed
 * stories as it would read now and prints it, without keeping it. {@code week} is any date in the week (2026-11-16)
 * or a week_of number; with none, the newest week whose issue time has passed. {@code !paper issue} keeps that week's
 * issue now if it has none (the Sunday minute's own work, so a second call writes nothing).
 */
public class PaperCommand extends Command {
    {
        setDescription("Compose and print the town paper. Usage: !paper [yyyy-mm-dd|week_of|issue]");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character gm = c.getPlayer();
        Population population = Population.get();
        PaperDesk desk = population == null ? null : population.paper();
        if (desk == null) {
            gm.yellowMessage("Population is disabled (population.ENABLED in config.yaml, needs living_world.ENABLED).");
            return;
        }
        long now = System.currentTimeMillis();
        String off = population.config().PAPER_ENABLED ? "" : " (population.PAPER_ENABLED is off: nothing is filed,"
                + " issued on Sunday or shown at the boards)";
        if (params.length > 0 && params[0].equalsIgnoreCase("issue")) {
            int week = desk.lastDueWeek(now);
            Optional<PaperIssue> issue = desk.issue(week);
            gm.yellowMessage(issue.isEmpty() ? "Week " + week + ": the issue could not be read or written." + off
                    : "Week " + week + " (" + LocalDate.ofEpochDay(week) + ") is issued." + off);
            issue.ifPresent(one -> PaperView.lines(one, desk.catalog().title()).forEach(gm::yellowMessage));
            return;
        }
        int week;
        try {
            week = params.length == 0 ? desk.lastDueWeek(now) : parseWeek(desk, params[0]);
        } catch (IllegalArgumentException bad) {
            gm.yellowMessage("Not a week: " + params[0] + ". Give a date (2026-11-16) or a week_of number.");
            return;
        }
        PaperIssue issue = desk.compose(week);
        gm.yellowMessage("Week " + week + " (from Monday " + LocalDate.ofEpochDay(week) + "), composed now, not kept." + off);
        PaperView.lines(issue, desk.catalog().title()).forEach(gm::yellowMessage);
    }

    static int parseWeek(PaperDesk desk, String text) {
        LocalDate day;
        try {
            day = LocalDate.ofEpochDay(Integer.parseInt(text.trim()));
        } catch (NumberFormatException notANumber) {
            try {
                day = LocalDate.parse(text.trim());
            } catch (DateTimeParseException notADate) {
                throw new IllegalArgumentException(text);
            }
        }
        return desk.weekOf(day.atStartOfDay(ZoneId.systemDefault()).plusHours(12).toInstant().toEpochMilli());
    }
}
