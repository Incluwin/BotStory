package server.agents.capabilities.partyquest.apq;

/**
 * What APQ companions say to their leader, stage by stage (APQ plan A4; the voice of AgentPpqCompanionPlay's lines):
 * casual, lower case but for names, one thought a line, nothing a player would not say. The stage NPCs answer only
 * the leader, so most lines tell him what to do next or what Amos's answer meant.
 */
final class AgentApqLines {
    private AgentApqLines() {
    }

    // ---- stage 1, the Magik Mirror ----

    static String mirrorStart(String leader) {
        return "girls take the left portal, boys the right. we'll clear every room, " + leader
                + ", then you talk to the glimmer man down here.";
    }

    static String roomsClear(String leader) {
        return "rooms are clear! " + leader + ", talk to the glimmer man.";
    }

    static String fierry() {
        return "there's the fierry. we'll get the hammer off it.";
    }

    static String hammerOnMirror() {
        return "dropping the hammer on the mirror...";
    }

    static String shardAtYourFeet(String leader) {
        return leader + ", the mirror shard's at your feet. give it to the glimmer man, then talk to amos up top.";
    }

    static String gateOpen() {
        return "a gate's open up top. let's go!";
    }

    // ---- stage 2, Heart Strings; stage 3, the Twisted Switcher ----

    static String talkToAmosFirst(String leader, int stage) {
        return stage == 2 ? leader + ", talk to amos first. we'll take the ropes."
                : leader + ", talk to amos first. we'll take the platforms.";
    }

    static String ready(String leader, int stage) {
        return stage == 2 ? "we're on the ropes. ask amos, " + leader + "."
                : "everyone's on a platform. ask amos, " + leader + ".";
    }

    static String humanSpot(String name, int stage, int area) {
        if (area < 0) {
            return name + ", stay off the " + (stage == 2 ? "ropes" : "platforms") + " this time.";
        }
        return stage == 2 ? name + ", grab rope " + (area + 1) + "." : name + ", get on platform " + (area + 1) + ".";
    }

    /** What Amos's answer means, said right after it (stage 2: ropes the same; stage 3: slimes, bodies right). */
    static String judged(int stage, int answer) {
        if (stage == 2) {
            return answer <= 0 ? "none of the ropes weigh right. switching around..."
                    : "one rope's right. trying another way...";
        }
        if (answer <= 0) {
            return "no slimes, so none of us were right. moving...";
        }
        return answer == 1 ? "one slime, so one of us was right. kill it and we'll move."
                : answer + " slimes, so " + answer + " of us were right. kill them and we'll move.";
    }

    static String solved() {
        return "that's it! the portal's open.";
    }

    static String redrawn() {
        return "that's seven tries, here they come! kill them, then we start over.";
    }

    // ---- stage 4, Last Man Standing ----

    static String piecesStart(String leader) {
        return "they all drop cupid code pieces. we'll bring you ours, " + leader + ". amos wants fifty.";
    }

    static String piecesAtYourFeet(String leader) {
        return leader + ", pieces at your feet.";
    }

    static String piecesFirst(String leader) {
        return leader + ", say hi to amos first. he only counts the pieces after that.";
    }

    static String pickThemUp(String leader) {
        return leader + ", pick up the pieces!";
    }

    static String fifty(String leader) {
        return "that's fifty! give them to amos, " + leader + ". then we get four minutes for the gates, so stay close.";
    }

    // ---- stage 5, Fluttering Hearts ----

    static String gatesStart() {
        return "go go! we'll break every gate. run to amos at the far end!";
    }

    static String gatesOpen(String leader) {
        return leader + ", the gates are open. run east to amos!";
    }

    static String gathered(String leader) {
        return "we're all here. talk to amos, " + leader + "!";
    }

    static String onceMore(String leader) {
        return "once more, " + leader + ". he checks us on the second talk.";
    }

    static String bossCall() {
        return "everyone ready? i'll ask amos to send us in.";
    }

    // ---- stage 6, Love Hurts ----

    static String bossStart() {
        return "talk to amos down there to get into the fight.";
    }

    static String immune() {
        return "it's immune to us now, hold on...";
    }

    static String fangAtYourFeet(String leader) {
        return "geist balrog's down! " + leader + ", the geist fang's at your feet. give it to amos.";
    }

    static String fangTalk(String leader) {
        return leader + ", give amos the geist fang!";
    }

    static String bonusNext(String leader) {
        return "we did it! " + leader + ", talk to amos again for the bonus.";
    }

    // ---- the vaults and the way out ----

    static String bonus() {
        return "bonus! break the boxes, quick!";
    }

    static String couplesVault(String leader) {
        return "the couples' vault! " + leader + ", amos gives a cape for thirty-five pieces. we'll bring you ours.";
    }

    static String goodRun() {
        return "good run! talk to the glimmer man to head out.";
    }

    // ---- the run ----

    static String leaderDown(String leader) {
        return leader + "! stay with us...";
    }

    static String keepUp(String name) {
        return name + ", this way!";
    }
}
