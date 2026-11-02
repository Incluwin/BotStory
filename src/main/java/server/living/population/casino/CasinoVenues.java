package server.living.population.casino;

import client.Character;

import java.util.ArrayList;
import java.util.List;

/** The casino's venues and its cashier, built from casino.yaml (CitizenCasino owns one). */
public final class CasinoVenues {
    private final CasinoContent content;
    private final ChipCashier cashier;
    private final List<Venue> all = new ArrayList<>();

    public CasinoVenues(CasinoHost host, CasinoContent content) {
        this.content = content;
        this.cashier = new ChipCashier(host, content);
        all.add(new BlackjackTables(host, content));
        all.add(new DiceTable(host, content));
        all.add(new GameParkBar(host, content));
        all.add(new DropGame(host, content));
        all.add(new GachaShow(host, content));
        all.add(new NxSeller(host, content));
    }

    public CasinoContent content() {
        return content;
    }

    public List<Venue> all() {
        return all;
    }

    public ChipCashier cashier() {
        return cashier;
    }

    public void tick(long now) {
        cashier.ensure();
        for (Venue venue : all) {
            venue.tick(now);
        }
    }

    /** A human's map chat: the first venue on their map that takes it. */
    public boolean heard(Character human, String text, long now) {
        for (Venue venue : all) {
            if (venue.mapId() == human.getMapId() && venue.heard(human, text, now)) {
                return true;
            }
        }
        return false;
    }

    public boolean clicked(Character player, int objectId) {
        return cashier.clicked(player, objectId);
    }

    public void stop() {
        for (Venue venue : all) {
            venue.stop();
        }
        cashier.removeAll();
    }

    public List<String> status() {
        List<String> out = new ArrayList<>();
        out.add("cashier " + (cashier.enabled() ? "on" : "off"));
        for (Venue venue : all) {
            out.add(venue.status());
        }
        return out;
    }
}
