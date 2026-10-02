package server.living.roster.ui;

import client.Character;
import server.living.config.Content.Persona;
import server.living.resident.LivingTalk;
import server.living.resident.LivingTalk.Option;
import server.living.roster.CompanionRunLifecycle;

import java.util.List;
import java.util.function.Consumer;

/**
 * A run's companion asks its leader whether to leave stage 1 and run the quest again (the KPQ re-roll, round 2b): its
 * own window, its portrait and name as on its card, its question as it just said it in chat, and two picks that need
 * no typing. A pick only records the answer; the run acts on it on its next tick. Shown on the leader's I/O thread,
 * and never over another window (LivingTalk refuses it then, and the question stands in chat).
 */
public final class RerollQuestion implements CompanionRunLifecycle.RerollWindow {
    static final String GO_AGAIN = "Let's go again.";
    static final String PUSH_THROUGH = "Nah, let's push through.";

    @Override
    public void ask(Character leader, Persona asker, String question, Consumer<Boolean> answer) {
        LivingTalk.open(leader, CompanionMenus.portrait(asker))
                .menu("#e" + asker.name() + "#n\r\n" + question + "\r\n", List.of(
                        new Option(GO_AGAIN, () -> answer.accept(true)),
                        new Option(PUSH_THROUGH, () -> answer.accept(false))))
                .show();
    }
}
