package server.agents.capabilities.movement;

/**
 * Mutable airborne horizontal steering state for one live Agent runtime.
 */
public final class AgentAirborneSteeringState {
    private double velocityX;
    private double steeringVelocityX;
    private boolean fixedAirArc;
    private boolean knockback;

    public double velocityX() {
        return velocityX;
    }

    public void setVelocityX(double velocityX) {
        this.velocityX = velocityX;
    }

    public double steeringVelocityX() {
        return steeringVelocityX;
    }

    public void setSteeringVelocityX(double steeringVelocityX) {
        this.steeringVelocityX = steeringVelocityX;
    }

    public void addClampedSteeringVelocityX(double delta, double maxAbs) {
        steeringVelocityX = Math.clamp(steeringVelocityX + delta, -maxAbs, maxAbs);
    }

    public boolean fixedAirArc() {
        return fixedAirArc;
    }

    public void setFixedAirArc(boolean fixedAirArc) {
        this.fixedAirArc = fixedAirArc;
    }

    /** True while the Agent flies from a hit's knockback rather than a jump, fall or drop it made. */
    public boolean knockback() {
        return knockback;
    }

    public void setKnockback(boolean knockback) {
        this.knockback = knockback;
    }
}
