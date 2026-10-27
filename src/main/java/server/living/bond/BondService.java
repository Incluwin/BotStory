package server.living.bond;

import client.Character;
import server.living.LivingWorld;
import server.living.config.Content.BondStage;
import server.living.config.Content.Persona;
import server.living.store.LivingStore;
import server.living.store.LivingStore.Bond;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Per (player, persona) relationship: stage from points, bond flags, and the player-wide flag
 * bucket ({@link #PLAYER_BUCKET}) that rumors and quests use for facts not tied to one persona.
 */
public final class BondService {
    /** Pseudo persona id whose bond row holds a player's persona-independent flags. */
    public static final String PLAYER_BUCKET = "_player";

    private final LivingWorld world;

    public BondService(LivingWorld world) {
        this.world = world;
    }

    /** Existing bond or a fresh MET bond (persisted) when the player first talks to the persona. */
    public Bond bond(Character chr, Persona persona) {
        return bond(chr.getId(), persona.id(), true);
    }

    public Bond bond(int characterId, String personaId, boolean create) {
        Bond bond = world.store().bond(characterId, personaId);
        if (bond == null && create) {
            bond = Bond.fresh(characterId, personaId, System.currentTimeMillis());
            world.store().saveBond(bond);
            world.store().event(characterId, personaId, "bond.met", null);
        }
        return bond;
    }

    public Bond peek(int characterId, String personaId) {
        return world.store().bond(characterId, personaId);
    }

    public BondStage stage(Character chr, Persona persona) {
        Bond bond = peek(chr.getId(), persona.id());
        return bond == null ? BondStage.NONE : bond.stage();
    }

    public BondStage stageFor(int points) {
        if (points >= world.config().BOND_SWORN_POINTS) {
            return BondStage.SWORN;
        }
        if (points >= world.config().BOND_TRUSTED_POINTS) {
            return BondStage.TRUSTED;
        }
        if (points >= world.config().BOND_ACQUAINTED_POINTS) {
            return BondStage.ACQUAINTED;
        }
        return BondStage.MET;
    }

    /** Adds points, recomputes the stage and tells the player when it changed. */
    public Bond addPoints(Character chr, Persona persona, int delta, String reason) {
        Bond bond = bond(chr, persona);
        int points = Math.max(0, bond.points() + delta);
        BondStage stage = stageFor(points);
        Bond updated = new Bond(bond.characterId(), bond.personaId(), stage, points, bond.flags(), bond.contractState(),
                bond.contractStartedAtMs(), bond.offerExpiresAtMs(), bond.proofProgress(), bond.paidMeso(),
                bond.partySeconds(), bond.leftAtMs(), System.currentTimeMillis());
        world.store().saveBond(updated);
        Friendship friendship = world.friendship();
        int level = friendship.levelFor(points);
        int was = friendship.levelFor(bond.points());
        if (stage != bond.stage() || level != was) {
            // a card shows the bond, and a hire's Crew row its friendship level (C1.4)
            server.living.roster.CompanionViews.invalidate(world, chr.getId(), bond.personaId());
        }
        if (delta != 0) {
            world.store().event(chr.getId(), persona.id(), "bond.points", delta + " (" + reason + ") -> " + points);
        }
        if (level > was && level > 1) {
            chr.dropMessage(5, "Friendship with " + persona.name() + ": Lv " + level + " " + Friendship.label(level) + ".");
        } else if (level < was) {
            chr.dropMessage(5, "Friendship with " + persona.name() + " fell to Lv " + level + " " + Friendship.label(level) + ".");
        }
        List<String> lines = List.of();
        if (stage != bond.stage() && stage.atLeast(BondStage.ACQUAINTED)) {
            lines = world.arcs().lines(persona, "bond." + stage.name().toLowerCase());
        } else if (level > was && level >= Friendship.CLOSE_FRIEND && was < Friendship.CLOSE_FRIEND) {
            lines = world.arcs().lines(persona, "friend.close");
        } else if (level > was && level >= Friendship.MAX_LEVEL) {
            lines = world.arcs().lines(persona, "friend.best");
        }
        if (!lines.isEmpty()) {
            world.residents().reply(persona, chr, lines.get(Math.floorMod(points, lines.size())));
        }
        return updated;
    }

    public Bond setFlag(int characterId, String personaId, String flag) {
        Bond bond = bond(characterId, personaId, true);
        if (bond.flags().contains(flag)) {
            return bond;
        }
        Set<String> flags = new LinkedHashSet<>(bond.flags());
        flags.add(flag);
        Bond updated = new Bond(bond.characterId(), bond.personaId(), bond.stage(), bond.points(), flags,
                bond.contractState(), bond.contractStartedAtMs(), bond.offerExpiresAtMs(), bond.proofProgress(),
                bond.paidMeso(), bond.partySeconds(), bond.leftAtMs(), bond.lastInteractionAtMs());
        world.store().saveBond(updated);
        world.store().event(characterId, personaId, "bond.flag", flag);
        return updated;
    }

    public void setPlayerFlag(int characterId, String flag) {
        setFlag(characterId, PLAYER_BUCKET, flag);
    }

    /** True when the flag is set on the world, on the player bucket, or on the (player, persona) bond. */
    public boolean hasFlag(int characterId, String personaId, String flag, Set<String> worldFlags) {
        if (worldFlags != null && worldFlags.contains(flag)) {
            return true;
        }
        Bond player = peek(characterId, PLAYER_BUCKET);
        if (player != null && player.has(flag)) {
            return true;
        }
        if (personaId != null) {
            Bond bond = peek(characterId, personaId);
            return bond != null && bond.has(flag);
        }
        return false;
    }

    public void save(Bond bond) {
        world.store().saveBond(bond);
    }

    /** Hours any player has fought beside this persona, summed over every bond. */
    public double partyHoursTotal(String personaId) {
        return world.store().partySecondsTotal(personaId) / 3600.0d;
    }

    public LivingStore store() {
        return world.store();
    }
}
