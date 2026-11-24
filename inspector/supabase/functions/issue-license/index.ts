// A11y Inspector - issue-license (Supabase Edge Function).
//
// Two steps, both with the user's Supabase session as the bearer token:
//   { step: "nonce" }  -> { nonce }   a one-time challenge (valid 5 min)
//   { step: "issue", nonce, signature, public_key, device_id, model, integrity_token? }
//                      -> { token, exp }   a licence for this login on this phone
//
// The phone signs the nonce with the key in its Android Keystore. The first time a phone is
// seen its public key is stored; after that the same key must sign. The licence token (ES256,
// 36 h) is signed with LICENSE_PRIVATE_JWK, a secret that exists only here.
//
// Secrets (supabase secrets set ...):
//   LICENSE_PRIVATE_JWK        required  the private key as JWK JSON (see make-keys.mjs)
//   REQUIRE_INTEGRITY          optional  "true": a valid Play Integrity answer is also needed
//   PLAY_INTEGRITY_SA_JSON     with it   a Google service account's JSON key (Play Integrity API user)
//   INTEGRITY_LEVEL            optional  "basic" (default "device"): which verdict is enough
//   APP_PACKAGE                optional  default com.kunukuntla.a11yinspector
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const admin = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
  auth: { persistSession: false },
});
const TOKEN_TTL_S = 36 * 3600;
const NONCE_TTL_MS = 5 * 60 * 1000;
const APP_PACKAGE = Deno.env.get("APP_PACKAGE") ?? "com.kunukuntla.a11yinspector";

const enc = new TextEncoder();
const b64url = (b: Uint8Array) => btoa(String.fromCharCode(...b)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const fromB64 = (s: string) => Uint8Array.from(atob(s.replace(/-/g, "+").replace(/_/g, "/")), (c) => c.charCodeAt(0));
const json = (status: number, body: Record<string, unknown>) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

/** Android signs with a DER SEQUENCE(r, s); WebCrypto wants r||s (64 bytes). */
function derToRaw(der: Uint8Array): Uint8Array {
  let i = 2;
  if (der[0] !== 0x30) throw new Error("not DER");
  if (der[1] & 0x80) i = 2 + (der[1] & 0x7f);
  const out = new Uint8Array(64);
  for (const at of [0, 32]) {
    if (der[i++] !== 0x02) throw new Error("not DER");
    let len = der[i++];
    let start = i;
    i += len;
    while (len > 32 && der[start] === 0) { start++; len--; }
    out.set(der.slice(start, start + len), at + 32 - len);
  }
  return out;
}

async function log(user: { id: string; email?: string } | null, device: string, result: string, detail = "") {
  await admin.from("license_checks").insert({
    user_id: user?.id ?? null, email: user?.email ?? null, device_id: device, result, detail,
  });
}

async function playIntegrityOk(token: string, nonce: string): Promise<string> {
  const sa = JSON.parse(Deno.env.get("PLAY_INTEGRITY_SA_JSON") ?? "null");
  if (!sa) return "integrity is required but no service account is set on the server";
  // A Google access token from the service account (RS256 JWT).
  const now = Math.floor(Date.now() / 1000);
  const head = b64url(enc.encode(JSON.stringify({ alg: "RS256", typ: "JWT" })));
  const claims = b64url(enc.encode(JSON.stringify({
    iss: sa.client_email, scope: "https://www.googleapis.com/auth/playintegrity",
    aud: "https://oauth2.googleapis.com/token", iat: now, exp: now + 3000,
  })));
  const pem = String(sa.private_key).replace(/-----[A-Z ]+-----/g, "").replace(/\s+/g, "");
  const key = await crypto.subtle.importKey("pkcs8", fromB64(pem), { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const sig = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, enc.encode(`${head}.${claims}`)));
  const oauth = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: `${head}.${claims}.${b64url(sig)}` }),
  }).then((r) => r.json());
  if (!oauth.access_token) return "could not reach Google to check the integrity answer";
  const res = await fetch(`https://playintegrity.googleapis.com/v1/${APP_PACKAGE}:decodeIntegrityToken`, {
    method: "POST", headers: { Authorization: `Bearer ${oauth.access_token}`, "Content-Type": "application/json" },
    body: JSON.stringify({ integrity_token: token }),
  }).then((r) => r.json());
  const p = res.tokenPayloadExternal;
  if (!p) return "the integrity answer could not be read";
  if (p.requestDetails?.requestPackageName !== APP_PACKAGE) return "integrity: another app";
  if (p.requestDetails?.nonce !== nonce) return "integrity: not for this challenge";
  if (Math.abs(Date.now() - Number(p.requestDetails?.timestampMillis ?? 0)) > 10 * 60 * 1000) return "integrity: too old";
  const verdicts: string[] = p.deviceIntegrity?.deviceRecognitionVerdict ?? [];
  const need = (Deno.env.get("INTEGRITY_LEVEL") ?? "device") === "basic" ? "MEETS_BASIC_INTEGRITY" : "MEETS_DEVICE_INTEGRITY";
  if (!verdicts.includes(need) && !verdicts.includes("MEETS_DEVICE_INTEGRITY")) return "this phone doesn't pass Play Integrity";
  return "";
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return json(405, { error: "method" });
  const bearer = (req.headers.get("Authorization") ?? "").replace(/^Bearer /i, "");
  const { data: who, error: whoErr } = await admin.auth.getUser(bearer);
  if (whoErr || !who.user) return json(401, { error: "unauthorized", message: "Sign in again" });
  const user = who.user;
  const email = (user.email ?? "").toLowerCase();

  let body: Record<string, string>;
  try { body = await req.json(); } catch { return json(400, { error: "bad_request" }); }

  // Kill switch.
  const { data: kill } = await admin.from("app_config").select("value").eq("key", "kill").maybeSingle();
  if (kill?.value === "on") {
    await log(user, body.device_id ?? "", "refused", "kill switch");
    return json(403, { error: "disabled", message: "The app is switched off for now" });
  }

  if (body.step === "nonce") {
    await admin.from("license_nonces").delete().lt("created_at", new Date(Date.now() - 2 * NONCE_TTL_MS).toISOString());
    const nonce = b64url(crypto.getRandomValues(new Uint8Array(32)));
    await admin.from("license_nonces").insert({ nonce, user_id: user.id });
    return json(200, { nonce });
  }

  if (body.step !== "issue") return json(400, { error: "bad_request" });
  const device = String(body.device_id ?? "");
  const refuse = async (error: string, message: string, status = 403) => {
    await log(user, device, error, message);
    return json(status, { error, message });
  };

  // 1. The challenge: ours, this user's, unused, fresh - and used up now.
  const { data: n } = await admin.from("license_nonces").select("*").eq("nonce", body.nonce ?? "").maybeSingle();
  if (!n || n.user_id !== user.id || Date.now() - new Date(n.created_at).getTime() > NONCE_TTL_MS) {
    return refuse("bad_nonce", "Try again", 400);
  }
  await admin.from("license_nonces").delete().eq("nonce", n.nonce);

  // 2. The phone's key signed it.
  try {
    const pub = await crypto.subtle.importKey("spki", fromB64(body.public_key), { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]);
    const good = await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, pub, derToRaw(fromB64(body.signature)), enc.encode(body.nonce));
    if (!good) return refuse("bad_signature", "This phone's key didn't match");
  } catch {
    return refuse("bad_signature", "This phone's key didn't match");
  }

  // 3. The licence: on the list, active, not ended.
  const { data: lic } = await admin.from("licenses").select("*").ilike("email", email).maybeSingle();
  if (!lic || !lic.active) return refuse("not_allowed", "This login has no licence");
  if (lic.expires_at && new Date(lic.expires_at).getTime() < Date.now()) return refuse("expired", "Your licence has ended");

  // 4. The phone: the same key as the first time; a new phone only within the limit.
  const { data: dev } = await admin.from("license_devices").select("*").eq("user_id", user.id).eq("device_id", device).maybeSingle();
  if (dev) {
    if (dev.revoked) return refuse("device_revoked", "This phone was removed from your licence");
    if (dev.public_key !== body.public_key) return refuse("device_key_changed", "This phone's key changed - ask for a reset");
    await admin.from("license_devices").update({ last_seen: new Date().toISOString(), model: body.model }).eq("user_id", user.id).eq("device_id", device);
  } else {
    const { count } = await admin.from("license_devices").select("device_id", { count: "exact", head: true }).eq("user_id", user.id).eq("revoked", false);
    if ((count ?? 0) >= (lic.max_devices ?? 2)) return refuse("device_limit", `This licence is already on ${lic.max_devices ?? 2} phones`);
    await admin.from("license_devices").insert({ user_id: user.id, device_id: device, public_key: body.public_key, model: body.model });
  }

  // 5. Play Integrity, when the server asks for it.
  if (Deno.env.get("REQUIRE_INTEGRITY") === "true") {
    const why = body.integrity_token ? await playIntegrityOk(body.integrity_token, body.nonce) : "no integrity answer from this phone";
    if (why) return refuse("integrity", why);
  }

  // 6. The licence token.
  const now = Math.floor(Date.now() / 1000);
  const head = b64url(enc.encode(JSON.stringify({ alg: "ES256", typ: "JWT" })));
  const payload = b64url(enc.encode(JSON.stringify({ sub: user.id, email, did: device, plan: lic.plan, iat: now, exp: now + TOKEN_TTL_S })));
  const priv = await crypto.subtle.importKey("jwk", JSON.parse(Deno.env.get("LICENSE_PRIVATE_JWK")!), { name: "ECDSA", namedCurve: "P-256" }, false, ["sign"]);
  const sig = new Uint8Array(await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, priv, enc.encode(`${head}.${payload}`)));
  await log(user, device, "ok");
  return json(200, { token: `${head}.${payload}.${b64url(sig)}`, exp: now + TOKEN_TTL_S });
});
