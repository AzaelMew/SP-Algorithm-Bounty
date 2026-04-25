package com.wynncraft.core.interfaces;

import com.wynncraft.enums.SkillPoint;

import java.util.List;

public interface IPlayer {

    /**
     * A list containing the equipment the player
     * currently has on their equipment slots
     *
     * @return the equipment list
     */
    List<IEquipment> equipment();

    /**
     * The player current skill point weight
     * @return the resulting weight
     */
    int weight();

    /**
     * Retrieves the total amount of skill points the
     * player currently has for the provided skill
     *
     * Total includes the points given by equipment
     *
     * @param skill the skill to retrieve for
     * @return the resulting amount
     */
    int total(SkillPoint skill);

    /**
     * Retrieves the allocated amount of skill points the
     * player currently has for the provided skill
     *
     * Allocated represents the points manually
     * "allocated" by the player
     *
     * @param skill the skill to retrieve for
     * @return the resulting amount
     */
    int allocated(SkillPoint skill);

    /**
     * Modifies the total skill points by the value of the
     * provided skill points
     *
     * @param skillPoints the skill points to be modified
     * @param sum if the values should be summed or subtracted
     */
    void modify(int[] skillPoints, boolean sum);

    /**
     * Resets the player total skill points back
     * to its original state (of nothing modified)
     */
    void reset();

}
