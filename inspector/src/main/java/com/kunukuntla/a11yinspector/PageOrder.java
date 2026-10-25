package com.kunukuntla.a11yinspector;

/**
 * The booking's pages always come in one order: 1 → 2 → 3 → 4 (and back, one at a time).
 * A reading is taken only as the page it says when it fits that order - the same page, the
 * next one, the one before, or page 1 (back to the start). A reading that jumps (page 1 →
 * page 3, page 2 → page 4: usually a page still drawing, e.g. page 2 before its calendar
 * shows) counts only once it holds for {@link #JUMP_READS} readings in a row. A reading
 * that is no booking page (a loading screen) leaves the place in the order as it is.
 */
final class PageOrder {

    /** Readings in a row a jump must hold before it counts. */
    static final int JUMP_READS = 2;

    private int pos, jumpTo, jumpCount;

    /** The page now (1-4), 0 when none yet. */
    int pos() {
        return pos;
    }

    /** Known for sure (e.g. Back from page 2 reached page 1). */
    void set(int page) {
        pos = page;
        jumpCount = 0;
    }

    /**
     * This reading says {@code page} (1-4, 0: not a booking page). Returns the page it is
     * taken as, or 0 - not a booking page, or a jump not yet holding (read again soon).
     */
    int next(int page) {
        if (page <= 0) return 0;
        if (pos == 0 || page == 1 || Math.abs(page - pos) <= 1) {
            set(page);
            return page;
        }
        if (page == jumpTo) {
            jumpCount++;
        } else {
            jumpTo = page;
            jumpCount = 1;
        }
        if (jumpCount >= JUMP_READS) {
            set(page);
            return page;
        }
        return 0;
    }

    /** A jump waits for more readings. */
    boolean waiting() {
        return jumpCount > 0;
    }
}
