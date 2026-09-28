package server.living.roster;

import org.junit.jupiter.api.Test;
import server.living.roster.FakeParties.Companion;
import server.living.roster.FakeParties.Group;
import server.living.store.LivingStore.RosterState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 10,000 random sequences of party operations by three friends with four companions each and three citizens
 * (calls, benches, invites and accepts including merges and make-room, leaves, expels, leader changes, log-offs and
 * log-ons, citizen guests, the Crew window's make-room, event instances starting and ending). After every operation
 * has settled, and whenever nobody is inside an event instance:
 * <ul>
 *   <li>no party has more than 6 members;</li>
 *   <li>every companion has one owner and is in at most one party;</li>
 *   <li>no character is loaded twice;</li>
 *   <li>the roster matches the parties: a companion in a party is loaded, ACTIVE, and in the same party as its online
 *       owner; a loaded companion is in a party; an ACTIVE companion of an offline owner is not loaded, and one of an
 *       online owner is loaded unless a login restore found no seat for it;</li>
 *   <li>a guest is in its host's party; the circuit breaker never opens and no drift is reported.</li>
 * </ul>
 */
class CompanionPartySyncPropertyTest {
    private static final int SEQUENCES = 10_000;
    private static final int OPS_PER_SEQUENCE = 30;
    private static final int[] HUMANS = {1, 2, 3};
    private static final int[] CITIZENS = {950, 951, 952};

    @Test
    void randomOperationSequencesKeepTheRosterAndThePartiesInStep() {
        Random seeds = new Random(20260928L);
        Map<String, Integer> reached = new HashMap<>();
        for (int sequence = 0; sequence < SEQUENCES; sequence++) {
            long seed = seeds.nextLong();
            for (String change : run(seed)) {
                reached.merge(change.split(" ")[0], 1, Integer::sum);
            }
        }
        // the sequences reach every kind of correction: merges and make-room, fair share, leader caps, re-forms,
        // guests stepping out and waiting companions coming back
        for (String kind : List.of("leave", "join", "seat", "OVERFLOW", "MAKE_ROOM", "EXPELLED", "BENCHED", "cap",
                "guest", "recall")) {
            if (reached.getOrDefault(kind, 0) == 0) {
                fail("no sequence reached " + kind + ": " + reached);
            }
        }
    }

    /** The sync's changes over the whole sequence. */
    private static List<String> run(long seed) {
        Random random = new Random(seed);
        FakeParties world = new FakeParties();
        world.keepPublished = false;
        for (int human : HUMANS) {
            world.human(human);
            for (int slot = 0; slot < 4; slot++) {
                world.companion("c" + human + slot, human * 100 + slot, human, slot);
            }
        }
        for (int citizen : CITIZENS) {
            world.citizen(citizen);
        }
        List<String> trace = new ArrayList<>();
        for (int op = 0; op < OPS_PER_SEQUENCE; op++) {
            trace.add(step(world, random));
            world.settle();
            world.now += CompanionPartySync.BREAKER_WINDOW_MS + 1_000L;
            if (world.committed.isEmpty()) {
                check(world, seed, trace);
            }
        }
        world.committed.clear();
        world.sync.resumeDeferred();
        world.settle();
        trace.add("instances end");
        check(world, seed, trace);
        return world.changes;
    }

    /** One random operation by a random human; its description for the failure trace. */
    private static String step(FakeParties world, Random random) {
        int human = HUMANS[random.nextInt(HUMANS.length)];
        int party = world.partyOf(human);
        Group group = world.parties.get(party);
        boolean online = world.onlineHuman(human);
        switch (random.nextInt(12)) {
            case 0, 1 -> {
                if (!online) {
                    return "offline " + human;
                }
                Companion companion = pick(random, world.crewOf(human));
                return "call " + companion.key + " " + world.call(human, companion.key);
            }
            case 2 -> {
                if (!online) {
                    return "offline " + human;
                }
                Companion companion = pick(random, world.crewOf(human));
                world.bench(human, companion.key);
                return "bench " + companion.key;
            }
            case 3, 4 -> {
                int friend = HUMANS[random.nextInt(HUMANS.length)];
                if (!online || friend == human || !world.onlineHuman(friend)) {
                    return "no invite";
                }
                if (party == 0) {
                    party = world.create(human);
                    group = world.parties.get(party);
                }
                // a full party cannot invite (P0 probe 12) unless it filled after the invite went out
                if (group.members.size() >= FakeParties.CAP && random.nextInt(3) != 0) {
                    return "full, no invite";
                }
                world.accept(friend, party);
                return friend + " accepts " + human + "'s invite into " + party;
            }
            case 5 -> {
                if (party == 0 || !online) {
                    return "no leave";
                }
                world.quit(human);
                return human + " leaves " + party;
            }
            case 6 -> {
                if (group == null || group.leader != human || group.members.size() < 2) {
                    return "no expel";
                }
                int target = group.members.get(1 + random.nextInt(group.members.size() - 1));
                world.expel(human, target);
                return human + " expels " + target;
            }
            case 7 -> {
                if (group == null || group.leader != human) {
                    return "no lead";
                }
                for (int member : group.members) {
                    if (member != human && world.onlineHuman(member)) {
                        world.changeLeader(human, member);
                        return human + " makes " + member + " leader";
                    }
                }
                return "no one to lead";
            }
            case 8 -> {
                if (online) {
                    world.logOff(human);
                    return human + " logs off";
                }
                world.logOn(human);
                return human + " logs on";
            }
            case 9 -> {
                if (!online) {
                    return "offline " + human;
                }
                int citizen = CITIZENS[random.nextInt(CITIZENS.length)];
                return human + " invites " + citizen + " " + world.inviteCitizen(human, citizen);
            }
            case 10 -> {
                if (group == null) {
                    return "no party for an instance";
                }
                if (!world.committed.isEmpty()) {
                    world.committed.clear();
                    world.sync.resumeDeferred();
                    return "instances end";
                }
                world.committed.addAll(group.members);
                return "party " + party + " enters an instance";
            }
            default -> {
                if (!online || group == null || group.members.size() < FakeParties.CAP) {
                    return "no make-room";
                }
                List<String> out = world.active(human);
                if (out.isEmpty()) {
                    return "nobody to step out";
                }
                String key = out.get(out.size() - 1);
                return human + " makes room with " + key + " " + world.sync.makeRoom(human, key);
            }
        }
    }

    private static void check(FakeParties world, long seed, List<String> trace) {
        List<String> broken = new ArrayList<>();
        Map<Integer, Integer> seen = new HashMap<>();
        for (Group group : world.parties.values()) {
            if (group.members.size() > FakeParties.CAP) {
                broken.add("party " + group.id + " has " + group.members.size() + " members");
            }
            for (int member : group.members) {
                if (seen.put(member, group.id) != null) {
                    broken.add(member + " is in two parties");
                }
                if (world.partyOf(member) != group.id) {
                    broken.add(member + " is listed in " + group.id + " but thinks it is in " + world.partyOf(member));
                }
                Integer host = world.guests.get(member);
                if (host != null && !group.members.contains(host)) {
                    broken.add("guest " + member + " is in " + group.id + " without its host " + host);
                }
            }
        }
        if (world.doubleLoads > 0) {
            broken.add(world.doubleLoads + " double loads");
        }
        for (Companion companion : world.companions.values()) {
            int party = world.partyOf(companion.id);
            boolean ownerOnline = world.onlineHuman(companion.owner);
            if (party != 0 && !(companion.loaded && companion.state == RosterState.ACTIVE && ownerOnline
                    && world.partyOf(companion.owner) == party)) {
                broken.add(companion.key + " is in party " + party + " but is " + companion.state + ", loaded "
                        + companion.loaded + ", owner online " + ownerOnline + " in " + world.partyOf(companion.owner));
            }
            if (companion.loaded && party == 0) {
                broken.add(companion.key + " is loaded with no party");
            }
            if (companion.state == RosterState.ACTIVE && !companion.loaded && ownerOnline
                    && !world.restoring.contains(companion.key)) {
                broken.add(companion.key + " is ACTIVE for an online owner but not out");
            }
            if (companion.state == RosterState.ACTIVE && companion.loaded && !ownerOnline) {
                broken.add(companion.key + " is out while its owner is offline");
            }
        }
        if (!world.drifts.isEmpty()) {
            broken.add("drift: " + world.drifts);
        }
        if (!broken.isEmpty()) {
            fail("seed " + seed + ": " + broken + "\nafter: " + String.join("; ", trace));
        }
    }

    private static <T> T pick(Random random, List<T> list) {
        return list.get(random.nextInt(list.size()));
    }
}
