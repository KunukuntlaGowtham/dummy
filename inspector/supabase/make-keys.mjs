// Makes the licence key pair. Run it on YOUR computer (never in a chat or a repo):
//   node inspector/supabase/make-keys.mjs
// Then:
//   supabase secrets set LICENSE_PRIVATE_JWK='<the JWK line>'      (the private key: server only)
//   GitHub -> Settings -> Secrets -> LICENSE_PUBLIC_KEY = <the public line>   (goes into the APK)
import { webcrypto as c } from "node:crypto";
const pair = await c.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"]);
const jwk = await c.subtle.exportKey("jwk", pair.privateKey);
const spki = Buffer.from(await c.subtle.exportKey("spki", pair.publicKey)).toString("base64");
console.log("\nLICENSE_PRIVATE_JWK (secret, server only):\n" + JSON.stringify(jwk));
console.log("\nLICENSE_PUBLIC_KEY (GitHub secret, built into the app):\n" + spki + "\n");
