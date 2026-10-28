package server.economy.ledger;

/**
 * Who holds meso, for the supply split (docs/ECONOMY_PLAN.md E0): a player, a GM, an active citizen, a retired
 * citizen (its purse frozen in the DB) or any other bot (companions, hired citizens, residents, probes).
 */
public enum MesoHolder {
    HUMAN,
    GM,
    CITIZEN,
    RETIRED,
    BOT
}
