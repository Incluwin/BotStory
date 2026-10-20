package server.agents.capabilities.expedition.cwkpq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.expedition.AgentBossFight;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Crimsonwood Keep run for adopted companions (CWKPQ plan Part B), the boss run's fight for key cwkpq: the boss
 * track signs members up at Jack and adopts them on any stage map (boss-rules.json stageMaps), and each tick this
 * hands the members on each stage map to that stage's play. The stage plays are built phase by phase (C4: hallway,
 * storage chamber and wit; C5: agility; C6: unity and the class rooms; C7: the Council Hall); a stage with no play yet
 * leaves its members standing where they are, logged once per run, so the run stays switched off until all are built.
 * The run's clear is the Battle Statue's third talk (eim.clearPQ), after which the boss track lets members follow their
 * owners through the Secret Chamber and the Armory.
 */
public final class AgentCwkpqRun implements AgentBossFight {
    private static final Logger log = LoggerFactory.getLogger(AgentCwkpqRun.class);

    /** The instance maps the run plays on, in order. */
    public enum Stage {
        HALLWAY(610_030_100),
        STORAGE(610_030_200),
        AGILITY(610_030_300),
        WIT(610_030_400),
        UNITY(610_030_500),
        WARRIOR_ROOM(610_030_510),
        MAGICIAN_ROOM(610_030_521),
        THIEF_ROOM(610_030_530),
        BOWMAN_ROOM(610_030_540),
        PIRATE_ROOM(610_030_550),
        COUNCIL(610_030_600);

        private final int mapId;

        Stage(int mapId) {
            this.mapId = mapId;
        }

        public int mapId() {
            return mapId;
        }

        /** The stage on {@code mapId}, or null off the run's maps. */
        public static Stage of(int mapId) {
            for (Stage stage : values()) {
                if (stage.mapId == mapId) {
                    return stage;
                }
            }
            return null;
        }
    }

    /** One stage's play: what its members do this tick. */
    @FunctionalInterface
    public interface StagePlay {
        void tick(List<Character> members, EventInstanceManager event, AgentCwkpqRun run, long nowMs);
    }

    /** The Council Hall, the battle map (AgentBossRunDefinition.battleMapId). */
    public static final int COUNCIL_HALL_MAP_ID = Stage.COUNCIL.mapId;

    private final Map<Stage, StagePlay> plays;
    private final Map<Integer, Integer> ordinals = new ConcurrentHashMap<>();
    private final Set<Stage> unbuiltLogged = EnumSet.noneOf(Stage.class);

    AgentCwkpqRun(Map<Stage, StagePlay> plays) {
        this.plays = plays.isEmpty() ? Map.of() : new EnumMap<>(plays);
    }

    /** A fresh run for one boss run session, with every stage play built so far. */
    public static AgentCwkpqRun forCompanions() {
        return new AgentCwkpqRun(Map.of());
    }

    /** The maps before the battle map (boss-rules.json stageMaps for cwkpq), in order. */
    public static List<Integer> stageMapsBeforeCouncil() {
        List<Integer> maps = new ArrayList<>();
        for (Stage stage : Stage.values()) {
            if (stage != Stage.COUNCIL) {
                maps.add(stage.mapId);
            }
        }
        return maps;
    }

    @Override
    public void adopt(int characterId, int ordinal) {
        ordinals.put(characterId, ordinal);
    }

    /** The member's formation slot, by adoption order; -1 when it was not adopted by this run. */
    public int ordinal(int characterId) {
        return ordinals.getOrDefault(characterId, -1);
    }

    @Override
    public void tick(List<Character> fighters, EventInstanceManager event, long nowMs) {
        Map<Stage, List<Character>> byStage = new EnumMap<>(Stage.class);
        for (Character fighter : fighters) {
            Stage stage = Stage.of(fighter.getMapId());
            if (stage != null) {
                byStage.computeIfAbsent(stage, ignored -> new ArrayList<>()).add(fighter);
            }
        }
        for (Map.Entry<Stage, List<Character>> here : byStage.entrySet()) {
            StagePlay play = plays.get(here.getKey());
            if (play != null) {
                play.tick(here.getValue(), event, this, nowMs);
            } else if (unbuiltLogged.add(here.getKey())) {
                log.info("CWKPQ stage {} has no companion play yet: {} member(s) stand where they are",
                        here.getKey(), here.getValue().size());
            }
        }
    }

    @Override
    public void end(EventInstanceManager event) {
        ordinals.clear();
    }
}
