package server.living.roster;

import client.Character;
import server.agents.capabilities.partyquest.apq.AgentApqAdmissionService;
import server.agents.capabilities.partyquest.lpq.AgentLpqAdmissionService;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * What a quest's lineup needs between its members before Yes (P6g LPQ step 5): the tower's roles at the Red Sign.
 * LPQ admission refuses a party whose companions do not cover Teleport with magic damage, Dark Sight, stage 7's
 * weapon-ranged attack and a physical attack between them (the humans are not counted, as admission counts them),
 * so the sign shows the first missing one as its blocker before the leader can say Yes, instead of a refusal after
 * it. APQ (plan A7) needs a lady and a gentleman between all six, humans too (AmoriaPQ.js's gender mask), and Amos
 * says which is missing. Every other quest's lineup is returned as it is.
 */
final class LineupComposition {
    private LineupComposition() {
    }

    /** {@link #apply(LineupPlan, Character, PqEntrance, Function, Function)} with humans found as companions are. */
    static LineupPlan apply(LineupPlan plan, Character leader, PqEntrance entrance,
                            Function<Integer, Character> companionById) {
        return apply(plan, leader, entrance, companionById, companionById);
    }

    /**
     * {@code plan} kept out by the first thing its members miss between them, in the entrance's notice; else {@code plan}
     * itself. Only an LPQ or APQ lineup with nothing else keeping it out is weighed; {@code companionById} finds a
     * companion of the lineup by its character id, {@code humanById} a human of it (null: not loaded; it adds nothing).
     */
    static LineupPlan apply(LineupPlan plan, Character leader, PqEntrance entrance,
                            Function<Integer, Character> companionById, Function<Integer, Character> humanById) {
        if (plan.blocker() != null) {
            return plan;
        }
        if ("apq".equals(entrance.key())) {
            return bothGenders(plan, leader, entrance, companionById, humanById);
        }
        if (!"lpq".equals(entrance.key())) {
            return plan;
        }
        List<Character> companions = new ArrayList<>();
        for (LineupPlan.Seat seat : plan.lineup()) {
            if (!seat.human()) {
                Character companion = companionById.apply(seat.characterId());
                if (companion != null) {
                    companions.add(companion);
                }
            }
        }
        List<String> missing = AgentLpqAdmissionService.missingCapabilities(companions);
        if (missing.isEmpty()) {
            return plan;
        }
        return plan.withBlocker(entrance.voice().forAdmit("Missing LPQ party capability: " + String.join(", ", missing),
                leader.getName()));
    }

    /** An APQ lineup of six with nobody of one gender, kept out in Amos's words; else as it is. */
    private static LineupPlan bothGenders(LineupPlan plan, Character leader, PqEntrance entrance,
                                          Function<Integer, Character> companionById,
                                          Function<Integer, Character> humanById) {
        List<Integer> genders = new ArrayList<>();
        for (LineupPlan.Seat seat : plan.lineup()) {
            Character member = seat.characterId() == leader.getId() ? leader
                    : (seat.human() ? humanById : companionById).apply(seat.characterId());
            if (member == null) {
                return plan; // not loaded: admission reads it again
            }
            genders.add(member.getGender());
        }
        if (genders.size() < entrance.seatRules().min()) {
            return plan; // too few: the size line says so first
        }
        String missing = AgentApqAdmissionService.genderProblem(genders);
        return missing.isEmpty() ? plan : plan.withBlocker(entrance.voice().forAdmit(missing, leader.getName()));
    }
}
