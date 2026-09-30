package server.living.roster;

/**
 * What a companion says as it steps out to wait outside a run (RosterService.hold), and where: {@link Voice#WHISPER}
 * to its owner, {@link Voice#MAP} aloud where it stands, {@link Voice#PARTY} in party chat just before it leaves the
 * party. Flow 3: "sorry, i'll wait outside." to the party as it taps out, "we'll wait here." aloud beside Lakelis, and
 * "Ben asked me to wait outside." whispered to its owner when a friend leads the run. A null line is its usual one: its
 * arc's "hold" line, or "i'll wait out here till you're done.".
 */
public record HoldNote(String line, Voice voice) {
    public enum Voice { WHISPER, MAP, PARTY }

    public static HoldNote whisper(String line) {
        return new HoldNote(line, Voice.WHISPER);
    }

    public static HoldNote map(String line) {
        return new HoldNote(line, Voice.MAP);
    }

    public static HoldNote party(String line) {
        return new HoldNote(line, Voice.PARTY);
    }
}
