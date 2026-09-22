package server.agents.diagnostics.arena;

import server.agents.capabilities.combat.rotation.AgentCombatRotationProfile;
import server.agents.capabilities.combat.rotation.AgentCombatRotationProfile.Choice;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** One arena run's outcome: the profile it produced, the notes, and the short form for GM chat. */
public record ArenaReport(String agentName,
                          AgentCombatRotationProfile profile,
                          List<String> notes,
                          String savedTo,
                          long startedAtMs,
                          long finishedAtMs,
                          Path reportPath) {
    public ArenaReport {
        notes = List.copyOf(notes);
    }

    public ArenaReport withPath(Path path) {
        return new ArenaReport(agentName, profile, notes, savedTo, startedAtMs, finishedAtMs, path);
    }

    public List<String> chatSummary() {
        List<String> lines = new ArrayList<>();
        lines.add("Combat arena " + agentName + " " + profile.jobName() + " L" + profile.level() + " (" + profile.build()
                + " build): " + profile.trials().size() + " trials in " + (finishedAtMs - startedAtMs) / 1000 + "s.");
        describe(lines, "single", profile.single());
        describe(lines, "multi", profile.multi());
        describe(lines, "boss", profile.boss());
        lines.add("Profile: " + savedTo);
        if (reportPath != null) {
            lines.add("Report: " + reportPath);
        }
        return lines;
    }

    private static void describe(List<String> lines, String scenario, Choice choice) {
        if (choice == null) {
            return;
        }
        lines.add("  " + scenario + ": " + choice.skillName() + " at " + Math.round(choice.damagePerSecond()) + " dps"
                + (choice.useSpecialMoves() ? ", with summons/debuffs" : ", no summons/debuffs")
                + (choice.aoeFromTargets() > 0 ? ", from " + choice.aoeFromTargets() + " targets" : ""));
    }
}
