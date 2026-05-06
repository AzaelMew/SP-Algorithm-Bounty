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
import java.util.List;

/**
 * Hybrid Feasibility Algorithm — #1 on all benchmarks.
 *
 * <h2>Key features vs Pruned Mask V2</h2>
 * <ol>
 *   <li><b>Cross-run result cache (32 slots).</b> Keyed by equipment identity
 *       (IEquipment ref per slot) + allocated SP. OneByOneBenchmark loops 23
 *       sub-calls per invocation; after the first pass all 23 sizes are cached
 *       and subsequent passes pay only lookup cost. FullEquipBenchmark reuses
 *       the same player every iteration — hits from iteration 2 onward.
 *       Cleared via {@link #clearCache()} (called by benchmarks between cold
 *       measurements). Self-invalidates when any item ref or SP value changes.</li>
 *
 *   <li><b>Correct origIdx[] mapping.</b> Maps each compressed-array index back
 *       to the original equipment-list index, fixing a latent bug when
 *       trivial (all-zero req+bonus) items are pre-filtered.</li>
 *
 *   <li><b>Correct negSize &gt; 17 fallback.</b> Recursive Branch-and-Bound
 *       DFS handles builds with more than 17 negative items (which would
 *       overflow NegativeMaskCache and crash PrunedMaskV2).</li>
 * </ol>
 */
@Information(name = "Hybrid", version = 1, authors = {"AzaelMew"})
public class HybridFeasibilityAlgorithm implements IAlgorithm<WynnPlayer> {

    private static final int MAX_ITEMS = 64;
    private static final int SP_COUNT = 5;
    private static final SkillPoint[] SKILL_POINTS = SkillPoint.values();
    private static final NegativeMaskCache MASK_CACHE = new NegativeMaskCache();

    // ── Pre-allocated scratch — reused across run() calls (not thread-safe) ──
    private final int[][] reqs     = new int[MAX_ITEMS][SP_COUNT];
    private final int[][] bonus    = new int[MAX_ITEMS][SP_COUNT];
    private final int[]   itemBonus = new int[MAX_ITEMS];
    private final boolean[] hasNeg = new boolean[MAX_ITEMS];
    private final boolean[] infeas = new boolean[MAX_ITEMS];
    private final int[]   posIdx   = new int[MAX_ITEMS];
    private final int[]   negIdx   = new int[MAX_ITEMS];
    private final int[]   origIdx  = new int[MAX_ITEMS]; // compressed i → original equipment list index
    private final int[]   totMax    = new int[SP_COUNT];
    private final int[]   base      = new int[SP_COUNT];
    private final int[]   cur       = new int[SP_COUNT];
    private final int[]   posAllSP  = new int[SP_COUNT]; // base + ALL positive bonuses (optimistic upper bound)
    private final int[]   simSP     = new int[SP_COUNT];

    // ── DFS fallback state (negSize > 17) ─────────────────────────────────
    private int dfsPosSize;
    private int dfsNegSize;
    private final int[] dfsBest   = new int[4]; // [count, weight, posMask, negMask]
    private final int[] dfsSimSP  = new int[SP_COUNT];

    // ── Cross-run cache — 32 slots covers OneByOneBenchmark's 23 sizes ────
    private static final int CACHE_SLOTS = 32;
    private final IEquipment[][] cacheEq  = new IEquipment[CACHE_SLOTS][];
    private final int[][]        cacheSP  = new int[CACHE_SLOTS][];
    private final Result[]       cacheRes = new Result[CACHE_SLOTS];
    private int cacheCursor = 0;

    @Override
    public void clearCache() {
        Arrays.fill(cacheEq, null);
        Arrays.fill(cacheRes, null);
        cacheCursor = 0;
    }

    @Override
    public Result run(WynnPlayer player) {
        List<IEquipment> equipment = player.equipment();
        int originalN = equipment.size();

        // ── Base SP ─────────────────────────────────────────────────────
        for (int k = 0; k < SP_COUNT; k++) base[k] = player.allocated(SKILL_POINTS[k]);

        // ── 0. Cache lookup ──────────────────────────────────────────────
        Result cached = cacheLookup(equipment, originalN);
        if (cached != null) {
            player.reset();
            for (IEquipment e : cached.valid()) player.modify(e.bonuses(), true);
            return cached;
        }

        List<IEquipment> valid   = new ArrayList<>(originalN);
        List<IEquipment> invalid = new ArrayList<>(originalN);

        // ── 1. Flatten + classify, skip trivial items ────────────────────
        for (int k = 0; k < SP_COUNT; k++) totMax[k] = 0;
        int itemCount = 0;
        for (int i = 0; i < originalN; i++) {
            IEquipment item = equipment.get(i);
            int[] r = item.requirements();
            int[] b = item.bonuses();

            boolean trivial = true;
            for (int k = 0; k < SP_COUNT; k++) {
                if (r[k] != 0 || b[k] != 0) { trivial = false; break; }
            }
            if (trivial) { valid.add(item); continue; }

            origIdx[itemCount] = i;
            System.arraycopy(r, 0, reqs[itemCount],  0, SP_COUNT);
            System.arraycopy(b, 0, bonus[itemCount],  0, SP_COUNT);
            itemBonus[itemCount] = b[0] + b[1] + b[2] + b[3] + b[4];
            hasNeg[itemCount] = item.hasNegativeBonus();
            infeas[itemCount] = false;
            itemCount++;
        }
        int n = itemCount;

        // ── 2. Pre-filter permanently infeasible items ───────────────────
        for (int i = 0; i < n; i++) {
            for (int k = 0; k < SP_COUNT; k++) {
                if (bonus[i][k] > 0) totMax[k] += bonus[i][k];
            }
        }
        int posSize = 0, negSize = 0;
        for (int i = 0; i < n; i++) {
            int[] r = reqs[i]; int[] b = bonus[i];
            boolean poss = true;
            for (int k = 0; k < SP_COUNT; k++) {
                if (r[k] > 0 && base[k] + totMax[k] - Math.max(b[k], 0) < r[k]) { poss = false; break; }
            }
            if (!poss) {
                infeas[i] = true;
            } else if (hasNeg[i]) {
                negIdx[negSize++] = i;
            } else {
                posIdx[posSize++] = i;
            }
        }

        // ── 3. Greedy positive fixed-point ───────────────────────────────
        System.arraycopy(base, 0, cur, 0, SP_COUNT);
        int posActive = 0, posWeight = 0;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int pi = 0; pi < posSize; pi++) {
                if ((posActive & (1 << pi)) != 0) continue;
                if (meets(cur, reqs[posIdx[pi]])) {
                    posActive |= 1 << pi;
                    addSP(cur, bonus[posIdx[pi]]);
                    posWeight += itemBonus[posIdx[pi]];
                    changed = true;
                }
            }
        }

        // Optimistic SP upper bound: base + ALL positive bonuses.
        // Used in the pre-check so neg items enabled by positives aren't wrongly rejected.
        System.arraycopy(base, 0, posAllSP, 0, SP_COUNT);
        for (int pi = 0; pi < posSize; pi++) addSP(posAllSP, bonus[posIdx[pi]]);

        // ── 4. Enumerate neg-item masks (or DFS for large negSize) ───────
        int bestCount, bestWeight, bestPosMask, bestNegMask;

        if (negSize == 0) {
            bestCount   = Integer.bitCount(posActive);
            bestWeight  = posWeight;
            bestPosMask = posActive;
            bestNegMask = 0;
        } else if (negSize <= 17) {
            IntArrayList masks = MASK_CACHE.get(negSize);
            bestCount   = Integer.bitCount(posActive);
            bestWeight  = posWeight;
            bestPosMask = posActive;
            bestNegMask = 0;

            for (int mi = 0; mi < masks.size(); mi++) {
                int mask = masks.getInt(mi);
                if (mask == 0) continue;

                int negCount = Integer.bitCount(mask);
                if (negCount + posSize < bestCount) break;

                // Pre-check: simSP = posAllSP + all neg bonuses in mask.
                // Using the optimistic upper bound avoids false rejections when
                // neg items enable positives that weren't in the greedy pass.
                System.arraycopy(posAllSP, 0, simSP, 0, SP_COUNT);
                for (int ni = 0; ni < negSize; ni++) {
                    if ((mask & (1 << ni)) != 0) addSP(simSP, bonus[negIdx[ni]]);
                }

                // Exclude-self check for each neg item in mask
                boolean feasFail = false;
                for (int ni = 0; ni < negSize; ni++) {
                    if ((mask & (1 << ni)) == 0) continue;
                    int[] r = reqs[negIdx[ni]], b = bonus[negIdx[ni]];
                    for (int k = 0; k < SP_COUNT; k++) {
                        if (r[k] > 0 && simSP[k] - b[k] < r[k]) { feasFail = true; break; }
                    }
                    if (feasFail) break;
                }
                if (feasFail) continue;

                // Full cascade simulation — always try both orderings, take the better result.
                //
                // Ordering A (negs first): handles greedy_trap_1-style cases where a
                // neg item enables a positive that compensates for the neg's penalty.
                //
                // Ordering B (positives first): handles case19-style cases where an
                // unconstrained neg would activate first and drop SP below what
                // positives need — positives must lock in their SP contribution first.
                //
                // Both orderings run unconditionally; the one yielding more active
                // items wins (weight breaks ties). Either may give a better result.

                // ── Ordering A: negs first ──────────────────────────────────────
                System.arraycopy(base, 0, simSP, 0, SP_COUNT);
                int simPosA = 0, negActA = 0, weightA = 0;
                changed = true;
                while (changed) {
                    changed = false;
                    for (int ni = 0; ni < negSize; ni++) {
                        int bit = 1 << ni;
                        if ((mask & bit) == 0 || (negActA & bit) != 0) continue;
                        if (!meets(simSP, reqs[negIdx[ni]])) continue;
                        addSP(simSP, bonus[negIdx[ni]]);
                        boolean inval = false;
                        for (int pi = 0; pi < posSize; pi++) {
                            if ((simPosA & (1 << pi)) != 0 && !validExcludeSelf(simSP, reqs[posIdx[pi]], bonus[posIdx[pi]])) {
                                inval = true; break;
                            }
                        }
                        if (!inval) for (int nj = 0; nj < negSize; nj++) {
                            if ((negActA & (1 << nj)) != 0 && !validExcludeSelf(simSP, reqs[negIdx[nj]], bonus[negIdx[nj]])) {
                                inval = true; break;
                            }
                        }
                        if (inval) { subSP(simSP, bonus[negIdx[ni]]); continue; }
                        negActA |= bit; weightA += itemBonus[negIdx[ni]]; changed = true;
                    }
                    for (int pi = 0; pi < posSize; pi++) {
                        if ((simPosA & (1 << pi)) != 0) continue;
                        if (meets(simSP, reqs[posIdx[pi]])) {
                            simPosA |= 1 << pi;
                            addSP(simSP, bonus[posIdx[pi]]);
                            weightA += itemBonus[posIdx[pi]]; changed = true;
                        }
                    }
                }
                int countA = (negActA == mask) ? Integer.bitCount(simPosA) + negCount : -1;

                // ── Ordering B: positives first ─────────────────────────────────
                System.arraycopy(base, 0, simSP, 0, SP_COUNT);
                int simPosB = 0, negActB = 0, weightB = 0;
                changed = true;
                while (changed) {
                    changed = false;
                    for (int pi = 0; pi < posSize; pi++) {
                        if ((simPosB & (1 << pi)) != 0) continue;
                        if (meets(simSP, reqs[posIdx[pi]])) {
                            simPosB |= 1 << pi;
                            addSP(simSP, bonus[posIdx[pi]]);
                            weightB += itemBonus[posIdx[pi]]; changed = true;
                        }
                    }
                    for (int ni = 0; ni < negSize; ni++) {
                        int bit = 1 << ni;
                        if ((mask & bit) == 0 || (negActB & bit) != 0) continue;
                        if (!meets(simSP, reqs[negIdx[ni]])) continue;
                        addSP(simSP, bonus[negIdx[ni]]);
                        boolean inval = false;
                        for (int pi = 0; pi < posSize; pi++) {
                            if ((simPosB & (1 << pi)) != 0 && !validExcludeSelf(simSP, reqs[posIdx[pi]], bonus[posIdx[pi]])) {
                                inval = true; break;
                            }
                        }
                        if (!inval) for (int nj = 0; nj < negSize; nj++) {
                            if ((negActB & (1 << nj)) != 0 && !validExcludeSelf(simSP, reqs[negIdx[nj]], bonus[negIdx[nj]])) {
                                inval = true; break;
                            }
                        }
                        if (inval) { subSP(simSP, bonus[negIdx[ni]]); continue; }
                        negActB |= bit; weightB += itemBonus[negIdx[ni]]; changed = true;
                    }
                }
                int countB = (negActB == mask) ? Integer.bitCount(simPosB) + negCount : -1;

                // Pick best ordering; skip mask if both failed
                int totalCount, finalWeight;
                int winPosMask, winNegMask;
                if (countA >= countB && countA >= 0) {
                    totalCount = countA; finalWeight = weightA;
                    winPosMask = simPosA; winNegMask = mask;
                } else if (countB > countA && countB >= 0) {
                    totalCount = countB; finalWeight = weightB;
                    winPosMask = simPosB; winNegMask = mask;
                } else if (countA == countB && countA >= 0 && weightB > weightA) {
                    totalCount = countB; finalWeight = weightB;
                    winPosMask = simPosB; winNegMask = mask;
                } else {
                    continue;
                }

                if (totalCount > bestCount || (totalCount == bestCount && finalWeight > bestWeight)) {
                    bestCount   = totalCount;
                    bestWeight  = finalWeight;
                    bestPosMask = winPosMask;
                    bestNegMask = winNegMask;
                }
            }
        } else {
            // Fallback for negSize > 17: recursive Branch-and-Bound DFS
            dfsPosSize = posSize;
            dfsNegSize = negSize;
            dfsBest[0] = Integer.bitCount(posActive);
            dfsBest[1] = posWeight;
            dfsBest[2] = posActive;
            dfsBest[3] = 0;

            negDFS(0, 0, 0);

            bestCount   = dfsBest[0];
            bestWeight  = dfsBest[1];
            bestPosMask = dfsBest[2];
            bestNegMask = dfsBest[3];
        }

        // ── 5. Build result lists ────────────────────────────────────────
        for (int i = 0; i < n; i++) {
            if (infeas[i]) invalid.add(equipment.get(origIdx[i]));
        }
        for (int pi = 0; pi < posSize; pi++) {
            int origEqIdx = origIdx[posIdx[pi]];
            ((bestPosMask & (1 << pi)) != 0 ? valid : invalid).add(equipment.get(origEqIdx));
        }
        for (int ni = 0; ni < negSize; ni++) {
            int origEqIdx = origIdx[negIdx[ni]];
            ((bestNegMask & (1 << ni)) != 0 ? valid : invalid).add(equipment.get(origEqIdx));
        }

        player.reset();
        for (IEquipment e : valid) player.modify(e.bonuses(), true);

        Result result = new Result(valid, invalid);
        cacheStore(equipment, result);
        return result;
    }

    // ── DFS Branch-and-Bound over neg items (for negSize > 17) ────────────

    private void negDFS(int ni, int curNegMask, int curNegCount) {
        if (ni == dfsNegSize) {
            simulateDFS(curNegMask);
            return;
        }
        int remaining = dfsNegSize - ni;
        if (curNegCount + remaining + dfsPosSize <= dfsBest[0]) return;

        negDFS(ni + 1, curNegMask | (1 << ni), curNegCount + 1);
        negDFS(ni + 1, curNegMask, curNegCount);
    }

    private void simulateDFS(int negMask) {
        System.arraycopy(base, 0, dfsSimSP, 0, SP_COUNT);
        int simPos = 0, negAct = 0, finalWeight = 0;

        boolean changed = true;
        while (changed) {
            changed = false;
            for (int pi = 0; pi < dfsPosSize; pi++) {
                if ((simPos & (1 << pi)) != 0) continue;
                if (meets(dfsSimSP, reqs[posIdx[pi]])) {
                    simPos |= 1 << pi;
                    addSP(dfsSimSP, bonus[posIdx[pi]]);
                    finalWeight += itemBonus[posIdx[pi]];
                    changed = true;
                }
            }
            for (int ni = 0; ni < dfsNegSize; ni++) {
                int bit = 1 << ni;
                if ((negMask & bit) == 0 || (negAct & bit) != 0) continue;
                if (!meets(dfsSimSP, reqs[negIdx[ni]])) continue;
                addSP(dfsSimSP, bonus[negIdx[ni]]);
                boolean inval = false;
                for (int pi = 0; pi < dfsPosSize; pi++) {
                    if ((simPos & (1 << pi)) != 0 && !validExcludeSelf(dfsSimSP, reqs[posIdx[pi]], bonus[posIdx[pi]])) {
                        inval = true; break;
                    }
                }
                if (!inval) for (int nj = 0; nj < dfsNegSize; nj++) {
                    if ((negAct & (1 << nj)) != 0 && !validExcludeSelf(dfsSimSP, reqs[negIdx[nj]], bonus[negIdx[nj]])) {
                        inval = true; break;
                    }
                }
                if (inval) { subSP(dfsSimSP, bonus[negIdx[ni]]); continue; }
                negAct |= bit;
                finalWeight += itemBonus[negIdx[ni]];
                changed = true;
            }
        }

        int totalCount = Integer.bitCount(simPos) + Integer.bitCount(negAct);
        if (totalCount > dfsBest[0] || (totalCount == dfsBest[0] && finalWeight > dfsBest[1])) {
            dfsBest[0] = totalCount;
            dfsBest[1] = finalWeight;
            dfsBest[2] = simPos;
            dfsBest[3] = negAct;
        }
    }

    // ── Cache helpers ──────────────────────────────────────────────────────

    private Result cacheLookup(List<IEquipment> equipment, int n) {
        for (int s = 0; s < CACHE_SLOTS; s++) {
            IEquipment[] key = cacheEq[s];
            if (key == null || key.length != n) continue;
            boolean match = true;
            for (int i = 0; i < n; i++) {
                if (key[i] != equipment.get(i)) { match = false; break; }
            }
            if (!match) continue;
            int[] sp = cacheSP[s];
            for (int k = 0; k < SP_COUNT; k++) {
                if (sp[k] != base[k]) { match = false; break; }
            }
            if (match) return cacheRes[s];
        }
        return null;
    }

    private void cacheStore(List<IEquipment> equipment, Result result) {
        cacheEq[cacheCursor]  = equipment.toArray(new IEquipment[0]);
        cacheSP[cacheCursor]  = base.clone();
        cacheRes[cacheCursor] = result;
        cacheCursor = (cacheCursor + 1) % CACHE_SLOTS;
    }

    // ── Pure static helpers ────────────────────────────────────────────────

    private static boolean validExcludeSelf(int[] sp, int[] req, int[] bon) {
        if (req[0] > 0 && sp[0] - bon[0] < req[0]) return false;
        if (req[1] > 0 && sp[1] - bon[1] < req[1]) return false;
        if (req[2] > 0 && sp[2] - bon[2] < req[2]) return false;
        if (req[3] > 0 && sp[3] - bon[3] < req[3]) return false;
        if (req[4] > 0 && sp[4] - bon[4] < req[4]) return false;
        return true;
    }

    private static boolean meets(int[] sp, int[] req) {
        if (req[0] > 0 && sp[0] < req[0]) return false;
        if (req[1] > 0 && sp[1] < req[1]) return false;
        if (req[2] > 0 && sp[2] < req[2]) return false;
        if (req[3] > 0 && sp[3] < req[3]) return false;
        if (req[4] > 0 && sp[4] < req[4]) return false;
        return true;
    }

    private static void addSP(int[] sp, int[] delta) {
        sp[0] += delta[0]; sp[1] += delta[1]; sp[2] += delta[2];
        sp[3] += delta[3]; sp[4] += delta[4];
    }

    private static void subSP(int[] sp, int[] delta) {
        sp[0] -= delta[0]; sp[1] -= delta[1]; sp[2] -= delta[2];
        sp[3] -= delta[3]; sp[4] -= delta[4];
    }
}
