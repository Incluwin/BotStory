package server.agents.capabilities.build;

import client.Character;
import client.Skill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.build.profiles.AgentSpBuildProfileService;
import server.agents.capabilities.dialogue.AgentDialogueCatalog;
import server.agents.capabilities.dialogue.AgentDialogueSelector;
import server.agents.commands.AgentReplyChannel;
import server.agents.integration.AgentDialogueTransportRuntime;
import server.agents.integration.SkillGateway;
import server.agents.runtime.AgentRuntimeEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;

/**
 * What an owned companion tells its owner about its skill and mastery books (Orrin, Lv200 Paladin, 2026-10-16: his
 * Blast 20 book failed its roll among eighteen books he read in two minutes on 2026-10-14; nobody knew, and Pink Bean
 * found him at Blast 10 of 30 with 108 SP waiting and his Blast 30 book unreadable in his bag). When a look through its
 * Use tab reads no book, its reading is done (AgentMasteryBooks.look) and it says one line:
 * <ul>
 *     <li>the books that failed since its last line, for skills its build plans above their master level
 *     (AgentSpBuildProfileService.plannedLevel): it asks for another;</li>
 *     <li>else the books it carries that wait on an earlier one (AgentMasteryBooks.waitingSkills) and that it has not
 *     named this session: it needs the earlier one first.</li>
 * </ul>
 * At most {@link #MAX_NAMED} skills a line; the rest wait for its next look. No line says a book's 20 or 30 (companion
 * speech carries no digits). The Agent layer stays owner-free: the host installs the {@link Rule} naming an owned
 * companion out with its owner in their party; with nothing installed nobody speaks. Said in party chat, and logged.
 */
public final class AgentBookSpeech {
    private static final Logger log = LoggerFactory.getLogger(AgentBookSpeech.class);
    /** The most skills one line names. */
    static final int MAX_NAMED = 3;

    /** The host's answer to who says it. */
    public interface Rule {
        /** True for an owned companion out with its owner, in its owner's party. */
        boolean speaksOfBooks(Character agent);
    }

    private static volatile Rule rule;

    private AgentBookSpeech() {
    }

    /** Installs the host's rule (null: nobody speaks). */
    public static void install(Rule installed) {
        rule = installed;
    }

    /** True when the installed rule names this Agent an owned companion out with its owner in their party. */
    public static boolean speaksOfBooks(Character agent) {
        Rule current = rule;
        return current != null && agent != null && current.speaksOfBooks(agent);
    }

    /** AgentMasteryBooks.look read no book: an owned companion out with its owner says what its books need. */
    static void afterReading(AgentRuntimeEntry entry, Character agent, SkillGateway skills,
                             AgentMasteryBooks.BookStats stats) {
        if (entry == null || !speaksOfBooks(agent)) {
            return;
        }
        String line = nextLine(entry.buildState(), agent, skills,
                () -> AgentMasteryBooks.waitingSkills(agent, skills, stats),
                skillId -> AgentSpBuildProfileService.plannedLevel(entry, agent, skillId));
        if (line != null) {
            log.info("companion {} says book: {}", agent.getName(), line);
            AgentDialogueTransportRuntime.sayNow(agent, AgentReplyChannel.PARTY, line);
        }
    }

    /**
     * The line {@code agent} says now, or null, and what it names is kept in {@code state}. A failed book's skill is
     * named once, and dropped unnamed when its build does not plan it above its master level ({@code planned}: the
     * planned level of a skill id); a waiting book's skill is named once a session, and not after its failed book was.
     */
    static String nextLine(AgentBuildState state, Character agent, SkillGateway skills,
                           Supplier<Set<Integer>> waiting, IntUnaryOperator planned) {
        List<Integer> failed = wanted(state.failedBookSkills(), agent, skills, planned);
        state.failedBookSkills().retainAll(failed);
        boolean ofFailed = !failed.isEmpty();
        List<Integer> candidates = failed;
        if (!ofFailed) {
            candidates = wanted(waiting.get(), agent, skills, planned);
            candidates.removeAll(state.bookSkillsNamed());
        }
        if (candidates.isEmpty()) {
            return null;
        }
        List<Integer> named = List.copyOf(candidates.subList(0, Math.min(MAX_NAMED, candidates.size())));
        state.failedBookSkills().removeAll(named);
        state.bookSkillsNamed().addAll(named);
        boolean many = named.size() > 1;
        List<String> templates = ofFailed ? AgentDialogueCatalog.bookFailedTemplates(many)
                : AgentDialogueCatalog.bookWaitingTemplates(many);
        return String.format(AgentDialogueSelector.randomReply(templates),
                joined(named.stream().map(skillId -> name(skills.getSkillName(skillId))).toList()));
    }

    /** The skills among {@code skillIds} its build plans above their master level, each with a name to say. */
    private static List<Integer> wanted(Set<Integer> skillIds, Character agent, SkillGateway skills,
                                        IntUnaryOperator planned) {
        List<Integer> wanted = new ArrayList<>();
        for (int skillId : skillIds) {
            Skill skill = skills.getSkill(skillId);
            if (skill != null && planned.applyAsInt(skillId) > agent.getMasterLevel(skill)
                    && !name(skills.getSkillName(skillId)).isEmpty()) {
                wanted.add(skillId);
            }
        }
        return wanted;
    }

    /** A skill's name as a player types it: lower case, no weapon ("Holy Charge : Sword" is "holy charge"). */
    static String name(String skillName) {
        if (skillName == null) {
            return "";
        }
        int colon = skillName.indexOf(':');
        return (colon < 0 ? skillName : skillName.substring(0, colon)).trim().toLowerCase(Locale.ROOT);
    }

    /** "blast", "blast and rush", "blast, rush and power stance". */
    static String joined(List<String> names) {
        if (names.size() == 1) {
            return names.get(0);
        }
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
    }
}
