package app.shengdu.audio.discovery;

/** Tracks whether an empty receiver scan is still waiting for its deadline. */
public final class DiscoveryScanDeadline {
    private boolean armed;
    private boolean timedOut;

    public void begin(boolean hasDevices) {
        armed = !hasDevices;
        timedOut = false;
    }

    public void onDevicesFound() {
        armed = false;
        timedOut = false;
    }

    public void cancel() {
        armed = false;
    }

    public boolean expire() {
        if (!armed) {
            return false;
        }
        armed = false;
        timedOut = true;
        return true;
    }

    public boolean isArmed() {
        return armed;
    }

    public boolean hasTimedOut() {
        return timedOut;
    }
}
