package server.agents.capabilities.combat;

/**
 * Pure ranged-spacing policy. Live movement and attack execution remain outside this class.
 *
 * <p>2026-10-27: a ranged Agent shoots at any distance (it never whacks), so a monster in the point-blank band only
 * asks for spacing; it no longer buys one close swing before the retreat.
 */
public final class AgentRangedEngagementPolicy {
    private AgentRangedEngagementPolicy() {
    }

    /**
     * @param degenerateAttackAlreadyCommitted a close-range skill (Power Knock-Back) just swung with a ranged weapon
     */
    public record Input(boolean degenerateAttackAlreadyCommitted,
                        boolean retreatRequested,
                        boolean bossSpacingRequired) {
    }

    public record Decision(boolean retreat,
                           boolean attackGateOpen) {
    }

    public static Decision decide(Input input) {
        boolean retreat = input.bossSpacingRequired()
                || input.degenerateAttackAlreadyCommitted()
                || input.retreatRequested();
        return new Decision(retreat, !input.bossSpacingRequired());
    }
}
