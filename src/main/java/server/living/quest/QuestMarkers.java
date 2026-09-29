package server.living.quest;

import client.Character;
import server.integration.AgentPresence;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.resident.ResidentRuntime;
import server.maps.MapleMap;
import tools.PacketCreator;

import java.awt.Point;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Quest icons over Residents. The client draws an NPC's quest icon itself from Quest.wz, but a
 * Resident is a character, so the server replays the same icon over it as a foreign avatar
 * effect, sent only to the player it is for: the lightbulb when the Resident has a quest that
 * player can take, the book when the player can hand something in. States are cached per player
 * and recomputed after quest events or {@code QUEST_MARKER_REFRESH_MS}.
 *
 * <p>The client never reports clicks on an effect, so each marker also gets a hitbox: an
 * invisible NPC ({@code QUEST_MARKER_HITBOX_NPC}) spawned for that player only, under the icon,
 * and moved along when the Resident walks. Clicking it arrives as an NPC talk and opens the
 * Resident's menu, the same as double-clicking the Resident.
 */
public final class QuestMarkers {
    private enum Marker { NONE, AVAILABLE, READY }

    /** How far a Resident may walk before its hitboxes are moved. */
    private static final int HITBOX_STEP_PX = 12;

    private static final class PlayerMarkers {
        final long atMs = System.currentTimeMillis();
        final Map<String, Marker> byPersona = new ConcurrentHashMap<>();
    }

    /** A hitbox the client has (or had, when {@code at} is null after a field change). */
    private record Hitbox(int objectId, int mapId, Point at) {
    }

    private static final class PlayerHitboxes {
        final Character viewer;
        final Map<Integer, Hitbox> byResident = new ConcurrentHashMap<>();

        PlayerHitboxes(Character viewer) {
            this.viewer = viewer;
        }
    }

    private final LivingWorld world;
    private final Map<Integer, PlayerMarkers> cache = new ConcurrentHashMap<>();
    private final Map<Integer, PlayerHitboxes> hitboxes = new ConcurrentHashMap<>();

    public QuestMarkers(LivingWorld world) {
        this.world = world;
    }

    /** Replays every due marker to the players standing with the Resident. */
    public void tick() {
        long now = System.currentTimeMillis();
        for (ResidentRuntime.Session session : world.residents().sessions()) {
            Character agent = session.agent();
            MapleMap map = agent == null ? null : agent.getMap();
            if (map == null) {
                continue;
            }
            for (Character viewer : map.getAllPlayers()) {
                if (AgentPresence.isAgent(viewer)) {
                    continue;
                }
                String path = pathOf(marker(viewer, session.persona, now));
                if (!path.isEmpty()) {
                    viewer.sendPacket(PacketCreator.showForeignInfo(agent.getId(), path));
                }
            }
        }
    }

    /** Keeps one hitbox under every marker a player sees, and removes the rest. */
    public synchronized void trackHitboxes() {
        int npcId = world.config().QUEST_MARKER_HITBOX_NPC;
        if (npcId <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        Set<Long> live = new HashSet<>();
        for (ResidentRuntime.Session session : world.residents().sessions()) {
            Character agent = session.agent();
            MapleMap map = agent == null ? null : agent.getMap();
            if (map == null) {
                continue;
            }
            for (Character viewer : map.getAllPlayers()) {
                if (AgentPresence.isAgent(viewer) || marker(viewer, session.persona, now) == Marker.NONE) {
                    continue;
                }
                live.add(key(viewer.getId(), agent.getId()));
                place(viewer, agent, map, npcId);
            }
        }
        for (PlayerHitboxes mine : hitboxes.values()) {
            mine.byResident.entrySet().removeIf(entry -> {
                if (live.contains(key(mine.viewer.getId(), entry.getKey()))) {
                    return false;
                }
                remove(mine.viewer, entry.getValue());
                return true;
            });
        }
        hitboxes.values().removeIf(mine -> mine.byResident.isEmpty());
    }

    /** The Resident a clicked hitbox belongs to, or null when the object is not one of this player's hitboxes. */
    public Character hitboxOwner(Character viewer, int objectId) {
        PlayerHitboxes mine = viewer == null ? null : hitboxes.get(viewer.getId());
        if (mine == null) {
            return null;
        }
        for (Map.Entry<Integer, Hitbox> entry : mine.byResident.entrySet()) {
            Hitbox box = entry.getValue();
            if (box.objectId() == objectId && box.mapId() == viewer.getMapId()) {
                for (ResidentRuntime.Session session : world.residents().sessions()) {
                    Character agent = session.agent();
                    if (agent != null && agent.getId() == entry.getKey()) {
                        return agent;
                    }
                }
            }
        }
        return null;
    }

    /** True when the object id is a hitbox this player was sent, whether or not its Resident is still here. */
    public boolean isHitbox(Character viewer, int objectId) {
        PlayerHitboxes mine = viewer == null ? null : hitboxes.get(viewer.getId());
        return mine != null && mine.byResident.values().stream().anyMatch(box -> box.objectId() == objectId);
    }

    /** The player's client rebuilt its field: every hitbox it had is gone and must be sent again. */
    public synchronized void enteredMap(Character viewer) {
        PlayerHitboxes mine = viewer == null ? null : hitboxes.get(viewer.getId());
        if (mine != null) {
            mine.byResident.replaceAll((residentId, box) -> new Hitbox(box.objectId(), box.mapId(), null));
        }
    }

    /** The player's quest situation changed: recompute their markers on the next pulse. */
    public void forget(Character chr) {
        if (chr != null) {
            cache.remove(chr.getId());
        }
    }

    /** The player logged out: their client and its hitboxes are gone. */
    public synchronized void loggedOut(Character chr) {
        if (chr != null) {
            cache.remove(chr.getId());
            hitboxes.remove(chr.getId());
        }
    }

    private void place(Character viewer, Character agent, MapleMap map, int npcId) {
        PlayerHitboxes mine = hitboxes.computeIfAbsent(viewer.getId(), id -> new PlayerHitboxes(viewer));
        Hitbox old = mine.byResident.get(agent.getId());
        Point at = new Point(agent.getPosition());
        if (old != null && old.at() != null && old.mapId() == map.getId() && old.at().distance(at) <= HITBOX_STEP_PX) {
            return;
        }
        remove(viewer, old);
        int objectId = old != null && old.mapId() == map.getId() ? old.objectId() : map.allocateMapObjectId();
        int lift = world.config().QUEST_MARKER_HITBOX_LIFT;
        viewer.sendPacket(PacketCreator.spawnNPC(objectId, npcId, at.x, at.y - lift, lift == 0 ? agent.getFh() : 0));
        mine.byResident.put(agent.getId(), new Hitbox(objectId, map.getId(), at));
    }

    /**
     * Object ids are only unique within one map, so a hitbox is removed only while the player is
     * still on the map it was spawned in; after a field change the client has already dropped it.
     */
    private static void remove(Character viewer, Hitbox box) {
        if (box != null && box.mapId() == viewer.getMapId() && viewer.isLoggedinWorld()) {
            viewer.sendPacket(PacketCreator.removeNPC(box.objectId()));
        }
    }

    private static long key(int viewerId, int residentId) {
        return ((long) viewerId << 32) | (residentId & 0xffffffffL);
    }

    private Marker marker(Character viewer, Persona persona, long now) {
        PlayerMarkers markers = cache.get(viewer.getId());
        if (markers == null || now - markers.atMs > world.config().QUEST_MARKER_REFRESH_MS) {
            markers = new PlayerMarkers();
            cache.put(viewer.getId(), markers);
        }
        return markers.byPersona.computeIfAbsent(persona.id(), id -> compute(viewer, persona));
    }

    private Marker compute(Character viewer, Persona persona) {
        if (LivingContent.isHire(persona.id())) {
            return Marker.NONE; // a hired citizen gives no quests
        }
        if (world.quests().readyToHandIn(viewer, persona)) {
            return Marker.READY;
        }
        return world.quests().available(viewer, persona).isEmpty() ? Marker.NONE : Marker.AVAILABLE;
    }

    private String pathOf(Marker marker) {
        String path = switch (marker) {
            case AVAILABLE -> world.config().QUEST_MARKER_AVAILABLE;
            case READY -> world.config().QUEST_MARKER_READY;
            case NONE -> null;
        };
        return path == null ? "" : path.trim();
    }
}
