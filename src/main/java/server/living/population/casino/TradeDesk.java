package server.living.population.casino;

import client.Character;
import server.Trade;
import server.Trade.TradeResult;

/**
 * A citizen taking a fee through the trade window, as a player selling a service does (the drop game's entry, an NX
 * code): it accepts a player's invite, reads the mesos they put in, and confirms only for the right amount. The
 * Agents' own trade tick ignores trades with players who are not their owner, so the window is driven here through the
 * stock Trade calls; the mesos move as any trade's do (MesoAccount.TRADE).
 */
final class TradeDesk {
    private TradeDesk() {
    }

    /** The player whose trade invite the citizen has not answered yet; null when there is none. */
    static Character inviter(Character agent) {
        Trade trade = agent.getTrade();
        if (trade == null || trade.isFullTrade() || trade.getPartner() == null) {
            return null;
        }
        return trade.getPartner().getChr();
    }

    static void accept(Character agent, Character inviter) {
        Trade.visitTrade(agent, inviter);
    }

    /** The open window's other side; null when the citizen has no open window. */
    static Character partner(Character agent) {
        Trade trade = agent.getTrade();
        return trade == null || !trade.isFullTrade() || trade.getPartner() == null ? null : trade.getPartner().getChr();
    }

    /** The mesos the other side put in. */
    static int offered(Character agent) {
        Trade trade = agent.getTrade();
        return trade == null || trade.getPartner() == null ? 0 : trade.getPartner().getMeso();
    }

    /** The other side clicked OK. */
    static boolean partnerLocked(Character agent) {
        Trade trade = agent.getTrade();
        return trade != null && trade.isPartnerConfirmed();
    }

    static void chat(Character agent, String text) {
        Trade trade = agent.getTrade();
        if (trade != null && text != null) {
            trade.chat(text);
        }
    }

    /** The citizen clicks OK; with the other side locked the trade goes through. */
    static void confirm(Character agent) {
        if (agent.getTrade() != null) {
            Trade.completeTrade(agent);
        }
    }

    static void cancel(Character agent) {
        if (agent.getTrade() != null) {
            Trade.cancelTrade(agent, TradeResult.PARTNER_CANCEL);
        }
    }
}
