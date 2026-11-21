package server.living.population.coliseum;

import client.Character;
import server.living.resident.LivingTalk;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The ladder's pages (docs/PVP_PLAN.md V4.4): where you stand, each bracket's top 10 with how far each moved this
 * week, and the week's biggest climbs. Cesar shows them, and so does the F1 Bulletin Board's "Coliseum" page, so a
 * player back from a break sees who moved while they were away.
 */
final class ColiseumBoard {
    static final int TOP = 10;
    static final int CLIMBERS = 3;

    private final LadderBook book;
    /** The weekly tournament's page (V5), given the time; null until the coliseum sets it. */
    private java.util.function.LongFunction<String> tournament;

    ColiseumBoard(LadderBook book) {
        this.book = book;
    }

    void tournament(java.util.function.LongFunction<String> page) {
        this.tournament = page;
    }

    /** The first page: the reader's standing, then a page a bracket and the week's climbs. */
    void show(Character player, int npc, Runnable back) {
        long now = System.currentTimeMillis();
        List<LivingTalk.Option> options = new ArrayList<>();
        for (LadderRules.Bracket bracket : LadderRules.Bracket.values()) {
            options.add(new LivingTalk.Option("#bThe " + bracket.label + " top " + TOP + "#k (" + bracket.range() + ")",
                    () -> top(player, npc, bracket, () -> show(player, npc, back))));
        }
        options.add(new LivingTalk.Option("#bThis week's climbers#k", () -> week(player, npc, () -> show(player, npc,
                back))));
        if (tournament != null) {
            options.add(new LivingTalk.Option("#bThe weekly tournament#k", () -> LivingTalk.open(player, npc)
                    .ok(tournament.apply(System.currentTimeMillis())).back(() -> show(player, npc, back)).show()));
        }
        LivingTalk talk = LivingTalk.open(player, npc).menu("The Coliseum ladder in Ariant.\r\n\r\n"
                + standing(player, now), options);
        if (back != null) {
            talk.back(back);
        }
        talk.show();
    }

    /** The reader's line: bracket, rating, place and this week's move, or why there is none. */
    String standing(Character player, long now) {
        Optional<LadderRules.Bracket> bracket = LadderRules.Bracket.of(player.getLevel());
        if (bracket.isEmpty()) {
            return "The ladder starts at Lv 30. Cesar in Ariant still lets anyone watch from the gallery.";
        }
        LadderBook.Row row = book.row(player.getId(), bracket.get());
        long rating = Math.round(book.rating(player.getId(), bracket.get(), now).rating());
        String head = "Your bracket: #b" + bracket.get().label + "#k (" + bracket.get().range() + "), rated #b" + rating
                + "#k, " + row.wins() + "-" + row.losses();
        if (row.played() == 0) {
            return head + ". Fight Cesar's ladder in Ariant to get on the board.";
        }
        int[] standing = book.standing(player.getId(), bracket.get());
        int climbed = row.climbed(LadderBook.week(now));
        String week = climbed == 0 ? "" : ", " + signed(climbed) + " this week";
        if (standing[0] == 0) {
            int more = LadderBook.LISTED_AFTER - row.played();
            return head + week + ". " + more + " more match" + (more == 1 ? "" : "es") + " to be listed.";
        }
        return head + week + ". #b" + ordinal(standing[0]) + "#k of " + standing[1] + ".";
    }

    void top(Character player, int npc, LadderRules.Bracket bracket, Runnable back) {
        int week = LadderBook.week(System.currentTimeMillis());
        LivingTalk.open(player, npc).ok(topText(book.top(bracket, TOP), bracket, week)).back(back).show();
    }

    static String topText(List<LadderBook.Ranked> top, LadderRules.Bracket bracket, int week) {
        StringBuilder text = new StringBuilder("The " + bracket.label + " ladder (" + bracket.range() + "):\r\n\r\n");
        if (top.isEmpty()) {
            text.append("Nobody has ").append(LadderBook.LISTED_AFTER).append(" matches in yet.");
        }
        for (int i = 0; i < top.size(); i++) {
            LadderBook.Ranked ranked = top.get(i);
            LadderBook.Row row = ranked.row();
            text.append(i + 1).append(". #b").append(ranked.name()).append("#k Lv ").append(ranked.level()).append("  ")
                    .append(Math.round(row.rating().rating())).append("  (").append(row.wins()).append('-')
                    .append(row.losses()).append(')');
            int climbed = row.climbed(week);
            if (climbed != 0) {
                text.append("  ").append(signed(climbed));
            }
            text.append("\r\n");
        }
        return text.toString();
    }

    void week(Character player, int npc, Runnable back) {
        long now = System.currentTimeMillis();
        StringBuilder text = new StringBuilder("The biggest climbs this week (since Monday, UTC):\r\n");
        for (LadderRules.Bracket bracket : LadderRules.Bracket.values()) {
            text.append("\r\n#e").append(bracket.label).append("#n\r\n");
            text.append(climbText(book.climbers(bracket, CLIMBERS, now), LadderBook.week(now)));
        }
        LivingTalk.open(player, npc).ok(text.toString()).back(back).show();
    }

    static String climbText(List<LadderBook.Ranked> climbers, int week) {
        if (climbers.isEmpty()) {
            return "Nobody has climbed yet.\r\n";
        }
        StringBuilder text = new StringBuilder();
        for (LadderBook.Ranked ranked : climbers) {
            text.append("#b").append(ranked.name()).append("#k ").append(signed(ranked.row().climbed(week)))
                    .append(" to ").append(Math.round(ranked.row().rating().rating())).append("\r\n");
        }
        return text.toString();
    }

    static String signed(int value) {
        return value > 0 ? "+" + value : Integer.toString(value);
    }

    static String ordinal(int n) {
        int mod100 = n % 100;
        String suffix = mod100 >= 11 && mod100 <= 13 ? "th"
                : switch (n % 10) {
                    case 1 -> "st";
                    case 2 -> "nd";
                    case 3 -> "rd";
                    default -> "th";
                };
        return n + suffix;
    }
}
