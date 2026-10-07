package server.living.roster;

import client.Character;
import server.agents.capabilities.partyquest.lpq.AgentLpqAdmissionService;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * What a quest's lineup needs between its members before Yes (P6g LPQ step 5): the tower's roles at the Red Sign.
 * LPQ admission refuses a party whose companions do not cover Teleport with magic damage, Dark Sight, stage 7's
 * weapon-ranged attack and a physical attack between them (the humans are not counted, as admission counts them),
 * so the sign shows the first missing one as its blocker before the leader can say Yes, instead of a refusal after
 * it. Every other quest's lineup is returned as it is.
 */
final class LineupComposition {
    private LineupComposition() {
    }

    /**
     * {@code plan} kept out by the first role its companions miss, in the entrance's notice; else {@code plan} itself.
     * Only an LPQ lineup with nothing else keeping it out is weighed; {@code companionById} finds a companion of the
     * lineup by its character id (null: not loaded; it adds nothing).
     */
    static LineupPlan apply(LineupPlan plan, Character leader, PqEntrance entrance,
                            Function<Integer, Character> companionById) {
        if (!"lpq".equals(entrance.key()) || plan.blocker() != null) {
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
}
