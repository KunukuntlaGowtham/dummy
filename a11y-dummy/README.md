# A11y Dummy Automation Test

Standalone Android test app for accessibility automation benchmarking. It does **not** connect to or automate TTD.

## Flow
1. 15 dummy people.
2. Each person has one real Android CheckBox with a stable content description such as `dummy_checkbox_01`.
3. Checking it immediately shows a modal popup.
4. Popup close button has content description `purple_cross_icon`.
5. After closing, scroll to the next person.
6. Counter shows checked and dismissed counts.

## Suggested accessibility loop
- Find an unchecked `CheckBox` and perform `ACTION_CLICK`.
- Wait for the popup/window state event rather than a fixed long sleep.
- Find `purple_cross_icon` and perform `ACTION_CLICK`.
- Re-scan the tree.
- Perform accessibility scroll-forward on the RecyclerView.
- Repeat until `Checked: 15 / 15`.

This project is intended for testing on a user-controlled dummy app. It does not bypass CAPTCHA, queues, rate limits, or anti-bot controls.
