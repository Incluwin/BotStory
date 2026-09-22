package server.agents.diagnostics.arena;

import server.agents.capabilities.combat.rotation.AgentCombatRotationProfile.Choice;
import server.agents.capabilities.combat.rotation.AgentCombatRotationProfile.Trial;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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

        md.append("## Optimal loop\n\n| Scenario | Skill | DPS | Summons/debuffs | Area from |\n|---|---|---:|---|---:|\n");
        row(md, "single", report.profile().single());
        row(md, "multi", report.profile().multi());
        row(md, "boss", report.profile().boss());
        md.append('\n');

        if (!report.notes().isEmpty()) {
            md.append("## How it got there\n\n");
            for (String note : report.notes()) {
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
