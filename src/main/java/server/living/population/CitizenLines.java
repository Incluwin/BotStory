package server.living.population;

import client.Job;
import client.JobProgressionPolicy;
import server.living.LivingWorld;
import server.living.population.PopulationContent.Career;
import server.living.population.PopulationContent.Region;
import server.living.resident.ResidentShaper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A citizen's job line and where it may live (2026-10-24, docs/CYGNUS_ARAN_BOTS_PLAN.md). A career is an Explorer's, a
 * Cygnus Knight's (born a Noblesse in Ereve) or the Aran's (born a Legend in Rien), by the beginner of its job. With the
 * Cygnus/Aran careers on (LivingWorldConfig.CYGNUS_ARAN_CAREERS_ENABLED) a region that names a line (regions.yaml
 * {@code line}) is that line's home: only its citizens live there, and one of them below its first job lives nowhere
 * else, as a player of that line is still in its starting area; elsewhere a Knight or an Aran lives from its first job
 * on. With them off, births and seeds draw the Explorers' careers only and regions take anyone, as before.
 */
public final class CitizenLines {
    public static final String EXPLORER = "explorer";
    public static final String CYGNUS = "cygnus";
    public static final String ARAN = "aran";
    /** Both lines take their first job at Lv10 and leave their starting area then. */
    static final int FIRST_JOB_LEVEL = 10;

    private CitizenLines() {
    }

    /** LivingWorldConfig.CYGNUS_ARAN_CAREERS_ENABLED; off while the population has no living world (tests). */
    public static boolean enabled(Population population) {
        LivingWorld world = population.world();
        return world != null && world.config().CYGNUS_ARAN_CAREERS_ENABLED;
    }

    /** The beginner the career's line starts from: BEGINNER, NOBLESSE or LEGEND. */
    public static Job beginner(Career career) {
        return career == null ? Job.BEGINNER : JobProgressionPolicy.beginnerOf(ResidentShaper.job(career.job()));
    }

    public static String line(Career career) {
        Job beginner = beginner(career);
        return beginner == Job.NOBLESSE ? CYGNUS : beginner == Job.LEGEND ? ARAN : EXPLORER;
    }

    /** The careers births and seeds draw from: all of them, or with the switch off only the Explorers'. */
    public static List<Career> drawable(Collection<Career> careers, boolean enabled) {
        List<Career> drawable = new ArrayList<>();
        for (Career career : careers) {
            if (enabled || EXPLORER.equals(line(career))) {
                drawable.add(career);
            }
        }
        return drawable;
    }

    /**
     * The part of {@code region}'s band a citizen of {@code career} may live in there, or null for none: a line's home
     * takes only its line, a Knight or an Aran lives elsewhere from its first job on. Switch off: the whole band.
     */
    public static Region band(Region region, Career career, boolean enabled) {
        if (!enabled) {
            return region;
        }
        String own = line(career);
        if (region.line() != null) {
            return region.line().equals(own) ? region : null;
        }
        return EXPLORER.equals(own) ? region : region.from(FIRST_JOB_LEVEL);
    }

    /** The home region of a Knight or an Aran below its first job (switch on), else null: any fitting region will do. */
    public static Region startingHome(Collection<Region> regions, Career career, int level, boolean enabled) {
        String own = line(career);
        if (!enabled || EXPLORER.equals(own) || level >= FIRST_JOB_LEVEL) {
            return null;
        }
        for (Region region : regions) {
            if (own.equals(region.line())) {
                return region;
            }
        }
        return null;
    }
}
