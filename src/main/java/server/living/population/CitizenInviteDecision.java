package server.living.population;

import client.BuddyList;
import client.Character;
import config.YamlConfig;
import server.living.population.CitizenStore.Acquaintance;

import java.util.function.Supplier;

/**
 * A citizen's answer to a player's party invite (docs/BOT_MANAGEMENT_PLAN.md, "Bot invite decisions"), in stock
 * words. It joins a player within the stock {@code EXP_SPLIT_LEECH_INTERVAL} levels, the widest gap at which a party
 * member still shares EXP from a kill it did not hit, unless it is busy: inside an event instance, minding the market
 * (a stall open, a market errand), or out with its own circle (in the party its circle formed). On yes it becomes the
 * player's guest (a GUEST seat charged to them, CompanionSlots).
 *
 * <p>P4 adds personality, from what the population already keeps: its temperament (its voice's reserve, its
 * personality's sociability, the recruiter and famer flags) and what it knows of the inviter (living_acquaintance
 * minutes together, a buddy, a guildmate). A reserved citizen turns strangers down and goes with someone it has
 * partied with; an easygoing one goes with anyone it has seen around and with some strangers; an outgoing one goes
 * with anyone. Whatever luck is involved is a fixed roll per (citizen, player), so asking again gives the same answer
 * until they get to know each other. The same temperament decides whether a guest offers to stay on
 * ({@link #staysOn}).</p>
 */
public final class CitizenInviteDecision {
    /** The answer and the stock party status the inviter sees on a no. */
    public enum Answer {
        ACCEPT(0),
        /** "... is taking care of another invitation." */
        BUSY(22),
        /** "... have denied request to the party." */
        TOO_FAR(23),
        /** "... have denied request to the party.": not with a stranger, by its own temperament. */
        DECLINED(23);

        private final int status;

        Answer(int status) {
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    /** How a citizen meets people. */
    public enum Temperament { RESERVED, EASYGOING, OUTGOING }

    /** The citizen's side of it: who it is and how it meets people. */
    public record Profile(int characterId, Temperament temperament, int sociability) { }

    /** What the citizen knows of the player: minutes together (in a party, on a map), a buddy, a guildmate. */
    public record Familiarity(int partyMinutes, int mapMinutes, boolean buddy, boolean guildmate) {
        public static final Familiarity NONE = new Familiarity(0, 0, false, false);

        /** Partied with, a buddy or a guildmate: someone it knows. */
        public boolean known() {
            return partyMinutes > 0 || buddy || guildmate;
        }

        /** Seen around (on the same map) long enough to be a familiar face. */
        public boolean seen() {
            return mapMinutes >= SEEN_MINUTES;
        }
    }

    /** Map minutes with a player that make them a familiar face rather than a stranger. */
    static final int SEEN_MINUTES = 5;
    /** A voice that ignores this share of lines or more is reserved (quiet-practical, terse-grinder). */
    static final double RESERVED_IGNORE_RATE = 0.3d;
    /** A personality below this sociability is reserved (efficient-v1). */
    static final int RESERVED_SOCIABILITY = 40;
    /** A voice that ignores this share of lines or fewer, with sociability {@link #OUTGOING_SOCIABILITY}, is outgoing. */
    static final double OUTGOING_IGNORE_RATE = 0.1d;
    static final int OUTGOING_SOCIABILITY = 55;
    /** Every this many party minutes together (living_acquaintance) add as many points to a guest's chance to stay on. */
    static final int PARTY_MINUTES_BONUS = 10;
    /** Salts of the fixed rolls, so the invite and the offer are two different coins. */
    private static final long INVITE_SALT = 0x1a71eL;
    private static final long OFFER_SALT = 0x57a1L;

    private CitizenInviteDecision() {
    }

    public static Answer decide(Character inviter, Character citizen) {
        Population population = Population.get();
        CitizenSession session = population == null ? null : population.presence().session(citizen.getId());
        Answer answer = decide(inviter.getLevel(), citizen.getLevel(), YamlConfig.config.server.EXP_SPLIT_LEECH_INTERVAL,
                busy(citizen, session));
        Citizen record = answer != Answer.ACCEPT || population == null ? null : population.citizen(citizen.getId());
        if (record == null) {
            return answer; // no personality on file: the level gap alone (P2a)
        }
        return welcome(profile(population, record), inviter.getId(), () -> familiarity(population, record, citizen, inviter));
    }

    public static Answer decide(int inviterLevel, int citizenLevel, int maxLevelGap, boolean busy) {
        if (busy) {
            return Answer.BUSY;
        }
        return Math.abs(inviterLevel - citizenLevel) <= maxLevelGap ? Answer.ACCEPT : Answer.TOO_FAR;
    }

    /**
     * Its temperament's answer to a player within its level range: someone it knows is welcome; a reserved citizen
     * declines anyone else; an outgoing one takes anyone; an easygoing one takes a familiar face, and a stranger on its
     * fixed roll for them. {@code familiarity} is read only when the answer needs it (a database row).
     */
    static Answer welcome(Profile profile, int inviterId, Supplier<Familiarity> familiarity) {
        if (profile.temperament() == Temperament.OUTGOING) {
            return Answer.ACCEPT;
        }
        Familiarity known = familiarity.get();
        if (known.known()) {
            return Answer.ACCEPT;
        }
        if (profile.temperament() == Temperament.RESERVED) {
            return Answer.DECLINED;
        }
        return known.seen() || roll(profile.characterId(), inviterId, INVITE_SALT) < 50 + profile.sociability() / 2
                ? Answer.ACCEPT : Answer.DECLINED;
    }

    /**
     * The hire list agrees with the invite (P4, "Hire list"): true when this citizen, within the owner's level range,
     * would take their invite by its temperament, from what it knows of them ({@code partyMinutes} and {@code
     * mapMinutes} together, a buddy; a guildmate is read here). A reserved citizen is no stranger's hired hand.
     */
    public static boolean welcomes(Citizen citizen, Character owner, int partyMinutes, int mapMinutes, boolean buddy) {
        Population population = Population.get();
        if (population == null || citizen == null) {
            return false;
        }
        boolean guildmate = citizen.guildId() > 0 && citizen.guildId() == owner.getGuildId();
        Familiarity known = new Familiarity(partyMinutes, mapMinutes, buddy, guildmate);
        return welcome(profile(population, citizen), owner.getId(), () -> known) == Answer.ACCEPT;
    }

    /**
     * True when this guest, after its party time with the owner, would offer to stay on: its fixed roll for the owner
     * under its sociability, higher when easygoing or outgoing, when the owner is a buddy or a guildmate, and the more
     * they have partied together ({@link #PARTY_MINUTES_BONUS}), so a guest that says no now may say yes after
     * another stint or two.
     */
    public static boolean staysOn(Character owner, Character guest, Citizen citizen) {
        Population population = Population.get();
        if (population == null || citizen == null) {
            return false;
        }
        return staysOn(profile(population, citizen), owner.getId(), familiarity(population, citizen, guest, owner));
    }

    static boolean staysOn(Profile profile, int ownerId, Familiarity familiarity) {
        int chance = profile.sociability() + switch (profile.temperament()) {
            case RESERVED -> 0;
            case EASYGOING -> 25;
            case OUTGOING -> 35;
        } + (familiarity.buddy() || familiarity.guildmate() ? 20 : 0)
                + familiarity.partyMinutes() / PARTY_MINUTES_BONUS * PARTY_MINUTES_BONUS;
        return roll(profile.characterId(), ownerId, OFFER_SALT) < Math.min(95, chance);
    }

    // ---- what the population knows -------------------------------------------------------------

    static Profile profile(Population population, Citizen citizen) {
        double reserve = population.voices().voiceOf(citizen).ignoreRate();
        int sociability = population.social().sociability(citizen);
        return new Profile(citizen.characterId(), temperament(citizen, reserve, sociability), sociability);
    }

    /** Recruiters and famers seek people out; a quiet voice or a low sociability keeps to itself; a chatty one is outgoing. */
    static Temperament temperament(Citizen citizen, double reserve, int sociability) {
        if (citizen.has(Citizen.FLAG_RECRUITER) || citizen.has(Citizen.FLAG_FAMER)) {
            return Temperament.OUTGOING;
        }
        if (reserve >= RESERVED_IGNORE_RATE || sociability < RESERVED_SOCIABILITY) {
            return Temperament.RESERVED;
        }
        return reserve <= OUTGOING_IGNORE_RATE && sociability >= OUTGOING_SOCIABILITY ? Temperament.OUTGOING
                : Temperament.EASYGOING;
    }

    /** Its living_acquaintance row with the player (one read), its buddy list and their guilds. */
    static Familiarity familiarity(Population population, Citizen citizen, Character loaded, Character player) {
        Acquaintance together = population.store().acquaintance(citizen.characterId(), player.getId());
        BuddyList buddies = loaded == null ? null : loaded.getBuddylist();
        boolean buddy = buddies != null && buddies.containsVisible(player.getId());
        boolean guildmate = citizen.guildId() > 0 && citizen.guildId() == player.getGuildId();
        return together == null ? new Familiarity(0, 0, buddy, guildmate)
                : new Familiarity(together.partyMinutes(), together.mapMinutes(), buddy, guildmate);
    }

    /** A fixed 0..99 roll for this citizen and this player: the same every time they meet. */
    static int roll(int citizenId, int playerId, long salt) {
        long h = ((long) citizenId << 32) ^ (playerId & 0xffffffffL) ^ salt;
        h = (h ^ (h >>> 33)) * 0xff51afd7ed558ccdL;
        h = (h ^ (h >>> 33)) * 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return (int) Math.floorMod(h, 100L);
    }

    private static boolean busy(Character citizen, CitizenSession session) {
        if (citizen.getEventInstance() != null) {
            return true;
        }
        return session != null && (session.stallOpen || session.activity == CitizenSession.Activity.MARKET
                || withItsCircle(citizen, session));
    }

    /** True while the citizen is out with its own circle: in the party its circle formed (SocialFabric.partyUp). */
    public static boolean withItsCircle(Character citizen) {
        Population population = Population.get();
        return population != null && withItsCircle(citizen, population.presence().session(citizen.getId()));
    }

    private static boolean withItsCircle(Character citizen, CitizenSession session) {
        return session != null && session.partyId != 0 && citizen.getParty() != null
                && citizen.getParty().getId() == session.partyId;
    }
}
