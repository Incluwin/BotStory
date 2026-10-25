package server.agents.capabilities.expedition.gpq;

import client.Character;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.expedition.gpq.AgentGpqRun.Stage;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.Reactor;

import java.awt.Point;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static server.agents.capabilities.expedition.gpq.AgentGpqMoves.bySlot;
import static server.agents.capabilities.expedition.gpq.AgentGpqMoves.dropAt;
import static server.agents.capabilities.expedition.gpq.AgentGpqMoves.hit;
import static server.agents.capabilities.expedition.gpq.AgentGpqMoves.noteRole;
import static server.agents.capabilities.expedition.gpq.AgentGpqMoves.pickUpNearest;
import static server.agents.capabilities.expedition.gpq.AgentGpqMoves.reactorNamed;
import static server.agents.capabilities.expedition.gpq.AgentGpqMoves.standAt;
import static server.agents.capabilities.expedition.gpq.AgentGpqMoves.takePortal;
import static server.agents.capabilities.expedition.gpq.AgentGpqMoves.waitAt;

/**
 * Stage 5 and after (GPQ plan B5 G3):
 * <ul>
 * <li>The King's Corridor (990000800): its gate (kinggate) costs one Protector Rock, and nobody holds a spare, so one
 * member gives up the rock it wears and the air kills it within seconds. That is the Lv&lt;=30 member if it is here
 * (its job ended with the crown), else the lowest-level companion here; never the human leader. It stays down until
 * the gate's opening lets the dead revive inside (canRevive, the boss track's HOLD_UNTIL_REVIVABLE), then comes back
 * from the Entrance with a new rock. Everyone else goes on through boss00 once the gate opens.</li>
 * <li>Ergoth's Throne (990000900): once the leader is in the room one member breaks the Rubian reactor (five hits) and
 * the boss track's priority fight takes over (boss-rules.json gpq: Ergoth first, his summons as adds, the statues left
 * alone, his immunities respected); when Ergoth falls, a companion picks up the Rubian and lays it at the leader's
 * feet for the guard (9040010).</li>
 * <li>After the clear: thirty seconds of the Treasure Warehouse's boxes, then Nuris (9040001) on the Returning Path
 * sees each member out to the camp.</li>
 * </ul>
 */
final class AgentGpqThronePlays {
    static final String KINGGATE = "kinggate";
    static final Point KINGGATE_STAND = new Point(239, 103);
    static final Point CORRIDOR_WAIT = new Point(-300, 155);
    static final String RUBIAN_REACTOR = "boss";
    static final int ERGOTH = 9_300_028;
    static final int RUBIAN = 4_001_024;
    static final Point THRONE_WAIT = new Point(-300, 155);
    static final int SEEK_PX = 1500;
    static final int BONUS_BOX = 9_202_012;
    static final int NURIS = 9_040_001;
    static final Point NURIS_STAND = new Point(165, 525);

    private AgentGpqThronePlays() {
    }

    // ---- the King's Corridor ----

    static void corridor(AgentGpqPorts ports, List<Character> members, EventInstanceManager event, AgentGpqRun run,
                         long nowMs) {
        List<Character> bySlot = bySlot(members, run);
        Reactor gate = bySlot.isEmpty() ? null : reactorNamed(ports, bySlot.getFirst(), KINGGATE);
        boolean open = gate != null && gate.getState() > 0;
        Character giver = open ? null : giver(run, bySlot);
        for (Character member : bySlot) {
            if (member.getHp() <= 0) {
                continue;
            }
            if (open) {
                run.releaseAll(member.getId(), "sacrifice");
                noteRole(run, member, "corridor", "go on to the throne");
                takePortal(ports, run, member, "boss00", nowMs);
            } else if (member == giver) {
                give(ports, run, member, nowMs);
            } else {
                noteRole(run, member, "corridor", "wait while the gate takes a rock");
                waitAt(ports, member, CORRIDOR_WAIT);
            }
        }
    }

    /**
     * The member who gives the gate its rock: the one already chosen while it is here and alive, else the Lv&lt;=30
     * member here, else the lowest-level member here (ties to the later slot).
     */
    static Character giver(AgentGpqRun run, List<Character> here) {
        int chosen = run.holder("sacrifice");
        for (Character member : here) {
            if (member.getId() == chosen && member.getHp() > 0) {
                return member;
            }
        }
        if (chosen != 0) {
            run.release("sacrifice");
        }
        Character pick = here.stream().filter(member -> member.getHp() > 0 && AgentGpqRoles.junior(member))
                .findFirst().orElse(null);
        if (pick == null) {
            pick = here.stream().filter(member -> member.getHp() > 0)
                    .min(Comparator.comparingInt(Character::getLevel)
                            .thenComparing(Comparator.comparingInt((Character member) -> run.ordinal(member.getId()))
                                    .reversed()))
                    .orElse(null);
        }
        if (pick != null) {
            run.claim("sacrifice", pick.getId());
        }
        return pick;
    }

    /** Walks onto the gate's platform and drops the rock it wears there, once. */
    private static void give(AgentGpqPorts ports, AgentGpqRun run, Character member, long nowMs) {
        noteRole(run, member, "corridor", "give the gate its rock");
        if (!ports.wearsRock(member)) {
            return; // given: the air takes it, and it waits for the gate to let it revive
        }
        AgentRuntimeEntry entry = ports.entry(member.getId());
        if (entry == null || !standAt(ports, entry, member, KINGGATE_STAND, AgentGpqMoves.DROP_REACH_PX)
                || !run.ready(member.getId(), nowMs)) {
            return;
        }
        if (run.once("sacrifice-line")) {
            ports.sayInParty(member, AgentGpqLines.SACRIFICE);
        }
        ports.gateway().stop(entry);
        ports.dropWornRock(member);
        run.busy(member.getId(), nowMs + AgentGpqMoves.ITEM_PAUSE_MS);
    }

    // ---- Ergoth's Throne ----

    static void throne(AgentGpqPorts ports, List<Character> members, EventInstanceManager event, AgentGpqRun run,
                       long nowMs) {
        List<Character> bySlot = bySlot(members, run);
        if (bySlot.isEmpty()) {
            return;
        }
        Character first = bySlot.getFirst();
        Reactor rubianReactor = reactorNamed(ports, first, RUBIAN_REACTOR);
        boolean ergoth = ports.monstersOn(event, Stage.THRONE.mapId(), Set.of(ERGOTH)) > 0;
        Character leader = event == null ? null : event.getLeader();
        boolean leaderHere = leader != null && leader.getMapId() == Stage.THRONE.mapId();
        if (!ergoth && rubianReactor != null && rubianReactor.isAlive() && rubianReactor.isActive()) {
            for (Character member : bySlot) {
                if (member == first && leaderHere) {
                    noteRole(run, member, "throne", "break the rubian's seal");
                    hit(ports, run, member, rubianReactor, nowMs);
                } else {
                    noteRole(run, member, "throne", "wait for the leader");
                    waitAt(ports, member, THRONE_WAIT);
                }
            }
            return;
        }
        if (ergoth) {
            if (run.once("ergoth")) {
                ports.sayInParty(first, AgentGpqLines.ERGOTH);
            }
            run.throne().tick(bySlot, event, nowMs);
            return;
        }
        handRubian(ports, bySlot, leader, leaderHere, run, nowMs);
    }

    /** Ergoth is down: the first companion picks the Rubian up and lays it at the leader's feet. */
    private static void handRubian(AgentGpqPorts ports, List<Character> bySlot, Character leader, boolean leaderHere,
                                   AgentGpqRun run, long nowMs) {
        Character bearer = bySlot.stream().filter(member -> ports.carried(member, RUBIAN) > 0).findFirst()
                .orElse(bySlot.getFirst());
        for (Character member : bySlot) {
            if (member != bearer) {
                noteRole(run, member, "throne", "wait for the guard to take the rubian");
                waitAt(ports, member, THRONE_WAIT);
                continue;
            }
            if (ports.carried(member, RUBIAN) <= 0) {
                if (pickUpNearest(ports, run, member, RUBIAN, SEEK_PX, nowMs)) {
                    noteRole(run, member, "throne", "pick up the rubian");
                } else {
                    noteRole(run, member, "throne", "wait for the guard to take the rubian");
                    waitAt(ports, member, THRONE_WAIT);
                }
                continue;
            }
            if (leaderHere && leader.getPosition() != null) {
                noteRole(run, member, "throne", "lay the rubian at the leader's feet");
                if (dropAt(ports, run, member, RUBIAN, 1, new Point(leader.getPosition()), nowMs)
                        && run.once("rubian")) {
                    ports.sayInParty(member, AgentGpqLines.RUBIAN);
                }
            }
        }
    }

    // ---- after the clear ----

    /** The Treasure Warehouse's boxes until its timer warps the team out, then Nuris on the Returning Path. */
    static void afterClear(AgentGpqPorts ports, List<Character> members, EventInstanceManager event, AgentGpqRun run,
                           long nowMs) {
        for (Character member : bySlot(members, run)) {
            if (member.getMapId() == AgentGpqRun.RETURNING_MAP_ID) {
                AgentRuntimeEntry entry = ports.entry(member.getId());
                if (entry != null && standAt(ports, entry, member, NURIS_STAND, 80) && run.ready(member.getId(), nowMs)) {
                    ports.gateway().stop(entry);
                    ports.partyQuest().runNpc(member, NURIS);
                    run.busy(member.getId(), nowMs + AgentGpqMoves.PORTAL_PAUSE_MS);
                }
                continue;
            }
            List<Reactor> boxes = AgentGpqMoves.live(ports, member, BONUS_BOX);
            Reactor box = boxes.stream().filter(candidate -> run.holder("box:" + candidate.getObjectId()) == 0
                    || run.holder("box:" + candidate.getObjectId()) == member.getId())
                    .min(Comparator.comparingDouble(candidate -> member.getPosition() == null ? 0d
                            : candidate.getPosition().distanceSq(member.getPosition())))
                    .orElse(null);
            if (box == null) {
                continue;
            }
            run.claim("box:" + box.getObjectId(), member.getId());
            noteRole(run, member, "warehouse", "break the treasure boxes");
            hit(ports, run, member, box, nowMs);
        }
    }
}
