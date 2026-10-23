package server.living.roster;

import server.agents.capabilities.expedition.cwkpq.AgentCwkpqPaceProfile;
import server.agents.capabilities.partyquest.pace.AgentPqMemberPower;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate.Kind;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate.Weakness;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Jack's words about a CWKPQ list's clear time (CWKPQ plan C8), over the leader's list in his window: how long he
 * thinks the keep takes, then up to {@link #CAUTIONS} cautions. Whole minutes are the only digits, as at every desk.
 * No run of the keep is recorded yet, so he always hedges; the keep's clock restarts at every stage, so he speaks of
 * the stage clocks the cautions are about, not of one timer.
 */
final class JackLines {
    /** At most this many cautions after the time. */
    static final int CAUTIONS = 2;
    /** A range wider than this (p10 to p90) is said. */
    static final double RANGE_FROM_S = 15.0d * 60.0d;

    /** The mobs as he names them (String.wz), plural where a clear holds many. */
    static final Map<Integer, String> MOB_NAMES = Map.ofEntries(
            Map.entry(AgentCwkpqPaceProfile.CRIMSON_GUARDIAN_MOB_ID, "Crimson Guardians"),
            Map.entry(AgentCwkpqPaceProfile.MASTER_GUARDIAN_MOB_ID, "Master Guardians"),
            Map.entry(AgentCwkpqPaceProfile.FIREBRAND_MOB_ID, "Firebrands"),
            Map.entry(AgentCwkpqPaceProfile.ELDERWRAITH_MOB_ID, "Elderwraiths"),
            Map.entry(AgentCwkpqPaceProfile.STORMBREAKER_MOB_ID, "Stormbreakers"),
            Map.entry(AgentCwkpqPaceProfile.MARGANA_MOB_ID, "Margana"),
            Map.entry(AgentCwkpqPaceProfile.RED_NIRG_MOB_ID, "Red Nirg"),
            Map.entry(AgentCwkpqPaceProfile.RELLIK_MOB_ID, "Rellik"),
            Map.entry(AgentCwkpqPaceProfile.HSALF_MOB_ID, "Hsalf"),
            Map.entry(AgentCwkpqPaceProfile.SCARLET_PHOENIX_MOB_ID, "the Scarlet Phoenix"),
            Map.entry(AgentCwkpqPaceProfile.AZURE_OCELOT_MOB_ID, "the Azure Ocelot"));

    /** Where each clear is, in his words. */
    static final Map<String, String> PLACES = Map.of(
            AgentCwkpqPaceProfile.WARRIOR_ROOM, "the warriors' room",
            AgentCwkpqPaceProfile.BOWMAN_ROOM, "the bowmen's room",
            AgentCwkpqPaceProfile.MAGE_ROOM, "the magicians' room",
            AgentCwkpqPaceProfile.GUARDS, "the Council Hall",
            AgentCwkpqPaceProfile.MASTERS, "the Council Hall");

    /** Which class a room's slowness is about, in his words. */
    private static final Map<String, String> ROOM_CLASSES = Map.of(
            AgentCwkpqPaceProfile.WARRIOR_ROOM, "warriors",
            AgentCwkpqPaceProfile.BOWMAN_ROOM, "bowmen",
            AgentCwkpqPaceProfile.MAGE_ROOM, "magicians");

    static final String MASTERS_SLOW = "#rThe Grandmasters will take this list a long while.#k The Council Hall closes"
            + " an hour after you reach it, guards and all.";
    static final String FRAGILE = "#b%s#k might not last long against the Crimson Guardians.";
    static final String MISSES = "#b%s#k will hardly land a hit on %s.";
    /**
     * 2026-10-22 (CWKPQ C9, user decision): companions on the list whose bags still lack the room the keep's weapons
     * need (AgentSupplyReadiness.etcRoomLacking), after the loot they could sell: a full Etc tab takes no master's
     * weapon and no chest's gun.
     */
    static final String FULL_BAGS = "#b%s#k %s no room in their Etc bag for the keep's weapons. Make some first.";
    /**
     * 2026-10-22 (CWKPQ C9): three of the thieves' room's five eyes sit where only Flash Jump reaches (the offline graph:
     * no walk or jump gets there, even hasted to 160/120), so a list whose thieves hold none cannot break them.
     */
    static final String NO_FLASH_JUMP = "None of your thieves can #bFlash Jump#k. Three of the eyes in the thieves'"
            + " room are out of reach without it.";

    private JackLines() {
    }

    /** His caution about the companions whose Etc bags are too full ({@link #FULL_BAGS}); null for none. */
    static String fullBags(List<String> names) {
        if (names.isEmpty()) {
            return null;
        }
        String who = names.size() == 1 ? names.getFirst()
                : String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.getLast();
        return FULL_BAGS.formatted(who, names.size() == 1 ? "has" : "have");
    }

    /** Everything he says over the list: the time, then the cautions; empty with no estimate. */
    static List<String> lines(AgentPqPaceEstimate estimate, List<AgentPqMemberPower> members) {
        if (estimate == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>(clearTime(estimate));
        lines.addAll(cautions(estimate, members));
        return lines;
    }

    /**
     * How long he thinks it takes, hedged (no run of the keep is recorded yet, so he has never seen a list in there);
     * for a list that cannot clear, the mob nobody can hurt and where it is.
     */
    static List<String> clearTime(AgentPqPaceEstimate estimate) {
        if (estimate == null) {
            return List.of();
        }
        if (!estimate.clearable()) {
            for (Weakness weakness : estimate.weaknesses()) {
                if (weakness.kind() == Kind.UNHITTABLE) {
                    return List.of("#rI don't see this list getting through the keep:#k nobody on it can hurt "
                            + mobName(weakness.mobId()) + " in " + place(weakness.segmentKey()) + ".");
                }
            }
            return List.of("#rI don't see this list getting through the keep.#k");
        }
        String time = DeskLines.minutesPhrase(DeskLines.minutes(estimate.p50S()));
        return List.of("I haven't seen this list in the keep, but I'd guess about #b" + time + "#k from the hallway"
                + " to the Grandmasters, if you keep things moving." + range(estimate));
    }

    /**
     * Up to {@link #CAUTIONS} cautions, in this order: a class room the estimate calls slow (the Test of Unity's clock
     * is 20 minutes), the Grandmasters slow, then each companion that might not last against the Crimson Guardians
     * (FRAGILE) and each that will hardly hit a mob it fights (MISSES).
     */
    static List<String> cautions(AgentPqPaceEstimate estimate, List<AgentPqMemberPower> members) {
        if (estimate == null) {
            return List.of();
        }
        Set<String> lines = new LinkedHashSet<>();
        for (Map.Entry<String, String> room : ROOM_CLASSES.entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            if (DeskLines.has(estimate, 0, Kind.SLOW, room.getKey(), 0)) {
                lines.add("#rYour " + room.getValue() + " look slow in their room.#k The Test of Unity gives you 20"
                        + " minutes to bring every weapon back.");
            }
        }
        if (DeskLines.has(estimate, 0, Kind.SLOW, AgentCwkpqPaceProfile.MASTERS, 0)) {
            lines.add(MASTERS_SLOW);
        }
        for (AgentPqMemberPower member : members) {
            if (!member.human() && DeskLines.has(estimate, member.characterId(), Kind.FRAGILE, null, 0)) {
                lines.add(String.format(FRAGILE, member.name()));
            }
        }
        for (AgentPqMemberPower member : members) {
            if (member.human()) {
                continue;
            }
            for (Weakness weakness : estimate.weaknesses()) {
                if (weakness.characterId() == member.characterId() && weakness.kind() == Kind.MISSES) {
                    lines.add(String.format(MISSES, member.name(), mobName(weakness.mobId())));
                    break;
                }
            }
        }
        return lines.stream().limit(CAUTIONS).toList();
    }

    /** " Anywhere from 40 to 60, depending on the Grandmasters." when the range is wide; else nothing. */
    private static String range(AgentPqPaceEstimate estimate) {
        long low = DeskLines.minutes(estimate.p10S());
        long high = DeskLines.minutes(estimate.p90S());
        return estimate.p90S() - estimate.p10S() >= RANGE_FROM_S && low != high
                ? " Anywhere from " + low + " to " + high + ", depending on the Grandmasters." : "";
    }

    static String mobName(int mobId) {
        return MOB_NAMES.getOrDefault(mobId, "what's in there");
    }

    static String place(String segmentKey) {
        return PLACES.getOrDefault(segmentKey, "the keep");
    }
}
