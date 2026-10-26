package server.living.population;

import client.Character;
import config.LivingWorldConfig;
import config.PopulationConfig;
import server.agents.capabilities.expedition.boss.AgentBossCitizenRuns;
import server.agents.capabilities.expedition.boss.AgentBossRunMember;
import server.agents.capabilities.expedition.boss.AgentBossRunRegistry;
import server.agents.capabilities.expedition.boss.AgentBossRunService;
import server.agents.capabilities.expedition.boss.AgentBossRunSession;
import server.agents.capabilities.partyquest.AgentPartyQuestRuntime;
import server.agents.capabilities.shop.AgentShopProvisioning;
import server.agents.capabilities.supplies.AgentSupplyReadiness;
import server.agents.integration.AgentExpeditionGatewayRuntime;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.integration.AgentPartySnapshot;
import server.expeditions.Expedition;
import server.expeditions.ExpeditionType;
import server.integration.AgentPresence;
import server.living.roster.BossEntrances;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Mu Young's door as a citizen party goes in by it for Balrog (docs/CITIZEN_EXPEDITIONS_PLAN.md), keyed by its lobby
 * id "balrog" in regions.yaml. Unlike a party quest's door the way in takes several ticks: {@link #admit} has the
 * citizen leader make the expedition with her window and opens the boss run, which signs the other citizens up
 * through it; a human of the party signs up by talking to her; once everyone is listed (or the wait for a human is
 * over) the population asks the run to start ({@link #start}), and its leader talks her Start. The difficulty is the
 * party's: Normal for six whose citizens are all BOSS_NORMAL_MIN_LEVEL or more and carry Normal's potions, else Easy.
 * Only a boss the server moves itself for a fight a bot signed up for is run ({@link Engine#carried}).
 */
final class CitizenBossDoor implements CitizenPqDoor.ExpeditionDoor {
    static final String KEY = "balrog";
    /** How a boss run's id reads among the population's runs (a party quest's are its own session ids). */
    static final String SESSION_PREFIX = "boss:";
    /** Mu Young's level gate, both difficulties (ExpeditionType BALROG_EASY and BALROG_NORMAL). */
    static final int MIN_LEVEL = ExpeditionType.BALROG_EASY.getMinLevel();
    /** Easy's minimum on her list (Normal's is 6). */
    static final int MIN_SEATS = ExpeditionType.BALROG_EASY.getMinSize();

    /** What the door asks of the boss runs and the world: {@link #LIVE} is the server, tests bring their own. */
    interface Engine {
        /**
         * True while the server moves boss {@code bossKey} for a fight a bot signed up for: the living world's switch
         * lists it and COMPANION_BOSS_SERVER_AUTHORITY is on (CompanionBossRuns.forcesServerAuthority). Without it
         * Balrog stands idle with no human's client to move it, and citizens do not go.
         */
        boolean carried(String bossKey);

        /** True while a Balrog expedition of either difficulty registers or fights on {@code anchor}'s channel. */
        boolean expeditionOpen(Character anchor);

        /** The leader makes the list with Mu Young and the run opens with {@code citizenIds} (the leader first). */
        AgentBossRunService.CitizenOpened open(Character leader, String bossKey, List<Integer> citizenIds, long nowMs);

        /** Asks run {@code sessionId} to start; false once it is gone or past its list. */
        boolean start(long sessionId);

        /** The run's phase; null once it is gone. */
        AgentBossRunSession.Phase phase(long sessionId);

        /** A member's phase in the run; null when it is none (or the run is gone). */
        AgentBossRunMember.Phase memberPhase(long sessionId, int characterId);

        /** True while character {@code characterId} is on the run's list (a human who signed up). */
        boolean listed(long sessionId, int characterId);

        /** True while the character is in a boss run or a party quest. */
        boolean busy(int characterId);

        /**
         * The citizen buys at the boss's approach store (Ant Tunnel Park's) what it lacks of {@code bossKey}'s potion
         * standard, as a bench ally does on arrival; then true when it lacks nothing. On the population's thread.
         */
        boolean provision(Character agent, String bossKey);

        /** True when the citizen carries {@code bossKey}'s potion standard. */
        boolean supplied(Character agent, String bossKey);

        Engine LIVE = new Engine() {
            @Override
            public boolean carried(String bossKey) {
                server.living.LivingWorld world = server.living.LivingWorld.get();
                LivingWorldConfig config = world == null ? null : world.config();
                return config != null && config.COMPANION_BOSS_SERVER_AUTHORITY && config.companionBossEnabled(bossKey);
            }

            @Override
            public boolean expeditionOpen(Character anchor) {
                return expedition(anchor, ExpeditionType.BALROG_EASY) != null
                        || expedition(anchor, ExpeditionType.BALROG_NORMAL) != null;
            }

            private Expedition expedition(Character anchor, ExpeditionType type) {
                return AgentExpeditionGatewayRuntime.expedition().current(anchor, type);
            }

            @Override
            public AgentBossRunService.CitizenOpened open(Character leader, String bossKey, List<Integer> citizenIds,
                                                         long nowMs) {
                return AgentBossRunService.openCitizenExpedition(leader, AgentBossCitizenRuns.of(bossKey), citizenIds,
                        true, nowMs);
            }

            @Override
            public boolean start(long sessionId) {
                return AgentBossRunService.startCitizens(sessionId);
            }

            @Override
            public AgentBossRunSession.Phase phase(long sessionId) {
                AgentBossRunSession session = AgentBossRunRegistry.byId(sessionId);
                return session == null ? null : session.phase();
            }

            @Override
            public AgentBossRunMember.Phase memberPhase(long sessionId, int characterId) {
                AgentBossRunSession session = AgentBossRunRegistry.byId(sessionId);
                AgentBossRunMember member = session == null ? null : session.member(characterId);
                return member == null ? null : member.phase();
            }

            @Override
            public boolean listed(long sessionId, int characterId) {
                AgentBossRunSession session = AgentBossRunRegistry.byId(sessionId);
                Expedition expedition = session == null ? null : session.expedition();
                return expedition != null && expedition.getMembers().containsKey(characterId);
            }

            @Override
            public boolean busy(int characterId) {
                return AgentBossRunRegistry.forMember(characterId) != null || AgentPartyQuestRuntime.active(characterId);
            }

            @Override
            public boolean provision(Character agent, String bossKey) {
                int store = BossEntrances.approachStoreNpcId(bossKey);
                if (store > 0) {
                    AgentShopProvisioning.provisionAt(agent, store, AgentSupplyReadiness.Activity.partyQuest(bossKey));
                }
                return supplied(agent, bossKey);
            }

            @Override
            public boolean supplied(Character agent, String bossKey) {
                return agent != null && AgentSupplyReadiness.shortfall(agent,
                        AgentSupplyReadiness.Activity.partyQuest(bossKey)).none();
            }
        };
    }

    private final PopulationConfig config;
    private final Engine engine;

    CitizenBossDoor(PopulationConfig config, Engine engine) {
        this.config = config;
        this.engine = engine;
    }

    /** The bosses citizens may run now: the population's switch and list, and the server moving each one. */
    Set<String> bosses() {
        Set<String> carried = new LinkedHashSet<>();
        for (AgentBossCitizenRuns.Boss boss : AgentBossCitizenRuns.all()) {
            if (config.citizenBosses().contains(boss.key()) && engine.carried(boss.key())) {
                carried.add(boss.key());
            }
        }
        return carried;
    }

    @Override
    public boolean enabled() {
        return !bosses().isEmpty();
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public int minSeats() {
        return MIN_SEATS;
    }

    @Override
    public int maxSeats() {
        return Math.max(MIN_SEATS, Math.min(6, config.BOSS_RUN_SEATS));
    }

    /** Mu Young's level gate; a human's potions are their own business, a citizen's were seen to on arrival. */
    @Override
    public String memberProblem(Character member, boolean leader) {
        return member == null || member.getLevel() < MIN_LEVEL ? "level below " + MIN_LEVEL : "";
    }

    @Override
    public String registrationBlocker(int leaderId, Collection<Integer> characterIds) {
        for (int id : characterIds) {
            if (engine.busy(id)) {
                return id + " is in a boss run or party quest";
            }
        }
        return "";
    }

    /**
     * Player first (the user, 2026-10-25): citizens open a list only while no Balrog expedition of either difficulty
     * registers or fights on the channel, and no human who could join one (Lv50+) stands in her room outside their own
     * party: that human may want to lead one. Once their list is open a human may still sign up on it.
     */
    @Override
    public boolean lobbyFree(Character anchor, int humanPartiesWaiting) {
        if (anchor == null || engine.expeditionOpen(anchor)) {
            return false;
        }
        MapleMap map = anchor.getMap();
        AgentPartySnapshot ours = AgentPartyGatewayRuntime.party().snapshot(anchor);
        for (Character chr : map == null ? List.<Character>of() : map.getAllPlayers()) {
            if (chr == null || AgentPresence.isAgent(chr) || chr.getLevel() < MIN_LEVEL) {
                continue;
            }
            boolean inOurParty = ours != null && ours.members().stream()
                    .anyMatch(member -> member != null && member.id() == chr.getId());
            if (!inOurParty) {
                return false;
            }
        }
        return true;
    }

    /**
     * The citizens of the party ({@code members} that are Agents; a human signs up themselves) go for Normal when there
     * are six of them, all BOSS_NORMAL_MIN_LEVEL or more and, once bought at the approach store, carrying Normal's
     * potions; else for Easy. The leader makes the list and the run opens.
     */
    @Override
    public Entered admit(Character leader, List<Character> members, long seed, long nowMs) {
        String boss = bossFor(members);
        if (boss == null) {
            return new Entered(false, "no Balrog fits this party", null);
        }
        List<Integer> citizens = new java.util.ArrayList<>();
        citizens.add(leader.getId());
        for (Character member : members) {
            if (member != null && member.getId() != leader.getId() && AgentPresence.isAgent(member)) {
                citizens.add(member.getId());
            }
        }
        AgentBossRunService.CitizenOpened opened = engine.open(leader, boss, citizens, nowMs);
        if (!opened.opened()) {
            return new Entered(false, opened.problem(), null);
        }
        return new Entered(true, boss, SESSION_PREFIX + opened.sessionId());
    }

    /** The boss for this party: Normal or Easy, by its size, its citizens' levels and Normal's potions; null: none. */
    String bossFor(List<Character> members) {
        Set<String> on = bosses();
        String normal = AgentBossCitizenRuns.BALROG_NORMAL.key();
        if (on.contains(normal) && members.size() >= 6 && members.stream().allMatch(member -> member != null
                && (!AgentPresence.isAgent(member) || member.getLevel() >= config.BOSS_NORMAL_MIN_LEVEL))) {
            boolean supplied = true;
            for (Character member : members) {
                // each citizen buys toward Normal's potions; one that cannot pay for them takes the party to Easy
                if (AgentPresence.isAgent(member) && !engine.provision(member, normal)) {
                    supplied = false;
                }
            }
            if (supplied) {
                return normal;
            }
        }
        String easy = AgentBossCitizenRuns.EASY_BALROG.key();
        return on.contains(easy) && members.size() >= MIN_SEATS ? easy : null;
    }

    @Override
    public boolean holds(int characterId, String sessionId) {
        long id = sessionNumber(sessionId);
        AgentBossRunMember.Phase phase = id <= 0L ? null : engine.memberPhase(id, characterId);
        return phase != null && phase != AgentBossRunMember.Phase.OUT && phase != AgentBossRunMember.Phase.REFUSED;
    }

    @Override
    public Point beside(MapleMap map, int index) {
        return BossEntrances.spot(AgentBossCitizenRuns.EASY_BALROG.key(), map, index);
    }

    /** A human of the party signs up by talking to her; anywhere in her room is fine to start from. */
    @Override
    public boolean atEntry(MapleMap map, Point at) {
        return map != null && at != null;
    }

    // ---- the way in, tick by tick --------------------------------------------------------------

    /**
     * Where run {@code sessionId}'s list stands: GONE once the run is; STARTED past its list; else SIGNING_UP.
     */
    @Override
    public Stage stage(String sessionId) {
        long id = sessionNumber(sessionId);
        AgentBossRunSession.Phase phase = id <= 0L ? null : engine.phase(id);
        if (phase == null || phase == AgentBossRunSession.Phase.ENDED) {
            return Stage.GONE;
        }
        return phase == AgentBossRunSession.Phase.REGISTERING ? Stage.SIGNING_UP : Stage.STARTED;
    }

    /**
     * True when the run may start: every citizen of it is listed (or out of it: refused, gone) and none still walks up,
     * and every human of the party is listed too, or {@code waitedLong} (BOSS_SIGNUP_WAIT_MS since it opened).
     */
    @Override
    public boolean ready(String sessionId, Collection<Integer> citizens, Collection<Integer> humans,
                         boolean waitedLong) {
        long id = sessionNumber(sessionId);
        if (id <= 0L) {
            return false;
        }
        int listed = 0;
        for (int citizen : citizens) {
            AgentBossRunMember.Phase phase = engine.memberPhase(id, citizen);
            if (phase == AgentBossRunMember.Phase.WAITING) {
                return false;
            }
            if (phase == AgentBossRunMember.Phase.REGISTERED) {
                listed++;
            }
        }
        if (listed == 0) {
            return false;
        }
        if (waitedLong) {
            return true;
        }
        for (int human : humans) {
            if (!engine.listed(id, human)) {
                return false;
            }
        }
        return true;
    }

    /** True when human {@code humanId} is on run {@code sessionId}'s list. */
    @Override
    public boolean listed(String sessionId, int humanId) {
        long id = sessionNumber(sessionId);
        return id > 0L && engine.listed(id, humanId);
    }

    @Override
    public boolean start(String sessionId) {
        long id = sessionNumber(sessionId);
        return id > 0L && engine.start(id);
    }

    /** A citizen arriving at her room buys what it lacks of Easy's potions; true when it then carries them. */
    @Override
    public boolean provision(Character agent) {
        return engine.provision(agent, AgentBossCitizenRuns.EASY_BALROG.key());
    }

    static boolean isBossSession(String sessionId) {
        return sessionId != null && sessionId.startsWith(SESSION_PREFIX);
    }

    /** The boss run's number of a population run id ("boss:12"); 0 for any other. */
    static long sessionNumber(String sessionId) {
        if (!isBossSession(sessionId)) {
            return 0L;
        }
        try {
            return Long.parseLong(sessionId.substring(SESSION_PREFIX.length()));
        } catch (NumberFormatException malformed) {
            return 0L;
        }
    }

    /** The population run id of boss run {@code sessionId}. */
    static String sessionKey(long sessionId) {
        return SESSION_PREFIX + sessionId;
    }

    /** A boss key as players say it ("easy balrog"). */
    @Override
    public String label(String bossKey) {
        return AgentBossCitizenRuns.BALROG_NORMAL.key().equals(bossKey) ? "Normal Balrog" : "Easy Balrog";
    }
}
