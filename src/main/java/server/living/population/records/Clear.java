package server.living.population.records;

import java.util.ArrayList;
import java.util.List;

/**
 * One cleared event instance (a living_clear row, docs/BOT_CONCEPT_PLAN.md F7): which quest ({@code quest} is the event
 * script, "KerningPQ", "BalrogBattle_Easy"), who was in it, when, and how long it took ({@code durationMs} 0 when the
 * start is unknown). {@code crewId} is the rival crew credited with it, 0 for none.
 */
public record Clear(int id, String quest, String instance, int channel, int mapId, long startedAtMs, long clearedAtMs,
                    long durationMs, Kind kind, int crewId, List<Member> members) {
    /** The members string keeps what fits the column; living_clear_member keeps everyone. */
    static final int MEMBERS_MAX_CHARS = 400;

    /** Who cleared it: a player was in it, else companions, else citizens, else other bots. */
    public enum Kind {
        HUMAN, COMPANION, CITIZEN, BOT;

        static Kind parse(String text) {
            for (Kind kind : values()) {
                if (kind.name().equals(text)) {
                    return kind;
                }
            }
            return BOT;
        }
    }

    /** A player (H), a companion (P), a citizen (C) or another bot (B). */
    public record Member(int id, char kind, String name) {
        public boolean human() {
            return kind == AS_PLAYER;
        }
    }

    public static final char AS_PLAYER = 'H';
    public static final char AS_COMPANION = 'P';
    public static final char AS_CITIZEN = 'C';
    public static final char AS_BOT = 'B';

    public Clear {
        quest = quest == null ? "" : quest;
        instance = instance == null ? "" : instance;
        members = List.copyOf(members);
    }

    /** The party's kind from its members: any player makes it a player's clear. */
    static Kind kindOf(List<Member> members) {
        boolean companion = false;
        boolean citizen = false;
        for (Member member : members) {
            switch (member.kind()) {
                case AS_PLAYER -> {
                    return Kind.HUMAN;
                }
                case AS_COMPANION -> companion = true;
                case AS_CITIZEN -> citizen = true;
                default -> { }
            }
        }
        return companion ? Kind.COMPANION : citizen ? Kind.CITIZEN : Kind.BOT;
    }

    Clear withId(int newId) {
        return new Clear(newId, quest, instance, channel, mapId, startedAtMs, clearedAtMs, durationMs, kind, crewId, members);
    }

    public boolean timed() {
        return durationMs > 0L;
    }

    public boolean has(int characterId) {
        for (Member member : members) {
            if (member.id() == characterId) {
                return true;
            }
        }
        return false;
    }

    /** "id:K:name,..." as far as it fits the column. */
    String encodeMembers() {
        StringBuilder out = new StringBuilder();
        for (Member member : members) {
            String one = member.id() + ":" + member.kind() + ":" + member.name().replace(",", "").replace(":", "");
            if (out.length() + one.length() + 1 > MEMBERS_MAX_CHARS) {
                break;
            }
            if (!out.isEmpty()) {
                out.append(',');
            }
            out.append(one);
        }
        return out.toString();
    }

    static List<Member> decodeMembers(String text) {
        List<Member> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        for (String one : text.split(",")) {
            String[] parts = one.split(":", 3);
            if (parts.length == 3 && !parts[1].isEmpty()) {
                try {
                    out.add(new Member(Integer.parseInt(parts[0]), parts[1].charAt(0), parts[2]));
                } catch (NumberFormatException ignored) {
                    // a hand-edited row: that member is skipped
                }
            }
        }
        return out;
    }
}
