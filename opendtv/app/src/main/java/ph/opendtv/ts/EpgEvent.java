package ph.opendtv.ts;

/** One EIT event. Times are as broadcast (ARIB: JST), stored as "epoch millis" of that wall-clock. */
public final class EpgEvent {
    public int serviceId;
    public int eventId;
    public long startMs = -1;     // -1 when undefined
    public int durationSec;
    public int runningStatus;     // 4 = running, 1 = not running, 2 = starts soon, 0 = undefined
    public String name = "";
    public String text = "";

    public long endMs() {
        return startMs < 0 ? -1 : startMs + durationSec * 1000L;
    }
}
