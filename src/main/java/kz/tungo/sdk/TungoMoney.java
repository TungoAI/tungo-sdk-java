package kz.tungo.sdk;

import java.util.Locale;

public final class TungoMoney {
    private TungoMoney() {}

    public static long toTiyn(double amount) {
        return Math.round(amount * 100.0);
    }

    public static double toTenge(long tiyn) {
        return tiyn / 100.0;
    }

    public static String formatTiyn(long tiyn, String currency) {
        String symbol = switch (currency == null ? "KZT" : currency.trim().toUpperCase(Locale.ROOT)) {
            case "", "KZT" -> "₸";
            case "USD" -> "$";
            case "EUR" -> "€";
            case "RUB" -> "₽";
            default -> currency.trim().toUpperCase(Locale.ROOT);
        };
        boolean neg = tiyn < 0;
        long abs = Math.abs(tiyn);
        long whole = abs / 100;
        long frac = abs % 100;
        String wholeStr = Long.toString(whole);
        StringBuilder grouped = new StringBuilder();
        for (int i = 0; i < wholeStr.length(); i++) {
            if (i > 0 && (wholeStr.length() - i) % 3 == 0) {
                grouped.append(' ');
            }
            grouped.append(wholeStr.charAt(i));
        }
        return String.format(Locale.ROOT, "%s%s,%02d %s", neg ? "-" : "", grouped, frac, symbol);
    }
}
