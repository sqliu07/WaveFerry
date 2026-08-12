package app.shengdu.audio.format;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Map;
import java.nio.charset.StandardCharsets;

/** Parsed RAOP format capabilities. An empty set means that the receiver did not advertise it. */
public final class ReceiverCapabilities {
    private final Set<Integer> sampleRatesHz;
    private final Set<Integer> sampleSizes;
    private final Set<Integer> channelCounts;

    public ReceiverCapabilities(Set<Integer> sampleRatesHz, Set<Integer> sampleSizes, Set<Integer> channelCounts) {
        this.sampleRatesHz = immutableCopy(sampleRatesHz);
        this.sampleSizes = immutableCopy(sampleSizes);
        this.channelCounts = immutableCopy(channelCounts);
    }

    public Set<Integer> sampleRatesHz() { return sampleRatesHz; }
    public Set<Integer> sampleSizes() { return sampleSizes; }
    public Set<Integer> channelCounts() { return channelCounts; }

    public boolean supports(AudioStreamFormat format) {
        return (sampleRatesHz.isEmpty() || sampleRatesHz.contains(format.sampleRateHz()))
                && (sampleSizes.isEmpty() || sampleSizes.contains(format.sampleFormat().bitsPerSample()))
                && (channelCounts.isEmpty() || channelCounts.contains(format.channelCount()));
    }

    public static ReceiverCapabilities fromTxt(Map<String, byte[]> txt) {
        return new ReceiverCapabilities(
                parse(txt, "sr"), parse(txt, "ss"), parse(txt, "ch"));
    }

    private static Set<Integer> parse(Map<String, byte[]> txt, String key) {
        LinkedHashSet<Integer> result = new LinkedHashSet<>();
        if (txt == null) {
            return result;
        }
        for (Map.Entry<String, byte[]> entry : txt.entrySet()) {
            if (entry.getKey() == null || !key.equalsIgnoreCase(entry.getKey()) || entry.getValue() == null) {
                continue;
            }
            String value = new String(entry.getValue(), StandardCharsets.US_ASCII);
            for (String token : value.split("[,\\s/]+")) {
                try {
                    result.add(Integer.parseInt(token.trim()));
                } catch (NumberFormatException ignored) {
                    // Unknown capability tokens are intentionally ignored.
                }
            }
        }
        return result;
    }

    private static Set<Integer> immutableCopy(Set<Integer> values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(values == null ? Collections.emptySet() : values));
    }
}
