package server.living.roster;

import server.living.LivingWorld;
import server.living.store.LivingStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a hired citizen is to its owner besides a companion (docs/BOT_CONCEPT_PLAN.md F4): a {@link Role#MENTOR} born
 * to show a new character around until its second job, or an {@link Role#APPRENTICE} its owner adopted and picks each
 * job for. Kept in living_roster.role / role_target (changeset 54) and cached here by persona id, since a hire has one
 * owner. Read at boot, written through; a release deletes the row and {@link #forget} drops the entry. A mentor holds a
 * party seat but no roster slot ({@link #takesRosterSlot}) and does no bench duty ({@link #refusal}).
 */
public final class CompanionRoles {
    public enum Role { MENTOR, APPRENTICE }

    /** One role: whose companion it is, the role, and an apprentice's next job (a Job name; null for none picked). */
    public record Held(int ownerId, String personaId, Role role, String target) { }

    /** What a mentor says when it is given bench work. */
    static final String MENTOR_REFUSAL = "i'm just here to show you around. no jobs for me.";

    private final LivingWorld world;
    private final Map<String, Held> byPersona = new ConcurrentHashMap<>();

    public CompanionRoles(LivingWorld world) {
        this.world = world;
    }

    /** Boot, on companion-ops after the roster's reconciliation: every role the roster rows hold. */
    public void load() {
        byPersona.clear();
        for (LivingStore.RosterRole row : world.store().rosterRoles()) {
            Role role = parse(row.role());
            if (role != null) {
                byPersona.put(row.personaId(), new Held(row.characterId(), row.personaId(), role, row.target()));
            }
        }
    }

    /**
     * Before the row exists (a hire under way): the role is known to the roster's own checks while it enrolls, so a
     * mentor's enrollment hands over no roster book and counts toward no cap. {@link #set} writes it once the row is
     * there; a hire that fails {@link #forget}s it.
     */
    public void pending(int ownerId, String personaId, Role role) {
        byPersona.put(personaId, new Held(ownerId, personaId, role, null));
    }

    /** Writes the role and target onto the owner's row; false (the cache unchanged) when the row did not take it. */
    public boolean set(int ownerId, String personaId, Role role, String target) {
        if (!world.store().saveRosterRole(ownerId, personaId, role == null ? null : role.name(), target)) {
            return false;
        }
        if (role == null) {
            byPersona.remove(personaId);
        } else {
            byPersona.put(personaId, new Held(ownerId, personaId, role, target));
        }
        return true;
    }

    /** Its row went (a release): nothing to write, the row's columns went with it. */
    public void forget(String personaId) {
        if (personaId != null) {
            byPersona.remove(personaId);
        }
    }

    public Held of(String personaId) {
        return personaId == null ? null : byPersona.get(personaId);
    }

    public Role role(String personaId) {
        Held held = of(personaId);
        return held == null ? null : held.role();
    }

    /** Every held role of this kind. */
    public List<Held> all(Role role) {
        List<Held> out = new ArrayList<>();
        for (Held held : byPersona.values()) {
            if (held.role() == role) {
                out.add(held);
            }
        }
        return out;
    }

    /** The owner's companion holding {@code role} (a new character has at most one mentor), or null. */
    public Held ofOwner(int ownerId, Role role) {
        for (Held held : byPersona.values()) {
            if (held.ownerId() == ownerId && held.role() == role) {
                return held;
            }
        }
        return null;
    }

    // ---- null-safe reads for the roster, which tests build over a mocked world ------------------

    /** True for a mentor while MENTOR_ENABLED. */
    public static boolean mentor(LivingWorld world, String personaId) {
        CompanionRoles roles = world == null ? null : world.roles();
        return roles != null && world.config() != null && world.config().MENTOR_ENABLED
                && roles.role(personaId) == Role.MENTOR;
    }

    /** True for an apprentice while APPRENTICE_ENABLED. */
    public static boolean apprentice(LivingWorld world, String personaId) {
        CompanionRoles roles = world == null ? null : world.roles();
        return roles != null && world.config() != null && world.config().APPRENTICE_ENABLED
                && roles.role(personaId) == Role.APPRENTICE;
    }

    /** False for a mentor: it holds a party seat but no roster slot, and is no hire for the hire cap. */
    public static boolean takesRosterSlot(LivingWorld world, String personaId) {
        return !mentor(world, personaId);
    }

    /** Rows of {@code entries} that take a roster slot (every one but a mentor's). */
    public static int rosterSlots(LivingWorld world, List<LivingStore.RosterEntry> entries) {
        int slots = 0;
        for (LivingStore.RosterEntry entry : entries) {
            slots += takesRosterSlot(world, entry.personaId()) ? 1 : 0;
        }
        return slots;
    }

    /** A mentor's refusal of bench work (duties, errands, delegated jobs), in its own typing; null for anyone else. */
    public static RosterResult refusal(LivingWorld world, String personaId, String name) {
        if (!mentor(world, personaId)) {
            return null;
        }
        HiredHands hires = world.hiredHands();
        int characterId = server.living.config.LivingContent.hireCharacterId(personaId);
        String line = hires == null || characterId <= 0 ? MENTOR_REFUSAL : hires.voice(characterId, MENTOR_REFUSAL);
        return new RosterResult.Failed(name + ": " + line);
    }

    static Role parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Role.valueOf(value.trim());
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
