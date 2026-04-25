package com.wynncraft.core.interfaces;

public interface IEquipment {

    /**
     * An integer array ordered by skill points
     * containing the skill point requirements
     *
     * @return the skill point requirements
     */
    int[] requirements();

    /**
     * An integer array ordered by skill points
     * containing the skill point bonuses given
     * by this equipment while active
     *
     * @return the resulting bonus
     */
    int[] bonuses();

    /**
     * @return if this equipment has a negative skill point bonus
     */
    boolean hasNegativeBonus();

    /**
     * Verifies if the provided player can currently
     * equip this equipment
     *
     * @param player the player to be verified
     * @return if it can be equipped
     */
    boolean canEquip(IPlayer player);

}
