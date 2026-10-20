package server.agents.capabilities.partyquest.ppq;

/**
 * What a companion of a PPQ companion run decides and says (P6i Steps 2 and 3), as pure rules: no character, map or
 * session is read here. The lines are player chat: short, lower case but for player names, no digits, no markup, never
 * an item id. AgentPpqCompanionRun and AgentPpqCoordinator decide when; AgentPpqDialogue says them.
 */
final class AgentPpqCompanionPlay {
    private AgentPpqCompanionPlay() {
    }

    // ---- movement (plan 7.2: companions go ahead once a stage is clear, P3's default) -------------------------------

    /** What a companion on the first stage or a deck does this pass. */
    enum DeckMove { FIGHT, FOLLOW_INTO_CHEST, WAIT_BY_CHEST, GO_ON }

    /**
     * A companion leaves a cleared stage through its exit without waiting for the leader, except on a deck whose chest
     * the leader can still open: while he holds a chest key, chests are not skipped, this deck's chest is not done and
     * he has not gone past this deck, it waits by the chest room's portal and follows him in only once he is inside.
     * A leader already in this deck's chest room is followed in at once, before the deck's own mobs.
     *
     * @param leaderPastThisDeck the leader stands on a later stage than this deck (he chose to go on)
     */
    static DeckMove deckMove(boolean deck, boolean leaderInThisChestRoom, boolean mobsLive, boolean chestsSkipped,
                             boolean chestDone, boolean leaderHoldsChestKey, boolean leaderPastThisDeck) {
        if (deck && leaderInThisChestRoom) return DeckMove.FOLLOW_INTO_CHEST;
        if (mobsLive) return DeckMove.FIGHT;
        if (deck && !chestsSkipped && !chestDone && leaderHoldsChestKey && !leaderPastThisDeck) {
            return DeckMove.WAIT_BY_CHEST;
        }
        return DeckMove.GO_ON;
    }

    /** What a companion on the door map does once every door is open. */
    enum DoorMove { FIGHT, WAIT, ENTER }

    /**
     * The boss door: davy_next4.js lets anyone through only while the door map is empty, and the first one through
     * spawns the Lord Pirate, so the companions keep the map clear by the door and go in only with the leader (beside
     * the door, or already inside).
     */
    static DoorMove bossDoor(boolean mobsLive, boolean leaderAtDoor, boolean leaderInside) {
        if ((leaderAtDoor || leaderInside) && !mobsLive) return DoorMove.ENTER;
        return mobsLive ? DoorMove.FIGHT : DoorMove.WAIT;
    }

    /**
     * The stock key rule (each opened door stops its spawn points, so the keys come first): no key goes into a door
     * until the companions' keys and the keys still lying on the map cover every closed door. Keys a human holds are
     * not counted: the companions cannot spend them.
     */
    static boolean mayDropKeys(int companionKeys, int floorKeys, int closedDoors) {
        return closedDoors > 0 && Math.max(0, companionKeys) + Math.max(0, floorKeys) >= closedDoors;
    }

    /** The leader holds a key and the companions' keys cannot open every closed door without it. */
    static boolean leaderKeyWanted(int leaderKeys, int companionKeys, int closedDoors) {
        return leaderKeys > 0 && closedDoors > Math.max(0, companionKeys);
    }

    /** By the stage ranks, whether {@code leader} stands past {@code deck} (its own chest and hidden rooms are not). */
    static boolean past(AgentPpqStage leader, AgentPpqStage deck) {
        return rank(leader) > rank(deck);
    }

    /** A stage's place in the run; a deck's chest and hidden rooms share the deck's. OUTSIDE and LOBBY come first. */
    static int rank(AgentPpqStage stage) {
        if (stage == null) return 0;
        return switch (stage) {
            case LOBBY, OUTSIDE -> 0;
            case STAGE_ONE -> 1;
            case MEDALS -> 2;
            case DECK_ONE, CHEST_ONE, SIDE_ONE -> 3;
            case DECK_TWO, CHEST_TWO, SIDE_TWO -> 4;
            case DOORS -> 5;
            case BOSS -> 6;
            case CLEAR -> 7;
        };
    }

    /**
     * After the clear (decision D-f'): companions wait by Wu Yang while the leader is still in his room, so the hand-back
     * never sends them after him into a room with no exit; they take the Feather once he left or {@code waitMs} passed.
     */
    static boolean waitByWuYang(boolean leaderInRoom, long sinceClearMs, long waitMs) {
        return leaderInRoom && sinceClearMs < waitMs;
    }

    /** At or under {@code percent} of its HP with no HP potion left: it cannot carry medals through the next hit. */
    static boolean dying(int hp, int maxHp, int hpPotions, int percent) {
        return hp > 0 && maxHp > 0 && hpPotions <= 0 && (long) hp * 100L <= (long) percent * maxHp;
    }

    // ---- lines (plan section 12) -----------------------------------------------------------------------------------

    /** S1: stage one, once. */
    static String stageOne() {
        return "clear the deck, then we're through the door up on the right.";
    }

    /** M1: each medal wave's start, in its own words. */
    static String waveStart(int medal) {
        return switch (medal) {
            case AgentPpqDefinition.RISING_MEDAL -> "rising pirate medals now. we'll bring them to you.";
            case AgentPpqDefinition.VETERAN_MEDAL -> "last wave! veteran pirate medals, same deal.";
            default -> "we'll grab the medals and bring them to you.";
        };
    }

    /** M2: a batch at his feet, or right next to him. */
    static String medalsAtFeet(String leader, boolean atHisFeet) {
        return atHisFeet ? "medals at your feet, " + leader + "." : "medals right next to you, " + leader + ".";
    }

    /** M2b: he could not be reached, so they lie by Guon. */
    static String medalsByGuon(String leader) {
        return leader + ", couldn't get to you. left the medals under guon's ledge.";
    }

    /** M3: a stack he left lying. */
    static String medalsStillLying(String leader) {
        return leader + ", the medals are still lying there.";
    }

    /** M4: a stack picked back up before it vanished and dropped for him again. */
    static String medalsPickedBackUp() {
        return "picked the medals back up so they don't vanish. here they are again.";
    }

    /** The hand-over that makes the wave's twenty: he still has to pick them up. */
    static String twentyWithThese(String leader) {
        return "that's twenty with these, " + leader + ". grab them and talk to guon.";
    }

    /** M5: he holds the wave's twenty. */
    static String allOfThem() {
        return "that's all of them! talk to guon up on the ledge.";
    }

    /** M6: dying with no potion, it leaves its medals where it stands. */
    static String leavingMyMedals() {
        return "almost out of pots, leaving my medals here. grab them.";
    }

    /** C0: the deck is clear and he holds a chest key: they wait for him by the chest room's portal. */
    static String chestRoomHere(String leader) {
        return leader + ", chest room's down here. you go in first, we'll follow.";
    }

    /** C1: the chest room is clear, its chest still shut, and he holds the key. */
    static String openTheChest() {
        return "talk to guon, then drop your chest key by the chest.";
    }

    /** K1: his old key is needed in a door. */
    static String dropYourKey(String leader) {
        return leader + ", drop that old key in a door.";
    }

    /** W1: every door open, the leader at the boss door. */
    static String bossDoor(String leader) {
        return "boss door. we'll go in with you, " + leader + ".";
    }

    /** B1: the Lord Pirate is up. */
    static String lordPirate(String leader) {
        return "lord pirate! stay behind us, " + leader + ".";
    }

    /** B2: the Lord Pirate is down. */
    static String lordPirateDown(String leader) {
        return "lord pirate's down! talk to wu yang, " + leader + ".";
    }

    /** B3: a chest key on the floor, and he holds none. */
    static String chestKeyDropped(String leader) {
        return "a chest key! grab it for next time, " + leader + ".";
    }

    /** E1: waiting by Wu Yang after the clear. */
    static String feathers() {
        return "nice! wu yang has a feather for each of us.";
    }

    /** D1: the leader went down inside. */
    static String leaderDown(String leader) {
        return leader + " went down!";
    }

    /** T1: a member left the run and it goes on. */
    static String hadToGo(String name) {
        return name + " had to go. we'll keep going.";
    }
}
