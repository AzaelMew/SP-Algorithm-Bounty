package com.wynncraft.algorithms;

import com.wynncraft.core.interfaces.IEquipment;
import com.wynncraft.core.interfaces.IPlayer;
import com.wynncraft.core.interfaces.IPlayerBuilder;
import com.wynncraft.core.interfaces.Information;
import com.wynncraft.enums.SkillPoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Starving Goblin algorithm.
 *
 * @author Melon Team (riege and trethore)
 * @version 2
 */
@SuppressWarnings("DuplicatedCode")
@Information(name = "Starving Player", version = 2, authors = "Melon")
public final class StarvingPlayer implements IPlayer {

    private static final SkillPoint[] SKILL_POINTS = SkillPoint.values();
    private static final int STR = 0;
    private static final int DEX = 1;
    private static final int INT = 2;
    private static final int DEF = 3;
    private static final int AGI = 4;

    /*
     * We kept this fast and dumb ->
     * StarvingGoblin reads/writes these arrays directly in the hot path.
     * That lets it copy the best bonus result into the player instead of
     * rebuilding it from equipment or going through getters for each skill wich is
     * slow af.
     */
    final List<IEquipment> equipment;
    final int[] allocated;
    final int[] bonus = new int[SKILL_POINTS.length];
    int weight;

    private StarvingPlayer(List<IEquipment> equipment, int[] allocated) {
        this.equipment = equipment;
        this.allocated = allocated;
    }

    @Override
    public List<IEquipment> equipment() {
        return equipment;
    }

    @Override
    public int weight() {
        return weight;
    }

    @Override
    public int total(SkillPoint skill) {
        int index = skill.ordinal();
        return allocated[index] + bonus[index];
    }

    @Override
    public int allocated(SkillPoint skill) {
        return allocated[skill.ordinal()];
    }

    @Override
    public void modify(int[] skillPoints, boolean sum) {
        // Keep the five skills unrolled, bc loop is a bottleneck here.
        int sign = sum ? 1 : -1;
        int delta0 = skillPoints[STR] * sign;
        int delta1 = skillPoints[DEX] * sign;
        int delta2 = skillPoints[INT] * sign;
        int delta3 = skillPoints[DEF] * sign;
        int delta4 = skillPoints[AGI] * sign;
        bonus[STR] += delta0;
        bonus[DEX] += delta1;
        bonus[INT] += delta2;
        bonus[DEF] += delta3;
        bonus[AGI] += delta4;
        weight += delta0 + delta1 + delta2 + delta3 + delta4;
    }

    @Override
    public void reset() {
        bonus[STR] = 0;
        bonus[DEX] = 0;
        bonus[INT] = 0;
        bonus[DEF] = 0;
        bonus[AGI] = 0;
        weight = 0;
    }

    public static final class Builder implements IPlayerBuilder<StarvingPlayer> {

        private final List<IEquipment> equipment = new ArrayList<>(16);
        private final int[] allocated = new int[SKILL_POINTS.length];

        @Override
        public IPlayerBuilder<StarvingPlayer> equipment(IEquipment... items) {
            Collections.addAll(equipment, items);
            return this;
        }

        @Override
        public IPlayerBuilder<StarvingPlayer> allocate(SkillPoint point, int amount) {
            allocated[point.ordinal()] = amount;
            return this;
        }

        @Override
        public StarvingPlayer build() {
            return new StarvingPlayer(new ArrayList<>(equipment), allocated.clone());
        }
    }
}
