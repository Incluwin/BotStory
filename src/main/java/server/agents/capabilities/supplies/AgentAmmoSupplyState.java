package server.agents.capabilities.supplies;

/**
 * Mutable ammo warning and sharing state for a live Agent.
 */
public final class AgentAmmoSupplyState {
    private boolean shareRequested = false;
    private boolean noAmmo = false;
    private boolean warnSent = false;
    /** It said in a companion run that it ran out; cleared with the warning state (back at AMMO_LOW_WARN). */
    private boolean dryLineSaid = false;

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

    public void clearWarningState() {
        noAmmo = false;
        warnSent = false;
        dryLineSaid = false;
    }
}
