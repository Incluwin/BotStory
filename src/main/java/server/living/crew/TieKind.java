package server.living.crew;

import java.util.Locale;
import java.util.Set;

/**
 * What two companions of one crew are to each other (docs/BOT_CONCEPT_PLAN.md F6.4), seen from one of them: OWES and
 * BLAMES have a mirror on the other side (OWED, BLAMED), the rest are mutual. Authored {@code Persona.relationships}
 * kinds are free text ("RIVAL_OF", "SPARS_WITH", "OWES_LIFE"...): {@link #of} is the one table that reads them, shared
 * with the story arcs' rival chapters. CREWMATE is a pair with nothing authored between them.
 */
public enum TieKind {
    SPOUSE, RIVAL, OWES, OWED, BLAMES, BLAMED, FRIEND, CREWMATE;

    /** Kinds that make two personas rivals: either side naming one is enough (agreed with story arcs 2026-10-28). */
    static final Set<String> RIVAL_KINDS = Set.of("RIVAL", "RIVALS", "RIVAL_OF", "OLD_RIVAL_OF", "SPARS_WITH",
            "SPARRING_PARTNER", "TEASES", "TEASED_BY", "IS_TEASED_BY", "ARGUES_WITH", "OUTSHOOTS", "LOSES_TO_AT_CARDS",
            "CHALLENGES", "RACES", "ENVIES", "WON_A_BET_OFF", "LOST_A_BET_TO");
    /** Kinds that are no tie at all: the two have never met, or only heard of each other. */
    static final Set<String> NO_TIE_KINDS = Set.of("NEVER_MET", "HAS_NOT_MET", "HEARD_OF");
    /**
     * Kinds read as FRIEND on purpose: every kind authored between two recruitables today that is no rivalry, debt or
     * grudge. Any other kind is FRIEND too, but the banter lint names it so its author can check the reading.
     */
    static final Set<String> FRIEND_KINDS = Set.of("FRIEND", "OLD_FRIEND", "ADMIRES", "RESPECTS", "TRUSTS", "KNOWS",
            "CARRIED_THE_CHILDREN_OUT_WITH", "DIVES_WITH", "DRINKS_WITH", "HAGGLES_WITH", "HUNTED_WITH", "HUNTS_BESIDE",
            "HUNTS_THE_PARKS_OF", "HUNTS_WITH", "IS_KEPT_STANDING_BY", "IS_PATCHED_UP_BY", "IS_WRITTEN_UP_BY",
            "KEEPS_AN_EYE_ON", "KEEPS_STANDING", "LEARNED_FIRE_FROM", "LETS_IN_THE_SIDE_DOOR", "OLD_SHIPMATE_OF_A_FRIEND",
            "PATCHES_UP", "SAILED_WITH", "SECOND_TO", "SERVED_WITH", "SHARED_A_FIRE_WITH", "SHARES_A_ROAD_WITH",
            "SHARES_THE_ROAD_WITH", "SHOOTS_WITH", "SPLITS_SHARES_WITH", "SQUIRED_WITH", "STANDS_BESIDE",
            "STANDS_WATCH_WITH", "SUPPLIES", "TAUGHT_THE_LAMP_TO", "TRADES_SECRETS_WITH", "TRADES_WITH", "TRAINS_WITH",
            "VOUCHES_FOR", "WAS_TAUGHT_THE_LAMP_BY", "WATCHED_BY", "WATCHES", "WORKS_FOR", "WORKS_WITH", "WORKS_BESIDE",
            "WORKS_ALONGSIDE", "WORRIES_ABOUT", "WRITES_UP", "LOOKS_UP_TO", "LOOKS_OUT_FOR", "CONFIDES_IN",
            "COLLEAGUE_OF", "NEIGHBOUR");

    /**
     * The tie an authored relationship kind gives its persona toward the other one: RIVAL for {@link #RIVAL_KINDS};
     * OWES for any OWES*; OWED for IS_OWED*, OWED_BY and SAVED; BLAMES for BLAMES*, RESENTS and HAS_NOT_FORGIVEN*;
     * BLAMED for DISTRUSTED_BY; null for {@link #NO_TIE_KINDS}; FRIEND for everything else. Case is ignored.
     */
    public static TieKind of(String relationshipKind) {
        String kind = relationshipKind == null ? "" : relationshipKind.trim().toUpperCase(Locale.ROOT);
        if (kind.isEmpty() || NO_TIE_KINDS.contains(kind)) {
            return null;
        }
        if (RIVAL_KINDS.contains(kind)) {
            return RIVAL;
        }
        if (kind.startsWith("OWES")) {
            return OWES;
        }
        if (kind.startsWith("IS_OWED") || kind.equals("OWED_BY") || kind.equals("SAVED")) {
            return OWED;
        }
        if (kind.startsWith("BLAMES") || kind.equals("RESENTS") || kind.startsWith("HAS_NOT_FORGIVEN")) {
            return BLAMES;
        }
        if (kind.equals("DISTRUSTED_BY")) {
            return BLAMED;
        }
        return FRIEND;
    }

    /** True when {@link #of} reads {@code relationshipKind} on purpose, not by the FRIEND default (the banter lint). */
    static boolean named(String relationshipKind) {
        String kind = relationshipKind == null ? "" : relationshipKind.trim().toUpperCase(Locale.ROOT);
        return of(kind) != FRIEND || FRIEND_KINDS.contains(kind);
    }

    /** The same tie seen from the other persona: OWES and OWED, BLAMES and BLAMED swap; the rest are mutual. */
    public TieKind mirror() {
        return switch (this) {
            case OWES -> OWED;
            case OWED -> OWES;
            case BLAMES -> BLAMED;
            case BLAMED -> BLAMES;
            default -> this;
        };
    }

    /** The name the banter catalog uses ("rival", "owes"...). */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The kind a catalog key names, or null. */
    static TieKind fromKey(String key) {
        for (TieKind kind : values()) {
            if (kind.key().equals(key)) {
                return kind;
            }
        }
        return null;
    }
}
