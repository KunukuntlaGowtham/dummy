# A11y Inspector - sign-in and licence (Supabase)

What protects the app on other people's phones:

1. **Sign-in** (Supabase Auth, email + password). The trigger in `licenses.sql` refuses an account
   for any email that is not on the `licenses` list - open sign-ups are closed.
2. **Licence**: after sign-in the phone asks the Edge Function `issue-license` for a token. The
   phone proves it holds its Android-Keystore key (the private half can't be copied out); the server
   keeps each phone's public key, limits phones per login (`max_devices`, default 2), and signs a
   36 h token (ES256). The app checks the token with the public key built into the APK and renews
   it in the background. A copied APK, token or login on another phone fails.
3. **Reaction**: set `active = false` on a licence, or `revoked = true` on a phone, or flip the kill
   switch - the app locks at its next check (15 min) / when its token runs out (36 h).
4. **Play Integrity** (optional): the server also checks that the phone passes Play Integrity.
   Note: for an app installed outside Google Play, Play Integrity vouches for the *phone*
   (genuine, not rooted), not for the app's own signature.

Until `LICENSE_PUBLIC_KEY` is in the build, the licence layer is off and only the sign-in applies,
so nobody is locked out while you set it up.

## Set-up (once)

1. Supabase -> SQL Editor: run `devices.sql`, then `licenses.sql`.
2. Add people: `insert into public.licenses (email) values ('friend@example.com');`
   (add yourself first, before turning anything else on).
3. On **your computer**: `node inspector/supabase/make-keys.mjs` - it prints a private JWK and a public key.
4. Deploy the function and give it the private key (never put it in the repo or a chat):
   ```
   supabase functions deploy issue-license
   supabase secrets set LICENSE_PRIVATE_JWK='<the JWK line>'
   ```
5. GitHub -> repo Settings -> Secrets and variables -> Actions: add `LICENSE_PUBLIC_KEY` (the public line).
   (`SUPABASE_URL` / `SUPABASE_ANON_KEY` are optional there - the build already has your project's.)
6. Run the build. Sign in on a phone: the first sign-in registers that phone.

### Optional: Play Integrity
- Google Cloud: enable the Play Integrity API; link the project in Play Console -> App integrity;
  create a service account with a JSON key.
- `supabase secrets set REQUIRE_INTEGRITY=true PLAY_INTEGRITY_SA_JSON='<the JSON>'`
- GitHub secret `INTEGRITY_PROJECT_NUMBER` = the Google Cloud project number.

## Looking after it
```sql
-- logins on more than one phone
select u.email, count(*) phones from public.license_devices d join auth.users u on u.id = d.user_id
  where not d.revoked group by u.email having count(*) > 1;
update public.licenses set active = false where email = 'x@y.z';            -- revoke a person
update public.license_devices set revoked = true where device_id = '...';   -- free / block a phone
update public.app_config set value = 'on' where key = 'kill';                -- switch every app off
select * from public.license_checks where result <> 'ok' order by at desc limit 50;
```
