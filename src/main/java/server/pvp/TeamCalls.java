package server.pvp;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.integration.AgentPartyGatewayRuntime;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Team fights (docs/PVP_PLAN.md V6): what a player's bot teammates say in party chat, short calls as players type them
 * mid-fight. A side with no player in it says nothing (nobody would read its party chat). The duel queues lines under
 * its lock and {@link #say}s them after, because party chat takes the party's locks.
 */
final class TeamCalls {
    private static final Logger log = LoggerFactory.getLogger(TeamCalls.class);

    /** A side says at most one call this often. */
    static final long GAP_MS = 6_000L;
    /** A foe under this HP share is called out as low. */
    static final double LOW_SHARE = 0.35d;

    enum Kind { FOCUS, LOW, ASSIST, DOWN, OUT }

    private static final List<String> FOCUS = List.of("on {name}", "focus {name}", "{name} first", "hit {name}",
            "go {name}");
    private static final List<String> LOW = List.of("{name} low", "{name} almost down", "finish {name}");
    private static final List<String> ASSIST = List.of("on it", "with u", "got ur back", "on ur target");
    private static final List<String> DOWN = List.of("1 down", "got em", "nice", "next one");
    private static final List<String> OUT = List.of("im out", "ugh im down", "sry im out");

    /** A line queued for after the duel's lock: who says it and what. */
    record Line(Character speaker, String text) {
    }

    private TeamCalls() {
    }

    /** The line for {@code kind} about {@code name} (a foe's), the {@code pick}th of its kind (wrapped). */
    static String line(Kind kind, String name, int pick) {
        List<String> lines = switch (kind) {
            case FOCUS -> FOCUS;
            case LOW -> LOW;
            case ASSIST -> ASSIST;
            case DOWN -> DOWN;
            case OUT -> OUT;
        };
        return lines.get(Math.floorMod(pick, lines.size())).replace("{name}", name == null ? "" : name);
    }

    /** A line of {@code kind}, picked at random. */
    static String line(Kind kind, String name) {
        return line(kind, name, ThreadLocalRandom.current().nextInt(1 << 16));
    }

    /** The kind a new call is said as: an assist, a low foe, or a plain focus. */
    static Kind kindOf(TeamTactics.Call call, double targetHpShare) {
        return call.assist() ? Kind.ASSIST : targetHpShare < LOW_SHARE ? Kind.LOW : Kind.FOCUS;
    }

    /** Says the queued lines in party chat (a bot outside a party says nothing). */
    static void say(List<Line> lines) {
        for (Line line : lines) {
            try {
                if (line.speaker().getParty() != null) {
                    AgentPartyGatewayRuntime.party().sendPartyChat(line.speaker(), line.text());
                }
            } catch (RuntimeException failure) {
                log.debug("team call by {} failed", line.speaker().getName(), failure);
            }
        }
    }
}
