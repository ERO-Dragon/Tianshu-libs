package com.rheinmetal.tianshu.libs.llm;

/**
 * Normalizes user-facing device selectors before passing them to JJML.
 */
public final class DeviceSelector {
    private DeviceSelector() {
    }

    public static String normalize(String selector) {
        if (selector == null || selector.isBlank()) return null;
        String normalized = selector.trim();
        if (normalized.startsWith("#")) return normalized;
        if (isUnsignedInteger(normalized)) return "#" + stripLeadingZeroes(normalized);
        return normalized;
    }

    private static boolean isUnsignedInteger(String value) {
        if (value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) return false;
        }
        return true;
    }

    private static String stripLeadingZeroes(String value) {
        int index = 0;
        while (index < value.length() - 1 && value.charAt(index) == '0') {
            index++;
        }
        return value.substring(index);
    }
}
