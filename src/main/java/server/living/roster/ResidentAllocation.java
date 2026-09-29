package server.living.roster;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.store.LivingStore.RosterEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Sharing the Residents among friends (docs/BOT_MANAGEMENT_PLAN.md "Resident allocation", decision 18 b). A friend keeps
 * at most RESIDENT_CAP_PER_OWNER Residents on their roster at once; hired hands do not count. ContractService.askToJoin
 * and RosterService.recruit refuse the next one in the Resident's own words ({@link #CAP_LINE}). A friend away for
 * RESIDENT_OWNER_INACTIVE_DAYS lets all of their rows go (plan flow 10 step 6): {@link #sweep}, at boot and daily on
 * companion-ops, releases each through the roster's release, so a Resident goes home to its routine with its bond
 * remembered (the contract ends, the points stay) and any friend may ask it along again (resident.released), and a hired
 * hand goes back to the population (HiredHands.onReleased). A deleted owner's rows go through RosterService.ownerDeleted.
 */
public final class ResidentAllocation {
    private static final Logger log = LoggerFactory.getLogger(ResidentAllocation.class);
    /** Event log kind of a Resident that went home because its owner left (deleted, or away too long). */
    public static final String EVENT_RELEASED = "resident.released";
    /** The release reason, and the event detail, of an owner away too long. */
    static final String AWAY_REASON = "owner inactive";
    /** A Resident's words to a friend who keeps RESIDENT_CAP_PER_OWNER of them already. */
    public static final String CAP_LINE = "you've already got enough of us townsfolk with you.";
    /** The sweep runs daily (LivingWorld), and once at boot. */
    public static final long SWEEP_INTERVAL_MS = TimeUnit.DAYS.toMillis(1);

    private final LivingWorld world;

    public ResidentAllocation(LivingWorld world) {
        this.world = world;
    }

    /** True when the owner keeps RESIDENT_CAP_PER_OWNER Residents already (0 = none allowed); hired hands do not count. */
    public boolean atCap(Character owner) {
        long residents = world.roster().roster(owner).stream().filter(entry -> !LivingContent.isHire(entry.personaId())).count();
        return residents >= world.config().RESIDENT_CAP_PER_OWNER;
    }

    /**
     * On companion-ops: every row of an owner who is offline and was last seen (their last logout, or their account's
     * last login) RESIDENT_OWNER_INACTIVE_DAYS ago or more is released through RosterService.release: a Resident goes
     * home with its bond kept, and resident.released is logged; a hired hand goes back to the population (roster.release
     * only; one whose character is gone is let go all the same). One still in a run waits for the next sweep. Nothing
     * when the setting is 0. Idempotent: a released row is gone. Returns how many were released.
     */
    public int sweep() {
        int days = world.config().RESIDENT_OWNER_INACTIVE_DAYS;
        if (days <= 0) {
            return 0;
        }
        long awaySince = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days);
        List<RosterEntry> rows = new ArrayList<>(world.store().allActive());
        rows.addAll(world.store().allBenched());
        rows.addAll(world.store().allInTransit());
        Map<Integer, List<RosterEntry>> rowsByOwner = new LinkedHashMap<>();
        for (RosterEntry entry : rows) {
            rowsByOwner.computeIfAbsent(entry.characterId(), owner -> new ArrayList<>()).add(entry);
        }
        Commitments commitments = world.commitments();
        int released = 0;
        for (Map.Entry<Integer, List<RosterEntry>> owned : rowsByOwner.entrySet()) {
            int ownerId = owned.getKey();
            if (world.roster().onlineCharacter(ownerId) != null || world.store().lastSeenAtMs(ownerId) >= awaySince) {
                continue;
            }
            for (RosterEntry entry : owned.getValue()) {
                boolean hire = LivingContent.isHire(entry.personaId());
                Persona persona = world.content().persona(entry.personaId());
                int characterId = hire ? LivingContent.hireCharacterId(entry.personaId())
                        : world.residents().characterIdOf(entry.personaId());
                if (persona == null && !hire || commitments != null && characterId != 0 && commitments.isCommitted(characterId)) {
                    continue;
                }
                if (persona == null) {
                    world.roster().releaseRow(entry, AWAY_REASON); // a hire whose character row is gone
                } else {
                    world.roster().release(ownerId, persona, AWAY_REASON);
                }
                if (!hire) {
                    world.store().event(ownerId, persona.id(), EVENT_RELEASED, AWAY_REASON);
                }
                released++;
            }
        }
        if (released > 0) {
            log.info("Resident allocation: {} rows of owners away {} days or more were released", released, days);
        }
        return released;
    }
}
