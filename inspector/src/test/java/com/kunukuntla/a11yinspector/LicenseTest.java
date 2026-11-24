package com.kunukuntla.a11yinspector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import org.junit.Test;

/**
 * The server (WebCrypto) signs the licence as r||s; the app checks it as a DER signature:
 * License.rawToDer must turn the first into the second for every signature, including those
 * with a leading zero or a high first bit. The vectors are made with a throwaway key.
 */
public class LicenseTest {

    private static final String SPKI = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEV6EclTbkwgqPiiOdvt/HCyj5I8hx4Lo3AR310ZeZfHF2VU+2rSqUcDKcrYt+FMu4LahvXDQOz7SAKLanA9i1bQ==";

    private static final String[][] SIGNED = {
            {"header.payload0", "o5yrQ1I1TlDNozzsg/gx7AUnfoBr1aUj2g/v7aCIemVpxODYSVac1jrNRg+ZQBzNncpYYzBHne+g0t8/Gvutxw=="},
            {"header.payload1", "qRoHSvyq+D70uTbj2H8B7xPHMhlbhBfFqu8EZM/xH/UdN8B2h2Pq5qwZJkdYZGWDGURMgZECrDFnzqFHuaFmpg=="},
            {"header.payload2", "qFwyfEZ9PoLfOXAGfzzW1uDhSci/pW0NJ8vxaT5oVPjZ2dK6CV5RAD0UwHS7kolYB7tEBAl08DiQyrBRvg/gqg=="},
            {"header.payload3", "8s0LSYMGoooEsHacLSk3+YEkEtRefhL8B3pdaMarBWyRYRz8BR7yIMG8RvaR6jKRVKtm4+f8s0aLLq6xc1bkAA=="},
            {"header.payload4", "ybbVQ+78BHdp9KmB6R2bOFuUvrohc66X7obDYPzlKt9qTBFJHhV6eBnK31+emKBrR847NP3K0qs/iWwyeQpisA=="},
            {"header.payload5", "Tbt4lOmgwBPCTNQ8uWJt9clg4nX8/mtVtg8AYWetGE+T8eL6x+uW3hZCoLnEUTk9C5V86vBd+h1Bd3/Sk3YhqA=="},
            {"header.payload6", "vx4uIci3mZhquVdQJaDm1ZyZyGKhXW3VO164OtpsExNucpKdFzUiN7O0VMa6Qq0Dgz6T9I+VYnTxUWbRhAhFAA=="},
            {"header.payload7", "1KDbk0+9zLu9NtaYwJ2LUGNudPdtMYopMZQmBKRs6OO8b69I/gzEmmh49DFw/M/19HaplefNrDHTHF9705Ijzw=="},
            {"header.payload8", "O0BxXNhBBTheuyYJX87xMtE++94oqj4afPDKejAXC02urpt1SkU1MLrk/WXtmA2gYLxl9TNSUr1bfsXW1q9sGg=="},
            {"header.payload9", "GSrOufy4+Ya7zvSYauAy5lDXdsaM7yzXGWJd2YEGtVIGc8d3/sX9PIE2sWTS8Cd3EnYD3V0Wp2wK3drRD9cmFw=="},
            {"header.payload10", "02yh789sVxFYobMhkvRsQLhiYRllmhO3VtEhaD1k4cxB1AQsrFxlJLz/nju75FXPiUw8u91oZLq57GUjTmTGLA=="},
            {"header.payload11", "xC2bPY+PT8xk0Y6Vrqc4XCbj0vFr+J4dLBjR2NxbI67SD6xAmb4chNYgX7Loqx7N6gbk8Q/LbzKgLbHZLNoAaw=="}
    };

    @Test
    public void rawSignaturesVerifyAsDer() throws Exception {
        PublicKey key = KeyFactory.getInstance("EC").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(SPKI)));
        for (String[] s : SIGNED) {
            Signature v = Signature.getInstance("SHA256withECDSA");
            v.initVerify(key);
            v.update(s[0].getBytes("US-ASCII"));
            assertTrue(s[0], v.verify(License.rawToDer(Base64.getDecoder().decode(s[1]))));
        }
    }

    @Test
    public void aSignatureThatIsNotSixtyFourBytesIsRefused() {
        try {
            License.rawToDer(new byte[10]);
        } catch (IllegalArgumentException e) {
            assertEquals("not an ES256 signature", e.getMessage());
            return;
        }
        throw new AssertionError("expected a refusal");
    }
}
