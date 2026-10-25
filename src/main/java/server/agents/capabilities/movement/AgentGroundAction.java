package server.agents.capabilities.movement;

/** {@code restX} non-null: a precise walk that stops its input once friction alone ends it on that pixel. */
public record AgentGroundAction(Type type, int stepX, Integer restX) {
    private static final AgentGroundAction IDLE = new AgentGroundAction(Type.IDLE, 0, null);
    private static final AgentGroundAction CROUCH = new AgentGroundAction(Type.CROUCH, 0, null);

    public enum Type {
        IDLE,
        WALK,
        CROUCH,
        JUMP
    }

    public static AgentGroundAction idle() {
        return IDLE;
    }

    public static AgentGroundAction walk(int stepX) {
        return new AgentGroundAction(Type.WALK, stepX, null);
    }

    public static AgentGroundAction walkToRest(int stepX, int restX) {
        return new AgentGroundAction(Type.WALK, stepX, restX);
    }

    public static AgentGroundAction crouch() {
        return CROUCH;
    }

    public static AgentGroundAction jump(int stepX) {
        return new AgentGroundAction(Type.JUMP, stepX, null);
    }
}
