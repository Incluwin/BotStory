package server.living.story;

import client.Character;
import scripting.event.EventManager;
import server.agents.integration.AgentDialogueTransportRuntime;
import server.life.LifeFactory;
import server.life.Monster;
import server.life.MonsterListener;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;
import java.util.function.Function;

/**
 * What a scene does to the game (F6.2), behind one seam so {@link StoryScenes} runs in tests without a channel: the
 * private copy of a map, the monsters on it, a companion's line in map chat, a warp, and the channel's own copy of the
 * exit map. {@link #LIVE} is the game's.
 */
interface Stagehand {
    /** The event name a scene asks its channel for; none is loaded, so the channel hands back its no-op manager. */
    String SCENE_EVENT = "StoryScene";

    /** A scene's private map, entered by registering into it. */
    interface Stage {
        MapleMap map();

        void register(Character chr);

        /** Out of the scene, so a warp lands on the channel's map; a character not in it is left alone. */
        void unregister(Character chr);

        /** Lets go of the copy (once). */
        void end();

        boolean ended();
    }

    /** A private copy of {@code mapId} on the owner's channel, its own monsters cleared; null when none can be made. */
    Stage open(Character owner, int mapId, int exitMapId, SceneInstance.Exits exits);

    /** One monster on the stage at {@code at}, dropping nothing; {@code listener} hears its death. Null for an unknown id. */
    Monster spawn(Stage stage, int mobId, Point at, Function<Monster, MonsterListener> listener);

    /** A companion's line in map chat (its balloon). */
    void say(Character speaker, String line);

    void warp(Character chr, MapleMap to, Point at);

    /** The channel's own map the owner returns to. */
    MapleMap channelMap(Character owner, int mapId);

    Stagehand LIVE = new Stagehand() {
        @Override
        public Stage open(Character owner, int mapId, int exitMapId, SceneInstance.Exits exits) {
            EventManager manager = owner.getClient().getEventManager(SCENE_EVENT);
            if (manager == null) {
                return null;
            }
            SceneInstance instance = new SceneInstance(manager, "Scene-" + owner.getId() + "-" + System.nanoTime(), mapId,
                    exitMapId, exits);
            MapleMap map = instance.stage();
            if (map == null) {
                instance.end();
                return null;
            }
            map.killAllMonsters(); // a fresh copy arrives with its own spawns; instance maps never respawn
            map.setForcedReturnMap(exitMapId); // a logout on the stage saves the exit, not the copied field
            return new Stage() {
                @Override
                public MapleMap map() {
                    return map;
                }

                @Override
                public void register(Character chr) {
                    instance.registerPlayer(chr, false);
                }

                @Override
                public void unregister(Character chr) {
                    if (chr.getEventInstance() == instance) {
                        instance.unregisterPlayer(chr);
                    }
                }

                @Override
                public void end() {
                    instance.end();
                }

                @Override
                public boolean ended() {
                    return instance.ended();
                }
            };
        }

        @Override
        public Monster spawn(Stage stage, int mobId, Point at, Function<Monster, MonsterListener> listener) {
            Monster mob = LifeFactory.getMonster(mobId);
            if (mob == null) {
                return null;
            }
            mob.disableDrops();
            mob.addListener(listener.apply(mob));
            stage.map().spawnMonsterOnGroundBelow(mob, at);
            return mob;
        }

        @Override
        public void say(Character speaker, String line) {
            AgentDialogueTransportRuntime.sayMapNow(speaker, line);
        }

        @Override
        public void warp(Character chr, MapleMap to, Point at) {
            chr.changeMap(to, at);
        }

        @Override
        public MapleMap channelMap(Character owner, int mapId) {
            return owner.getClient().getChannelServer().getMapFactory().getMap(mapId);
        }
    };

    /** Where the owner is set down: the scene's spot, else the map's first portal, else its middle. */
    static Point landing(MapleMap map, int x, int y) {
        if (x != 0 || y != 0) {
            return new Point(x, y);
        }
        Portal first = map.getPortal(0);
        return first != null && first.getPosition() != null ? new Point(first.getPosition()) : new Point(0, 0);
    }
}
