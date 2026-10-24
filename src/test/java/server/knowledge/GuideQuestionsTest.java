package server.knowledge;

import org.junit.jupiter.api.Test;
import server.knowledge.ContentQuestionDetector.Kind;
import server.knowledge.ContentQuestionDetector.Shape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The guide question shapes (docs/GUIDE_KNOWLEDGE_PLAN.md section 9): BUILD and HOW_TO, and what stays as it was. */
class GuideQuestionsTest {
    private static Shape shape(String line) {
        return ContentQuestionDetector.shape(line);
    }

    private static void build(String line, String topic, int level) {
        Shape s = shape(line);
        assertEquals(Kind.BUILD, s == null ? null : s.kind(), line);
        assertEquals(topic, s.topic(), line);
        assertEquals(level, s.level(), line);
    }

    private static void howTo(String line, String slot) {
        Shape s = shape(line);
        assertEquals(Kind.HOW_TO, s == null ? null : s.kind(), line);
        assertEquals(slot, s.slot(), line);
    }

    @Test
    void buildQuestions() {
        build("what should i put my ap in?", "ap", 0);
        build("where do i put my sp at lvl 30", "sp", 30);
        build("how much dex should a sin have", "ap", 0);
        build("whats a good bandit build", "", 0);
        build("what skills should i max first", "sp", 0);
        build("hey what should i add my ap to", "ap", 0);
    }

    @Test
    void howToQuestions() {
        howTo("how do i do the zakum prequest", "zakum");
        howTo("any tips for apq?", "apq");
        howTo("how does cpq work", "cpq");
        howTo("what do i bring to pap", "pap");
        howTo("how do i beat horntail", "horntail");
        howTo("zakum prequests", "zakum");
    }

    @Test
    void otherQuestionsKeepTheirShape() {
        assertEquals(Kind.TRAIN_WHERE, shape("where should i train at 30").kind());
        assertEquals(Kind.WHERE_IS, shape("how do i join kpq").kind());
        assertEquals(Kind.JOB_ADVANCE, shape("how do i 2nd job").kind());
        assertEquals(Kind.GET_WHERE, shape("how do i get steelies").kind());
        assertNull(shape("what are your stats"), "about the bot, not a build");
        assertNull(shape("how much int"), "the companion stats command");
        assertNull(shape("what skills do you have"), "the companion skills command");
        Shape nice = shape("nice build");
        assertNotEquals(Kind.BUILD, nice == null ? null : nice.kind(), "a statement");
    }
}
