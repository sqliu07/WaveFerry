package app.shengdu.audio.update;

import java.util.regex.Pattern;

/** Numeric release tags, with an optional v prefix and no prerelease suffix. */
final class NumericVersion implements Comparable<NumericVersion> {
    private static final Pattern FORMAT = Pattern.compile("[vV]?[0-9]+(?:\\.[0-9]+)*");
    private static final int MAX_LENGTH = 128;

    private final String name;
    private final String[] components;

    private NumericVersion(String name) {
        this.name = name;
        components = name.split("\\.");
    }

    static NumericVersion parse(String value) {
        if (value == null || value.length() > MAX_LENGTH || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid numeric version");
        }
        return new NumericVersion(value.charAt(0) == 'v' || value.charAt(0) == 'V'
                ? value.substring(1) : value);
    }

    String getName() {
        return name;
    }

    @Override
    public int compareTo(NumericVersion other) {
        int count = Math.max(components.length, other.components.length);
        for (int index = 0; index < count; index++) {
            String left = normalizedComponent(index < components.length ? components[index] : "0");
            String right = normalizedComponent(index < other.components.length ? other.components[index] : "0");
            int comparison = Integer.compare(left.length(), right.length());
            if (comparison == 0) {
                comparison = left.compareTo(right);
            }
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    private static String normalizedComponent(String component) {
        int firstDigit = 0;
        while (firstDigit < component.length() - 1 && component.charAt(firstDigit) == '0') {
            firstDigit++;
        }
        return component.substring(firstDigit);
    }
}
