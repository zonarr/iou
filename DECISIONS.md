# Decisions

Every design decision made while building iou, with a one-line reason.
**(you)** marks decisions the owner answered directly; the rest were made during the build.

## Product decisions answered by the owner

| Decision | Reason |
|---|---|
| **(you)** UI in Thai and English, with a switch; starts in the phone's language | The family reads Thai; English stays available. Thai wording needs a review by a native speaker. |
| **(you)** Per-group "trusted group" switch that auto-confirms debts and payments | The README's accept-every-debt rule stays the default, but on a family trip it would leave balances incomplete until everyone taps. Only the group owner can flip it. |
| **(you)** Family joins through one invite link and taps "I am Mum" to claim a placeholder | Least typing for non-technical people. Anyone holding the link can join, so the owner can reset the link at any time. Adding a registered person by email also works. |
| **(you)** Render free web service, accepting the wake-up wait after 15 idle minutes | Costs nothing and needs no extra account. The app shows a "waking up" message while the server starts. |
| **(you)** Multi-currency: every expense, debt and payment stores original amount + ISO 4217 currency + a manually entered rate to THB + the THB amount; balances and settle-up are in THB | Specified in the brief. |
