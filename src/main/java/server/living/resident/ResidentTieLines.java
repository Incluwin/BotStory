package server.living.resident;

import server.living.config.Content;
import server.living.config.Content.Persona;
import server.living.config.Content.Relationship;
import server.living.config.LivingContent;
import server.living.crew.TieKind;
import server.living.population.Population;
import server.living.population.TieTalk;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Residents talk about each other (docs/WORLD_SYSTEMS_PLAN.md N2.2): a line about another Resident of the speaker's own
 * home town it has an authored relationship with, either side's (an OWES on theirs is an OWED on its), read through
 * {@link TieKind#of} and filled from chatter/ties.yaml's residents section. No line is written per pair. Null when
 * population.CITIZEN_TIES_ENABLED is off, there is no such Resident, or the catalog has no line for the tie.
 */
public final class ResidentTieLines {
    private ResidentTieLines() {
    }

    /** One tied Resident of its town and how the speaker is tied to them. */
    record Tied(Persona other, TieKind kind) { }

    /** A line of {@code section} ("idle") about a tied Resident of its town, picked at random. */
    public static String idle(LivingContent content, Persona persona) {
        List<Tied> tied = tied(content, persona);
        if (tied.isEmpty()) {
            return null;
        }
        Tied pick = tied.get(ThreadLocalRandom.current().nextInt(tied.size()));
        return line("idle", pick);
    }

    /** Its answer to "tell me about {@code other}" when it is tied to them and they share a town; null otherwise. */
    public static String about(LivingContent content, Persona persona, Persona other) {
        if (other == null) {
            return null;
        }
        for (Tied tied : tied(content, persona)) {
            if (tied.other().id().equals(other.id())) {
                return line("about", tied);
            }
        }
        return null;
    }

    private static String line(String section, Tied tied) {
        Population population = Population.get();
        TieTalk talk = population == null ? null : population.tieTalk();
        return talk == null ? null : talk.residentLine(section, tied.kind(), tied.other().name());
    }

    /** Every Resident of the speaker's home town it is tied to, by its own relationships, theirs, or a marriage. */
    static List<Tied> tied(LivingContent content, Persona persona) {
        List<Tied> out = new ArrayList<>();
        if (content == null || persona == null || persona.homeTown() == null) {
            return out;
        }
        for (Persona other : content.personas().values()) {
            if (other == persona || other.id().equals(persona.id()) || !persona.homeTown().equalsIgnoreCase(other.homeTown())) {
                continue;
            }
            TieKind kind = kind(persona, other);
            if (kind != null) {
                out.add(new Tied(other, kind));
            }
        }
        return out;
    }

    /** How {@code persona} is tied to {@code other}: a spouse first, then its own relationship, then theirs mirrored. */
    static TieKind kind(Persona persona, Persona other) {
        if (married(persona.spouse(), other.id()) || married(other.spouse(), persona.id())) {
            return TieKind.SPOUSE;
        }
        TieKind own = named(persona, other.id());
        if (own != null) {
            return own;
        }
        TieKind theirs = named(other, persona.id());
        return theirs == null ? null : theirs.mirror();
    }

    private static boolean married(Content.Spouse spouse, String personaId) {
        return spouse != null && !spouse.citizen() && personaId.equals(spouse.persona());
    }

    private static TieKind named(Persona from, String toId) {
        if (from.relationships() == null) {
            return null;
        }
        for (Relationship relationship : from.relationships()) {
            if (toId.equals(relationship.persona())) {
                return TieKind.of(relationship.kind());
            }
        }
        return null;
    }
}
