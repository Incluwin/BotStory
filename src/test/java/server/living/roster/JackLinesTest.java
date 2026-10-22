package server.living.roster;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.pace.AgentPqMemberPower;
import server.agents.capabilities.partyquest.pace.AgentPqPaceCalibration;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate.Kind;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate.Weakness;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CWKPQ plan C8: Jack's words over the leader's list, from an estimate. */
class JackLinesTest {
    private static final AgentPqMemberPower LEADER = power(1, "Ganre", true);
    private static final AgentPqMemberPower ORRIN = power(2, "Orrin", false);
    private static final AgentPqMemberPower KATE = power(3, "Kate", false);

    private static AgentPqMemberPower power(int id, String name, boolean human) {
        return new AgentPqMemberPower(id, name, human, human, 140, 112, Map.of(), Map.of(), Map.of(), 20.0d);
    }

    private static AgentPqPaceEstimate clearable(double p10S, double p50S, double p90S, List<Weakness> weaknesses) {
        return new AgentPqPaceEstimate(true, p10S, p50S, p90S, p50S, 0.0d, Map.of(), Map.of(), weaknesses,
                AgentPqPaceCalibration.Source.PRIOR, 0);
    }

    @Test
    void heHedgesHisGuessInWholeMinutes() {
        assertEquals(List.of("I haven't seen this list in the keep, but I'd guess about #b45 minutes#k from the hallway"
                + " to the Grandmasters, if you keep things moving."), JackLines.clearTime(
                clearable(44 * 60, 46 * 60, 50 * 60, List.of())));
        assertEquals(List.of("I haven't seen this list in the keep, but I'd guess about #b55 minutes#k from the hallway"
                        + " to the Grandmasters, if you keep things moving. Anywhere from 40 to 75, depending on the"
                        + " Grandmasters."),
                JackLines.clearTime(clearable(40 * 60, 55 * 60, 75 * 60, List.of())), "a wide range is said");
    }

    @Test
    void aListThatCannotClearIsToldWhatNobodyCanHurtAndWhere() {
        AgentPqPaceEstimate cannot = new AgentPqPaceEstimate(false, 7_200.0d, 7_200.0d, 7_200.0d, 7_200.0d, 1.0d,
                Map.of(), Map.of(), List.of(new Weakness(0, 9_400_594, "bowmanRoom", Kind.UNHITTABLE)),
                AgentPqPaceCalibration.Source.PRIOR, 0);

        assertEquals(List.of("#rI don't see this list getting through the keep:#k nobody on it can hurt Master"
                + " Guardians in the bowmen's room."), JackLines.clearTime(cannot));
    }

    @Test
    void slowRoomsAndGrandmastersComeFirstThenCompanionsAtMostTwo() {
        AgentPqPaceEstimate estimate = clearable(50 * 60, 60 * 60, 70 * 60, List.of(
                new Weakness(ORRIN.characterId(), 9_400_582, "guards", Kind.FRAGILE),
                new Weakness(0, 9_400_594, "bowmanRoom", Kind.SLOW),
                new Weakness(0, 9_400_590, "masters", Kind.SLOW)));

        assertEquals(List.of("#rYour bowmen look slow in their room.#k The Test of Unity gives you 20 minutes to bring"
                + " every weapon back.", JackLines.MASTERS_SLOW), JackLines.cautions(estimate, List.of(LEADER, ORRIN,
                KATE)));
    }

    @Test
    void companionsAreNamedButTheHumansNever() {
        AgentPqPaceEstimate estimate = clearable(50 * 60, 60 * 60, 70 * 60, List.of(
                new Weakness(LEADER.characterId(), 9_400_582, "guards", Kind.FRAGILE),
                new Weakness(ORRIN.characterId(), 9_400_582, "guards", Kind.FRAGILE),
                new Weakness(KATE.characterId(), 9_400_590, "masters", Kind.MISSES)));

        assertEquals(List.of("#bOrrin#k might not last long against the Crimson Guardians.",
                "#bKate#k will hardly land a hit on Margana."), JackLines.cautions(estimate, List.of(LEADER, ORRIN,
                KATE)));
    }

    @Test
    void wholeMinutesAreTheOnlyDigitsHeSays() {
        Pattern number = Pattern.compile("\\d+");
        for (int p50 = 600; p50 <= 7_200; p50 += 97) {
            AgentPqPaceEstimate estimate = clearable(p50 * 0.6d, p50, p50 * 1.5d, List.of(
                    new Weakness(0, 9_400_594, "bowmanRoom", Kind.SLOW),
                    new Weakness(0, 9_400_590, "masters", Kind.SLOW)));
            for (String line : JackLines.lines(estimate, List.of(LEADER, ORRIN))) {
                Matcher matcher = number.matcher(line.replace("Test of Unity gives you 20 minutes", ""));
                while (matcher.find()) {
                    String digits = matcher.group();
                    int at = matcher.end();
                    boolean minutes = line.startsWith(" minutes", at) || line.startsWith(" to ", at)
                            || line.startsWith(",", at);
                    assertTrue(minutes, () -> "a number that is not whole minutes: " + digits + " in " + line);
                }
            }
        }
        assertEquals(List.of(), JackLines.lines(null, List.of(LEADER)));
    }

    @Test
    void heNamesTheCompanionsWhoseBagsAreTooFullForTheWeapons() {
        assertEquals(null, JackLines.fullBags(List.of()));
        assertEquals("#bOrrin#k has no room in their Etc bag for the keep's weapons. Make some first.",
                JackLines.fullBags(List.of("Orrin")));
        assertEquals("#bOrrin, Kate and Vex#k have no room in their Etc bag for the keep's weapons. Make some first.",
                JackLines.fullBags(List.of("Orrin", "Kate", "Vex")));
    }
}
