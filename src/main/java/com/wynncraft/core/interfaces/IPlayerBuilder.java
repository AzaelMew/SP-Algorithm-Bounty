package com.wynncraft.core.interfaces;

import com.wynncraft.enums.SkillPoint;

public interface IPlayerBuilder<T extends IPlayer> {

    /**
     * Includes the provided equipment for the final player
     *
     * @param equipment the equipment to be included
     * @return this builder
     */
    IPlayerBuilder<T> equipment(IEquipment... equipment);

    /**
     * Includes the provided skill point as allocated for
     * the final player
     *
     * @param point the point type
     * @param amount the amount to be allocated
     * @return this builder
     */
    IPlayerBuilder<T> allocate(SkillPoint point, int amount);

    /**
     * Creates the player that should be used
     * @return the resulting builder
     */
    T build();

}
