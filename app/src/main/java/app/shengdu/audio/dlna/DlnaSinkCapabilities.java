package app.shengdu.audio.dlna;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Parsed ConnectionManager SinkProtocolInfo advertised by a MediaRenderer. */
public final class DlnaSinkCapabilities {
    private final List<String> protocolInfos;

    private DlnaSinkCapabilities(List<String> protocolInfos) {
        this.protocolInfos = Collections.unmodifiableList(protocolInfos);
    }

    public static DlnaSinkCapabilities parse(String sinkProtocolInfo) {
        ArrayList<String> entries = new ArrayList<>();
        if (sinkProtocolInfo != null) {
            for (String entry : sinkProtocolInfo.split(",")) {
                String trimmed = entry.trim();
                if (!trimmed.isEmpty()) {
                    entries.add(trimmed);
                }
            }
        }
        return new DlnaSinkCapabilities(entries);
    }

    public List<String> protocolInfos() {
        return protocolInfos;
    }

    public boolean supportsLiveWav() {
        for (String info : protocolInfos) {
            String[] parts = info.split(":", 4);
            if (parts.length < 3 || !("http-get".equalsIgnoreCase(parts[0]) || "*".equals(parts[0]))) {
                continue;
            }
            String mimeType = parts[2].toLowerCase(java.util.Locale.US);
            if ("*".equals(mimeType) || "audio/wav".equals(mimeType)
                    || "audio/x-wav".equals(mimeType)) {
                return true;
            }
        }
        return false;
    }

    public WavSupport wavSupport() {
        if (protocolInfos.isEmpty()) {
            return WavSupport.UNKNOWN;
        }
        return supportsLiveWav() ? WavSupport.ADVERTISED : WavSupport.NOT_ADVERTISED;
    }

    public enum WavSupport {
        UNKNOWN,
        ADVERTISED,
        NOT_ADVERTISED
    }
}
