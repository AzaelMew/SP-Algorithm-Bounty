package com.wynncraft.core.interfaces;

import java.util.List;

public interface IAlgorithm<T extends IPlayer> {

    /**
     * Runs and calculates the best equipment combination
     * for the provided player.
     *
     * When marking an equipment as valid make sure to run
     * {@link IPlayer#modify(int[], boolean)} to apply their
     * bonuses accordingly
     *
     * @param player the player to run for
     * @return the resulting item combination
     */
    Result run(T player);

    /**
     * Holds the final combinatory result of the algorithm
     *
     * @param valid the equipment that should be marked as valid
     * @param invalid the equipment that should be marked as invalid
     */
    record Result(List<IEquipment> valid, List<IEquipment> invalid) {

    }

}
