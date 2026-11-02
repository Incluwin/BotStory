package server.living.population.casino;

import client.Character;
import server.living.population.Citizen;
import server.living.population.CitizenSession;

/** A place where citizens on a CASINO session have a role: a dealer's table, a host's post, a player's seat. */
public interface Venue {
    /** Short name for logs and {@code !citizens casino}. */
    String name();

    int mapId();

    /** Its switch is on. */
    boolean enabled();

    /** A role here could suit this citizen now (a free post or seat it can afford). */
    boolean wants(Citizen citizen);

    /** The citizen arrived on the venue's map: it takes a role (true) or there is none for it after all (false). */
    boolean take(CitizenSession session, long now);

    /** True while the citizen holds a role here. */
    boolean holds(int characterId);

    /** Every 20 s: walks finished, shifts that are over, rounds to start. */
    void tick(long now);

    /** A human's map chat on the venue's map; true when the venue took it ("deal me in", "hit", "cho"). */
    boolean heard(Character human, String text, long now);

    /** Everything stops: citizens leave their roles, chips and props are cleared. */
    void stop();

    /** One line for {@code !citizens casino}. */
    String status();
}
