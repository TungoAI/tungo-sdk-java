package kz.tungo.sdk;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kz.tungo.sdk.errors.ValidationException;

/**
 * Money helpers. The API expresses amounts in tiyn (1/100 of a tenge) as 64-bit integers.
 */
public final class TungoMoney {
    private static final Pattern DECIMAL = Pattern.compile("^(-?)([0-9]+)(?:\\.([0-9]+))?$");
    private static final BigInteger MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private static final BigInteger MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger HUNDRED = BigInteger.valueOf(100);

    private TungoMoney() {
    }

    /**
     * Converts a decimal amount to tiyn exactly, rounding half away from zero to two places
     * ({@code "1.005"} gives 101, {@code "-1.005"} gives -101).
     *
     * @param amount a decimal string: an optional minus sign, digits, and optionally a dot and
     *     more digits; ASCII only, no spaces, no exponent
     * @return the amount in tiyn
     * @throws ValidationException when the text is not a decimal number or the result does not
     *     fit into 64 bits
     */
    public static long toTiyn(String amount) {
        if (amount == null) {
            throw new ValidationException("The amount must not be null");
        }
        Matcher m = DECIMAL.matcher(amount);
        if (!m.matches()) {
            throw new ValidationException("The amount must be a decimal number such as \"1234.56\"");
        }
        String fraction = (m.group(3) == null ? "" : m.group(3)) + "000";
        BigInteger n = new BigInteger(m.group(2) + fraction.substring(0, 2));
        if (fraction.charAt(2) >= '5') {
            n = n.add(BigInteger.ONE);
        }
        if (!m.group(1).isEmpty()) {
            n = n.negate();
        }
        if (n.compareTo(MAX) > 0 || n.compareTo(MIN) < 0) {
            throw new ValidationException("The amount does not fit into 64-bit tiyn");
        }
        return n.longValue();
    }

    /**
     * Converts an exact decimal amount to tiyn, rounding half away from zero to two places.
     *
     * @param amount the amount in tenge
     * @return the amount in tiyn
     * @throws ValidationException when the amount is null or the result does not fit into 64 bits
     */
    public static long toTiyn(BigDecimal amount) {
        if (amount == null) {
            throw new ValidationException("The amount must not be null");
        }
        // Bound the exponent before writing the value out: new BigDecimal("1E-2000000000") would
        // otherwise become a two-billion-character string. precision() - scale() is the number of
        // digits before the point: below 10^-3 an amount rounds to zero tiyn, and at 10^19 tenge
        // and above it cannot fit into 64-bit tiyn.
        long integerDigits = (long) amount.precision() - amount.scale();
        if (amount.signum() == 0 || integerDigits < -2) {
            return 0;
        }
        if (integerDigits > 19) {
            throw new ValidationException("The amount does not fit into 64-bit tiyn");
        }
        return toTiyn(amount.toPlainString());
    }

    /**
     * Converts a binary floating-point amount to tiyn through its shortest decimal
     * representation ({@code 1.005} gives 101). Prefer the {@link String} or {@link BigDecimal}
     * overloads: a {@code double} cannot hold most decimal fractions exactly.
     *
     * @param amount the amount in tenge
     * @return the amount in tiyn
     * @throws ValidationException when the amount is NaN, infinite or does not fit into 64 bits
     */
    public static long toTiyn(double amount) {
        if (Double.isNaN(amount) || Double.isInfinite(amount)) {
            throw new ValidationException("The amount must be a finite number");
        }
        return toTiyn(BigDecimal.valueOf(amount));
    }

    /**
     * Converts tiyn to tenge exactly.
     *
     * @param tiyn the amount in tiyn
     * @return the amount in tenge with two decimal places
     */
    public static BigDecimal toTenge(long tiyn) {
        return BigDecimal.valueOf(tiyn, 2);
    }

    /**
     * Formats tiyn as tenge for display: {@code 4500000} gives {@code "45 000,00 ₸"}.
     *
     * @param tiyn the amount in tiyn
     * @return the formatted amount
     */
    public static String formatTiyn(long tiyn) {
        return formatTiyn(tiyn, "KZT");
    }

    /**
     * Formats tiyn for display: thousands separated by a space, a comma, two decimals, a space
     * and the currency symbol (KZT ₸, USD $, EUR €, RUB ₽) or the upper-cased currency code.
     *
     * @param tiyn the amount in minor units
     * @param currency the currency code, case-insensitive; null or empty means KZT
     * @return the formatted amount, for example {@code "-0,50 ₸"} or {@code "1 234,56 GBP"}
     */
    public static String formatTiyn(long tiyn, String currency) {
        String symbol = currency == null ? "" : currency.toUpperCase(Locale.ROOT);
        switch (symbol) {
            case "":
            case "KZT":
                symbol = "₸";
                break;
            case "USD":
                symbol = "$";
                break;
            case "EUR":
                symbol = "€";
                break;
            case "RUB":
                symbol = "₽";
                break;
            default:
                break;
        }
        BigInteger[] parts = BigInteger.valueOf(tiyn).abs().divideAndRemainder(HUNDRED);
        String digits = parts[0].toString();
        StringBuilder sb = new StringBuilder();
        if (tiyn < 0) {
            sb.append('-');
        }
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) {
                sb.append(' ');
            }
            sb.append(digits.charAt(i));
        }
        int fraction = parts[1].intValue();
        sb.append(',').append((char) ('0' + fraction / 10)).append((char) ('0' + fraction % 10));
        return sb.append(' ').append(symbol).toString();
    }

    /**
     * Checks a Kazakhstan BIN or IIN: exactly 12 ASCII digits with a correct check digit. The
     * value is not trimmed.
     *
     * @param value the candidate
     * @return true when the value is a valid BIN or IIN
     */
    public static boolean isValidBinIin(String value) {
        if (value == null || value.length() != 12) {
            return false;
        }
        int[] d = new int[12];
        for (int i = 0; i < 12; i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
            d[i] = c - '0';
        }
        int sum = 0;
        for (int i = 0; i < 11; i++) {
            sum += d[i] * (i + 1);
        }
        int control = sum % 11;
        if (control == 10) {
            int[] weights = {3, 4, 5, 6, 7, 8, 9, 10, 11, 1, 2};
            sum = 0;
            for (int i = 0; i < 11; i++) {
                sum += d[i] * weights[i];
            }
            control = sum % 11;
        }
        return control < 10 && control == d[11];
    }
}
