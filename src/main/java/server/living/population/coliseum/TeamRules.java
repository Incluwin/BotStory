package server.living.population.coliseum;

import server.pvp.Glicko2;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Team fights' rules (docs/PVP_PLAN.md V6), pure so they are tested apart: who makes up a citizen team to face a
 * player's, and where each side stands. A team match is 2v2 or 3v3 and the sides are always equal in size (focus fire
 * is a real tactic, so one more on a side would decide it). A player's own companions are never on the other side:
 * hired citizens are never free to be picked (Coliseum.free), so they cannot be.
 */
final class TeamRules {
    static final int MIN_SIZE = 2;
    static final int MAX_SIZE = 3;

    /** Side 0 (the player's) stands left, side 1 right, on the arena's bottom floor (x -920..312, y about 255). */
    private static final int[] PLAYER_XS = {-560, -640, -480};
    private static final int[] FOE_XS = {-180, -100, -260};
    private static final int FLOOR_Y = 230;

    private TeamRules() {
    }

    /** Where the {@code index}th fighter of {@code side} stands at a round's start. */
    static Point spot(int side, int index) {
        int[] xs = side == 0 ? PLAYER_XS : FOE_XS;
        return new Point(xs[Math.floorMod(index, xs.length)], FLOOR_Y);
    }

    /** A free citizen who could make a team: its guild (0 when none) and its team rating. */
    record Member(int characterId, int level, int guildId, String guildName, Glicko2.Rating rating) {
    }

    /** A citizen team: its members, nearest the target rating first, and the name it fights under. */
    record Team(List<Integer> members, String name, Glicko2.Rating rating) {
    }

    /** The name a pickup team (citizens from no one guild) fights under. */
    static final String PICKUP = "a pickup team";

    /**
     * A team of {@code size} for a player team of level {@code level} rated {@code target}: from one guild when one has
     * {@code size} free members within {@link LadderRules#LEVEL_WINDOW} levels (the guild team rated nearest the
     * target), else the {@code size} free members rated nearest it. Empty when fewer than {@code size} are free.
     */
    static Optional<Team> pick(int level, Glicko2.Rating target, int size, List<Member> free) {
        List<Member> near = new ArrayList<>();
        for (Member member : free) {
            if (Math.abs(member.level() - level) <= LadderRules.LEVEL_WINDOW) {
                near.add(member);
            }
        }
        if (size < MIN_SIZE || near.size() < size) {
            return Optional.empty();
        }
        Comparator<Member> nearest = Comparator.comparingDouble(m -> Math.abs(m.rating().rating() - target.rating()));
        Map<Integer, List<Member>> guilds = new LinkedHashMap<>();
        for (Member member : near) {
            if (member.guildId() > 0) {
                guilds.computeIfAbsent(member.guildId(), id -> new ArrayList<>()).add(member);
            }
        }
        Team best = null;
        for (List<Member> guild : guilds.values()) {
            if (guild.size() < size) {
                continue;
            }
            Team team = team(guild, size, nearest, guild.get(0).guildName());
            if (best == null || gap(team, target) < gap(best, target)) {
                best = team;
            }
        }
        return Optional.of(best != null ? best : team(near, size, nearest, PICKUP));
    }

    /**
     * A guild match's side for a player of {@code guildId} (docs/WORLD_SYSTEMS_PLAN.md N5.3): the player, then its free
     * guild mates within {@link LadderRules#LEVEL_WINDOW} levels (nearest the player's level first), then its own
     * {@code companions}, {@code size} in all; empty when they leave a seat open. Never a citizen of another guild.
     */
    static Optional<List<Integer>> guildSide(int humanId, int level, int guildId, int size, List<Member> free,
                                             List<Integer> companions) {
        if (guildId <= 0 || size < MIN_SIZE || size > MAX_SIZE) {
            return Optional.empty();
        }
        List<Member> mates = new ArrayList<>();
        for (Member member : free) {
            if (member.guildId() == guildId && member.characterId() != humanId
                    && Math.abs(member.level() - level) <= LadderRules.LEVEL_WINDOW) {
                mates.add(member);
            }
        }
        mates.sort(Comparator.comparingInt(m -> Math.abs(m.level() - level)));
        List<Integer> side = new ArrayList<>();
        side.add(humanId);
        for (Member mate : mates) {
            if (side.size() < size) {
                side.add(mate.characterId());
            }
        }
        for (int companion : companions) {
            if (side.size() < size && !side.contains(companion)) {
                side.add(companion);
            }
        }
        return side.size() == size ? Optional.of(side) : Optional.empty();
    }

    /**
     * A guild match's foe: {@code size} free members of one guild other than {@code ownGuildId} within the level
     * window, the guild team rated nearest {@code target}; never a pickup team, so empty when no other guild has as many.
     */
    static Optional<Team> guildFoe(int level, Glicko2.Rating target, int size, int ownGuildId, List<Member> free) {
        List<Member> others = new ArrayList<>();
        for (Member member : free) {
            if (member.guildId() > 0 && member.guildId() != ownGuildId) {
                others.add(member);
            }
        }
        Optional<Team> team = pick(level, target, size, others);
        return team.filter(t -> !PICKUP.equals(t.name()) && oneGuild(t, others));
    }

    private static boolean oneGuild(Team team, List<Member> members) {
        int guildId = 0;
        for (Member member : members) {
            if (team.members().contains(member.characterId())) {
                if (guildId != 0 && member.guildId() != guildId) {
                    return false;
                }
                guildId = member.guildId();
            }
        }
        return guildId > 0;
    }

    private static Team team(List<Member> pool, int size, Comparator<Member> nearest, String name) {
        List<Member> sorted = new ArrayList<>(pool);
        sorted.sort(nearest);
        List<Member> chosen = sorted.subList(0, size);
        double rating = 0;
        double deviationSquares = 0;
        double volatility = 0;
        List<Integer> ids = new ArrayList<>();
        for (Member member : chosen) {
            ids.add(member.characterId());
            rating += member.rating().rating();
            deviationSquares += member.rating().deviation() * member.rating().deviation();
            volatility += member.rating().volatility();
        }
        return new Team(ids, name == null || name.isBlank() ? PICKUP : name,
                new Glicko2.Rating(rating / size, Math.sqrt(deviationSquares / size), volatility / size));
    }

    private static double gap(Team team, Glicko2.Rating target) {
        return Math.abs(team.rating().rating() - target.rating());
    }

    /** The size a player can field: itself and its companions standing with it, at most {@link #MAX_SIZE}. */
    static int fieldable(int companionsHere) {
        return Math.min(MAX_SIZE, 1 + Math.max(0, companionsHere));
    }
}
