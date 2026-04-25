package com.wynncraft.core;

import com.wynncraft.core.interfaces.IEquipment;
import com.wynncraft.core.interfaces.IPlayer;
import com.wynncraft.core.interfaces.IPlayerBuilder;
import com.wynncraft.enums.SkillPoint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class WynnPlayer implements IPlayer {

    private static final SkillPoint[] SKILL_POINTS = SkillPoint.values();

    final List<IEquipment> equipment;

    int[] _allocated;
    int[] _bonus = new int[SKILL_POINTS.length];
    int _weight = 0;

    private WynnPlayer(List<IEquipment> equipment, int[] allocated) {
        this.equipment = equipment;
        this._allocated = allocated;
    }

    @Override
    public List<IEquipment> equipment() {
        return equipment;
    }

    @Override
    public int weight() {
        return _weight;
    }

    @Override
    public int total(SkillPoint skill) {
        return _allocated[skill.ordinal()] + _bonus[skill.ordinal()];
    }

    @Override
    public int allocated(SkillPoint skill) {
        return _allocated[skill.ordinal()];
    }

    @Override
    public void modify(int[] skillPoints, boolean sum) {
        for (int i = 0; i < skillPoints.length; i++) {
            int value = sum ? skillPoints[i] : -skillPoints[i];
            _bonus[i] += value;
            _weight += value;
        }
    }

    @Override
    public void reset() {
        modify(_bonus.clone(), false);
    }

    /**
     * Represents the builder that will be used for creating
     * the generic wynn player similar to the wynncraft implementation
     */
    public static class Builder implements IPlayerBuilder<WynnPlayer> {

        final List<IEquipment> equipment = new ArrayList<>();
        final int[] allocated = new int[SKILL_POINTS.length];

        public Builder() { }

        @Override
        public IPlayerBuilder<WynnPlayer> equipment(IEquipment... equipment) {
            this.equipment.addAll(Arrays.asList(equipment));
            return this;
        }

        @Override
        public IPlayerBuilder<WynnPlayer> allocate(SkillPoint point, int amount) {
            allocated[point.ordinal()] = amount;
            return this;
        }

        @Override
        public WynnPlayer build() {
            return new WynnPlayer(equipment, allocated);
        }

    }

}
