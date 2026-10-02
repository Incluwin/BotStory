package client.command.commands.gm5;

import client.Character;
import client.Client;
import client.command.Command;
import server.knowledge.ContentAnswers;
import server.knowledge.KnowledgeLoader;
import server.knowledge.KnownWorld;
import server.knowledge.NameIndex;
import server.knowledge.PersonalKnowledge;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code !knowledge} operates the server knowledge layer (docs/SERVER_KNOWLEDGE_PLAN.md 5.5): {@code status}
 * (data version, staleness, sizes, last overlay rebuild, question counters), {@code reload} (re-read the knowledge
 * file, travel.yaml, aliases.yaml and the DB overlay), or a chat line to dry-run as a bot would hear it from you
 * here: the detected shape, resolved entities with scores, the facts and the reply, without anyone speaking.
 * {@code @<level>} in front sets the answering bot's level (default 200: knows everything); {@code @<name>} answers as
 * that online character, with its known world (plan 7.8). {@code known <name>} prints a character's known world.
 */
public class KnowledgeCommand extends Command {
    private static final int ALL_KNOWING = 200;
    private static final int CANDIDATES_SHOWN = 3;

    {
        setDescription("Server knowledge. Usage: !knowledge status|reload|known <name>|[@<botLevel>|@<name>] <question line>");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character gm = c.getPlayer();
        if (params.length == 0) {
            gm.yellowMessage(getDescription());
            return;
        }
        switch (params[0].toLowerCase(Locale.ROOT)) {
            case "status" -> {
                gm.yellowMessage(KnowledgeLoader.status());
                gm.yellowMessage(ContentAnswers.counters());
                gm.yellowMessage(PersonalKnowledge.shared().status());
            }
            case "known" -> known(c, gm, params);
            case "reload" -> {
                gm.yellowMessage(KnowledgeLoader.reload()
                        ? "Knowledge reload queued (file, travel.yaml, aliases.yaml, overlay); check !knowledge status."
                        : "Knowledge is disabled (config.yaml knowledge.ENABLED); nothing to reload.");
            }
            default -> dryRun(c, gm, params);
        }
    }

    private void known(Client c, Character gm, String[] params) {
        Character who = params.length < 2 ? null : c.getChannelServer().getPlayerStorage().getCharacterByName(params[1]);
        if (who == null) {
            gm.yellowMessage("Usage: !knowledge known <name of a character online on this channel>");
            return;
        }
        for (String line : PersonalKnowledge.shared().describe(who.getId())) {
            gm.yellowMessage(who.getName() + ": " + line);
        }
    }

    private void dryRun(Client c, Character gm, String[] params) {
        int botLevel = ALL_KNOWING;
        int botId = 0;
        KnownWorld known = null;
        int start = 0;
        if (params[0].startsWith("@")) {
            String as = params[0].substring(1);
            try {
                botLevel = Integer.parseInt(as);
            } catch (NumberFormatException notLevel) {
                Character bot = c.getChannelServer().getPlayerStorage().getCharacterByName(as);
                if (bot == null) {
                    gm.yellowMessage("Usage: !knowledge @<botLevel>|@<name online on this channel> <question line>");
                    return;
                }
                botId = bot.getId();
                botLevel = bot.getLevel();
                known = PersonalKnowledge.shared().known(botId);
            }
            start = 1;
        }
        String line = joinStringFrom(params, start);
        ContentAnswers.Ask ask = new ContentAnswers.Ask(line,
                new ContentAnswers.Asker(gm.getId(), gm.getName(), gm.getMapId(), gm.getLevel(), gm.getJob().getId(), gm.getWorld()),
                new ContentAnswers.Responder(botId, botLevel, null, "friendly-casual-v1", Set.of(), true, known),
                name -> !name.equalsIgnoreCase(gm.getName()) && c.getChannelServer().getPlayerStorage().getCharacterByName(name) != null,
                System.currentTimeMillis());
        ContentAnswers.ContentAnswer answer = ContentAnswers.shared().explain(ask);
        gm.yellowMessage("[" + answer.outcome() + "] kind " + answer.kind() + (answer.shape() == null ? ""
                : ", slot '" + answer.shape().slot() + "'" + (answer.shape().from().isEmpty() ? "" : " from '" + answer.shape().from() + "'")
                + (answer.shape().level() > 0 ? ", level " + answer.shape().level() : ""))
                + (answer.subject() == null ? "" : ", subject " + answer.subject().kind() + " " + answer.subject().id()
                + " '" + answer.subject().say() + "'"));
        if (answer.resolution() != null) {
            StringBuilder candidates = new StringBuilder("resolved '" + answer.resolution().text() + "'"
                    + (answer.resolution().tie() ? " (tie)" : "") + ":");
            int shown = 0;
            for (NameIndex.Candidate candidate : answer.resolution().candidates()) {
                if (shown++ == CANDIDATES_SHOWN) {
                    break;
                }
                candidates.append(' ').append(candidate.entity().kind()).append(' ').append(candidate.entity().id()).append(" '")
                        .append(candidate.entity().name()).append("' ").append(String.format(Locale.ROOT, "%.2f", candidate.score()))
                        .append(' ').append(candidate.how()).append(';');
            }
            gm.yellowMessage(candidates.toString());
        }
        for (Map.Entry<String, String> fact : answer.facts().entrySet()) {
            gm.yellowMessage(fact.getKey() + ": " + fact.getValue());
        }
        gm.yellowMessage("reply: " + (answer.reply() == null ? "(none)" : answer.reply()));
    }
}
