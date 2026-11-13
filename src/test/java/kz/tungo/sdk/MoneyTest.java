package kz.tungo.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import kz.tungo.sdk.errors.ValidationException;
import org.junit.jupiter.api.Test;

class MoneyTest {
    @Test
    void bigDecimalWithAHugeExponentIsBoundedBeforeItIsWrittenOut() {
        // A BigDecimal parsed from user text may carry any exponent: no two-billion-character
        // string, no OutOfMemoryError, only 0 or a ValidationException.
        long started = System.nanoTime();
        assertEquals(0, TungoMoney.toTiyn(new BigDecimal("1E-2000000000")));
        assertEquals(0, TungoMoney.toTiyn(new BigDecimal("-1E-999999999")));
        assertEquals(0, TungoMoney.toTiyn(new BigDecimal("0E-2000000000")));
        assertEquals(0, TungoMoney.toTiyn(new BigDecimal("9.99E-4")));
        assertEquals(1, TungoMoney.toTiyn(new BigDecimal("5E-3")));
        assertEquals(-1, TungoMoney.toTiyn(new BigDecimal("-5E-3")));
        assertEquals(10050, TungoMoney.toTiyn(new BigDecimal("1.005E2")));
        assertEquals(9223372036854775800L, TungoMoney.toTiyn(new BigDecimal("9.2233720368547758E16")));
        assertThrows(ValidationException.class, () -> TungoMoney.toTiyn(new BigDecimal("1E+999999999")));
        assertThrows(ValidationException.class, () -> TungoMoney.toTiyn(new BigDecimal("-1E19")));
        assertTrue(System.nanoTime() - started < 2_000_000_000L, "the conversion must stay cheap");
    }

    @Test
    void roundsHalfAwayFromZeroExactly() {
        assertEquals(101, TungoMoney.toTiyn("1.005"));
        assertEquals(29, TungoMoney.toTiyn("0.285"));
        assertEquals(833, TungoMoney.toTiyn("8.325"));
        assertEquals(13, TungoMoney.toTiyn("0.125"));
        assertEquals(-101, TungoMoney.toTiyn("-1.005"));
        assertEquals(101, TungoMoney.toTiyn(new BigDecimal("1.005")));
        assertEquals(-101, TungoMoney.toTiyn(new BigDecimal("-1.005")));
        assertEquals(101, TungoMoney.toTiyn(1.005));
        assertEquals(29, TungoMoney.toTiyn(0.285));
        assertEquals(new BigDecimal("12.34"), TungoMoney.toTenge(1234));
    }

    @Test
    void badInputIsATypedError() {
        for (String bad : new String[] {"", "abc", "1,5", " 1", "1e3", "+1", "92233720368547758.08", null}) {
            assertThrows(ValidationException.class, () -> TungoMoney.toTiyn(bad), String.valueOf(bad));
        }
        assertThrows(ValidationException.class, () -> TungoMoney.toTiyn(Double.NaN));
        assertThrows(ValidationException.class, () -> TungoMoney.toTiyn(Double.POSITIVE_INFINITY));
        assertThrows(ValidationException.class, () -> TungoMoney.toTiyn(1e21));
        assertThrows(ValidationException.class, () -> TungoMoney.toTiyn((BigDecimal) null));
    }

    @Test
    void formatsEveryInt64() {
        assertEquals("0,00 ₸", TungoMoney.formatTiyn(0));
        assertEquals("-0,50 ₸", TungoMoney.formatTiyn(-50));
        assertEquals("1 234,56 GBP", TungoMoney.formatTiyn(123456, "GBP"));
        assertTrue(TungoMoney.formatTiyn(Long.MIN_VALUE).startsWith("-92 233 720 368 547 758,08"));
        assertTrue(TungoMoney.formatTiyn(Long.MAX_VALUE).startsWith("92 233 720 368 547 758,07"));
    }

    @Test
    void validatesBinIin() {
        assertTrue(TungoMoney.isValidBinIin("990140000017"));
        assertFalse(TungoMoney.isValidBinIin(" 990140000017"));
        assertFalse(TungoMoney.isValidBinIin("99014000001"));
        assertFalse(TungoMoney.isValidBinIin(null));
    }
}
