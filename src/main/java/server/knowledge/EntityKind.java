package server.knowledge;

/**
 * What a name resolves to. ITEM..QUEST are game ids; REGION (a map series or authored area), PQ (a party
 * quest: lobby, NPC, level band) and ROLE (an NPC job such as the potion shop) come from aliases.yaml and
 * the numbered map series. Ids are always keyed with their kind: NPC 2043000 is Papulatus, item 2043000 a scroll.
 */
public enum EntityKind {
    ITEM, MOB, MAP, NPC, QUEST, REGION, PQ, ROLE
}
