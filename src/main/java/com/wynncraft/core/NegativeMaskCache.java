package com.wynncraft.core;

import speiger.src.collections.ints.lists.IntArrayList;

public class NegativeMaskCache {

    // Increase this and you will use a shitton of ram and cpu
    // to compute combinations :(
    private static final int MAX_ITEMS = 18;
    final IntArrayList[] _cache;

    public NegativeMaskCache() {
        this._cache = populate();
    }

    /**
     * Retrieves the negative item mask with all possible combinations
     * based on the provided amount of items
     *
     * @param items the amount of items to retrieve
     * @return the resulting int array
     */
    public IntArrayList get(int items) {
        return _cache[items];
    }

    /**
     * Generates and populates te cache of negative masks appropriately
     * @return the resulting populated cahce
     */
    private IntArrayList[] populate() {
        IntArrayList[] result = new IntArrayList[MAX_ITEMS];
        {
            for (int items = 0; items < MAX_ITEMS; items++) {
                IntArrayList list = new IntArrayList();
                {
                    // Include all possible combinations
                    int combinations = 1 << items;
                    for (int i = 0; i < combinations; i++) {
                        list.add(i);
                    }

                    // Sort them by most items
                    list.sort(
                        (a, b) -> Integer.bitCount(b) - Integer.bitCount(a)
                    );
                }
                result[items] = list;
            }
        }
        return result;
    }

}
