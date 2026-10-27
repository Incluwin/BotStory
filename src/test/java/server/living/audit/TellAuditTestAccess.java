package server.living.audit;

/** Starts a tell audit from another package's test (the admin endpoint's), with no store behind it. */
public final class TellAuditTestAccess {
    private TellAuditTestAccess() {
    }

    public static void startNow(String build) {
        TellAudit.start(new TellAudit(build, System.currentTimeMillis(), line -> { }));
    }
}
