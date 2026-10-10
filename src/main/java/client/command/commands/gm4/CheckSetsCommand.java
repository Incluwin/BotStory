package client.command.commands.gm4;

import client.Character;
import client.Client;
import client.command.Command;
import server.set.ActiveSet;
import server.set.SetBonusTotal;

public class CheckSetsCommand extends Command {
    {
        setDescription("Show currently-active set effects and their cumulative bonuses.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        SetBonusTotal bonus = player.getSetBonus();
        if (bonus.activeSets.isEmpty()) {
            player.yellowMessage("No set effects active.");
            return;
        }
        for (ActiveSet a : bonus.activeSets) {
            player.yellowMessage(a.name + " (" + a.pieces + "/" + a.total + ")");
        }
        StringBuilder stats = new StringBuilder("Cumulative: ");
        appendStat(stats, "STR", bonus.str);
        appendStat(stats, "DEX", bonus.dex);
        appendStat(stats, "INT", bonus.int_);
        appendStat(stats, "LUK", bonus.luk);
        appendStat(stats, "WA", bonus.watk);
        appendStat(stats, "MA", bonus.matk);
        appendStat(stats, "HP", bonus.hp);
        appendStat(stats, "MP", bonus.mp);
        if (stats.length() == "Cumulative: ".length()) {
            stats.append("(no applied stats)");
        }
        player.yellowMessage(stats.toString());

        // Stats recorded but not yet wired to combat math.
        StringBuilder pending = new StringBuilder("Pending (not yet applied): ");
        int lenBefore = pending.length();
        appendStat(pending, "WDEF", bonus.wdef);
        appendStat(pending, "MDEF", bonus.mdef);
        appendStat(pending, "ACC", bonus.acc);
        appendStat(pending, "AVOID", bonus.avoid);
        appendStat(pending, "Speed", bonus.speed);
        appendStat(pending, "Jump", bonus.jump);
        appendStat(pending, "BossDmg%", bonus.bossDamagePct);
        appendStat(pending, "FinalDmg%", bonus.finalDamagePct);
        if (pending.length() > lenBefore) {
            player.yellowMessage(pending.toString());
        }
    }

    private static void appendStat(StringBuilder sb, String label, int value) {
        if (value == 0) return;
        if (sb.charAt(sb.length() - 1) != ' ') {
            sb.append(", ");
        }
        sb.append("+").append(value).append(' ').append(label);
    }
}
