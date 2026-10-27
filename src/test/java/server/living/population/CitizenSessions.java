package server.living.population;

/** For tests outside this package (the needs board's): a loaded citizen session, as a login makes one. */
public final class CitizenSessions {
    private CitizenSessions() {
    }

    public static CitizenSession loaded(Citizen citizen, long startedAtMs, long endsAtMs) {
        return new CitizenSession(citizen, startedAtMs, endsAtMs, false);
    }
}
