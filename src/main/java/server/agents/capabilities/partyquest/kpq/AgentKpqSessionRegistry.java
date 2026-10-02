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
    /** Leader id -> the stage-1 re-roll question put to him (round 2b), while it awaits his answer. */
    private static final Map<Integer, RerollQuestion> rerollQuestions = new ConcurrentHashMap<>();
    /** Session id -> its leader's answer to the re-roll question (true: go again), until the coordinator takes it. */
    private static final Map<String, Boolean> rerollAnswers = new ConcurrentHashMap<>();

    /** A re-roll question put to a leader in {@code sessionId} at {@code askedAtMs}. */
    private record RerollQuestion(String sessionId, long askedAtMs) {
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
        closeReroll(session.sessionId());
        sessions.remove(session.sessionId(), session);
        sessionByOperator.remove(session.operatorId(), session.sessionId());
        session.members().forEach(member -> sessionByMember.remove(member.characterId(), session.sessionId()));
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
     * answer is recorded. Asked before the window is shown, so no answer can arrive first.
     */
    static void askReroll(String sessionId, int leaderId, long nowMs) {
        rerollAnswers.remove(sessionId);
        rerollQuestions.put(leaderId, new RerollQuestion(sessionId, nowMs));
    }

    /**
     * The leader answered the re-roll question in {@code sessionId} (true: go again): on whatever thread the answer
     * came, his I/O thread for the window. It is only recorded, the first answer only, and only while the question is
     * still out; the coordinator acts on it on its next tick. Never takes a session's lock.
     *
     * @return true when the answer was recorded
     */
    public static boolean answerReroll(String sessionId, int leaderId, boolean goAgain) {
        RerollQuestion question = rerollQuestions.get(leaderId);
        if (sessionId == null || question == null || !question.sessionId().equals(sessionId)) return false;
        return rerollAnswers.putIfAbsent(sessionId, goAgain) == null;
    }

    /**
     * A typed reply in party or map chat, on the speaker's I/O thread: recorded as his answer when a re-roll question
     * awaits him and the whole line is one ("yes", "ok", "go again" / "no", "nah", "push through"). Costs one map
     * lookup for anyone else.
     *
     * @return true when the line was his answer
     */
    public static boolean answerRerollInChat(int speakerId, String message) {
        RerollQuestion question = rerollQuestions.get(speakerId);
        if (question == null) return false;
        Boolean goAgain = AgentKpqRerollPolicy.typedAnswer(message);
        return goAgain != null && answerReroll(question.sessionId(), speakerId, goAgain);
    }

    /** The leader's answer to the session's re-roll question, taken once; null while none came. */
    static Boolean takeRerollAnswer(String sessionId) {
        return sessionId == null ? null : rerollAnswers.remove(sessionId);
    }

    /** The session's re-roll question is over (answered, let pass, or the run ended): later answers are ignored. */
    static void closeReroll(String sessionId) {
        if (sessionId == null) return;
        rerollQuestions.values().removeIf(question -> question.sessionId().equals(sessionId));
        rerollAnswers.remove(sessionId);
    }

    /** True while a re-roll question of this session awaits its leader. */
    static boolean rerollAsked(String sessionId) {
        return sessionId != null && rerollQuestions.values().stream()
                .anyMatch(question -> question.sessionId().equals(sessionId));
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
