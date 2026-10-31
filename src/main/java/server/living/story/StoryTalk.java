package server.living.story;

import client.Character;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.config.Content.StoryChoice;
import server.living.config.Content.StoryOption;
import server.living.resident.LivingTalk;
import server.living.resident.ResidentDialogue;

import java.util.List;

/**
 * A chapter's conversation (F6.1) in the companion's NPC window: its pages, then the player's choice of reply when it
 * has one, then the companion's answer, and its last page has one option that starts the chapter. LivingTalk has one
 * portrait per window and an OK window has no way on, so every hand-over to the next window is a menu option: each one
 * opens as the answer to the player's click, never over another window.
 */
final class StoryTalk {
    static final String GO_ON = "Go on.";
    static final String LETS_GO = "Let's go.";

    private StoryTalk() {
    }

    /**
     * Shows {@code talk}, then {@code choice} (its pick sets the option's bond flag and shows the reply), then runs
     * {@code start}; {@code last} labels the final option ({@link #GO_ON}, or {@link #LETS_GO} before a scene).
     */
    static void open(LivingWorld world, Character owner, Persona persona, List<String> talk, StoryChoice choice,
                     String last, Runnable start) {
        if (choice == null || choice.options().isEmpty()) {
            pages(owner, persona, talk, last, start);
            return;
        }
        LivingTalk window = LivingTalk.open(owner, ResidentDialogue.portraitOf(persona));
        for (String page : talk) {
            window.say(page);
        }
        List<LivingTalk.Option> options = choice.options().stream().map(option -> new LivingTalk.Option(option.label(),
                () -> picked(world, owner, persona, option, last, start))).toList();
        window.menu(choice.prompt().isBlank() ? "..." : choice.prompt(), options).show();
    }

    private static void picked(LivingWorld world, Character owner, Persona persona, StoryOption option, String last,
                               Runnable start) {
        if (option.flag() != null) {
            world.bonds().setFlag(owner.getId(), persona.id(), option.flag());
        }
        pages(owner, persona, option.reply(), last, start);
    }

    /** The pages, the last one under a single option that starts the chapter; none: it starts at once. */
    private static void pages(Character owner, Persona persona, List<String> pages, String last, Runnable start) {
        if (pages.isEmpty()) {
            start.run();
            return;
        }
        LivingTalk window = LivingTalk.open(owner, ResidentDialogue.portraitOf(persona));
        for (String page : pages.subList(0, pages.size() - 1)) {
            window.say(page);
        }
        window.menu(pages.get(pages.size() - 1), List.of(new LivingTalk.Option(last, start))).show();
    }
}
