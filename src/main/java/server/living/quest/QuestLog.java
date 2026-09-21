package server.living.quest;

import client.Character;
import client.QuestStatus;
import client.QuestStatus.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.integration.AgentPresence;
import server.living.LivingWorld;
import server.living.config.Content.Quest;
import server.living.store.LivingStore;
import server.living.store.LivingStore.QuestState;
import server.quest.QuestTime;

/**
 * Mirrors living-world quest state into the client's quest log (plan 5.3 phase 2). Each quest
 * with an id in {@code quest-log-ids.yaml} has a Quest.wz entry shipped by MapleEdit; here the
 * matching {@link QuestStatus} is set STARTED on accept and COMPLETED on completion, straight
 * through {@link Character#updateQuestStatus} so no Check/Act data is needed. Off unless
 * {@code living_world.QUEST_LOG_MIRROR} is on, because without the Quest.wz patch the client
 * would list nameless quests.
 */
public final class QuestLog {
    private static final Logger log = LoggerFactory.getLogger(QuestLog.class);

    private final LivingWorld world;

    public QuestLog(LivingWorld world) {
        this.world = world;
    }

    private boolean off(Character chr) {
        return !world.config().QUEST_LOG_MIRROR || chr == null || AgentPresence.isAgent(chr);
    }

    public void started(Character chr, Quest quest) {
        int id = world.content().questLogId(quest.id());
        if (off(chr) || id == 0) {
            return;
        }
        set(chr, id, Status.STARTED);
    }

    public void completed(Character chr, Quest quest) {
        int id = world.content().questLogId(quest.id());
        if (off(chr) || id == 0) {
            return;
        }
        set(chr, id, Status.COMPLETED);
    }

    /** Brings the client log in line with the ledger, for quests taken before the mirror was on. */
    public void reconcile(Character chr) {
        if (off(chr)) {
            return;
        }
        for (QuestState state : world.store().questStates(chr.getId(), null)) {
            Quest quest = world.content().quest(state.questId());
            int id = quest == null ? 0 : world.content().questLogId(quest.id());
            if (id == 0) {
                continue;
            }
            Status want = state.status() == LivingStore.QuestStatus.ACTIVE ? Status.STARTED
                    : state.status() == LivingStore.QuestStatus.COMPLETED ? Status.COMPLETED : null;
            if (want == null) {
                continue;
            }
            QuestStatus current = chr.getQuest(server.quest.Quest.getInstance(id));
            if (current.getStatus() != want) {
                set(chr, id, want);
            }
        }
    }

    private void set(Character chr, int id, Status status) {
        try {
            server.quest.Quest quest = server.quest.Quest.getInstance(id);
            QuestStatus updated = new QuestStatus(quest, status, 0);
            QuestStatus previous = chr.getQuest(quest);
            updated.setForfeited(previous.getForfeited());
            updated.setCompleted(previous.getCompleted());
            if (status == Status.COMPLETED) {
                updated.setCompletionTime(QuestTime.now());
            }
            chr.updateQuestStatus(updated);
        } catch (RuntimeException failure) {
            log.warn("quest log mirror for {} quest {} failed", chr.getName(), id, failure);
        }
    }
}
