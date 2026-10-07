package server.agents.integration;

import java.awt.Point;

/** One NPC map object as an Agent sees it: its object id, its NPC id and where it stands. */
public record NpcSighting(int objectId, int npcId, Point position) {
    public NpcSighting {
        position = position == null ? null : new Point(position);
    }

    @Override
    public Point position() {
        return position == null ? null : new Point(position);
    }
}
