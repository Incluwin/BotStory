package server.agents.capabilities.partyquest.apq;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One APQ combination stage as its party works it (APQ plan A4 S2/S3): the arrangement being formed, who stands where
 * for it, and what Amos's answers have ruled out. A try is judged when the leader asks Amos and the wrong effect shows
 * on the map (EventInstanceManager.wrongEffectCount): Amos's answer to it is what he says aloud (stage 2: how many ropes
 * weigh the same) or the slimes that appear (stage 3: how many stood right), worked out from the formation that stood
 * and the event's combination exactly as 9201044.js counts it. On stage 2 the seventh miss redraws the combination
 * (its property is emptied) and the deduction starts over. Owned by its session; every method locks this object.
 */
final class AgentApqPuzzle {
    /** What one judged try told the party. */
    record Judged(int[] tried, int answer, boolean redrawn) { }

    private final int stage;
    private final int mapId;
    private final List<Rectangle> areas;
    private final AgentApqCombinationSolver solver;
    private int[] trying;
    private final Map<Integer, Integer> spots = new LinkedHashMap<>();
    private int wrongSeen = -1;
    private int tries;
    private int draws;
    private int[] lastFormation;
    private long formedSinceMs;

    private AgentApqPuzzle(int stage, int mapId, List<Rectangle> areas, AgentApqCombinationSolver solver) {
        this.stage = stage;
        this.mapId = mapId;
        this.areas = areas;
        this.solver = solver;
    }

    /** The puzzle of the stage played on {@code mapId}: Heart Strings (2) or the Twisted Switcher (3); else null. */
    static AgentApqPuzzle forMap(int mapId) {
        if (AgentApqDefinition.ROPE_MAPS.contains(mapId)) {
            return new AgentApqPuzzle(2, mapId, AgentApqDefinition.ROPE_AREAS, AgentApqCombinationSolver.ropes());
        }
        if (mapId == AgentApqDefinition.PLATFORM_MAP) {
            return new AgentApqPuzzle(3, mapId, AgentApqDefinition.PLATFORM_AREAS,
                    AgentApqCombinationSolver.platforms());
        }
        return null;
    }

    int stage() { return stage; }

    int mapId() { return mapId; }

    List<Rectangle> areas() { return areas; }

    synchronized int tries() { return tries; }

    synchronized int draws() { return draws; }

    synchronized int remaining() { return solver.remaining(); }

    /** The area index of the first area containing {@code at}, as 9201044.js counts a body; -1 for none. */
    int areaOf(Point at) {
        if (at == null) {
            return -1;
        }
        for (int i = 0; i < areas.size(); i++) {
            if (areas.get(i).contains(at)) {
                return i;
            }
        }
        return -1;
    }

    /** Counts per area of these positions, as 9201044.js builds its objset (a body counts in its first area). */
    int[] formation(List<Point> bodies) {
        int[] counts = new int[areas.size()];
        for (Point at : bodies) {
            int area = areaOf(at);
            if (area >= 0) {
                counts[area]++;
            }
        }
        return counts;
    }

    /**
     * The arrangement to form now and who takes which area of it: the solver's next try while none is open, kept until
     * Amos judges it. {@code standing} is each participant's area (-1 off every area), {@code xs} where each stands;
     * a participant already on an area the try still needs keeps it, the rest take the nearest one short of bodies.
     * Participants beyond the five bodies are given no spot (-1): they stay off the areas. The spots are dealt again
     * whenever the participants change (one arrives on the stage after the try was drawn, one goes down or leaves).
     */
    synchronized Map<Integer, Integer> plan(Map<Integer, Integer> standing, Map<Integer, Integer> xs) {
        if (trying == null) {
            int[] counts = new int[areas.size()];
            standing.values().forEach(area -> {
                if (area >= 0) counts[area]++;
            });
            trying = solver.next(counts);
            spots.clear();
            spots.putAll(assign(trying, standing, xs, areas));
            formedSinceMs = 0L;
        } else if (!spots.keySet().equals(standing.keySet())) {
            spots.clear();
            spots.putAll(assign(trying, standing, xs, areas));
            formedSinceMs = 0L;
        }
        return Map.copyOf(spots);
    }

    /** The area this participant takes in the try being formed (-1: it stays off); null before a plan has it. */
    synchronized Integer spotOf(int id) {
        return spots.get(id);
    }

    /** The try being formed; null before the first plan or right after a judged one. */
    synchronized int[] trying() {
        return trying == null ? null : trying.clone();
    }

    /** The formation stands as planned since {@code nowMs} (first call), or stopped standing (0). */
    synchronized long observeFormed(boolean formed, int[] formation, long nowMs) {
        if (!formed) {
            formedSinceMs = 0L;
            return 0L;
        }
        lastFormation = formation.clone();
        if (formedSinceMs == 0L) formedSinceMs = Math.max(1L, nowMs);
        return formedSinceMs;
    }

    /**
     * Reads Amos's judgement: {@code wrongCount} is the map's wrong effects so far, {@code combination} the event's
     * combination property now (null or blank once a seventh miss redrew it). Null when no new try was judged; else
     * the try and its answer, the solver told, and the next try left to plan. The first reading only sets the base.
     */
    synchronized Judged observeJudged(int wrongCount, int[] combination) {
        if (wrongSeen < 0) {
            wrongSeen = wrongCount;
            return null;
        }
        if (wrongCount <= wrongSeen) {
            return null;
        }
        wrongSeen = wrongCount;
        tries++;
        int[] tried = lastFormation != null ? lastFormation.clone() : trying == null ? null : trying.clone();
        trying = null;
        spots.clear();
        formedSinceMs = 0L;
        if (combination == null) {
            // stage 2's seventh miss: a new draw, everything ruled out so far is open again
            draws++;
            solver.reset();
            return new Judged(tried, -1, true);
        }
        if (tried == null) {
            return new Judged(null, -1, false);
        }
        int answer = stage == 2 ? AgentApqCombinationSolver.sameCounts(tried, combination)
                : AgentApqCombinationSolver.bodiesRight(tried, combination);
        solver.observe(tried, answer);
        return new Judged(tried, answer, false);
    }

    /** The event's combination property ("2,1,2,0,0,0,0,0,0") as counts per area; null when blank or unreadable. */
    int[] parseCombination(String property) {
        if (property == null || property.isBlank()) {
            return null;
        }
        String[] parts = property.split(",");
        if (parts.length < areas.size()) {
            return null;
        }
        int[] counts = new int[areas.size()];
        try {
            for (int i = 0; i < counts.length; i++) {
                counts[i] = Integer.parseInt(parts[i].trim());
            }
        } catch (NumberFormatException unreadable) {
            return null;
        }
        return counts;
    }

    /**
     * Who takes which area of {@code arrangement}: a participant standing on an area it still needs keeps it; every
     * other one, nearest first, takes the nearest area still short; -1 for one the five bodies leave over.
     */
    static Map<Integer, Integer> assign(int[] arrangement, Map<Integer, Integer> standing, Map<Integer, Integer> xs,
                                        List<Rectangle> areas) {
        int[] needed = arrangement.clone();
        Map<Integer, Integer> spots = new LinkedHashMap<>();
        List<Integer> movers = new ArrayList<>();
        List<Integer> order = new ArrayList<>(standing.keySet());
        order.sort(Integer::compare);
        for (int id : order) {
            int area = standing.getOrDefault(id, -1);
            if (area >= 0 && area < needed.length && needed[area] > 0) {
                needed[area]--;
                spots.put(id, area);
            } else {
                movers.add(id);
            }
        }
        for (int id : movers) {
            int x = xs.getOrDefault(id, 0);
            int best = -1;
            for (int area = 0; area < needed.length; area++) {
                if (needed[area] <= 0) continue;
                if (best < 0 || Math.abs(centerX(areas.get(area)) - x) < Math.abs(centerX(areas.get(best)) - x)) {
                    best = area;
                }
            }
            if (best >= 0) needed[best]--;
            spots.put(id, best);
        }
        return spots;
    }

    /** True when every planned body stands on its own area and nobody else of the party stands on any. */
    static boolean formed(Map<Integer, Integer> spots, Map<Integer, Integer> standing) {
        for (Map.Entry<Integer, Integer> spot : spots.entrySet()) {
            int at = standing.getOrDefault(spot.getKey(), -1);
            if (spot.getValue() >= 0 ? at != spot.getValue() : at >= 0) {
                return false;
            }
        }
        return true;
    }

    /** The participants of {@code spots}, ordered by who most needs to move (furthest from its area first). */
    static List<Integer> byDistance(Map<Integer, Integer> spots, Map<Integer, Integer> xs, List<Rectangle> areas) {
        List<Integer> ids = new ArrayList<>(spots.keySet());
        ids.sort(Comparator.comparingInt((Integer id) -> spots.get(id) < 0 ? 0
                : -Math.abs(centerX(areas.get(spots.get(id))) - xs.getOrDefault(id, 0))).thenComparingInt(id -> id));
        return ids;
    }

    static int centerX(Rectangle area) {
        return area.x + area.width / 2;
    }

    @Override
    public synchronized String toString() {
        return "stage" + stage + " trying=" + Arrays.toString(trying) + " spots=" + spots + " tries=" + tries
                + " remaining=" + solver.remaining();
    }
}
