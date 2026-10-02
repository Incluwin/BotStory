package server.living.roster.ui;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.living.config.Content.Persona;
import server.living.resident.LivingTalk;
import server.living.resident.LivingTalk.Option;
import server.living.roster.CompanionRunLifecycle;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A run's companion asks its leader whether to leave stage 1 and run the quest again (the KPQ re-roll, round 2b): its
 * own window, its portrait and name as on its card, its question as it just said it in chat, and two picks that need
 * no typing. A pick only records the answer; the run acts on it on its next tick. Shown on the leader's I/O thread,
 * offered by the run only at a lull in his fight, and never over another window (it is refused then, logged once per
 * question, and the run offers it again at its next lull). A "go again" picked after the question closed (he let it
 * pass, or said no in chat) is answered in pink text in the companion's words while the run goes on: the window always
 * gets an answer, and closes.
 */
public final class RerollQuestion implements CompanionRunLifecycle.RerollWindow {
    private static final Logger log = LoggerFactory.getLogger(RerollQuestion.class);
    static final String GO_AGAIN = "Let's go again.";
    static final String PUSH_THROUGH = "Nah, let's push through.";
    /** The companion's answer to a "go again" that came after the run carried on (lower-case player chat). */
    static final String TOO_LATE = "eh, we're already into it now. let's just finish.";

    /** Leader id -> the run whose window was last refused for him, so a refusal is logged once per question. */
    private final Map<Integer, String> refusedFor = new ConcurrentHashMap<>();

    @Override
    public void ask(Character leader, Persona asker, String question, CompanionRunLifecycle.RerollPicks picks) {
        if (!picks.wanted()) {
            return; // on screen already, or answered or closed meanwhile
        }
        if (!LivingTalk.mayShow(leader)) {
            if (!picks.sessionId().equals(refusedFor.put(leader.getId(), picks.sessionId()))) {
                log.info("KPQ stage 1 re-roll window refused: session={} leader={} reason={}", picks.sessionId(),
                        leader.getName(), LivingTalk.awaitsReply(leader) ? "window-open" : "click-throttle");
            }
            return;
        }
        if (!picks.shown()) {
            return;
        }
        refusedFor.remove(leader.getId());
        LivingTalk.open(leader, CompanionMenus.portrait(asker))
                .menu("#e" + asker.name() + "#n\r\n" + question + "\r\n", List.of(
                        new Option(GO_AGAIN, () -> pick(leader, asker, picks, true)),
                        new Option(PUSH_THROUGH, () -> pick(leader, asker, picks, false))))
                .show();
    }

    /** The answer to the click: recorded, or, for a "go again" the run no longer takes, the companion's word on it. */
    private static void pick(Character leader, Persona asker, CompanionRunLifecycle.RerollPicks picks,
                             boolean goAgain) {
        if (!picks.pick(goAgain) && goAgain && picks.runGoesOn()) {
            leader.dropMessage(5, asker.name() + ": " + TOO_LATE);
        }
    }
}
