package kz.tungo.sdk;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

public final class TungoWebhooks {
    private TungoWebhooks() {}

    public static String signHookPayload(String rawBody, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new TungoError(0, "crypto_error", e.getMessage(), null, null, null);
        }
    }

    public static boolean verifyHookSignature(String rawBody, String signature, String secret) {
        if (signature == null || secret == null) return false;
        String trimmed = signature.trim();
        if (trimmed.isEmpty() || secret.isEmpty()) return false;
        String hex = trimmed.startsWith("sha256=") ? trimmed.substring(7) : trimmed;
        String expected = signHookPayload(rawBody, secret).substring(7);
        return MessageDigest.isEqual(
            expected.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8),
            hex.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)
        );
    }

    public static String verifyWebhookSignature(
        String rawBody,
        Map<String, String> headers,
        String secret,
        Long nowUnix,
        long toleranceSec
    ) {
        String msgId = findHeader(headers, "svix-id", "webhook-id");
        String msgTs = findHeader(headers, "svix-timestamp", "webhook-timestamp");
        String msgSig = findHeader(headers, "svix-signature", "webhook-signature");
        if (msgId == null || msgTs == null || msgSig == null) {
            throw new TungoError(0, "webhook_error", "Отсутствуют заголовки svix-id / svix-timestamp / svix-signature", null, null, null);
        }
        long ts = Long.parseLong(msgTs);
        if (nowUnix != null && toleranceSec > 0 && Math.abs(nowUnix - ts) > toleranceSec) {
            throw new TungoError(0, "webhook_error", "Метка времени вебхука вне допустимого окна", null, null, null);
        }
        String cleanSecret = secret.startsWith("whsec_") ? secret.substring(6) : secret;
        byte[] keyBytes = Base64.getDecoder().decode(cleanSecret);
        String signed = msgId + "." + msgTs + "." + rawBody;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keyBytes, "HmacSHA256"));
            String expectedB64 = Base64.getEncoder().encodeToString(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8)));
            for (String part : msgSig.trim().split("\\s+")) {
                if (part.startsWith("v1,")) {
                    String cand = part.substring(3);
                    if (MessageDigest.isEqual(expectedB64.getBytes(StandardCharsets.UTF_8), cand.getBytes(StandardCharsets.UTF_8))) {
                        return rawBody;
                    }
                }
            }
        } catch (Exception e) {
            throw new TungoError(0, "webhook_error", e.getMessage(), null, null, null);
        }
        throw new TungoError(0, "webhook_error", "Неверная подпись svix-signature", null, null, null);
    }

    private static String findHeader(Map<String, String> headers, String... names) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String kl = entry.getKey().toLowerCase(Locale.ROOT);
            for (String n : names) {
                if (kl.equals(n)) return entry.getValue();
            }
        }
        return null;
    }
}
