package server.living.population;

import server.living.population.PopulationContent.Career;
import server.living.resident.ResidentShaper;

/** A citizen's career as its Explorer branch (Cygnus and Aran as theirs). */
public final class CareerBranch {
    private CareerBranch() {
    }

    /** 1 warrior, 2 magician, 3 bowman, 4 thief, 5 pirate. */
    public static int of(Population population, Citizen citizen) {
        Career career = population.content().career(citizen.career());
        if (career == null) {
            return 1;
        }
        int branch = CitizenScrolling.branch(ResidentShaper.job(career.job()).getId());
        return branch == 52 ? 5 : branch;
    }
}
