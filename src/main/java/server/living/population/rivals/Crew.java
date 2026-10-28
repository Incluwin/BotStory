package server.living.population.rivals;

import java.util.ArrayList;
import java.util.List;

/**
 * A player account's rival crew (a living_rival row, docs/BOT_CONCEPT_PLAN.md F7.2): citizens near its main
 * character's level under one name, which is also their guild's; {@code circleId} is their circle (they log in and party
 * up together). {@code guildId} 0 while it has no guild (founded again at the next refresh). {@code leaderId} is the
 * guild's master.
 */
public record Crew(int id, int accountId, int mainId, String name, int guildId, int circleId, int leaderId,
                   List<Integer> members, long formedAtMs, long helpAskedAtMs) {
    public Crew {
        members = List.copyOf(members);
    }

    public boolean has(int citizenId) {
        return members.contains(citizenId);
    }

    Crew withId(int newId) {
        return new Crew(newId, accountId, mainId, name, guildId, circleId, leaderId, members, formedAtMs, helpAskedAtMs);
    }

    Crew withMain(int newMainId) {
        return new Crew(id, accountId, newMainId, name, guildId, circleId, leaderId, members, formedAtMs, helpAskedAtMs);
    }

    Crew withGuild(String newName, int newGuildId, int newLeaderId) {
        return new Crew(id, accountId, mainId, newName, newGuildId, circleId, newLeaderId, members, formedAtMs,
                helpAskedAtMs);
    }

    Crew withCircle(int newCircleId) {
        return new Crew(id, accountId, mainId, name, guildId, newCircleId, leaderId, members, formedAtMs, helpAskedAtMs);
    }

    Crew withLeader(int newLeaderId) {
        return new Crew(id, accountId, mainId, name, guildId, circleId, newLeaderId, members, formedAtMs, helpAskedAtMs);
    }

    Crew without(int citizenId) {
        List<Integer> left = new ArrayList<>(members);
        left.remove(Integer.valueOf(citizenId));
        return new Crew(id, accountId, mainId, name, guildId, circleId, leaderId, left, formedAtMs, helpAskedAtMs);
    }

    Crew with(int citizenId) {
        List<Integer> more = new ArrayList<>(members);
        if (!more.contains(citizenId)) {
            more.add(citizenId);
        }
        return new Crew(id, accountId, mainId, name, guildId, circleId, leaderId, more, formedAtMs, helpAskedAtMs);
    }

    Crew withHelpAsked(long atMs) {
        return new Crew(id, accountId, mainId, name, guildId, circleId, leaderId, members, formedAtMs, atMs);
    }

    String encodeMembers() {
        List<String> ids = new ArrayList<>();
        members.forEach(member -> ids.add(String.valueOf(member)));
        return String.join(",", ids);
    }

    static List<Integer> decodeMembers(String text) {
        List<Integer> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        for (String part : text.split(",")) {
            try {
                out.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException ignored) {
                // a hand-edited row: that member is skipped
            }
        }
        return out;
    }
}
