package com.kunukuntla.a11yinspector;

/**
 * Which click ticks this page's checkboxes - a click on the box itself, or on its label -
 * learned within one Tick run, so later rows try the working way first instead of spending
 * the first 450 ms on a click that never works there. Reset at the start of every run and
 * never kept across runs. Plain Java (no Android), so it is unit-tested on its own.
 */
final class ClickMethod {

    enum Way { BOX, LABEL }

    private Way preferred = Way.BOX;

    /** A new run: the box is clicked first again (the existing direct click). */
    void reset() {
        preferred = Way.BOX;
    }

    Way preferred() {
        return preferred;
    }

    /** The first click for a row: the learned way, when this row has a label to click. */
    Way first(boolean rowHasLabel) {
        return preferred == Way.LABEL && rowHasLabel ? Way.LABEL : Way.BOX;
    }

    /**
     * The other way, tried once after the first click's wait ran out - or null: when this row
     * has no label for it, or when the box (read again just now) is already ☑, a late tick by
     * the first click that a second click would undo.
     */
    Way fallback(Way first, boolean rowHasLabel, boolean nowChecked) {
        if (nowChecked) return null;
        Way other = first == Way.BOX ? Way.LABEL : Way.BOX;
        if (other == Way.LABEL && !rowHasLabel) return null;
        return other;
    }

    /**
     * The box read ☑ after {@code way} (verified - not just that the click was sent). Learns
     * it as the way to try first; a box click on a row without a label says nothing against
     * labels, so a learned label way stays. True when the learned way changed.
     */
    boolean verified(Way way, boolean rowHadLabel) {
        if (way == Way.BOX && preferred == Way.LABEL && !rowHadLabel) return false;
        boolean changed = way != preferred;
        preferred = way;
        return changed;
    }
}
