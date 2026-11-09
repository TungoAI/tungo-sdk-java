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

    /** Проверяет контрольную сумму 12-значного БИН или ИИН Республики Казахстан (модуль 11, два прохода). */
    public static boolean isValidBinIin(String value) {
        if (value == null || value.length() != 12) return false;
        int[] digits = new int[12];
        for (int i = 0; i < 12; i++) {
            char ch = value.charAt(i);
            if (ch < '0' || ch > '9') return false;
            digits[i] = ch - '0';
        }
        int[] w1 = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
        int sum1 = 0;
        for (int i = 0; i < 11; i++) sum1 += digits[i] * w1[i];
        int control = sum1 % 11;
        if (control == 10) {
            int[] w2 = {3, 4, 5, 6, 7, 8, 9, 10, 11, 1, 2};
            int sum2 = 0;
            for (int i = 0; i < 11; i++) sum2 += digits[i] * w2[i];
            control = sum2 % 11;
        }
        return control < 10 && control == digits[11];
    }
}
