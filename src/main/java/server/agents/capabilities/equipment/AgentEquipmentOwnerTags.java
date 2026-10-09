package server.agents.capabilities.equipment;

/**
 * Owner tags (an equip's owner text) that the equipment engine reads. The look-diversity filter
 * ({@link AgentEquipmentAppearanceDiversityPolicy}) never takes off a piece tagged with the Agent's own name or with
 * {@link #BOSS_GEAR}. Whoever forges such a piece uses the tag from here, so both sides read one text.
 */
public final class AgentEquipmentOwnerTags {
    /** A piece of a GM boss-test kit ({@code !bossgear}): untradeable, every upgrade slot scrolled, worn for a fight. */
    public static final String BOSS_GEAR = "bossgear";

    private AgentEquipmentOwnerTags() {
    }
}
