package com.trophy.promostandards.sync;

import java.util.List;

/**
 * A grouping (or a split) was asked to be applied that its own preview refuses. Nothing was written: every
 * reason is checked before the first mutation, against a fresh listing of the store.
 */
public class GroupConflictException extends RuntimeException {

    private final List<String> conflicts;

    public GroupConflictException(List<String> conflicts) {
        this("Grouping", conflicts);
    }

    /** @param what what was not applied, for the message: "Grouping", "Split" */
    public GroupConflictException(String what, List<String> conflicts) {
        super(what + " not applied: " + String.join("; ", conflicts));
        this.conflicts = List.copyOf(conflicts);
    }

    public List<String> conflicts() {
        return conflicts;
    }
}
