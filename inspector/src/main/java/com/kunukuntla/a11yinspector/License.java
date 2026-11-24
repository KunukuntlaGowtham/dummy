package com.kunukuntla.a11yinspector;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.function.Consumer;

/**
 * The licence: a short-lived token your server signs for ONE login on ONE phone.
 * <ul>
 *   <li>This phone makes a key pair in the Android Keystore (the private key can't be copied
 *       out). The server records its public half the first time and accepts the login on this
 *       phone only while the same key signs its challenge - a copied APK or token on another
 *       phone has no such key.</li>
 *   <li>The token (ES256, signed by the server's private key, checked here with the public key
 *       built into the app) names the login, the phone and an expiry; it is renewed in the
 *       background while it has less than 24 h left. Removed on the server: no renewal, and
 *       the app locks when the token runs out.</li>
 *   <li>Off until the build has LICENSE_PUBLIC_KEY (then the app works with the sign-in only).</li>
 * </ul>
 */
final class License {

    private static final String PREFS = "license", ALIAS = "a11y_device_key";
    private static final long RENEW_WHEN_LEFT_S = 24L * 3600;

    private License() {
    }

    static boolean enforced() {
        return !BuildConfig.LICENSE_PUBLIC_KEY.isEmpty();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Why the server refused the licence last time (shown on the sign-in screen), or "". */
    static String lastError(Context c) {
        return prefs(c).getString("error", "");
    }

    /** Signed out: the token and the reason are dropped (the phone's key stays). */
    static void forget(Context c) {
        prefs(c).edit().clear().apply();
    }

    // ---- the token ---------------------------------------------------------------------

    /** A valid token for this login on this phone (always true while the licence layer is off). */
    static boolean ok(Context c) {
        if (!enforced()) return true;
        JSONObject p = payload(c);
        if (p == null) return false;
        long now = System.currentTimeMillis() / 1000;
        SharedPreferences sp = prefs(c);
        long seen = sp.getLong("seen", 0);
        if (now + 300 < seen) return false; // the clock was moved back
        if (now > seen + 60) sp.edit().putLong("seen", now).apply();
        return p.optLong("exp", 0) > now && p.optLong("iat", 0) <= now + 300
                && Auth.userId(c).equals(p.optString("sub", "x"))
                && Auth.deviceId(c).equals(p.optString("did", "x"));
    }

    static boolean dueForRenewal(Context c) {
        if (!enforced()) return false;
        JSONObject p = payload(c);
        return p == null || p.optLong("exp", 0) - System.currentTimeMillis() / 1000 < RENEW_WHEN_LEFT_S;
    }

    /** The token's payload when its signature is the server's (else null). */
    private static JSONObject payload(Context c) {
        try {
            String token = prefs(c).getString("token", "");
            String[] part = token.split("\\.");
            if (part.length != 3) return null;
            PublicKey key = KeyFactory.getInstance("EC").generatePublic(
                    new X509EncodedKeySpec(Base64.decode(BuildConfig.LICENSE_PUBLIC_KEY, Base64.DEFAULT)));
            Signature v = Signature.getInstance("SHA256withECDSA");
            v.initVerify(key);
            v.update((part[0] + "." + part[1]).getBytes(StandardCharsets.US_ASCII));
            if (!v.verify(rawToDer(b64(part[2])))) return null;
            return new JSONObject(new String(b64(part[1]), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    // ---- asking the server -------------------------------------------------------------

    /**
     * A new token: a challenge from the server, signed by this phone's key (and, when set up,
     * with a Play Integrity answer for it), sent back. {@code done}: true when a token came.
     */
    static void renew(Context c, Consumer<Boolean> done) {
        String access = Auth.accessToken(c);
        if (!enforced() || access.isEmpty()) {
            done.accept(false);
            return;
        }
        JSONObject ask = new JSONObject();
        try {
            ask.put("step", "nonce");
        } catch (JSONException e) {
            done.accept(false);
            return;
        }
        Auth.post(c, "/functions/v1/issue-license", ask, access, (code, json) -> {
            String nonce = json == null ? "" : json.optString("nonce", "");
            if (code != 200 || nonce.isEmpty()) {
                failed(c, code, json);
                done.accept(false);
                return;
            }
            integrity(c, nonce, integrityToken -> {
                JSONObject give = new JSONObject();
                try {
                    give.put("step", "issue");
                    give.put("nonce", nonce);
                    give.put("signature", Base64.encodeToString(sign(nonce), Base64.NO_WRAP));
                    give.put("public_key", Base64.encodeToString(deviceKey(), Base64.NO_WRAP));
                    give.put("device_id", Auth.deviceId(c));
                    give.put("model", (android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL).trim());
                    give.put("integrity_token", integrityToken);
                } catch (Exception e) {
                    done.accept(false);
                    return;
                }
                Auth.post(c, "/functions/v1/issue-license", give, access, (code2, json2) -> {
                    String token = json2 == null ? "" : json2.optString("token", "");
                    if (code2 == 200 && !token.isEmpty()) {
                        prefs(c).edit().putString("token", token).remove("error").apply();
                        done.accept(ok(c));
                    } else {
                        failed(c, code2, json2);
                        done.accept(false);
                    }
                });
            });
        });
    }

    /** The server said no (revoked, no licence, too many phones ...): the token is dropped. No connection: kept. */
    private static void failed(Context c, int code, JSONObject json) {
        if (code == 0 || code >= 500) return;
        String why = json == null ? "" : json.optString("message", json.optString("error", ""));
        SharedPreferences.Editor e = prefs(c).edit();
        if (code == 403) e.remove("token");
        e.putString("error", why.isEmpty() ? "The licence was refused (" + code + ")" : why).apply();
    }

    // ---- this phone's key (Android Keystore: the private half never leaves the chip) ---------

    private static byte[] deviceKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            KeyPairGenerator g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
            g.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build());
            g.generateKeyPair();
        }
        return ks.getCertificate(ALIAS).getPublicKey().getEncoded();
    }

    private static byte[] sign(String text) throws Exception {
        deviceKey();
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign((PrivateKey) ks.getKey(ALIAS, null));
        s.update(text.getBytes(StandardCharsets.UTF_8));
        return s.sign();
    }

    // ---- Play Integrity (optional: INTEGRITY_PROJECT_NUMBER in the build) ------------------

    private static void integrity(Context c, String nonce, Consumer<String> done) {
        if (BuildConfig.INTEGRITY_PROJECT_NUMBER.isEmpty()) {
            done.accept("");
            return;
        }
        try {
            com.google.android.play.core.integrity.IntegrityManagerFactory.create(c.getApplicationContext())
                    .requestIntegrityToken(com.google.android.play.core.integrity.IntegrityTokenRequest.builder()
                            .setNonce(nonce)
                            .setCloudProjectNumber(Long.parseLong(BuildConfig.INTEGRITY_PROJECT_NUMBER))
                            .build())
                    .addOnSuccessListener(r -> done.accept(r.token()))
                    .addOnFailureListener(e -> done.accept(""));
        } catch (RuntimeException e) {
            done.accept("");
        }
    }

    // ---- bytes -------------------------------------------------------------------------

    private static byte[] b64(String s) {
        return Base64.decode(s, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    /** A JWS ES256 signature is r||s (64 bytes); Java checks a DER SEQUENCE of two integers. */
    static byte[] rawToDer(byte[] raw) {
        if (raw.length != 64) throw new IllegalArgumentException("not an ES256 signature");
        byte[] r = integer(raw, 0), s = integer(raw, 32);
        byte[] der = new byte[6 + r.length + s.length];
        der[0] = 0x30;
        der[1] = (byte) (4 + r.length + s.length);
        der[2] = 0x02;
        der[3] = (byte) r.length;
        System.arraycopy(r, 0, der, 4, r.length);
        der[4 + r.length] = 0x02;
        der[5 + r.length] = (byte) s.length;
        System.arraycopy(s, 0, der, 6 + r.length, s.length);
        return der;
    }

    private static byte[] integer(byte[] raw, int from) {
        int start = from;
        while (start < from + 31 && raw[start] == 0) start++;
        int len = from + 32 - start;
        boolean pad = (raw[start] & 0x80) != 0;
        byte[] out = new byte[len + (pad ? 1 : 0)];
        System.arraycopy(raw, start, out, pad ? 1 : 0, len);
        return out;
    }
}
