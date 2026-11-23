package server.living.story;

import client.Character;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The ground of a rival challenge's duel (docs/PVP_PLAN.md V3, PD1): a private copy of the field its player stands on,
 * without monsters, entered by the fighters and their watchers only, so nobody else on the field sees a fight they did
 * not join. Everyone enters at the spot they stood on (the copy has the same footholds) and {@link #close} puts each one
 * back on the channel's field at that same spot. Built on the story scenes' {@link SceneInstance} copy; a portal taken
 * or a disconnect on the copy is heard as {@link #left}.
 */
public final class DuelGround {
    private record Spot(int mapId, Point at) { }

    private final Stagehand hands;
    private final Stagehand.Stage stage;
    private final Character owner;
    private final Map<Integer, Character> inside = new ConcurrentHashMap<>();
    private final Map<Integer, Spot> spots = new ConcurrentHashMap<>();
    private final Set<Integer> gone = ConcurrentHashMap.newKeySet();

    private DuelGround(Stagehand hands, Stagehand.Stage stage, Character owner) {
        this.hands = hands;
        this.stage = stage;
        this.owner = owner;
    }

    /** A copy of the field {@code owner} stands on, on its channel; null when none can be made. */
    public static DuelGround open(Character owner) {
        return open(Stagehand.LIVE, owner);
    }

    /**
     * A copy of map {@code mapId} on {@code owner}'s channel (a coliseum arena floor, docs/PVP_PLAN.md V4), whose
     * logouts save {@code exitMapId}; null when none can be made. Fighters enter at a point of the caller's choosing
     * ({@link #enter(Character, Point)}) and {@link #close} still puts each back where it stood before.
     */
    public static DuelGround open(Character owner, int mapId, int exitMapId) {
        return open(Stagehand.LIVE, owner, mapId, exitMapId);
    }

    /**
     * The same copy, telling {@code onLeft} (on the game thread that saw it) of each character that left it on its own:
     * a portal, a death, a disconnect, a warp from elsewhere (the crew camp, docs/WORLD_SYSTEMS_PLAN.md N7).
     */
    public static DuelGround open(Character owner, int mapId, int exitMapId, java.util.function.Consumer<Character> onLeft) {
        return open(Stagehand.LIVE, owner, mapId, exitMapId, onLeft);
    }

    static DuelGround open(Stagehand hands, Character owner) {
        if (owner == null || owner.getMap() == null) {
            return null;
        }
        return open(hands, owner, owner.getMapId(), owner.getMapId());
    }

    static DuelGround open(Stagehand hands, Character owner, int mapId, int exitMapId) {
        return open(hands, owner, mapId, exitMapId, null);
    }

    static DuelGround open(Stagehand hands, Character owner, int mapId, int exitMapId,
                           java.util.function.Consumer<Character> onLeft) {
        if (owner == null || owner.getClient() == null) {
            return null;
        }
        DuelGround[] made = new DuelGround[1];
        SceneInstance.Exits exits = new SceneInstance.Exits() {
            @Override
            public void left(Character chr) {
                if (made[0] != null) {
                    made[0].gone.add(chr.getId());
                }
                if (onLeft != null) {
                    onLeft.accept(chr);
                }
            }

            @Override
            public void disconnected(Character chr) {
                left(chr);
            }
        };
        Stagehand.Stage stage = hands.open(owner, mapId, exitMapId, exits);
        if (stage == null) {
            return null;
        }
        made[0] = new DuelGround(hands, stage, owner);
        return made[0];
    }

    public MapleMap map() {
        return stage.map();
    }

    /** {@code chr} steps onto the copy at the spot it stands on now (once). */
    public void enter(Character chr) {
        if (chr == null) {
            return;
        }
        enter(chr, new Point(chr.getPosition()));
    }

    /** {@code chr} steps onto the copy at {@code onStage} (once); {@link #close} returns it to where it stands now. */
    public void enter(Character chr, Point onStage) {
        if (chr == null || onStage == null || stage.ended() || inside.putIfAbsent(chr.getId(), chr) != null) {
            return;
        }
        spots.put(chr.getId(), new Spot(chr.getMapId(), new Point(chr.getPosition())));
        stage.register(chr);
        hands.warp(chr, stage.map(), new Point(onStage));
    }

    /** Whether {@code characterId} left the copy on its own (a portal, a death, a disconnect). */
    public boolean left(int characterId) {
        return gone.contains(characterId);
    }

    public boolean ended() {
        return stage.ended();
    }

    /**
     * Everyone still on the copy back to their own spot on the channel's field, then the copy is let go (once).
     * {@code followers} are companions, told to follow their owner again.
     */
    public void close(Collection<Integer> followers) {
        if (stage.ended()) {
            return;
        }
        for (Character chr : inside.values()) {
            Spot spot = spots.get(chr.getId());
            if (spot == null || gone.contains(chr.getId()) || chr.getMap() != stage.map()) {
                continue;
            }
            stage.unregister(chr); // out of the instance first, so the warp lands on the channel's field
            MapleMap back = hands.channelMap(owner, spot.mapId());
            if (back != null) {
                hands.warp(chr, back, spot.at());
            }
            if (followers != null && followers.contains(chr.getId())) {
                hands.follow(chr);
            }
        }
        stage.end();
    }
}
