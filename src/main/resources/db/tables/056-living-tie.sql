-- Citizen ties (docs/WORLD_SYSTEMS_PLAN.md N2.1): what two citizens are to each other, grown only from pairs the server
-- already has in hand (one party at the minute tick, circle mates on one map, a cleared party quest, the two sides of a
-- coliseum match). One row per pair with a < b; kind FRIEND or RIVAL; strength 1-100, faded since updated_at the way
-- warmth fades. At most population.TIES_PER_CITIZEN_MAX rows name one citizen (the weakest gives way).
CREATE TABLE IF NOT EXISTS living_tie (
    a               INT          NOT NULL,
    b               INT          NOT NULL,
    kind            VARCHAR(8)   NOT NULL,
    strength        INT          NOT NULL,
    updated_at      BIGINT       NOT NULL,
    PRIMARY KEY (a, b),
    KEY idx_living_tie_b (b)
);
