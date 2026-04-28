package com.wynncraft.algorithms;

import com.wynncraft.core.interfaces.IAlgorithm;
import com.wynncraft.core.interfaces.IEquipment;
import com.wynncraft.core.interfaces.Information;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Starving Goblin algorithm.
 *
 * @author Melon Team (riege and trethore)
 * @version 2
 */
@SuppressWarnings("DuplicatedCode")
@Information(name = "Starving Goblin", version = 2, authors = "Melon")
public final class StarvingGoblinAlgorithm implements IAlgorithm<StarvingPlayer> {

    private static final int STR = 0;
    private static final int DEX = 1;
    private static final int INT = 2;
    private static final int DEF = 3;
    private static final int AGI = 4;
    private static final int SKILLS = AGI + 1;
    private static final int EMPTY_CAPACITY = 0;

    /*
     * Use the visited table only while it stays small.
     * Past this size it eats too much memory for what it saves.
     */
    private static final int VISITED_LIMIT = 22;
    private static final int VISITED_WORDS = (1 << VISITED_LIMIT) >>> 6;
    private final int[] state = new int[SKILLS];
    private final int[] bestBonuses = new int[SKILLS];
    private final long[] requiredBySkill = new long[SKILLS];
    private final long[] visited = new long[VISITED_WORDS];
    /*
     * Copy item data into plain arrays. It is old-school, but it
     * avoids repeated virtual calls and array lookups
     * inside the recursive search where almost all runtime is spent.
     */
    private IEquipment[] items = new IEquipment[EMPTY_CAPACITY];
    private int[] weights = new int[EMPTY_CAPACITY];
    private int[] req0 = new int[EMPTY_CAPACITY];
    private int[] req1 = new int[EMPTY_CAPACITY];
    private int[] req2 = new int[EMPTY_CAPACITY];
    private int[] req3 = new int[EMPTY_CAPACITY];
    private int[] req4 = new int[EMPTY_CAPACITY];
    private int[] bonus0 = new int[EMPTY_CAPACITY];
    private int[] bonus1 = new int[EMPTY_CAPACITY];
    private int[] bonus2 = new int[EMPTY_CAPACITY];
    private int[] bonus3 = new int[EMPTY_CAPACITY];
    private int[] bonus4 = new int[EMPTY_CAPACITY];
    private boolean[] hasRequirement = new boolean[EMPTY_CAPACITY];
    private boolean[] hasNegativeBonus = new boolean[EMPTY_CAPACITY];
    /*
     * Negative items can break already equipped items.
     * This mask tells which items need to be checked again.
     */
    private long[] impactMasks = new long[EMPTY_CAPACITY];
    private int base0;
    private int base1;
    private int base2;
    private int base3;
    private int base4;

    private int itemCount;
    private boolean useVisited;
    private long negativeMask;
    private long bestMask;
    private int bestCount;
    private int bestWeight;
    private int activatedFreeWeight;

    @Override
    public Result run(StarvingPlayer player) {
        List<IEquipment> equipment = player.equipment();
        prepare(player, equipment);

        long allMask = itemCount == 64 ? -1L : (1L << itemCount) - 1L;
        long activeMask = activateFreeItems();
        long remainingMask = allMask & ~activeMask;
        bestMask = activeMask;
        bestCount = Long.bitCount(activeMask);
        bestWeight = activatedFreeWeight;
        captureBestBonuses();

        // Try the cheap path first (No DFS)
        // The reason we don't want to use DFS as it does too much permutations for nothing ( which we could call it NAIVE DFS)
        //
        if (tryEquipEverything(remainingMask, allMask)) {
            Result result = createResult(equipment);
            applyBestResult(player);
            return result;
        }

        // Then just equip.
        if (negativeMask == 0L) {
            equipPositiveOnly(remainingMask);
            Result result = createResult(equipment);
            applyBestResult(player);
            return result;
        }

        // Negative bonuses are freaking annoying.
        prepareVisited();
        search(activeMask, remainingMask, bestCount, bestWeight, maskWeight(remainingMask));
        Result result = createResult(equipment);
        applyBestResult(player);
        return result;
    }

    private void prepareVisited() {
        useVisited = itemCount <= VISITED_LIMIT;
        if (useVisited) {
            int words = Math.min(VISITED_WORDS, ((1 << itemCount) >>> 6) + 1);
            Arrays.fill(visited, 0, words, 0L);
        }
    }

    private void prepare(StarvingPlayer player, List<IEquipment> equipment) {
        itemCount = equipment.size();
        ensureCapacity(itemCount);
        negativeMask = 0L;
        Arrays.fill(requiredBySkill, 0L);

        for (int i = 0; i < itemCount; i++) {
            loadItem(i, equipment.get(i));
        }

        prepareImpactMasks();
        loadState(player);
    }

    private void loadItem(int index, IEquipment item) {
        int[] req = item.requirements();
        int[] bonus = item.bonuses();

        items[index] = item;
        loadItemStats(index, req, bonus, item.hasNegativeBonus());
    }

    private void loadItemStats(int index, int[] req, int[] bonus, boolean negativeBonus) {
        long bit = 1L << index;
        negativeMask &= ~bit; //simply removes an item from negative mask. so it's no longer a requirement breaking neg item
        int r0 = req[STR];
        int r1 = req[DEX];
        int r2 = req[INT];
        int r3 = req[DEF];
        int r4 = req[AGI];
        int b0 = bonus[STR];
        int b1 = bonus[DEX];
        int b2 = bonus[INT];
        int b3 = bonus[DEF];
        int b4 = bonus[AGI];
        req0[index] = r0;
        req1[index] = r1;
        req2[index] = r2;
        req3[index] = r3;
        req4[index] = r4;
        bonus0[index] = b0;
        bonus1[index] = b1;
        bonus2[index] = b2;
        bonus3[index] = b3;
        bonus4[index] = b4;
        weights[index] = b0 + b1 + b2 + b3 + b4;
        hasRequirement[index] = r0 > 0 || r1 > 0 || r2 > 0 || r3 > 0 || r4 > 0; // basic boolean algebra if one of the condition is met it should obviously return true
        hasNegativeBonus[index] = negativeBonus;
        negativeMask |= negativeBonus ? bit : 0L;
        markRequirements(req, bit);
    }

    // SO HERE WE WANNA AVOID O(n) validation after each negative item
    // so instead of doing so, we directly jump to only potentially affected items
    // which means -> item A require STR and item B STR + DEX
    // then requiredBySkill[STR] -> contains the bits for A AND B, as for DEX only B by logic
    private void markRequirements(int[] req, long bit) {
        requiredBySkill[STR] |= req[STR] > 0 ? bit : 0L;
        requiredBySkill[DEX] |= req[DEX] > 0 ? bit : 0L;
        requiredBySkill[INT] |= req[INT] > 0 ? bit : 0L;
        requiredBySkill[DEF] |= req[DEF] > 0 ? bit : 0L;
        requiredBySkill[AGI] |= req[AGI] > 0 ? bit : 0L;
    }

    private void prepareImpactMasks() {
        for (int i = 0; i < itemCount; i++) {
            impactMasks[i] = impactMask(i);
        }
    }

    private long impactMask(int item) {
        long mask = 0L;
        mask |= bonus0[item] < 0 ? requiredBySkill[STR] : 0L;
        mask |= bonus1[item] < 0 ? requiredBySkill[DEX] : 0L;
        mask |= bonus2[item] < 0 ? requiredBySkill[INT] : 0L;
        mask |= bonus3[item] < 0 ? requiredBySkill[DEF] : 0L;
        mask |= bonus4[item] < 0 ? requiredBySkill[AGI] : 0L;
        return mask;
    }

    private void loadState(StarvingPlayer player) {
        int[] allocated = PlayerAccess.allocated(player);
        base0 = allocated[STR];
        base1 = allocated[DEX];
        base2 = allocated[INT];
        base3 = allocated[DEF];
        base4 = allocated[AGI];
        state[STR] = base0;
        state[DEX] = base1;
        state[INT] = base2;
        state[DEF] = base3;
        state[AGI] = base4;
    }

    private void ensureCapacity(int size) {
        if (items.length >= size) {
            return;
        }

        int capacity = Math.max(size, items.length == 0 ? 16 : items.length * 2);
        items = Arrays.copyOf(items, capacity);
        weights = Arrays.copyOf(weights, capacity);
        req0 = Arrays.copyOf(req0, capacity);
        req1 = Arrays.copyOf(req1, capacity);
        req2 = Arrays.copyOf(req2, capacity);
        req3 = Arrays.copyOf(req3, capacity);
        req4 = Arrays.copyOf(req4, capacity);
        bonus0 = Arrays.copyOf(bonus0, capacity);
        bonus1 = Arrays.copyOf(bonus1, capacity);
        bonus2 = Arrays.copyOf(bonus2, capacity);
        bonus3 = Arrays.copyOf(bonus3, capacity);
        bonus4 = Arrays.copyOf(bonus4, capacity);
        hasRequirement = Arrays.copyOf(hasRequirement, capacity);
        hasNegativeBonus = Arrays.copyOf(hasNegativeBonus, capacity);
        impactMasks = Arrays.copyOf(impactMasks, capacity);
    }

    private long activateFreeItems() {
        // Free item don't mind me if I do.
        long mask = 0L;
        int weight = 0;
        for (int i = 0; i < itemCount; i++) {
            if (hasRequirement[i] || hasNegativeBonus[i]) {
                continue;
            }

            mask |= 1L << i;
            weight += weights[i];
            if (weights[i] != 0) {
                addBonus(i);
            }
        }
        activatedFreeWeight = weight;
        return mask;
    }

    private void equipPositiveOnly(long remainingMask) {
        long addedMask = closePositiveItems(remainingMask);
        bestMask |= addedMask;
        bestCount += Long.bitCount(addedMask);
        bestWeight += maskWeight(addedMask);
        captureBestBonuses();
    }

    private boolean tryEquipEverything(long remainingMask, long allMask) {
        if (!allCanEquipNow(remainingMask)) {
            return false;
        }

        addBonuses(remainingMask);
        // We recheck if a negatives is applied on the items.
        if (activeItemsRemainValid(allMask & impactedBy(remainingMask))) {
            bestMask = allMask;
            bestCount = itemCount;
            bestWeight += maskWeight(remainingMask);
            captureBestBonuses();
            return true;
        }
        removeBonuses(remainingMask);
        return false;
    }

    private boolean allCanEquipNow(long mask) {
        long remaining = mask;
        while (remaining != 0L) {
            long bit = remaining & -remaining;
            remaining &= remaining - 1L;
            if (!canEquipNow(Long.numberOfTrailingZeros(bit))) {
                return false;
            }
        }
        return true;
    }

    private long impactedBy(long mask) {
        long impacted = 0L;
        long remaining = mask & negativeMask;
        while (remaining != 0L) {
            long bit = remaining & -remaining;
            remaining &= remaining - 1L;
            impacted |= impactMasks[Long.numberOfTrailingZeros(bit)];
        }
        return impacted;
    }

    private int maskWeight(long mask) {
        int weight = 0;
        long remaining = mask;
        while (remaining != 0L) {
            long bit = remaining & -remaining;
            remaining &= remaining - 1L;
            weight += weights[Long.numberOfTrailingZeros(bit)];
        }
        return weight;
    }

    private void search(long activeMask, long remainingMask, int count, int weight, int remainingWeight) {
        // If items give positives only, we just equip it without DFS.
        long addedPositiveMask = (remainingMask & negativeMask) == 0L ? closePositiveItems(remainingMask) : 0L;
        if (addedPositiveMask != 0L) {
            int addedWeight = maskWeight(addedPositiveMask);
            activeMask |= addedPositiveMask;
            remainingMask &= ~addedPositiveMask;
            count += Long.bitCount(addedPositiveMask);
            weight += addedWeight;
            remainingWeight -= addedWeight;
        }

        if (alreadyVisited(activeMask)) {
            removeBonuses(addedPositiveMask);
            return;
        }

        recordBest(activeMask, count, weight);

        if (isSearchExhausted(count, remainingMask, weight, remainingWeight)) {
            removeBonuses(addedPositiveMask);
            return;
        }

        long candidates = remainingMask;
        while (candidates != 0L) {
            long bit = candidates & -candidates;
            candidates &= candidates - 1L;
            int item = Long.numberOfTrailingZeros(bit);

            if (!canEquipNow(item)) {
                continue;
            }

            addBonus(item);
            if (!hasNegativeBonus[item] || activeItemsRemainValid(activeMask & impactMasks[item])) {
                search(
                        activeMask | bit,
                        remainingMask & ~bit,
                        count + 1,
                        weight + weights[item],
                        remainingWeight - weights[item]);
            }
            subtractBonus(item);
        }

        removeBonuses(addedPositiveMask);
    }

    private void recordBest(long activeMask, int count, int weight) {
        if (count > bestCount || (count == bestCount && weight > bestWeight)) {
            bestMask = activeMask;
            bestCount = count;
            bestWeight = weight;
            captureBestBonuses();
        }
    }

    private boolean isSearchExhausted(int count, long remainingMask, int weight, int remainingWeight) {
        if (bestCount == itemCount) {
            return true;
        }

        int maximumReachableCount = count + Long.bitCount(remainingMask);
        // Best is ordered by:
        // 1. most equipped items
        // 2. highest total bonus weight
        return maximumReachableCount < bestCount
                || (maximumReachableCount == bestCount && weight + remainingWeight <= bestWeight);
    }

    private long closePositiveItems(long remainingMask) {
        long addedMask = 0L;
        long changedMask;
        do {
            changedMask = 0L;
            long candidates = remainingMask & ~addedMask;
            while (candidates != 0L) {
                long bit = candidates & -candidates;
                candidates &= candidates - 1L;
                int item = Long.numberOfTrailingZeros(bit);
                if (!hasNegativeBonus[item] && canEquipNow(item)) {
                    changedMask |= bit;
                    if (weights[item] != 0) {
                        addBonus(item);
                    }
                }
            }
            addedMask |= changedMask;
        } while (changedMask != 0L);
        return addedMask;
    }

    private boolean alreadyVisited(long mask) {
        if (!useVisited) {
            return false;
        }

        int index = (int) mask;
        int word = index >>> 6;
        long bit = 1L << (index & 63);
        if ((visited[word] & bit) != 0L) {
            return true;
        }

        visited[word] |= bit;
        return false;
    }

    private boolean canEquipNow(int item) {
        int r0 = req0[item];
        int r1 = req1[item];
        int r2 = req2[item];
        int r3 = req3[item];
        int r4 = req4[item];
        return (r0 <= 0 || state[STR] >= r0)
                && (r1 <= 0 || state[DEX] >= r1)
                && (r2 <= 0 || state[INT] >= r2)
                && (r3 <= 0 || state[DEF] >= r3)
                && (r4 <= 0 || state[AGI] >= r4);
    }

    private boolean activeItemsRemainValid(long activeMask) {
        long remaining = activeMask;
        while (remaining != 0L) {
            long bit = remaining & -remaining;
            remaining &= remaining - 1L;
            int item = Long.numberOfTrailingZeros(bit);
            if (!itemStillValidWithoutOwnBonus(item)) {
                return false;
            }
        }
        return true;
    }

    private boolean itemStillValidWithoutOwnBonus(int item) {
        int r0 = req0[item];
        int r1 = req1[item];
        int r2 = req2[item];
        int r3 = req3[item];
        int r4 = req4[item];
        return (r0 <= 0 || state[STR] >= r0 + bonus0[item])
                && (r1 <= 0 || state[DEX] >= r1 + bonus1[item])
                && (r2 <= 0 || state[INT] >= r2 + bonus2[item])
                && (r3 <= 0 || state[DEF] >= r3 + bonus3[item])
                && (r4 <= 0 || state[AGI] >= r4 + bonus4[item]);
    }

    private void addBonuses(long mask) {
        long remaining = mask;
        while (remaining != 0L) {
            long bit = remaining & -remaining;
            remaining &= remaining - 1L;
            addBonus(Long.numberOfTrailingZeros(bit));
        }
    }

    private void removeBonuses(long mask) {
        long remaining = mask;
        while (remaining != 0L) {
            long bit = remaining & -remaining;
            remaining &= remaining - 1L;
            subtractBonus(Long.numberOfTrailingZeros(bit));
        }
    }

    private void addBonus(int item) {
        state[STR] += bonus0[item];
        state[DEX] += bonus1[item];
        state[INT] += bonus2[item];
        state[DEF] += bonus3[item];
        state[AGI] += bonus4[item];
    }

    private void subtractBonus(int item) {
        state[STR] -= bonus0[item];
        state[DEX] -= bonus1[item];
        state[INT] -= bonus2[item];
        state[DEF] -= bonus3[item];
        state[AGI] -= bonus4[item];
    }

    private void applyBestResult(StarvingPlayer player) {
        if (bestCount == 0) {
            resetPlayer(player);
            return;
        }

        PlayerAccess.setBonuses(player, bestBonuses);
    }

    private Result createResult(List<IEquipment> equipment) {
        if (bestCount == itemCount) {
            return new Result(equipment, Collections.emptyList());
        }

        if (bestCount == 0) {
            return new Result(Collections.emptyList(), equipment);
        }

        long allMask = itemCount == 64 ? -1L : (1L << itemCount) - 1L;
        return new Result(
                new MaskEquipmentList(items, bestMask, bestCount),
                new MaskEquipmentList(items, allMask & ~bestMask, itemCount - bestCount));
    }

    private void captureBestBonuses() {
        bestBonuses[STR] = state[STR] - base0;
        bestBonuses[DEX] = state[DEX] - base1;
        bestBonuses[INT] = state[INT] - base2;
        bestBonuses[DEF] = state[DEF] - base3;
        bestBonuses[AGI] = state[AGI] - base4;
    }

    private void resetPlayer(StarvingPlayer player) {
        PlayerAccess.clearBonuses(player);
    }

    // We have this lazy view bcc it havoid using for example an ArrayList if the
    // result is just read once. note by edgn: NEVER USE ARRAY LISTS IN OPTIMISATION PROBLEMS !!!!!!!
    private static final class MaskEquipmentList extends AbstractList<IEquipment> {
        private final IEquipment[] items;
        private final long mask;
        private final int size;

        private MaskEquipmentList(IEquipment[] items, long mask, int size) {
            this.items = items;
            this.mask = mask;
            this.size = size;
        }

        @Override
        public IEquipment get(int index) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException(index);
            }

            long remaining = mask;
            for (int i = 0; i < index; i++) {
                remaining &= remaining - 1L;
            }
            return items[Long.numberOfTrailingZeros(remaining)];
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public boolean equals(Object object) {
            if (object == this) {
                return true;
            }
            if (!(object instanceof List<?> other) || other.size() != size) {
                return false;
            }

            long remaining = mask;
            for (Object otherItem : other) {
                IEquipment item = items[Long.numberOfTrailingZeros(remaining)];
                if (item != otherItem && !item.equals(otherItem)) {
                    return false;
                }
                remaining &= remaining - 1L;
            }
            return true;
        }

        @Override
        public int hashCode() {
            int hash = 1;
            long remaining = mask;
            while (remaining != 0L) {
                IEquipment item = items[Long.numberOfTrailingZeros(remaining)];
                hash = 31 * hash + item.hashCode();
                remaining &= remaining - 1L;
            }
            return hash;
        }
    }

    private static final class PlayerAccess {
        private PlayerAccess() {
        }

        private static int[] allocated(StarvingPlayer player) {
            return player.allocated;
        }

        private static void setBonuses(StarvingPlayer player, int[] bonuses) {
            // Search already found the best bonus vector.
            // Copy it directly instead of replaying equipment.
            // Same tech here, we don't use loop those are slow.
            int[] target = player.bonus;
            target[STR] = bonuses[STR];
            target[DEX] = bonuses[DEX];
            target[INT] = bonuses[INT];
            target[DEF] = bonuses[DEF];
            target[AGI] = bonuses[AGI];
            player.weight = bonuses[STR] + bonuses[DEX] + bonuses[INT] + bonuses[DEF] + bonuses[AGI];
        }

        private static void clearBonuses(StarvingPlayer player) {
            player.reset();
        }
    }
}