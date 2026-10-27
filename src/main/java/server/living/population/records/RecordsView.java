package server.living.population.records;

import client.Character;
import server.living.resident.LivingTalk;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The records page (docs/BOT_CONCEPT_PLAN.md F7.1, decision D13): for each quest or boss with a clear, its first clear
 * (who, when) and its fastest (who, how long), and the viewer's own best. On a needs-board Bulletin Board ("Records")
 * and in chat ({@code @records [quest]}).
 */
public final class RecordsView {
    private final ClearLog clears;

    public RecordsView(ClearLog clears) {
        this.clears = clears;
    }

    /** The board's records window: one entry a quest, each opening its details; {@code back} returns to the board. */
    public void board(Character player, int npcId, Runnable back) {
        List<String> quests = clears.questsWithRecords();
        if (quests.isEmpty()) {
            LivingTalk.open(player, npcId).back(back).ok("No clears recorded yet. Be the first!").show();
            return;
        }
        List<LivingTalk.Option> options = new ArrayList<>();
        for (String quest : quests) {
            Clear fastest = clears.fastest(quest);
            options.add(new LivingTalk.Option("#b" + clears.labels().label(quest) + "#k"
                    + (fastest == null ? "" : " - " + ClearLog.time(fastest.durationMs())),
                    () -> LivingTalk.open(player, npcId).back(() -> board(player, npcId, back))
                            .ok(String.join("\r\n", details(player, quest))).show()));
        }
        LivingTalk.open(player, npcId).back(back).menu("Who got there first, and who was fastest.", options).show();
    }

    /** One quest's record lines: its first clear, its fastest, the viewer's best. */
    public List<String> details(Character player, String quest) {
        List<String> out = new ArrayList<>();
        out.add("#e" + clears.labels().label(quest) + "#n");
        Clear first = clears.first(quest);
        if (first != null) {
            out.add("First clear: " + ClearLog.names(first, 6) + " (" + date(first.clearedAtMs()) + ")");
        }
        Clear fastest = clears.fastest(quest);
        if (fastest != null) {
            out.add("Fastest: #b" + ClearLog.time(fastest.durationMs()) + "#k by " + ClearLog.names(fastest, 6)
                    + " (" + date(fastest.clearedAtMs()) + ")");
        }
        long mine = player == null ? 0L : clears.playerBest(player.getId(), quest);
        out.add(mine > 0L ? "Your best: " + ClearLog.time(mine) : "You have no time here yet.");
        return out;
    }

    /** {@code @records}: one line a quest; {@code @records <quest>}: that quest's lines. */
    public List<String> chat(Character player, String typed) {
        List<String> out = new ArrayList<>();
        if (typed != null && !typed.isBlank()) {
            String quest = clears.labels().find(typed);
            if (quest == null) {
                out.add("No such quest or boss: " + typed + ". Try @records for the list.");
                return out;
            }
            if (clears.first(quest) == null) {
                out.add(clears.labels().label(quest) + ": no clears recorded yet.");
                return out;
            }
            for (String line : details(player, quest)) {
                out.add(line.replace("#e", "").replace("#n", "").replace("#b", "").replace("#k", ""));
            }
            return out;
        }
        List<String> quests = clears.questsWithRecords();
        if (quests.isEmpty()) {
            out.add("No clears recorded yet.");
            return out;
        }
        for (String quest : quests) {
            Clear first = clears.first(quest);
            Clear fastest = clears.fastest(quest);
            long mine = player == null ? 0L : clears.playerBest(player.getId(), quest);
            out.add(clears.labels().label(quest) + ": first " + ClearLog.names(first, 2)
                    + (fastest == null ? "" : ", fastest " + ClearLog.time(fastest.durationMs()) + " " + ClearLog.names(fastest, 2))
                    + (mine > 0L ? ", yours " + ClearLog.time(mine) : ""));
        }
        return out;
    }

    /** "Oct 27" in UTC, as every living time is kept. */
    static String date(long ms) {
        SimpleDateFormat format = new SimpleDateFormat("MMM d", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(ms));
    }
}
