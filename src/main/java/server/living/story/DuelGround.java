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

    static DuelGround open(Stagehand hands, Character owner) {
        if (owner == null || owner.getMap() == null || owner.getClient() == null) {
            return null;
        }
        DuelGround[] made = new DuelGround[1];
        SceneInstance.Exits exits = new SceneInstance.Exits() {
            @Override
            public void left(Character chr) {
                if (made[0] != null) {
                    made[0].gone.add(chr.getId());
                }
            }

            @Override
            public void disconnected(Character chr) {
                left(chr);
            }
        };
        int mapId = owner.getMapId();
        Stagehand.Stage stage = hands.open(owner, mapId, mapId, exits);
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
        if (chr == null || stage.ended() || inside.putIfAbsent(chr.getId(), chr) != null) {
            return;
        }
        Point at = new Point(chr.getPosition());
        spots.put(chr.getId(), new Spot(chr.getMapId(), at));
        stage.register(chr);
        hands.warp(chr, stage.map(), at);
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
