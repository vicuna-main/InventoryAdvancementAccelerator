package dev.invadvopt.index;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

public final class IdentitySet {
    private IdentitySet() {}

    public static <T> Set<T> create() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }
}
