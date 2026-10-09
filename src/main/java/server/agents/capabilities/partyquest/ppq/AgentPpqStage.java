package server.agents.capabilities.partyquest.ppq;

/**
 * Where a PPQ member stands in the run, by its map ({@link AgentPpqDefinition#stageOf}): Guon's lobby, the stages of
 * PiratePQ.js from the first deck to the boss, the chest and hidden side rooms off each deck, Wu Yang's room after the
 * clear, or outside the run.
 */
public enum AgentPpqStage {
    LOBBY, STAGE_ONE, MEDALS, DECK_ONE, CHEST_ONE, SIDE_ONE, DECK_TWO, CHEST_TWO, SIDE_TWO, DOORS, BOSS, CLEAR, OUTSIDE
}
