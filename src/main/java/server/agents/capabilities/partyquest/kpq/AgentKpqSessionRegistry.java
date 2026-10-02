package server.agents.capabilities.partyquest.kpq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Indexes active KPQ sessions by session, operator, and member without touching Agent entries. */
public final class AgentKpqSessionRegistry {
    private static final Logger log = LoggerFactory.getLogger(AgentKpqSessionRegistry.class);
    private static final Map<String, AgentKpqSession> sessions = new ConcurrentHashMap<>();
    private static final Map<Integer, String> sessionByMember = new ConcurrentHashMap<>();
    private static final Map<Integer, String> sessionByOperator = new ConcurrentHashMap<>();
    /**
     * Leader id -> the stage-1 re-roll question put to him (round 2b), with his answer once he gives it, until the
     * coordinator takes the answer or the question is closed. One entry holds both, replaced whole under the map's
     * per-key lock, so an answer can never outlive its question.
     */
    private static final Map<Integer, RerollQuestion> rerollQuestions = new ConcurrentHashMap<>();

    /**
     * A re-roll question put to a leader in {@code sessionId} at {@code askedAtMs}: {@code oneWordCounts} lets a bare yes
     * or no typed in chat answer it (nothing else was just asked of him), {@code windowShown} once the companion's window
     * went on his screen, and {@code answer} his answer (true: go again), null until he gives one.
     */
    private record RerollQuestion(String sessionId, long askedAtMs, boolean oneWordCounts, boolean windowShown,
                                  Boolean answer) {
        RerollQuestion answered(boolean goAgain) {
            return new RerollQuestion(sessionId, askedAtMs, oneWordCounts, windowShown, goAgain);
        }

        RerollQuestion shown() {
            return new RerollQuestion(sessionId, askedAtMs, oneWordCounts, true, answer);
        }

        boolean open(String session) {
            return sessionId.equals(session) && answer == null;
        }
    }

    private AgentKpqSessionRegistry() {
    }

    /** Atomically publishes a fully constructed session and all of its member indexes. */
    public static synchronized void registerComplete(AgentKpqSession session) {
        if (session == null) throw new IllegalArgumentException("KPQ session is required");
        if (sessionByOperator.containsKey(session.operatorId())) {
            throw new IllegalStateException("Operator already owns a KPQ session");
        }
        for (AgentKpqMemberState member : session.members()) {
            String existing = sessionByMember.get(member.characterId());
            if (existing != null && !existing.equals(session.sessionId())) {
                throw new IllegalStateException("Party member already belongs to another KPQ session");
            }
        }
        sessions.put(session.sessionId(), session);
        sessionByOperator.put(session.operatorId(), session.sessionId());
        session.members().forEach(member ->
                sessionByMember.put(member.characterId(), session.sessionId()));
    }

    public static synchronized String registrationBlocker(
            int operatorId, Collection<Integer> characterIds) {
        if (sessionByOperator.containsKey(operatorId)) return "operator-session";
        if (characterIds != null) {
            for (int characterId : characterIds) {
                if (sessionByMember.containsKey(characterId)) return "member-" + characterId;
            }
        }
        return "";
    }

    public static AgentKpqSession forMember(int characterId) {
        String sessionId = sessionByMember.get(characterId);
        return sessionId == null ? null : sessions.get(sessionId);
    }

    public static AgentKpqSession forOperator(int operatorId) {
        String sessionId = sessionByOperator.get(operatorId);
        return sessionId == null ? null : sessions.get(sessionId);
    }

    public static Collection<AgentKpqSession> sessions() {
        return java.util.List.copyOf(sessions.values());
    }

    public static synchronized void remove(AgentKpqSession session) {
        if (session == null) return;
        sessions.remove(session.sessionId(), session);
        sessionByOperator.remove(session.operatorId(), session.sessionId());
        session.members().forEach(member -> sessionByMember.remove(member.characterId(), session.sessionId()));
        // after the session: a pick that finds the question closed never finds its run still going (runGoesOn)
        closeReroll(session.sessionId());
    }

    /** True while the session is registered: its run goes on. A lookup only, never the session's lock. */
    public static boolean runGoesOn(String sessionId) {
        return sessionId != null && sessions.containsKey(sessionId);
    }

    public static synchronized void unindexMember(AgentKpqSession session, int characterId) {
        if (session != null) sessionByMember.remove(characterId, session.sessionId());
    }

    public static boolean active(int characterId) {
        return forMember(characterId) != null;
    }

    public static boolean canLootCoupon(int characterId) {
        AgentKpqSession session = forMember(characterId);
        AgentKpqMemberState member = session == null ? null : session.member(characterId);
        return member != null && member.role() == AgentKpqMemberState.Role.COUPON_COLLECTOR;
    }

    /** The leader, and in a companion run the stage-5 carriers, who bring the passes up to a human leader. */
    public static boolean canLootPass(int characterId) {
        AgentKpqSession session = forMember(characterId);
        AgentKpqMemberState member = session == null ? null : session.member(characterId);
        return member != null && !session.stage5LootDelayActive(System.currentTimeMillis())
                && (member.role() == AgentKpqMemberState.Role.EVENT_LEADER
                        || member.role() == AgentKpqMemberState.Role.STAGE5_PASS_COLLECTOR
                        || member.role() == AgentKpqMemberState.Role.STAGE5_PASS_CARRIER);
    }

    public static boolean canLootSquishyShoes(int characterId) {
        AgentKpqSession session = forMember(characterId);
        return session != null && !session.stage5LootDelayActive(System.currentTimeMillis())
                && session.squishyShoesWinnerId() == characterId;
    }

    /**
     * Called by Cloto's ordinary NPC script after a human event leader checks a puzzle formation. In a companion run
     * where everyone stands is read here, on the script thread, while she judges.
     */
    public static void recordHumanPuzzleValidation(int characterId, int stage, boolean accepted) {
        AgentKpqSession session = forMember(characterId);
        AgentKpqMemberState member = session == null ? null : session.member(characterId);
        if (session == null || session.eventLeaderId() != characterId || member == null
                || member.memberType() != AgentKpqMemberState.MemberType.HUMAN) {
            return;
        }
        String verdict;
        int attempt;
        synchronized (session) {
            boolean placementHeld = !accepted && session.companionAdopted()
                    && AgentKpqCoordinator.formationStandsAsAssigned(session, stage);
            verdict = session.recordHumanPuzzleValidation(stage, accepted, placementHeld);
            attempt = session.attemptIndex();
        }
        log.info("KPQ human puzzle verdict: session={} stage={} attempt={} verdict={} companionRun={}",
                session.sessionId(), stage, attempt, verdict, session.companionAdopted());
    }

    /**
     * The coordinator put the re-roll question to {@code leaderId} (on its tick): from now his window's or typed
     * answer is recorded. Asked before the window is offered, so no answer can arrive first. {@code oneWordCounts}: a
     * bare yes or no he types answers it too (no other companion line asked him something just before).
     */
    static void askReroll(String sessionId, int leaderId, long nowMs, boolean oneWordCounts) {
        rerollQuestions.put(leaderId, new RerollQuestion(sessionId, nowMs, oneWordCounts, false, null));
    }

    /**
     * The leader answered the re-roll question in {@code sessionId} (true: go again): on whatever thread the answer
     * came, his I/O thread for the window. It is only recorded, the first answer only, and only while the question is
     * still out; the coordinator acts on it on its next tick. Atomic with the question's close: an answer is kept in
     * the question's own entry, so one that comes as the question closes is refused rather than left behind. Never
     * takes a session's lock.
     *
     * @return true when the answer was recorded
     */
    public static boolean answerReroll(String sessionId, int leaderId, boolean goAgain) {
        if (sessionId == null) return false;
        boolean[] recorded = {false};
        rerollQuestions.computeIfPresent(leaderId, (id, question) -> {
            if (!question.open(sessionId)) return question;
            recorded[0] = true;
            return question.answered(goAgain);
        });
        return recorded[0];
    }

    /**
     * A typed reply in party or map chat, on the speaker's I/O thread: recorded as his answer when a re-roll question
     * awaits him and the whole line is one ("go again", "push through"; a bare "ok" or "nah" only while nothing else
     * was just asked of him). Costs one map lookup for anyone else.
     *
     * @return true when the line was his answer
     */
    public static boolean answerRerollInChat(int speakerId, String message) {
        RerollQuestion question = rerollQuestions.get(speakerId);
        if (question == null) return false;
        Boolean goAgain = AgentKpqRerollPolicy.typedAnswer(message, question.oneWordCounts());
        return goAgain != null && answerReroll(question.sessionId(), speakerId, goAgain);
    }

    /**
     * The leader's answer to the session's re-roll question, taken once: an answered question is over, and leaves
     * with its answer. Null while none came (the question stays out).
     */
    static Boolean takeRerollAnswer(String sessionId, int leaderId) {
        if (sessionId == null) return null;
        Boolean[] taken = {null};
        rerollQuestions.computeIfPresent(leaderId, (id, question) -> {
            if (!question.sessionId().equals(sessionId) || question.answer() == null) return question;
            taken[0] = question.answer();
            return null;
        });
        return taken[0];
    }

    /**
     * The session's re-roll question is over (answered, let pass, or the run ended): later answers are refused. Each
     * entry goes under its own key's lock, answered or not.
     */
    static void closeReroll(String sessionId) {
        if (sessionId == null) return;
        for (Integer leaderId : rerollQuestions.keySet()) {
            rerollQuestions.computeIfPresent(leaderId,
                    (id, question) -> question.sessionId().equals(sessionId) ? null : question);
        }
    }

    /** True while a re-roll question of this session awaits its leader. */
    static boolean rerollAsked(String sessionId) {
        return sessionId != null && rerollQuestions.values().stream()
                .anyMatch(question -> question.sessionId().equals(sessionId));
    }

    /**
     * On the leader's I/O thread, before the companion's window is drawn: true while the question is out, unanswered,
     * and its window not on screen yet. A lookup only.
     */
    public static boolean rerollWindowWanted(String sessionId, int leaderId) {
        RerollQuestion question = sessionId == null ? null : rerollQuestions.get(leaderId);
        return question != null && question.open(sessionId) && !question.windowShown();
    }

    /**
     * On the leader's I/O thread: the companion's window goes on his screen now, once per question; the coordinator
     * times the answer from it at its next tick.
     *
     * @return true when this call marked it (false: answered or closed meanwhile, or already shown)
     */
    public static boolean markRerollWindowShown(String sessionId, int leaderId) {
        if (sessionId == null) return false;
        boolean[] marked = {false};
        rerollQuestions.computeIfPresent(leaderId, (id, question) -> {
            if (!question.open(sessionId) || question.windowShown()) return question;
            marked[0] = true;
            return question.shown();
        });
        return marked[0];
    }

    /** True once the companion's window for the session's question went on the leader's screen. */
    static boolean rerollWindowShown(String sessionId, int leaderId) {
        RerollQuestion question = sessionId == null ? null : rerollQuestions.get(leaderId);
        return question != null && question.sessionId().equals(sessionId) && question.windowShown();
    }

    /** Tests only: the re-roll questions out, answered or not. */
    static int rerollQuestionsOut() {
        return rerollQuestions.size();
    }

    public static boolean isManagedEvent(Character character) {
        return managedSession(character) != null;
    }

    public static boolean isRegisteredParticipant(Character character) {
        AgentKpqSession session = managedSession(character);
        return character != null && session != null && session.member(character.getId()) != null;
    }

    public static boolean beginRewardClaim(Character character) {
        AgentKpqSession session = managedSession(character);
        if (character != null && session != null && !session.rewardEligibilityFrozen()
                && character.getEventInstance().isEventCleared()) {
            session.freezeRewardEligibility();
        }
        return character != null && session != null && session.beginRewardClaim(character.getId());
    }

    public static boolean completeRewardClaim(Character character) {
        AgentKpqSession session = managedSession(character);
        return character != null && session != null && session.completeRewardClaim(character.getId());
    }

    public static void cancelRewardClaim(Character character) {
        AgentKpqSession session = managedSession(character);
        if (character != null && session != null) session.cancelRewardClaim(character.getId());
    }

    public static void forfeitUnclaimedReward(Character character) {
        AgentKpqSession session = character == null ? null : forMember(character.getId());
        if (session != null && session.eventInstance() == character.getEventInstance()) {
            session.forfeitReward(character.getId());
        }
    }

    private static AgentKpqSession managedSession(Character character) {
        if (character == null || character.getEventInstance() == null) return null;
        return sessions.values().stream()
                .filter(session -> session.eventInstance() == character.getEventInstance())
                .findFirst().orElse(null);
    }
}
