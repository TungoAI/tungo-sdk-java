package kz.tungo.sdk;

import java.util.Map;

public final class TungoConformanceTest {
    public static void main(String[] args) {
        assert TungoMoney.toTiyn(45000.0) == 4500000L;
        assert TungoMoney.formatTiyn(4500000L, "KZT").equals("45 000,00 ₸");
        assert TungoMoney.isValidBinIin("990140000017");
        assert TungoMoney.isValidBinIin("991140000015");
        assert !TungoMoney.isValidBinIin("990140000016");
        String sig = TungoWebhooks.signHookPayload("{\"event\":\"order.paid\",\"order_id\":\"ORD-771\",\"amount_tiyn\":12500000}", "hook_secret_tungo_2026");
        assert TungoWebhooks.verifyHookSignature("{\"event\":\"order.paid\",\"order_id\":\"ORD-771\",\"amount_tiyn\":12500000}", sig, "hook_secret_tungo_2026");
        assert TungoWebhooks.verifyHookSignature("{\"event\":\"order.paid\",\"order_id\":\"ORD-771\",\"amount_tiyn\":12500000}", sig.substring(7), "hook_secret_tungo_2026");
        Map<String, String> headers = Map.of(
            "svix-id", "msg_2xTungoTestId998877",
            "svix-timestamp", "1793974568",
            "svix-signature", "v1,0000000000000000000000000000000000000000000="
        );
        assert headers.containsKey("svix-id");
    }
}
