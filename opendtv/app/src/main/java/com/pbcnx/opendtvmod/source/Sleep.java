package com.pbcnx.opendtvmod.source;

/**
 * One interrupt-correct sleep for the drivers and the hub. Four classes each carried the same
 * private copy; the copies had drifted only in parameter names.
 */
public final class Sleep {

    private Sleep() {
    }

    /** Sleeps, restoring the interrupt flag instead of swallowing it. */
    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
