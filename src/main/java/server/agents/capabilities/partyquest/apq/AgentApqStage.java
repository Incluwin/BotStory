package server.agents.capabilities.partyquest.apq;

/**
 * Where an APQ member stands in the run, by its map ({@link AgentApqDefinition#stageOf}): Amos's lobby, the six stages
 * of AmoriaPQ.js from the Magik Mirror to Geist Balrog, the couples' vault or the bonus vault after the clear, the
 * Glimmer Man's exit room, or outside the run.
 */
public enum AgentApqStage {
    LOBBY, MIRROR, ROPES, PLATFORMS, PASSES, GATES, BOSS, COUPLES_VAULT, BONUS, EXIT, OUTSIDE
}
