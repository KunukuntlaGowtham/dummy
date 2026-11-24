# A11y Inspector - sign-in protection (Supabase)

**One step:** Supabase -> your project -> **SQL Editor** -> paste all of `setup.sql` -> **Run**.

That is all. Then install the new APK and sign in.

What protects the app:
- Only emails on the list can make an account (everyone who has one now is put on it for you).
- After sign-in and every 15 minutes the app asks the server (`check_license`): is this login on the list,
  active, not ended, and is this phone one of its 2 allowed phones? A third phone is refused.
- No answer for 24 h, or a "no" -> the app locks. Remove a person, free a phone, or switch every app off
  with one line of SQL (the commands are at the bottom of `setup.sql`).
- Until `setup.sql` has been run, the app works with the plain sign-in, so nothing breaks while you do it.
- The app is built with code shrinking/obfuscation, and nothing secret is inside it (the Supabase key in it
  is public by design; the database is closed to it by the rules in `setup.sql`).

Also worth doing in Supabase -> Authentication: **Providers -> Email -> turn "Confirm email" on**
(so a typed email must be real), and **Sign In / Up -> turn off "Allow new users to sign up"** if you
prefer to create accounts yourself (Authentication -> Users -> Add user).
