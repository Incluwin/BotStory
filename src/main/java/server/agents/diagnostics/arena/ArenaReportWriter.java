package server.agents.diagnostics.arena;

import server.agents.capabilities.combat.rotation.AgentCombatRotationProfile.Choice;
import server.agents.capabilities.combat.rotation.AgentCombatRotationProfile.Trial;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Writes an arena run as a Markdown report under {@link #REPORT_DIR}. */
public final class ArenaReportWriter {
    public static final Path REPORT_DIR = Path.of("docs", "agents", "evidence", "arena");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private ArenaReportWriter() {
    }

    public static Path write(ArenaReport report) throws IOException {
        Files.createDirectories(REPORT_DIR);
        String file = report.profile().jobName().toLowerCase(Locale.ROOT) + "-L" + report.profile().level() + "-"
                + STAMP.format(Instant.ofEpochMilli(report.startedAtMs())) + ".md";
        Path path = REPORT_DIR.resolve(file);
        Files.writeString(path, render(report), StandardCharsets.UTF_8);
        return path;
    }

    public static String render(ArenaReport report) {
        StringBuilder md = new StringBuilder();
        md.append("# Combat arena: ").append(report.profile().jobName()).append(" L").append(report.profile().level())
                .append(" (").append(report.agentName()).append(", ").append(report.profile().build()).append(" build)\n\n");
        md.append("- Run: ").append(Instant.ofEpochMilli(report.startedAtMs())).append(" UTC, ")
                .append((report.finishedAtMs() - report.startedAtMs()) / 1000).append("s, ")
                .append(report.profile().trials().size()).append(" trials\n");
        md.append("- Profile: ").append(report.savedTo()).append("\n\n");

        // Anomalies come before the result: a reading the arena itself cannot justify should be
        // read before the table it produced, not found afterwards by someone who knows the class.
        List<String> anomalies = report.notes().stream().filter(n -> n.startsWith("ANOMALY: ")).toList();
        if (!anomalies.isEmpty()) {
            md.append("## Anomalies (").append(anomalies.size()).append(")\n\n");
            for (String note : anomalies) {
                md.append("- ").append(note.substring("ANOMALY: ".length())).append('\n');
            }
            md.append('\n');
        }

        md.append("## Optimal loop\n\n| Scenario | Skill | DPS | Summons/debuffs | Area from |\n|---|---|---:|---|---:|\n");
        row(md, "single", report.profile().single());
        row(md, "multi", report.profile().multi());
        row(md, "boss", report.profile().boss());
        md.append('\n');

        List<String> learned = report.notes().stream().filter(n -> n.startsWith("LEARNED ")).toList();
        if (!learned.isEmpty()) {
            md.append("## Learned policy vs the prior scorer\n\n");
            for (String note : learned) {
                md.append("- ").append(note.substring("LEARNED ".length())).append('\n');
            }
            md.append('\n');
        }

        List<String> policy = report.notes().stream().filter(n -> n.startsWith("POLICY ")).toList();
        if (!policy.isEmpty()) {
            // Each row is a rule the Agent found: in this situation it does this, and the
            // alternatives are worth this much less (discounted damage that followed them).
            md.append("## What the Agent learned to do\n\n| Scenario | Situation | Does | Alternatives (value vs chosen, tries) | Decisions |\n");
            md.append("|---|---|---|---|---:|\n");
            for (String note : policy) {
                String[] cells = note.substring("POLICY ".length()).split("\t", -1);
                md.append('|');
                for (String cell : cells) {
                    md.append(' ').append(cell.replace("|", "/")).append(" |");
                }
                md.append('\n');
            }
            md.append('\n');
        }

        List<String> tooltips = report.notes().stream().filter(n -> n.startsWith("TOOLTIP ")).toList();
        if (!tooltips.isEmpty()) {
            // The Agents fight on what the server computes; this is where that differs from what
            // players read, and what an Agent must not quote when it talks about its skills.
            md.append("## Tooltips that disagree with the server\n\n");
            for (String note : tooltips) {
                md.append("- ").append(note.substring("TOOLTIP ".length())).append('\n');
            }
            md.append('\n');
        }

        List<String> rotation = report.notes().stream().filter(n -> n.startsWith("ROTATION ")).toList();
        if (!rotation.isEmpty()) {
            md.append("## What the winning rotation actually casts\n\n");
            for (String note : rotation) {
                String body = note.substring("ROTATION ".length());
                int colon = body.indexOf(": ");
                md.append("- **").append(body, 0, colon < 0 ? body.length() : colon).append("** - ")
                        .append(colon < 0 ? "" : body.substring(colon + 2)).append('\n');
            }
            md.append('\n');
        }

        if (!report.notes().isEmpty()) {
            md.append("## How it got there\n\n");
            for (String note : report.notes()) {
                if (note.startsWith("ANOMALY: ") || note.startsWith("ROTATION ")
                        || note.startsWith("LEARNED ") || note.startsWith("POLICY ") || note.startsWith("TOOLTIP ")) {
                    continue;   // already reported above
                }
                md.append("- ").append(note).append('\n');
            }
            md.append('\n');
        }

        md.append("## Trials\n\n| Scenario | Skill | Targets | Special | Seconds | Damage | DPS | Attacks | Special casts | MP |\n");
        md.append("|---|---|---:|---|---:|---:|---:|---:|---:|---:|\n");
        for (Trial trial : report.profile().trials()) {
            md.append("| ").append(trial.scenario())
                    .append(" | ").append(trial.skillName())
                    .append(" | ").append(trial.targets())
                    .append(" | ").append(trial.specialMoves() ? "on" : "off")
                    .append(" | ").append(trial.seconds())
                    .append(" | ").append(trial.damage())
                    .append(" | ").append(Math.round(trial.damagePerSecond()))
                    .append(" | ").append(trial.attacks())
                    .append(" | ").append(trial.specialMovesCast())
                    .append(" | ").append(trial.mpSpent())
                    .append(" |\n");
        }
        return md.toString();
    }

    private static void row(StringBuilder md, String scenario, Choice choice) {
        if (choice == null) {
            md.append("| ").append(scenario).append(" | not run | | | |\n");
            return;
        }
        md.append("| ").append(scenario).append(" | ").append(choice.skillName())
                .append(" | ").append(Math.round(choice.damagePerSecond()))
                .append(" | ").append(choice.useSpecialMoves() ? "yes" : "no")
                .append(" | ").append(choice.aoeFromTargets() > 0 ? choice.aoeFromTargets() + " targets" : "-")
                .append(" |\n");
    }
}
