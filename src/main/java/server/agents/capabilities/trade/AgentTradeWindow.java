package server.agents.capabilities.trade;

import client.Character;
import client.inventory.Item;

import java.util.List;

public interface AgentTradeWindow {
    Object identity();

    Character character();

    AgentTradeWindow partner();

    boolean isFullTrade();

    boolean isPartnerConfirmed();

    int number();

    void chat(String message);

    boolean addItem(Item item);

    void setMeso(int meso);

    List<Item> items();

    /** The mesos this side has put into the window so far (a trade window only ever adds to them). */
    int meso();

    boolean hasAnyOffer();

    /**
     * True once this trade went through: both sides' items and mesos moved. A refused or cancelled trade never is. It
     * stays readable after the trade closed, so a window held across a press of Trade can say what came of it.
     */
    boolean isSettled();
}
