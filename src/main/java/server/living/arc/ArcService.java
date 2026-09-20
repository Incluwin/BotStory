package server.living.arc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.living.LivingWorld;
import server.living.config.Content.Arc;
import server.living.config.Content.Chapter;
import server.living.config.Content.ChapterGate;
import server.living.config.Content.Persona;
import server.living.store.LivingStore;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * World-level story state: which chapter each Persona's arc is in, what that unlocks, and the
 * automatic transitions driven by world flags, rumors and time fought beside players.
 */
public final class ArcService {
    private static final Logger log = LoggerFactory.getLogger(ArcService.class);

    private final LivingWorld world;
    private final Map<String, String> chapterByPersona = new ConcurrentHashMap<>();

    public ArcService(LivingWorld world) {
        this.world = world;
    }

    public void loadState() {
        chapterByPersona.clear();
        for (Map.Entry<String, LivingStore.PersonaState> entry : world.store().allPersonaStates().entrySet()) {
            if (entry.getValue().arcChapter() != null) {
                chapterByPersona.put(entry.getKey(), entry.getValue().arcChapter());
            }
        }
    }

    public Arc arcOf(Persona persona) {
        return persona == null ? null : world.content().arc(persona.arc());
    }

    /** The chapter the Persona is in; the first chapter when nothing is stored; null without an arc. */
    public Chapter currentChapter(Persona persona) {
        Arc arc = arcOf(persona);
        if (arc == null) {
            return null;
        }
        String stored = chapterByPersona.get(persona.id());
        Chapter chapter = stored == null ? null : arc.chapter(stored);
        return chapter == null ? arc.chapters().get(0) : chapter;
    }

    public int currentChapterIndex(Persona persona) {
        Arc arc = arcOf(persona);
        Chapter chapter = currentChapter(persona);
        return arc == null || chapter == null ? -1 : arc.indexOf(chapter.id());
    }

    public boolean chapterReached(Persona persona, String arcId, String chapterId) {
        Arc arc = world.content().arc(arcId);
        if (arc == null || persona == null || !arc.id().equals(persona.arc())) {
            return false;
        }
        int wanted = arc.indexOf(chapterId);
        return wanted >= 0 && currentChapterIndex(persona) >= wanted;
    }

    public boolean chapterReached(String arcId, String chapterId) {
        Arc arc = world.content().arc(arcId);
        if (arc == null) {
            return false;
        }
        Persona persona = world.content().persona(arc.persona());
        return chapterReached(persona, arcId, chapterId);
    }

    /** Moves the arc to a chapter (forwards only unless {@code force}). */
    public boolean advance(Persona persona, String chapterId, String reason, boolean force) {
        Arc arc = arcOf(persona);
        if (arc == null) {
            return false;
        }
        int target = arc.indexOf(chapterId);
        if (target < 0) {
            return false;
        }
        int current = currentChapterIndex(persona);
        if (!force && target <= current) {
            return false;
        }
        chapterByPersona.put(persona.id(), chapterId);
        LivingStore.PersonaState state = world.store().personaState(persona.id());
        world.store().savePersonaState(state.withChapter(chapterId));
        world.store().event(0, persona.id(), "arc.advance", arc.id() + " -> " + chapterId + " (" + reason + ")");
        log.info("Arc {}: {} -> chapter {} ({})", arc.id(), persona.name(), chapterId, reason);
        Chapter chapter = arc.chapter(chapterId);
        List<String> announce = chapter.dialogueOverrides().get("chapterEnter");
        if (announce != null && !announce.isEmpty()) {
            world.residents().say(persona, announce.get(Math.floorMod((int) (System.currentTimeMillis() / 1000L), announce.size())));
        }
        return true;
    }

    /** Checks every arc's next chapter gate; called periodically and after flags change. */
    public void evaluateGates() {
        Set<String> worldFlags = world.store().worldFlags();
        for (Arc arc : world.content().arcs().values()) {
            Persona persona = world.content().persona(arc.persona());
            if (persona == null) {
                continue;
            }
            int current = currentChapterIndex(persona);
            if (current < 0 || current + 1 >= arc.chapters().size()) {
                continue;
            }
            Chapter next = arc.chapters().get(current + 1);
            if (gateOpen(persona, next.enterWhen(), worldFlags)) {
                advance(persona, next.id(), "gate satisfied", false);
            }
        }
    }

    private boolean gateOpen(Persona persona, ChapterGate gate, Set<String> worldFlags) {
        if (gate == null) {
            return false;
        }
        for (String flag : gate.flags()) {
            if (!worldFlags.contains(flag)) {
                return false;
            }
        }
        if (gate.rumorPerformed() != null
                && world.rumors().performances(gate.rumorPerformed()) < Math.max(1, gate.rumorTimes())) {
            return false;
        }
        if (gate.adventurerHoursTotal() > 0
                && world.bonds().partyHoursTotal(persona.id()) < gate.adventurerHoursTotal()) {
            return false;
        }
        return true;
    }

    // ---- what the current chapter allows -------------------------------------------------

    public boolean recruitable(Persona persona) {
        Arc arc = arcOf(persona);
        boolean recruitable = persona.recruitable();
        if (arc == null) {
            return recruitable;
        }
        int current = currentChapterIndex(persona);
        for (int index = 0; index <= current && index < arc.chapters().size(); index++) {
            Boolean override = arc.chapters().get(index).recruitable();
            if (override != null) {
                recruitable = override;
            }
        }
        return recruitable;
    }

    /** A quest listed in a chapter's unlocks is available only once that chapter is reached. */
    public boolean questUnlocked(Persona persona, String questId) {
        return unlocked(persona, questId, true);
    }

    public boolean rumorUnlocked(Persona persona, String rumorId) {
        return unlocked(persona, rumorId, false);
    }

    private boolean unlocked(Persona persona, String id, boolean quest) {
        Arc arc = arcOf(persona);
        if (arc == null) {
            return true;
        }
        int current = currentChapterIndex(persona);
        for (int index = 0; index < arc.chapters().size(); index++) {
            Chapter chapter = arc.chapters().get(index);
            List<String> unlocks = quest ? chapter.unlockQuestLines() : chapter.unlockRumors();
            if (unlocks.contains(id)) {
                return index <= current;
            }
        }
        return true;
    }

    public boolean contractUnlocked(Persona persona) {
        Arc arc = arcOf(persona);
        if (arc == null || persona.contract() == null) {
            return true;
        }
        int current = currentChapterIndex(persona);
        for (int index = 0; index < arc.chapters().size(); index++) {
            if (persona.contract().equals(arc.chapters().get(index).unlockContract())) {
                return index <= current;
            }
        }
        return true;
    }

    /** Dialogue for an intent: chapter override, then the persona's own lines. */
    public List<String> lines(Persona persona, String intent) {
        Chapter chapter = currentChapter(persona);
        if (chapter != null) {
            List<String> override = chapter.dialogueOverrides().get(intent);
            if (override != null && !override.isEmpty()) {
                return override;
            }
        }
        return persona.lines(intent);
    }
}
