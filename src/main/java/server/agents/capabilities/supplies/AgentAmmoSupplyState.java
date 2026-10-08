package server.agents.capabilities.supplies;

/**
 * Mutable ammo warning and sharing state for a live Agent.
 */
public final class AgentAmmoSupplyState {
    private boolean shareRequested = false;
    private boolean noAmmo = false;
    private boolean warnSent = false;
    /**
     * It said in a companion run that it ran out; cleared out of a run and when its bag is back at AMMO_LOW_WARN
     * (AgentCombatAmmoCheckRuntime), not by Soul Arrow. Volatile: the LPQ coordinator sets it (S7d) while the
     * member's own tick reads it.
     */
    private volatile boolean dryLineSaid = false;

    public boolean shareRequested() {
        return shareRequested;
    }

    public void setShareRequested(boolean shareRequested) {
        this.shareRequested = shareRequested;
    }

    public boolean noAmmo() {
        return noAmmo;
    }

    public void setNoAmmo(boolean noAmmo) {
        this.noAmmo = noAmmo;
    }

    public boolean warnSent() {
        return warnSent;
    }

    public void setWarnSent(boolean warnSent) {
        this.warnSent = warnSent;
    }

    public boolean dryLineSaid() {
        return dryLineSaid;
    }

    public void setDryLineSaid(boolean dryLineSaid) {
        this.dryLineSaid = dryLineSaid;
    }

    /** Clears the low and no-ammo warnings; the dry news keeps its own rule (dryLineSaid). */
    public void clearWarningState() {
        noAmmo = false;
        warnSent = false;
    }
}
