package server.agents.diagnostics.arena;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compares a skill's tooltip with what the server computes. Servers routinely edit Skill.wz and
 * leave String.wz alone, so the two drift apart; the Agents learn from what the server does, but
 * anything they say about a skill, and anything a player expects from it, comes from the tooltip.
 */
final class ArenaTooltipCheck {
    private static final Pattern[] DAMAGE = {
            Pattern.compile("damage\\s*:?\\s*(\\d+)\\s*%", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(\\d+)\\s*%\\s*damage", Pattern.CASE_INSENSITIVE)};
    private static final Pattern[] MOBS = {
            Pattern.compile("up to\\s*(\\d+)\\s*(?:monsters|enemies)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(\\d+)\\s*(?:monsters|enemies)\\s*at once", Pattern.CASE_INSENSITIVE)};
    private static final Pattern[] MP = {Pattern.compile("MP\\s*-\\s*(\\d+)", Pattern.CASE_INSENSITIVE)};

    private ArenaTooltipCheck() {
    }

    /** "damage 420% (server 180%)" style differences; empty when the tooltip agrees or says nothing checkable. */
    static List<String> differences(String tooltip, int damagePercent, int mobCount, int mpCon) {
        List<String> differences = new ArrayList<>();
        if (tooltip == null || tooltip.isBlank()) {
            return differences;
        }
        compare(differences, tooltip, DAMAGE, damagePercent, "damage %d%%", "%d%%");
        compare(differences, tooltip, MOBS, mobCount, "%d monsters", "%d");
        compare(differences, tooltip, MP, mpCon, "MP %d", "%d");
        return differences;
    }

    private static void compare(List<String> differences, String tooltip, Pattern[] patterns, int actual,
                                String told, String computed) {
        if (actual <= 0) {
            return;
        }
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(tooltip);
            if (matcher.find()) {
                int said = Integer.parseInt(matcher.group(1));
                if (said != actual) {
                    differences.add(String.format(java.util.Locale.ROOT, told, said)
                            + " (server " + String.format(java.util.Locale.ROOT, computed, actual) + ")");
                }
                return;
            }
        }
    }
}
