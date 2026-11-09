package kz.tungo.sdk;

import java.util.Map;

public final class TungoConformanceTest {
    public static void main(String[] args) {
        assert TungoMoney.toTiyn(45000.0) == 4500000L;
        assert TungoMoney.formatTiyn(4500000L, "KZT").equals("45 000,00 ₸");
        String sig = TungoWebhooks.signHookPayload("{\"event\":\"invoice.paid\",\"invoice_id\":\"INV-2026-0042\",\"amount_tiyn\":12500000}", "hook_sec_demo_99887766");
        assert sig.equals("sha256=342da1d1b976ca2bc9da3c6a3dc8ae657b654acde8a545869fc0ff9d9bc79702");
        assert TungoWebhooks.verifyHookSignature("{\"event\":\"invoice.paid\",\"invoice_id\":\"INV-2026-0042\",\"amount_tiyn\":12500000}", sig, "hook_sec_demo_99887766");
        Map<String, String> headers = Map.of(
            "svix-id", "msg_2abc123def456",
            "svix-timestamp", "1762164000",
            "svix-signature", "v1,deadbeef00000000000000000000000000000000000= v1,b3ccJ/zT1C3+4aR2U2t6q/wH2rK6P/xI5vN7aM9bL0E="
        );
        TungoWebhooks.verifyWebhookSignature(
            "{\"event\":\"proposal.written\",\"timestamp\":\"2026-11-03T10:00:00Z\",\"company_id\":\"co_test123\",\"data\":{\"proposal_id\":\"prop_01\",\"amount_tiyn\":4500000,\"currency\":\"KZT\"}}",
            headers,
            "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw",
            1762164000L,
            300L
        );
    }
}
