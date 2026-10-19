package dev.flipmod;

public final class Fmt {
    private Fmt() {}

    /** 1234567 -> "1.23m" */
    public static String coins(long v) {
        double a = Math.abs((double) v);
        if (a >= 1e9) return String.format("%.2fb", v / 1e9);
        if (a >= 1e6) return String.format("%.2fm", v / 1e6);
        if (a >= 1e3) return String.format("%.1fk", v / 1e3);
        return Long.toString(v);
    }
}
