package com.ishm.auth;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Hand-rolled JWT utility using HMAC-SHA256.
 * Does NOT depend on Micronaut Security token infrastructure.
 */
@Singleton
public class JwtUtil {

    private static final Logger LOG = LoggerFactory.getLogger(JwtUtil.class);
    private static final String ALGORITHM = "HmacSHA256";
    private static final long DEFAULT_EXPIRY_MS = 86400 * 1000L; // 24h

    private final String secret;

    public JwtUtil(
            @Value("${jwt.custom.secret:ISHMSuperSecretKeyForJWT2025Production!@#$}") String secret) {
        this.secret = secret;
    }

    /** Build and sign a JWT. Claims must be simple String/Number/Boolean values. */
    public String generateToken(Map<String, Object> claims) {
        try {
            // Header
            String header = base64url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");

            // Payload — add iat + exp
            long now = System.currentTimeMillis() / 1000;
            Map<String, Object> payload = new HashMap<>(claims);
            payload.put("iat", now);
            payload.put("exp", now + DEFAULT_EXPIRY_MS / 1000);

            String payloadJson = mapToJson(payload);
            String payloadEncoded = base64url(payloadJson);

            // Signature
            String signingInput = header + "." + payloadEncoded;
            String signature = hmacSha256(signingInput, secret);

            return signingInput + "." + signature;
        } catch (Exception e) {
            LOG.error("JWT generation error", e);
            throw new RuntimeException("Could not generate token", e);
        }
    }

    /**
     * Verify token signature and expiry.
     * Returns parsed claims if valid, throws {@link JwtException} otherwise.
     */
    public Map<String, Object> verify(String token) {
        if (token == null || token.isBlank()) throw new JwtException("Token is empty");

        String[] parts = token.split("\\.");
        if (parts.length != 3) throw new JwtException("Malformed token");

        try {
            // Verify signature
            String signingInput = parts[0] + "." + parts[1];
            String expectedSig = hmacSha256(signingInput, secret);
            if (!constantTimeEquals(expectedSig, parts[2])) {
                throw new JwtException("Invalid signature");
            }

            // Decode payload
            String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            Map<String, Object> claims = jsonToMap(payloadJson);

            // Check expiry
            Object exp = claims.get("exp");
            if (exp != null) {
                long expiry = ((Number) exp).longValue();
                if (System.currentTimeMillis() / 1000 > expiry) {
                    throw new JwtException("Token expired");
                }
            }

            return claims;
        } catch (JwtException e) {
            throw e;
        } catch (Exception e) {
            LOG.warn("JWT verification failed: {}", e.getMessage());
            throw new JwtException("Token verification failed: " + e.getMessage());
        }
    }

    /** Extract subject (username) from a verified token. */
    public String getSubject(String token) {
        Map<String, Object> claims = verify(token);
        Object sub = claims.get("sub");
        if (sub == null) throw new JwtException("No subject in token");
        return sub.toString();
    }

    // ─── Internal helpers ────────────────────────────────────────────────────

    private String hmacSha256(String data, String key) throws Exception {
        Mac mac = Mac.getInstance(ALGORITHM);
        SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), ALGORITHM);
        mac.init(keySpec);
        byte[] rawHmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(rawHmac);
    }

    private String base64url(String input) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(input.getBytes(StandardCharsets.UTF_8));
    }

    /** Constant-time string comparison to prevent timing attacks. */
    private boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    /** Minimal JSON serialiser for Map<String, Object> — handles String, Number, Boolean, null. */
    private String mapToJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v == null) sb.append("null");
            else if (v instanceof Number || v instanceof Boolean) sb.append(v);
            else sb.append("\"").append(v.toString().replace("\"", "\\\"")).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    /** Minimal JSON parser for the payload — handles string/number/boolean values. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonToMap(String json) {
        // Use Jackson which is already on classpath
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            return om.readValue(json, Map.class);
        } catch (Exception e) {
            throw new JwtException("Could not parse payload: " + e.getMessage());
        }
    }

    public static class JwtException extends RuntimeException {
        public JwtException(String msg) { super(msg); }
    }
}
