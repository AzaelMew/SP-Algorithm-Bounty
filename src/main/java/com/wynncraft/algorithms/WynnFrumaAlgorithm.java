package com.wynncraft.algorithms;

import com.wynncraft.core.NegativeMaskCache;
import com.wynncraft.core.WynnPlayer;
import com.wynncraft.core.interfaces.IAlgorithm;
import com.wynncraft.core.interfaces.IEquipment;
import com.wynncraft.core.interfaces.Information;
import com.wynncraft.enums.SkillPoint;
import speiger.src.collections.ints.lists.IntArrayList;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Information(name = "Wynncraft Fruma", authors = "Wynncraft")
public class WynnFrumaAlgorithm implements IAlgorithm<WynnPlayer> {

    private static final SkillPoint[] SKILL_POINTS = SkillPoint.values();
    private static final NegativeMaskCache MASK_CACHE = new NegativeMaskCache();
    private static final Combination EMPTY = new Combination(Integer.MIN_VALUE, Collections.emptyList(), Collections.emptyList());

    @Override
    public Result run(WynnPlayer player) {
        // First step is to prepare two lists of equipment one with items
        // that have positive skill bonuses (or no sp) and one with negative
        // bonuses
        List<IEquipment> positive = new ArrayList<>(), negative = new ArrayList<>();
        {
            List<IEquipment> equipment = player.equipment();

            // Not using an enhanced for to save allocations
            for (int i = 0; i < equipment.size(); i++) {
                IEquipment content = equipment.get(i);
                if (content.hasNegativeBonus()) negative.add(content);
                else positive.add(content);
            }
        }

        // Start the process of calculating the best equipment configuration
        // with everything that is equipped
        Combination best = EMPTY;
        {
            int[] positiveSkills = allocate();
            int basePositiveWeight = 0;
            boolean[] baseActivePositive = new boolean[positive.size()];
            {
                player.reset();

                while (true) {
                    boolean changed = false;

                    // Not using an enhanced for to save allocations
                    for (int i = 0; i < positive.size(); i++) {
                        if (baseActivePositive[i]) {
                            continue;
                        }

                        IEquipment item = positive.get(i);
                        if (!item.canEquip(player)) {
                            continue;
                        }

                        baseActivePositive[i] = true;
                        changed = true;

                        int[] skills = item.bonuses();
                        player.modify(skills, true);
                        modify(positiveSkills, skills, true);
                        basePositiveWeight += weight(skills);
                    }

                    if (!changed) {
                        break;
                    }
                }
            }

            IntArrayList masks = MASK_CACHE.get(negative.size());
            combination: for (int mask : masks) {
                int negativeCount = Integer.bitCount(mask);
                int maxCount = negativeCount + positive.size();
                if (best.items() > maxCount) {
                    break;
                }

                List<IEquipment> valid = new ArrayList<>(), invalid = new ArrayList<>();
                IEquipment[] negativeItems = new IEquipment[negativeCount];
                int[] negativeSkills = allocate();
                {
                    int index = 0;
                    for (int i = 0; i < negative.size(); i++) {
                        IEquipment item = negative.get(i);
                        if ((mask & (1 << i)) == 0) {
                            invalid.add(item);
                            continue;
                        }

                        negativeItems[index++] = item;
                        modify(negativeSkills, item.bonuses(), true);
                    }
                }

                {
                    int total = 0;
                    for (int i = 0; i < positiveSkills.length; i++) {
                        total += positiveSkills[i] + negativeSkills[i];
                    }

                    if (maxCount == best.items() && total <= best.weight()) {
                        continue;
                    }
                }

                int finalWeight = basePositiveWeight;
                boolean[] activePositive = Arrays.copyOf(baseActivePositive, baseActivePositive.length);
                boolean[] activeNegative = new boolean[negativeItems.length];
                {
                    player.reset();
                    for (int i = 0; i < positive.size(); i++) {
                        if (!activePositive[i]) {
                            continue;
                        }

                        player.modify(positive.get(i).bonuses(), true);
                    }

                    while (true) {
                        boolean changed = false;

                        // Negative items are only allowed to join if they keep the
                        // currently active positive set valid at the moment of insertion
                        for (int i = 0; i < negativeItems.length; i++) {
                            if (activeNegative[i]) {
                                continue;
                            }

                            IEquipment item = negativeItems[i];
                            if (!item.canEquip(player)) {
                                continue;
                            }

                            int[] skills = item.bonuses();
                            player.modify(skills, true);

                            boolean invalidatesPositive = false;

                            // Not using an enhanced for to save allocations
                            for (int j = 0; j < positive.size(); j++) {
                                if (!activePositive[j]) {
                                    continue;
                                }

                                if (positive.get(j).canEquip(player)) {
                                    continue;
                                }

                                invalidatesPositive = true;
                                break;
                            }

                            if (invalidatesPositive) {
                                player.modify(skills, false);
                                continue;
                            }

                            activeNegative[i] = true;
                            changed = true;
                            finalWeight += weight(skills);
                        }

                        // Not using an enhanced for to save allocations
                        for (int i = 0; i < positive.size(); i++) {
                            if (activePositive[i]) {
                                continue;
                            }

                            IEquipment item = positive.get(i);
                            if (!item.canEquip(player)) {
                                continue;
                            }

                            activePositive[i] = true;
                            changed = true;

                            int[] skills = item.bonuses();
                            player.modify(skills, true);
                            finalWeight += weight(skills);
                        }

                        if (!changed) {
                            break;
                        }
                    }
                }

                for (int i = 0; i < negativeItems.length; i++) {
                    if (activeNegative[i]) {
                        continue;
                    }

                    continue combination;
                }

                for (int i = 0; i < positive.size(); i++) {
                    if (!activePositive[i]) {
                        invalid.add(positive.get(i));
                        continue;
                    }

                    if (!positive.get(i).canEquip(player)) {
                        continue combination;
                    }
                }

                for (int i = 0; i < negativeItems.length; i++) {
                    if (!negativeItems[i].canEquip(player)) {
                        continue combination;
                    }
                }

                for (int i = 0; i < negativeItems.length; i++) {
                    valid.add(negativeItems[i]);
                }

                for (int i = 0; i < positive.size(); i++) {
                    if (!activePositive[i]) {
                        continue;
                    }

                    valid.add(positive.get(i));
                }

                if (best.items() > valid.size()) {
                    continue;
                }

                if (best.items() == valid.size() && best.weight() > finalWeight) {
                    continue;
                }

                best = new Combination(finalWeight, valid, invalid);
            }
        }
        return best.asResult();
    }

    /**
     * @return a fresh skill point array
     */
    private int[] allocate() {
        return new int[SKILL_POINTS.length];
    }

    /**
     * Modifies the provided skill points by the value of the
     * provided skill points
     *
     * @param target the skill points to be modified
     * @param skillPoints the skill points to modify by
     * @param sum if the values should be summed or subtracted
     */
    private void modify(int[] target, int[] skillPoints, boolean sum) {
        for (int i = 0; i < skillPoints.length; i++) {
            target[i] += sum ? skillPoints[i] : -skillPoints[i];
        }
    }

    /**
     * @param skillPoints the skill points to sum
     * @return the resulting weight
     */
    private int weight(int[] skillPoints) {
        int total = 0;
        for (int i = 0; i < skillPoints.length; i++) {
            total += skillPoints[i];
        }

        return total;
    }

    /**
     * Represents a valid equipment combination, the equipment
     * that can be active, the ones that should be inactive,
     * and the total skill weight
     *
     * @param weight the total sp weight
     * @param valid the equipment slots that are valid
     * @param invalid the equipment slots that are invalid
     */
    private record Combination(int weight, List<IEquipment> valid, List<IEquipment> invalid) {

        /**
         * @return the number of valid items
         */
        public int items() {
            return weight == Integer.MIN_VALUE ? -1 : valid.size();
        }

        /**
         * @return this combination as result
         */
        public Result asResult() {
            return new Result(valid, invalid);
        }

    }

}
