package com.wynncraft.enums;

import com.wynncraft.core.interfaces.IEquipment;
import com.wynncraft.core.interfaces.IPlayer;

public enum RefEquipment implements IEquipment {

    ;

    private static final SkillPoint[] SKILL_POINTS = SkillPoint.values();

    RefEquipment(int[] requirements, int[] bonuses) {
        this.requirements = requirements;
        this.bonuses = bonuses;
        this.hasNegativeBonus = checkNegativeBonus(bonuses);
    }

    final int[] requirements;
    final int[] bonuses;
    final boolean hasNegativeBonus;

    @Override
    public int[] requirements() {
        return requirements;
    }

    @Override
    public int[] bonuses() {
        return bonuses;
    }

    @Override
    public boolean hasNegativeBonus() {
        return hasNegativeBonus;
    }

    @Override
    public boolean canEquip(IPlayer player) {
        // Not using an enhanced for here to save allocations!
        for (int i = 0; i < requirements.length; i++) {
            int requirement = requirements[i];
            if (requirement <= 0 || player.total(SKILL_POINTS[i]) >= requirement) {
                continue;
            }

            return false;
        }

        return true;
    }

    /**
     * Verifies if the provided array of skills contains
     * a negative value
     *
     * @param skills the skills to verify
     * @return if it contains a negative values
     */
    private boolean checkNegativeBonus(int[] skills) {
        for (int skill : skills) {
            if (skill < 0) return true;
        }

        return false;
    }

}
