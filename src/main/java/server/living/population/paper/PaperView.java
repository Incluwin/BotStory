package server.living.population.paper;

import client.Character;
import server.living.resident.LivingTalk;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** How an issue reads: plain lines for a GM's chat, and one page a section for the Bulletin Board. */
public final class PaperView {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d", Locale.ROOT);

    private PaperView() {
    }

    /** "<title>, week of Nov 16", then per section its title and each story's headline and body. */
    public static List<String> lines(PaperIssue issue, String title) {
        List<String> out = new ArrayList<>();
        out.add(masthead(issue, title));
        if (issue.empty()) {
            out.add("(no stories)");
        }
        for (PaperIssue.Section section : issue.sections()) {
            out.add("== " + section.title() + " ==");
            for (PaperIssue.Story story : section.stories()) {
                out.add(story.headline() + ". " + story.body() + " [" + story.key() + ", " + story.weight() + "]");
            }
        }
        return out;
    }

    /**
     * The Bulletin Board's "The paper" (NeedScreens): this week's issue and last week's, each one page a section;
     * reading this week's marks it seen, so the letters say nothing of it. {@code back} returns to the board.
     */
    public static void board(Character player, int npcId, PaperDesk desk, Runnable back) {
        long now = System.currentTimeMillis();
        PaperCatalog catalog = desk.catalog();
        Optional<PaperIssue> latest = desk.latest(now);
        if (latest.isEmpty()) {
            LivingTalk.open(player, npcId).back(back).ok("No paper yet. The first " + catalog.title() + " comes out "
                    + nextIssue(desk, now) + ".").show();
            return;
        }
        Runnable menu = () -> board(player, npcId, desk, back);
        List<LivingTalk.Option> options = new ArrayList<>();
        PaperIssue current = latest.get();
        options.add(new LivingTalk.Option("#bThis week's paper#k (" + day(current) + ")", () -> {
            desk.seen(player.getId(), current.weekOf());
            read(player, npcId, current, catalog, menu);
        }));
        desk.before(current).ifPresent(last -> options.add(new LivingTalk.Option("#bLast week's#k (" + day(last) + ")",
                () -> read(player, npcId, last, catalog, menu))));
        LivingTalk.open(player, npcId).back(back).menu(catalog.title() + " is pinned up here. The next one comes out "
                + nextIssue(desk, now) + ".", options).show();
    }

    private static void read(Character player, int npcId, PaperIssue issue, PaperCatalog catalog, Runnable back) {
        List<String> pages = pages(issue, catalog.title(), catalog.quiet(issue.weekOf()));
        LivingTalk talk = LivingTalk.open(player, npcId).back(back);
        for (int i = 0; i < pages.size() - 1; i++) {
            talk.say(pages.get(i));
        }
        talk.ok(pages.get(pages.size() - 1)).show();
    }

    /** "Nov 22": the day an issue came out, in the server's zone. */
    private static String day(PaperIssue issue) {
        return Instant.ofEpochMilli(issue.issuedAtMs()).atZone(ZoneId.systemDefault()).toLocalDate().format(DATE);
    }

    /** "Sunday 00:00": when the next issue comes out. */
    static String nextIssue(PaperDesk desk, long now) {
        int week = desk.weekOf(now);
        long at = desk.issueAtMs(week);
        if (at <= now) {
            at = desk.issueAtMs(week + 7);
        }
        var when = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault());
        String name = when.getDayOfWeek().name();
        return name.charAt(0) + name.substring(1).toLowerCase(Locale.ROOT) + String.format(Locale.ROOT, " %02d:00",
                when.getHour());
    }

    static String masthead(PaperIssue issue, String title) {
        return title + ", week of " + LocalDate.ofEpochDay(issue.weekOf()).format(DATE);
    }

    /** One NPC-window page a section: its title, then each story's bold headline over its body. */
    static List<String> pages(PaperIssue issue, String title, String quiet) {
        List<String> out = new ArrayList<>();
        if (issue.empty()) {
            out.add("#e" + masthead(issue, title) + "#n\r\n\r\n" + quiet);
            return out;
        }
        for (int i = 0; i < issue.sections().size(); i++) {
            PaperIssue.Section section = issue.sections().get(i);
            StringBuilder page = new StringBuilder();
            if (i == 0) {
                page.append("#e").append(masthead(issue, title)).append("#n\r\n\r\n");
            }
            page.append("#e").append(section.title()).append("#n");
            for (PaperIssue.Story story : section.stories()) {
                page.append("\r\n\r\n#b").append(story.headline()).append("#k\r\n").append(story.body());
            }
            out.add(page.toString());
        }
        return out;
    }
}
