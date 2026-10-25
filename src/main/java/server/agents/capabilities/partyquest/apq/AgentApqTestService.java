package server.agents.capabilities.partyquest.apq;

import client.Character;
import net.server.channel.handlers.RingActionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.auth.AgentAuthorityService;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.integration.AgentCharacterGatewayRuntime;
import server.agents.integration.AgentMapGatewayRuntime;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.integration.AgentPartySnapshot;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GM-only APQ harness (APQ plan A3, A5b): "party prod" adopts the operator's own party of six beside Amos as a companion
 * run through the public admit, as the desk would; "married" marries the party's unmarried members to each other for
 * the challenge's married rule, as the stock wedding does it (a relationship, partners, a Moonstone ring each), and
 * "divorce" undoes it; "checkpoint" moves an adopted run forward to a stage. Nothing is spawned or granted.
 */
public final class AgentApqTestService {
    private static final Logger log = LoggerFactory.getLogger(AgentApqTestService.class);
    /** The Moonstone Wedding Ring, the stock wedding's first ring (9201002.js getRingId). */
    static final int TEST_WEDDING_RING = 1_112_803;
    /** Fluttering Hearts' clock once the passes are in (9201045.js: four minutes). */
    static final long GATES_TIMER_MS = 4L * 60_000L;
    private static final ConcurrentHashMap<Integer, Run> RUNS = new ConcurrentHashMap<>();

    private AgentApqTestService() {
    }

    public static List<String> execute(Character operator, String[] params, long nowMs) {
        if (operator == null || !AgentAuthorityService.mayOperate(operator)) {
            return List.of("You are not configured as an Agent operator.");
        }
        if (params == null || params.length == 0) return help();
        try {
            return switch (params[0].toLowerCase(Locale.ROOT)) {
                case "party", "adopt" -> adopt(operator, params, nowMs);
                case "married", "marry" -> marry(operator);
                case "divorce" -> divorce(operator);
                case "checkpoint" -> checkpoint(operator, params, nowMs);
                case "status" -> status(operator);
                case "stop" -> stop(operator, "stopped by operator", nowMs);
                default -> help();
            };
        } catch (Exception failure) {
            log.warn("APQ test command failed for operator {}", operator.getId(), failure);
            return List.of("APQ test command failed: " + failure.getMessage());
        }
    }

    /** "party prod [seed]": the operator's own fully online party of six beside Amos becomes a companion run. */
    private static synchronized List<String> adopt(Character operator, String[] params, long nowMs) {
        if (params.length < 2 || !"prod".equalsIgnoreCase(params[1])) {
            return List.of("APQ adopts a party only as a companion run: !apqtest party prod [seed].");
        }
        if (operator.getMapId() != AgentApqDefinition.RECRUIT_MAP) {
            return List.of("Stand at the Entrance of the Amorian Challenge (670010100) first.");
        }
        Run existing = RUNS.get(operator.getId());
        if (existing != null && finished(existing)) {
            RUNS.remove(operator.getId(), existing);
        }
        if (RUNS.containsKey(operator.getId())) {
            return List.of("Stop the current APQ test before adopting another party.");
        }
        AgentPartySnapshot snapshot = AgentPartyGatewayRuntime.party().snapshot(operator);
        if (snapshot == null) {
            return List.of("Create the party first: you and five companions.");
        }
        List<Character> online = AgentPartyGatewayRuntime.party().onlineMembers(operator).stream()
                .filter(Objects::nonNull).distinct().toList();
        if (online.size() != snapshot.members().size()) {
            return List.of("Every party member must be online on this channel before the companion run starts.");
        }
        boolean leads = snapshot.members().stream().anyMatch(member -> member != null
                && member.id() == operator.getId() && member.leader());
        if (!leads) {
            return List.of("Lead the party yourself for a companion run: the public admit keys the run by its leader.");
        }
        long seed = params.length > 2 ? Long.parseLong(params[2]) : nowMs;
        AgentApqAdmissionService.AdmissionResult result =
                AgentApqAdmissionService.admit(operator, operator, online, seed, nowMs);
        if (!result.success()) {
            return List.of("Could not adopt the party: " + result.message());
        }
        result.session().setMemberCoordinationChatEnabled(true);
        RUNS.put(operator.getId(), new Run(operator, result.engagement(), result.session()));
        return List.of("Adopted as a companion run. Talk to Amos to go in.");
    }

    /** An adopted run with nothing left for a stop to do: its session ended and its engagement is gone or closed. */
    private static boolean finished(Run run) {
        if (!run.session.terminal()) return false;
        AgentPartyQuestEngagement engagement = run.engagement;
        if (AgentPartyQuestEngagementRegistry.byId(engagement.engagementId()) != engagement) return true;
        AgentPartyQuestEngagement.State state = engagement.state();
        return state == AgentPartyQuestEngagement.State.FAILED || state == AgentPartyQuestEngagement.State.CLOSED;
    }

    /**
     * "married": the party's unmarried members, married to each other two by two, a man with a woman while both are
     * left (the stock wedding's way: World.createRelationship, partner ids, RingActionHandler.giveMarriageRings). For
     * test parties only; the lore couples are the wedding branch's.
     */
    private static List<String> marry(Character operator) {
        List<Character> members = partyHere(operator);
        if (members.isEmpty()) {
            return List.of("Stand with your party on one map first.");
        }
        List<Character[]> couples = couples(members);
        if (couples.isEmpty()) {
            return List.of("Everyone here is married already (or one is left without a partner).");
        }
        List<String> lines = new ArrayList<>();
        for (Character[] couple : couples) {
            Character groom = couple[0];
            Character bride = couple[1];
            groom.getClient().getWorldServer().createRelationship(groom.getId(), bride.getId());
            groom.setPartnerId(bride.getId());
            bride.setPartnerId(groom.getId());
            RingActionHandler.giveMarriageRings(groom, bride, TEST_WEDDING_RING);
            groom.setMarriageItemId(TEST_WEDDING_RING);
            bride.setMarriageItemId(TEST_WEDDING_RING);
            log.info("APQ test: married {} and {} for operator {}", groom.getName(), bride.getName(), operator.getId());
            lines.add(groom.getName() + " and " + bride.getName() + " are married.");
        }
        return lines;
    }

    /** The unmarried in {@code members}, two by two: a man with a woman while both are left, then whoever is left. */
    static List<Character[]> couples(List<Character> members) {
        List<Character> men = new ArrayList<>();
        List<Character> women = new ArrayList<>();
        for (Character member : members) {
            if (!member.isMarried() && member.getPartnerId() <= 0) {
                (member.getGender() == 1 ? women : men).add(member);
            }
        }
        List<Character[]> couples = new ArrayList<>();
        while (!men.isEmpty() && !women.isEmpty()) {
            couples.add(new Character[] {men.remove(0), women.remove(0)});
        }
        List<Character> rest = men.isEmpty() ? women : men;
        while (rest.size() >= 2) {
            couples.add(new Character[] {rest.remove(0), rest.remove(0)});
        }
        return couples;
    }

    /** "divorce": undoes a test marriage for each party member here whose ring is in its bag (unequipped). */
    private static List<String> divorce(Character operator) {
        List<String> lines = new ArrayList<>();
        for (Character member : partyHere(operator)) {
            if (member.getPartnerId() > 0 && member.getMarriageItemId() == TEST_WEDDING_RING
                    && member.getInventory(client.inventory.InventoryType.EQUIP).findById(TEST_WEDDING_RING) != null) {
                RingActionHandler.breakMarriageRing(member, TEST_WEDDING_RING);
                lines.add(member.getName() + " is no longer married.");
            }
        }
        return lines.isEmpty() ? List.of("Nobody here wears a test marriage in their bag (unequip the ring first).")
                : lines;
    }

    /** The operator's party members on the operator's map, the operator first. */
    private static List<Character> partyHere(Character operator) {
        List<Character> here = new ArrayList<>();
        for (Character member : AgentPartyGatewayRuntime.party().onlineMembers(operator)) {
            if (member != null && member.getMapId() == operator.getMapId() && !here.contains(member)) {
                here.add(member);
            }
        }
        return here;
    }

    /**
     * "checkpoint <ropes|platforms|passes|gates|boss>": an adopted run inside APQ goes forward to that stage. The stages
     * before it are marked clear as their NPCs would ("<n>stageclear"), "gates" restarts the clock at Fluttering
     * Hearts' four minutes as handing in the passes does, and every member is moved inside the instance to the
     * target map's first portal (never a !warp), so the companions read the stage from the leader's map.
     */
    private static List<String> checkpoint(Character operator, String[] params, long nowMs) {
        Checkpoint target = params.length < 2 ? null : Checkpoint.of(params[1]);
        if (target == null) return List.of("Syntax: !apqtest checkpoint <ropes|platforms|passes|gates|boss>");
        Run run = RUNS.get(operator.getId());
        if (run == null) return List.of("Checkpoints apply only to a party prod companion run.");
        AgentApqSession session = run.session;
        EventInstanceManager event;
        List<Character> members = new ArrayList<>();
        synchronized (session) {
            event = session.eventInstance();
            if (event == null || session.terminal() || session.terminationStarted()
                    || operator.getEventInstance() != event) {
                return List.of("Enter APQ through Amos before a checkpoint.");
            }
            for (AgentApqMemberState member : session.members()) {
                Character character = online(member.characterId());
                if (character == null || character.getEventInstance() != event) {
                    return List.of("Every member must be inside the APQ instance first.");
                }
                members.add(character);
            }
        }
        prepare(event, target);
        MapleMap map = event.getMapInstance(target.mapId);
        var portal = map.getPortal(0);
        Point spawn = portal == null ? new Point(0, 0) : portal.getPosition();
        for (Character member : members) {
            AgentMapGatewayRuntime.map().changeMapNear(member, map, spawn);
        }
        session.markProgress(nowMs);
        log.info("APQ checkpoint: session={} stage={} by={}", session.sessionId(),
                target.name().toLowerCase(Locale.ROOT), operator.getId());
        return List.of("APQ checkpoint: everyone is at the " + target.name().toLowerCase(Locale.ROOT)
                + ". Earlier stages are skipped for this run only.");
    }

    /** The event state the stock stages before {@code target} would have left. */
    static void prepare(EventInstanceManager event, Checkpoint target) {
        for (int stage = 1; stage < target.stage; stage++) {
            event.setProperty(stage + "stageclear", "true");
        }
        long timeLeft = event.getTimeLeft();
        if (target == Checkpoint.GATES && timeLeft >= GATES_TIMER_MS + 60_000L) {
            // as 9201045.js hands in the passes: the rest of the clock saved for after the gates, four minutes now
            event.setProperty("timeLeft", Long.toString(timeLeft));
            event.restartEventTimer(GATES_TIMER_MS);
        }
    }

    /** The stages a checkpoint can skip to: the target map and its stage number (9201044.js: map - 670010200). */
    enum Checkpoint {
        ROPES(AgentApqDefinition.ROPE_MAPS.get(0), 2),
        PLATFORMS(AgentApqDefinition.PLATFORM_MAP, 3),
        PASSES(AgentApqDefinition.PASS_MAP, 4),
        GATES(AgentApqDefinition.GATE_RUN_MAP, 5),
        BOSS(AgentApqDefinition.BOSS_MAP, 6);

        final int mapId;
        final int stage;

        Checkpoint(int mapId, int stage) {
            this.mapId = mapId;
            this.stage = stage;
        }

        static Checkpoint of(String name) {
            for (Checkpoint checkpoint : values()) {
                if (checkpoint.name().equalsIgnoreCase(name)) return checkpoint;
            }
            return null;
        }
    }

    private static List<String> status(Character operator) {
        Run run = RUNS.get(operator.getId());
        if (run == null) return List.of("No APQ test is active.");
        Character leader = online(run.session.eventLeaderId());
        return List.of("APQ phase: " + run.session.phase(),
                "leader: " + run.session.eventLeaderId() + (leader == null ? "" : " on " + leader.getMapId()),
                "failure: " + (run.session.failure() == null ? "none" : run.session.failure()));
    }

    /**
     * Stops an adopted run as the companion run it is: a live session is released (the party is kept); without one,
     * an engagement still registered is recovered, so its companions go back to their owner.
     */
    private static List<String> stop(Character operator, String reason, long nowMs) {
        Run run = RUNS.remove(operator.getId());
        if (run == null) return List.of("No APQ test is active.");
        if (AgentApqSessionRegistry.forOperator(operator.getId()) == run.session) {
            AgentApqTerminationService.release(run.session, reason, nowMs);
        } else if (AgentPartyQuestEngagementRegistry.byId(run.engagement.engagementId()) != null) {
            run.engagement.beginRecovery(reason, nowMs);
            AgentPartyQuestLifecycleRuntime.recover(run.engagement, nowMs);
        }
        return List.of("APQ test stopped safely.");
    }

    private static Character online(int id) {
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(id);
        Character agent = entry == null ? null : AgentRuntimeIdentityRuntime.bot(entry);
        return agent != null ? agent : AgentCharacterGatewayRuntime.characters().findOnlineCharacterById(id);
    }

    private static List<String> help() {
        return List.of("!apqtest party prod [seed] (beside Amos: adopt your party of six as a companion run)",
                "!apqtest married (marry your party's unmarried members to each other, test parties only)",
                "!apqtest divorce (undo those test marriages; the rings must be in the bag)",
                "!apqtest checkpoint <ropes|platforms|passes|gates|boss> (party prod runs inside APQ)",
                "!apqtest status | stop");
    }

    private record Run(Character operator, AgentPartyQuestEngagement engagement, AgentApqSession session) {
    }
}
