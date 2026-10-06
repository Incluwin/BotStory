package server.agents.capabilities.partyquest.epq;

/**
 * A companion run's EPQ play, kept apart from the coordinator so each rule and every line is pinned on its own: when
 * the companions go through a walking exit with their leader, how one lurer works both sides of stage 2, when and
 * where a carrier brings the monster marbles or the purple stone to the leader's feet, what they do after the golem
 * and at the fountain, and every line they say. Lines are lower-case and use the game's own words ("diluted poison",
 * "the fountain", "milepost", "purification marble", "golem's marble", "sprite"); only a player's name keeps its
 * capitals, and no line holds a digit.
 */
final class AgentEpqCompanionPlay {
    private AgentEpqCompanionPlay() {
    }
}
