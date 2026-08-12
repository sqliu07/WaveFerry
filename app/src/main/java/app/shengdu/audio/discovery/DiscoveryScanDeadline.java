package app.shengdu.audio.discovery;

/** Tracks one visible scan round across immediate AirPlay and bounded DLNA discovery. */
public final class DiscoveryScanDeadline {
    private boolean armed;
    private boolean boundedScanFinished;
    private boolean timedOut;

    public void begin(boolean hasDevices) {
        armed = !hasDevices;
        boundedScanFinished = false;
        timedOut = false;
    }

    public void onDevicesFound() {
        if (boundedScanFinished) {
            complete();
        }
    }

    public void onBoundedScanFinished(boolean hasDevices) {
        boundedScanFinished = true;
        if (hasDevices) {
            complete();
        }
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

    private void complete() {
        armed = false;
        timedOut = false;
    }
}
