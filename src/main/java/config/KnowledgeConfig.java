package config;

/**
 * Server knowledge settings: the WZ-derived knowledge file plus the live drop/shop overlay that lets
 * bots answer content questions (who drops X, where is Y, how do I get to Z) truthfully for this
 * server. Defaults mean "on", so a missing {@code knowledge:} block keeps the feature running. See
 * docs/SERVER_KNOWLEDGE_PLAN.md.
 */
public class KnowledgeConfig {
    /** Master switch. Off: nothing is loaded and every host path keeps today's behaviour. */
    public boolean ENABLED = true;
    /** Directory with server-knowledge.json (tools\knowledge-export.bat), travel.yaml and aliases.yaml. */
    public String CONTENT_DIR = "scripts/config/knowledge";
    /** Citizens answer content questions from the knowledge layer. */
    public boolean CITIZENS = true;
    /** Companions answer content questions from the knowledge layer (and skip the chat-intent Jev call). */
    public boolean COMPANIONS = true;
    /** Residents answer content questions from the knowledge layer. */
    public boolean RESIDENTS = true;
    /** A bot knows an entity whose level is at most its own level plus this many levels. */
    public int HORIZON_LEVEL_SLACK = 10;
    /** Longest deterministic reply, in characters (residents use their bubble limit instead). */
    public int MAX_REPLY_CHARS = 70;
    /** Log content questions whose slot did not resolve (the to-do list for aliases.yaml). */
    public boolean LOG_UNRESOLVED = true;
    /** Bots answer from what they have lived and heard (plan 7); off: the level horizon only, nothing recorded. */
    public boolean PERSONAL = true;
    /** At or above: exact drop chances and rate numbers in replies. */
    public int VETERAN_LEVEL = 70;
    /** Bots act on server knowledge: FARM_ITEM sites and the honest bench ledger, healer fields, off-Victoria travel times. */
    public boolean DECISIONS = true;
    /** Generated citizens wear obtainable gear fitting their level and job (gear already given stays when this is off). */
    public boolean CITIZEN_GEAR = true;

    public void validate() {
        if (CONTENT_DIR == null || CONTENT_DIR.isBlank()) {
            throw new IllegalArgumentException("knowledge.CONTENT_DIR must name a directory");
        }
        if (HORIZON_LEVEL_SLACK < 0 || MAX_REPLY_CHARS < 1) {
            throw new IllegalArgumentException("knowledge.HORIZON_LEVEL_SLACK must be >= 0 and MAX_REPLY_CHARS >= 1");
        }
        if (VETERAN_LEVEL < 1 || VETERAN_LEVEL > 200) {
            throw new IllegalArgumentException("knowledge.VETERAN_LEVEL must be in 1..200");
        }
    }
}
