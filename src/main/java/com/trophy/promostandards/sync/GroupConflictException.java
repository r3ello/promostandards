package com.trophy.promostandards.sync;

import java.util.List;

/**
 * A grouping was asked to be applied that its own preview refuses. Nothing was written: every
 * reason is checked before the first mutation, against a fresh listing of the store.
 */
public class GroupConflictException extends RuntimeException {

    private final List<String> conflicts;

    public GroupConflictException(List<String> conflicts) {
        super("Grouping not applied: " + String.join("; ", conflicts));
        this.conflicts = List.copyOf(conflicts);
    }

    public List<String> conflicts() {
        return conflicts;
    }
}
