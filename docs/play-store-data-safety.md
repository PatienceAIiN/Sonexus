# Play Store data-safety form notes

> Answers below reflect the shipped defaults. Re-check before every release —
> the form is per-build, and enabling any new collection changes answers.

## Data collection (defaults: everything OFF)
- **Audio**: *Not collected* by default. If the user enables "Upload clips":
  collected, optional, user-initiated, encrypted in transit, deletable.
- **App activity** (detection events/overrides): only with "learn my home" or
  telemetry consent → optional, encrypted in transit, deletable.
- **Personal info**: email only if the user links a SoNex account (Phase 1 is
  on-device only; no account data leaves the phone).
- **Device IDs**: a random per-device key when a server is configured; not an
  advertising ID.

## Required disclosures
- Microphone: foreground service type `microphone`, persistent notification.
  Declare `RECORD_AUDIO`, `FOREGROUND_SERVICE_MICROPHONE`.
- `READ_PHONE_STATE`: call-state only (duck during calls); no numbers read.
- Data deletion: in-app ("Delete all my data") + `POST /v1/data/delete` — both
  must be linked in the store listing's data-deletion URL field.
- Security practices: encrypted in transit (HTTPS), user-requestable deletion.

## Not applicable
No ads, no data sold, no location, no contacts, no financial data,
no advertising ID.
