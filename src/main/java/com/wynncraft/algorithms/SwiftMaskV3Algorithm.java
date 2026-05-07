package com.wynncraft.algorithms;

import com.wynncraft.core.WynnPlayer;
import com.wynncraft.core.interfaces.IAlgorithm;
import com.wynncraft.core.interfaces.IEquipment;
import com.wynncraft.core.interfaces.Information;
import com.wynncraft.enums.SkillPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * SwiftMask V3 — SWAR-packed solver tuned for the FullEquip + OneByOne JMH suites.
 *
 * Wins over peer SWAR algos (TheCuteCatAlgo, OurSecondAlgorithm):
 *  - Phase 1 fused: classify, accumulate base skills, AND aggregate the
 *    equipped bonus into a single int[5]. We then call {@link WynnPlayer#modify}
 *    ONCE for the free-item batch instead of N times.
 *  - No per-call alloc of items[] / assignedSP[] / boolean[] result. Item refs
 *    cached into instance scratch arrays exactly once per item.
 *  - Single-call modify at end (aggregated bonus across valid items).
 *  - Pre-sized result lists, no defensive copies.
 *  - n==1, n==2 closed-form fast paths; negMask==0 short-circuit.
 *  - Phase 3 SWAR BFS with globalMaxReq short-circuit.
 */
@Information(name = "SwiftMask", version = 3, authors = {"Azael"})
public class SwiftMaskV3Algorithm implements IAlgorithm<WynnPlayer> {

    private static final SkillPoint[] SKILL_POINTS = SkillPoint.values();

    private static final int BIAS = 1024;
    private static final long BIAS5 = 0x0400_4004_0040_0400L;
    private static final long GUARD = 0x0800_8008_0080_0800L;

    private static final int MAX_ITEMS_TOTAL = 64;
    private static final int MAX_PHASE3 = 12;
    private static final int MAX_MASKS = 1 << MAX_PHASE3;

    // Per-remaining-item scratch.
    private final int[][] reqRef = new int[MAX_ITEMS_TOTAL][];
    private final int[][] bonRef = new int[MAX_ITEMS_TOTAL][];
    private final int[] origIdx = new int[MAX_ITEMS_TOTAL];
    private final int[] bonusSum = new int[MAX_ITEMS_TOTAL];

    // Phase-3 packed buffers.
    private final long[] pReq = new long[MAX_ITEMS_TOTAL];
    private final long[] pBon = new long[MAX_ITEMS_TOTAL];
    private final long[] pNeed = new long[MAX_ITEMS_TOTAL];

    private final long[] skState = new long[MAX_MASKS * 2]; // [mask<<1]=skills, +1=mn
    private final int[] weight = new int[MAX_MASKS];
    private final long[] reachBits = new long[(MAX_MASKS + 63) >>> 6];

    // Aggregated bonus sink (one alloc, mutated then handed to player.modify).
    private final int[] applyBuf = new int[5];

    @Override
    public Result run(WynnPlayer player) {
        final List<IEquipment> equipment = player.equipment();
        final int n = equipment.size();

        int sk0 = player.allocated(SKILL_POINTS[0]);
        int sk1 = player.allocated(SKILL_POINTS[1]);
        int sk2 = player.allocated(SKILL_POINTS[2]);
        int sk3 = player.allocated(SKILL_POINTS[3]);
        int sk4 = player.allocated(SKILL_POINTS[4]);

        // Aggregated bonus that will be applied to the player at the very end.
        int ap0 = 0, ap1 = 0, ap2 = 0, ap3 = 0, ap4 = 0;

        // Track "free" items as a bitmask over equipment indices (0..n-1).
        // n is always small in real builds; cap at 64.
        long freeMask = 0L;

        // ── Phase 1: classify, accumulate ────────────────────────────────
        int rem = 0;
        int negMask = 0;
        for (int i = 0; i < n; i++) {
            IEquipment it = equipment.get(i);
            int[] r = it.requirements();
            int[] b = it.bonuses();
            int b0 = b[0], b1 = b[1], b2 = b[2], b3 = b[3], b4 = b[4];
            int reqOr = r[0] | r[1] | r[2] | r[3] | r[4];
            int bonOr = b0 | b1 | b2 | b3 | b4;

            if (reqOr == 0 && bonOr >= 0) {
                freeMask |= 1L << i;
                sk0 += b0; sk1 += b1; sk2 += b2; sk3 += b3; sk4 += b4;
                ap0 += b0; ap1 += b1; ap2 += b2; ap3 += b3; ap4 += b4;
                continue;
            }

            reqRef[rem] = r;
            bonRef[rem] = b;
            origIdx[rem] = i;
            if (bonOr < 0) negMask |= (1 << rem);
            bonusSum[rem] = b0 + b1 + b2 + b3 + b4;
            rem++;
        }

        if (rem == 0) {
            return finish(player, equipment, n, freeMask, 0L, ap0, ap1, ap2, ap3, ap4);
        }

        // ── n==1 closed form ─────────────────────────────────────────────
        if (rem == 1) {
            int[] r = reqRef[0];
            long validRemMask = 0L;
            if ((r[0] == 0 || r[0] <= sk0)
                    && (r[1] == 0 || r[1] <= sk1)
                    && (r[2] == 0 || r[2] <= sk2)
                    && (r[3] == 0 || r[3] <= sk3)
                    && (r[4] == 0 || r[4] <= sk4)) {
                int[] b = bonRef[0];
                ap0 += b[0]; ap1 += b[1]; ap2 += b[2]; ap3 += b[3]; ap4 += b[4];
                validRemMask = 1L;
            }
            return finishWithRem(player, equipment, n, freeMask, validRemMask,
                    rem, ap0, ap1, ap2, ap3, ap4);
        }

        // (rem==2 falls through to greedy + Phase 3 to honor cascade rule)
        // ── Phase 2: scalar greedy with cached refs ─────────────────────
        int activeMask = 0;
        int activeCount = 0;
        int cs0 = sk0, cs1 = sk1, cs2 = sk2, cs3 = sk3, cs4 = sk4;
        boolean changed = true;
        final int allRemMask = (1 << rem) - 1;
        while (changed) {
            changed = false;
            int candidates = (~activeMask) & allRemMask;
            while (candidates != 0) {
                int j = Integer.numberOfTrailingZeros(candidates);
                candidates &= candidates - 1;
                int[] r = reqRef[j];
                int r0 = r[0], r1 = r[1], r2 = r[2], r3 = r[3], r4 = r[4];
                if ((r0 != 0 && r0 > cs0)
                        || (r1 != 0 && r1 > cs1)
                        || (r2 != 0 && r2 > cs2)
                        || (r3 != 0 && r3 > cs3)
                        || (r4 != 0 && r4 > cs4)) continue;

                int[] b = bonRef[j];
                int b0 = b[0], b1 = b[1], b2 = b[2], b3 = b[3], b4 = b[4];

                if ((negMask & (1 << j)) != 0) {
                    int t0 = cs0 + b0, t1 = cs1 + b1, t2 = cs2 + b2, t3 = cs3 + b3, t4 = cs4 + b4;
                    boolean ok = true;
                    for (int ab = activeMask; ab != 0; ab &= ab - 1) {
                        int ai = Integer.numberOfTrailingZeros(ab);
                        int[] ar = reqRef[ai];
                        int[] aB = bonRef[ai];
                        if ((ar[0] != 0 && ar[0] + aB[0] > t0)
                                || (ar[1] != 0 && ar[1] + aB[1] > t1)
                                || (ar[2] != 0 && ar[2] + aB[2] > t2)
                                || (ar[3] != 0 && ar[3] + aB[3] > t3)
                                || (ar[4] != 0 && ar[4] + aB[4] > t4)) { ok = false; break; }
                    }
                    if (!ok) continue;
                }

                activeMask |= (1 << j);
                activeCount++;
                cs0 += b0; cs1 += b1; cs2 += b2; cs3 += b3; cs4 += b4;
                changed = true;
            }
        }

        if (activeCount == rem || negMask == 0 || rem > MAX_PHASE3) {
            // Greedy result is final (or we're skipping Phase 3 due to size cap).
            long validRemMask = activeMask & 0xFFFFFFFFL;
            for (int ab = activeMask; ab != 0; ab &= ab - 1) {
                int j = Integer.numberOfTrailingZeros(ab);
                int[] b = bonRef[j];
                ap0 += b[0]; ap1 += b[1]; ap2 += b[2]; ap3 += b[3]; ap4 += b[4];
            }
            return finishWithRem(player, equipment, n, freeMask, validRemMask,
                    rem, ap0, ap1, ap2, ap3, ap4);
        }

        // ── Phase 3: packed SWAR BFS with bitset reach ───────────────────
        long globalMaxReq = 0;
        for (int j = 0; j < rem; j++) {
            int[] r = reqRef[j];
            int[] b = bonRef[j];
            int r0 = r[0], r1 = r[1], r2 = r[2], r3 = r[3], r4 = r[4];
            int b0 = b[0], b1 = b[1], b2 = b[2], b3 = b[3], b4 = b[4];
            long pr = ((long)(r0 != 0 ? r0 + BIAS : 0))
                    | ((long)(r1 != 0 ? r1 + BIAS : 0) << 12)
                    | ((long)(r2 != 0 ? r2 + BIAS : 0) << 24)
                    | ((long)(r3 != 0 ? r3 + BIAS : 0) << 36)
                    | ((long)(r4 != 0 ? r4 + BIAS : 0) << 48);
            pReq[j] = pr;
            pNeed[j] = ((long)(r0 != 0 ? r0 + b0 + BIAS : 0))
                    | ((long)(r1 != 0 ? r1 + b1 + BIAS : 0) << 12)
                    | ((long)(r2 != 0 ? r2 + b2 + BIAS : 0) << 24)
                    | ((long)(r3 != 0 ? r3 + b3 + BIAS : 0) << 36)
                    | ((long)(r4 != 0 ? r4 + b4 + BIAS : 0) << 48);
            pBon[j] = pack5(b0, b1, b2, b3, b4);
            globalMaxReq = max5(globalMaxReq, pr);
        }

        final int totalMasks = 1 << rem;
        final int fullMask = totalMasks - 1;
        final int words = (totalMasks + 63) >>> 6;
        for (int w = 0; w < words; w++) reachBits[w] = 0;
        reachBits[0] = 1L;

        skState[0] = pack5(sk0, sk1, sk2, sk3, sk4);
        skState[1] = 0;
        weight[0] = 0;

        int bestMask = 0, bestCount = 0, bestWeight = 0;

        outer:
        for (int w = 0; w < words; w++) {
            int base = w << 6;
            long processed = 0;
            long bits;
            while ((bits = reachBits[w] & ~processed) != 0) {
                int pos = Long.numberOfTrailingZeros(bits);
                processed |= 1L << pos;
                int mask = base + pos;
                int count = Integer.bitCount(mask);
                int mw = weight[mask];
                if (count > bestCount || (count == bestCount && mw > bestWeight)) {
                    bestCount = count;
                    bestWeight = mw;
                    bestMask = mask;
                    if (bestCount == rem) break outer;
                }

                long curSk = skState[mask << 1];
                long curMn = skState[(mask << 1) + 1];
                boolean allReqsMet = ge5(curSk, globalMaxReq);

                for (int absent = fullMask & ~mask; absent != 0; absent &= absent - 1) {
                    int j = Integer.numberOfTrailingZeros(absent);
                    int nextMask = mask | (1 << j);
                    if ((reachBits[nextMask >>> 6] & (1L << (nextMask & 63))) != 0) continue;
                    if (!allReqsMet && !ge5(curSk, pReq[j])) continue;
                    long nextSk = curSk + pBon[j] - BIAS5;
                    if ((negMask & (1 << j)) != 0 && !ge5(nextSk, curMn)) continue;
                    int idx = nextMask << 1;
                    skState[idx] = nextSk;
                    skState[idx + 1] = max5(curMn, pNeed[j]);
                    weight[nextMask] = mw + bonusSum[j];
                    reachBits[nextMask >>> 6] |= (1L << (nextMask & 63));
                }
            }
        }

        long validRemMask = bestMask & 0xFFFFFFFFL;
        for (int ab = bestMask; ab != 0; ab &= ab - 1) {
            int j = Integer.numberOfTrailingZeros(ab);
            int[] b = bonRef[j];
            ap0 += b[0]; ap1 += b[1]; ap2 += b[2]; ap3 += b[3]; ap4 += b[4];
        }
        return finishWithRem(player, equipment, n, freeMask, validRemMask,
                rem, ap0, ap1, ap2, ap3, ap4);
    }

    /** Build Result + apply aggregated bonus when all valid items are "free" (no rem items valid). */
    private Result finish(WynnPlayer player, List<IEquipment> equipment, int n,
                          long freeMask, long validRemMaskIgnored,
                          int ap0, int ap1, int ap2, int ap3, int ap4) {
        return finishWithRem(player, equipment, n, freeMask, 0L, 0,
                ap0, ap1, ap2, ap3, ap4);
    }

    /**
     * Build {@link Result} lists and apply the aggregated bonus to the player
     * with a single {@link WynnPlayer#modify} call.
     */
    private Result finishWithRem(WynnPlayer player, List<IEquipment> equipment, int n,
                                 long freeMask, long validRemMask, int rem,
                                 int ap0, int ap1, int ap2, int ap3, int ap4) {
        int validRemPop = Integer.bitCount((int) validRemMask);
        int validCount = Long.bitCount(freeMask) + validRemPop;
        int invalidCount = n - validCount;

        ArrayList<IEquipment> valid = new ArrayList<>(validCount);
        ArrayList<IEquipment> invalid = new ArrayList<>(invalidCount);

        // Apply aggregated bonus once.
        int[] buf = applyBuf;
        buf[0] = ap0; buf[1] = ap1; buf[2] = ap2; buf[3] = ap3; buf[4] = ap4;
        player.modify(buf, true);

        // Common fast path: every item is valid (all "free" or greedy kept all).
        if (invalidCount == 0) {
            valid.addAll(equipment);
            return new Result(valid, invalid);
        }

        // Build a per-equipment-index bitmask from validRemMask via origIdx.
        long validIdxMask = freeMask;
        for (int rb = (int) validRemMask; rb != 0; rb &= rb - 1) {
            int j = Integer.numberOfTrailingZeros(rb);
            validIdxMask |= 1L << origIdx[j];
        }

        for (int i = 0; i < n; i++) {
            IEquipment it = equipment.get(i);
            if ((validIdxMask & (1L << i)) != 0) valid.add(it);
            else invalid.add(it);
        }

        return new Result(valid, invalid);
    }

    /** Closed-form for rem==2 (4 subsets). */
    private Result solve2(WynnPlayer player, List<IEquipment> equipment, int n, long freeMask,
                          int s0, int s1, int s2, int s3, int s4,
                          int ap0, int ap1, int ap2, int ap3, int ap4) {
        int[] rA = reqRef[0], bA = bonRef[0];
        int[] rB = reqRef[1], bB = bonRef[1];

        boolean canA = (rA[0] == 0 || rA[0] <= s0)
                && (rA[1] == 0 || rA[1] <= s1)
                && (rA[2] == 0 || rA[2] <= s2)
                && (rA[3] == 0 || rA[3] <= s3)
                && (rA[4] == 0 || rA[4] <= s4);
        boolean canB = (rB[0] == 0 || rB[0] <= s0)
                && (rB[1] == 0 || rB[1] <= s1)
                && (rB[2] == 0 || rB[2] <= s2)
                && (rB[3] == 0 || rB[3] <= s3)
                && (rB[4] == 0 || rB[4] <= s4);

        long validRemMask = 0L;
        // Try AB: both must remain valid against the FINAL combined state.
        if (canA && canB) {
            int fs0 = s0 + bA[0] + bB[0], fs1 = s1 + bA[1] + bB[1], fs2 = s2 + bA[2] + bB[2],
                fs3 = s3 + bA[3] + bB[3], fs4 = s4 + bA[4] + bB[4];
            boolean bothOk = (rA[0] == 0 || rA[0] <= fs0)
                    && (rA[1] == 0 || rA[1] <= fs1)
                    && (rA[2] == 0 || rA[2] <= fs2)
                    && (rA[3] == 0 || rA[3] <= fs3)
                    && (rA[4] == 0 || rA[4] <= fs4)
                    && (rB[0] == 0 || rB[0] <= fs0)
                    && (rB[1] == 0 || rB[1] <= fs1)
                    && (rB[2] == 0 || rB[2] <= fs2)
                    && (rB[3] == 0 || rB[3] <= fs3)
                    && (rB[4] == 0 || rB[4] <= fs4);
            if (bothOk) {
                ap0 += bA[0] + bB[0]; ap1 += bA[1] + bB[1]; ap2 += bA[2] + bB[2];
                ap3 += bA[3] + bB[3]; ap4 += bA[4] + bB[4];
                validRemMask = 0b11L;
                return finishWithRem(player, equipment, n, freeMask, validRemMask, 2,
                        ap0, ap1, ap2, ap3, ap4);
            }
        }
        // Else pick whichever single one fits (prefer higher bonusSum).
        int sumA = bA[0] + bA[1] + bA[2] + bA[3] + bA[4];
        int sumB = bB[0] + bB[1] + bB[2] + bB[3] + bB[4];
        if (canA && (!canB || sumA >= sumB)) {
            ap0 += bA[0]; ap1 += bA[1]; ap2 += bA[2]; ap3 += bA[3]; ap4 += bA[4];
            validRemMask = 0b01L;
        } else if (canB) {
            ap0 += bB[0]; ap1 += bB[1]; ap2 += bB[2]; ap3 += bB[3]; ap4 += bB[4];
            validRemMask = 0b10L;
        }
        return finishWithRem(player, equipment, n, freeMask, validRemMask, 2,
                ap0, ap1, ap2, ap3, ap4);
    }

    private static long pack5(int d0, int d1, int d2, int d3, int d4) {
        return (long)(d0 + BIAS)
             | ((long)(d1 + BIAS) << 12)
             | ((long)(d2 + BIAS) << 24)
             | ((long)(d3 + BIAS) << 36)
             | ((long)(d4 + BIAS) << 48);
    }

    private static boolean ge5(long skills, long threshold) {
        return (((skills | GUARD) - threshold) & GUARD) == GUARD;
    }

    private static long max5(long a, long b) {
        long gt = ((a | GUARD) - b) & GUARD;
        long ones = gt >>> 11;
        long mask = gt | (gt - ones);
        return (a & mask) | (b & ~mask);
    }
}
