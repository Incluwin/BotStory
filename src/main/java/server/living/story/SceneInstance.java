package server.living.story;

import client.Character;
import scripting.event.EventInstanceManager;
import scripting.event.EventManager;
import server.maps.MapleMap;

/**
 * A scene's private copy of a map (F6.2): an {@link EventInstanceManager} with its own MapManager, as every party quest
 * has, but driven from Java. It sits on the channel's no-op {@code 0_EXAMPLE} event manager (what an unknown event name
 * returns) and never calls into a script: {@link #invokeScriptFunction} answers the few callbacks a scene needs and
 * nothing else. {@code startInstance} is never called, so it takes none of that manager's lobbies.
 *
 * <ul>
 *   <li>{@code changedMap} to any map but the stage: that character leaves the scene. It is unregistered at once, so the
 *       warp lands on the channel's map, not on this instance's copy of it.</li>
 *   <li>{@code playerRevive}: a death in the scene revives at the scene's exit, out of the instance.</li>
 *   <li>{@code playerDisconnected}: the scene ends for everyone.</li>
 *   <li>{@code monsterValue} is 0 (EventInstanceManager casts it to an int).</li>
 * </ul>
 */
final class SceneInstance extends EventInstanceManager {
    /** What the scene hears from its instance, on the thread the game ran it on (memory only; work goes to companion-ops). */
    interface Exits {
        void left(Character chr);

        void disconnected(Character chr);
    }

    private final int stageMapId;
    private final int exitMapId;
    private final Exits exits;
    private volatile boolean ended;

    SceneInstance(EventManager manager, String name, int stageMapId, int exitMapId, Exits exits) {
        super(manager, name);
        this.stageMapId = stageMapId;
        this.exitMapId = exitMapId;
        this.exits = exits;
    }

    MapleMap stage() {
        return getMapInstance(stageMapId);
    }

    boolean ended() {
        return ended;
    }

    /** Lets go of every map; idempotent. Whoever is still registered is no longer (dispose clears them). */
    void end() {
        if (!ended) {
            ended = true;
            dispose();
        }
    }

    @Override
    public Object invokeScriptFunction(String name, Object... args) {
        switch (name) {
            case "monsterValue":
                return 0;
            case "changedMap":
                if (args.length > 2 && args[1] instanceof Character chr && args[2] instanceof Integer mapId
                        && mapId != stageMapId) {
                    unregisterPlayer(chr);
                    exits.left(chr);
                }
                return null;
            case "playerRevive":
                if (args.length > 1 && args[1] instanceof Character chr) {
                    chr.respawn(this, exitMapId); // unregisters, then the exit; the scene hears it as left
                    exits.left(chr);
                }
                return false;
            case "playerDisconnected":
                if (args.length > 1 && args[1] instanceof Character chr) {
                    exits.disconnected(chr);
                }
                return null;
            default:
                return null;
        }
    }
}
