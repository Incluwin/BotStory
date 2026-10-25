package server.living.quest;

import client.Character;
import client.QuestStatus;
import client.QuestStatus.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.integration.AgentPresence;
import server.living.LivingWorld;
import server.living.config.Content.Quest;
import server.living.config.ContentLint;
import server.living.config.QuestLogTargets;
import server.living.store.LivingStore;
import server.living.store.LivingStore.QuestState;
import server.quest.QuestTime;
import tools.StringUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mirrors living-world quest state into the client's quest log (plan 5.3 phase 2). Each quest
 * with an id in {@code quest-log-ids.yaml} has a Quest.wz entry shipped by MapleEdit; here the
 * matching {@link QuestStatus} is set STARTED on accept and COMPLETED on completion, straight
 * through {@link Character#updateQuestStatus} so no Act data is needed. The ledger's kill counts
 * go along as the quest's progress string (three digits per monster, in {@link QuestLogTargets}
 * order), which the Quest Helper counts and the detail text's {@code #a} codes read; a kill sends
 * only that record, as the stock kill counter does. Off unless
 * {@code living_world.QUEST_LOG_MIRROR} is on, because without the Quest.wz patch the client
 * would list nameless quests.
 */
public final class QuestLog {
    private static final Logger log = LoggerFactory.getLogger(QuestLog.class);

    private final LivingWorld world;

    public QuestLog(LivingWorld world) {
        this.world = world;
    }

    /**
     * True for a client quest id the mirror owns (the living-world range): the stock kill counter leaves it to the
     * ledger, and only its first completion earns a quest point and the quest ring's growth.
     */
    public static boolean isMirrored(int questId) {
        return questId >= ContentLint.QUEST_LOG_ID_MIN && questId <= ContentLint.QUEST_LOG_ID_MAX;
    }

    private boolean off(Character chr) {
        return !world.config().QUEST_LOG_MIRROR || chr == null || AgentPresence.isAgent(chr);
    }

    public void started(Character chr, Quest quest) {
        int id = world.content().questLogId(quest.id());
        if (off(chr) || id == 0) {
            return;
        }
        set(chr, id, Status.STARTED, QuestLogTargets.kills(quest, 0, 0));
    }

    public void completed(Character chr, Quest quest) {
        int id = world.content().questLogId(quest.id());
        if (off(chr) || id == 0) {
            return;
        }
        set(chr, id, Status.COMPLETED, Map.of());
    }

    /** The ledger moved an active quest on: the client's kill counts follow (Quest Helper, detail pane). */
    public void progressed(Character chr, Quest quest, QuestState state) {
        int id = world.content().questLogId(quest.id());
        if (off(chr) || id == 0 || state.status() != LivingStore.QuestStatus.ACTIVE) {
            return;
        }
        Map<Integer, Integer> kills = QuestLogTargets.kills(quest, state.stepIndex(), state.progress());
        QuestStatus current = chr.getQuest(server.quest.Quest.getInstance(id));
        if (kills.isEmpty() || current.getStatus() != Status.STARTED) {
            return;
        }
        showKills(chr, id, current, kills);
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
                set(chr, id, want, want == Status.STARTED
                        ? QuestLogTargets.kills(quest, state.stepIndex(), state.progress()) : Map.of());
            } else if (want == Status.STARTED) {
                showKills(chr, id, current, QuestLogTargets.kills(quest, state.stepIndex(), state.progress()));
            }
        }
    }

    /**
     * Brings a started quest's record to these kills. The client reads it slot by slot in Check.img/1 order, so a record
     * holding the same monsters in that order only takes the new counts and one record update (no start result, as for a
     * stock kill); any other record (another order, a monster content no longer tracks) is replaced whole.
     */
    private void showKills(Character chr, int id, QuestStatus current, Map<Integer, Integer> kills) {
        Map<Integer, String> record = record(kills);
        Map<Integer, String> shown = current.getProgress();
        if (new ArrayList<>(shown.entrySet()).equals(new ArrayList<>(record.entrySet()))) {
            return;
        }
        if (!new ArrayList<>(shown.keySet()).equals(new ArrayList<>(record.keySet()))) {
            set(chr, id, Status.STARTED, kills);
            return;
        }
        try {
            record.forEach(current::setProgress);
            chr.announceUpdateQuest(Character.DelayedQuestUpdate.UPDATE, current, false);
        } catch (RuntimeException failure) {
            log.warn("quest log mirror for {} quest {} failed", chr.getName(), id, failure);
        }
    }

    /** The record the client reads: three digits per tracked monster, in Check.img/1 order. */
    private static Map<Integer, String> record(Map<Integer, Integer> kills) {
        Map<Integer, String> record = new LinkedHashMap<>();
        kills.forEach((mob, count) -> record.put(mob, digits(count)));
        return record;
    }

    private static String digits(int kills) {
        return StringUtil.getLeftPaddedStr(Integer.toString(Math.min(999, Math.max(0, kills))), '0', 3);
    }

    private void set(Character chr, int id, Status status, Map<Integer, Integer> kills) {
        try {
            server.quest.Quest quest = server.quest.Quest.getInstance(id);
            QuestStatus updated = new QuestStatus(quest, status, 0);
            record(kills).forEach(updated::setProgress); // in Check.img/1 order
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
