package server.living.population.paper;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
