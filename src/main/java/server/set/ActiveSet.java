package server.set;

/**
 * Snapshot of one set's contribution at the time a {@link SetBonusTotal} was computed.
 * Used for !checksets display and for diffing previous-vs-current in change announcements.
 */
public final class ActiveSet {
    public final String id;
    public final String name;
    public final int pieces;
    public final int total;     // how many distinct slots could contribute

    public ActiveSet(String id, String name, int pieces, int total) {
        this.id = id;
        this.name = name;
        this.pieces = pieces;
        this.total = total;
    }
}
