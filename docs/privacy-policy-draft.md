# SoNex privacy policy — DRAFT

> **⚠️ FLAG FOR LEGAL REVIEW.** This draft is written against India's DPDP Act
> 2023 and the GDPR but has not been reviewed by counsel. Do not publish as-is.

## What SoNex hears
SoNex keeps the microphone open to decide whether someone in the room is
talking. **By default, every byte of audio is processed on your phone and
immediately discarded.** No audio, transcript, or acoustic fingerprint leaves
the device. A persistent notification (and the OS microphone indicator) is
shown the entire time the mic is live.

## What leaves your phone, and only with consent
Each purpose has its own toggle in Settings, **all OFF by default**, each
revocable at any time (revocation takes effect immediately):

| Purpose | What is sent | Toggle |
|---|---|---|
| Improve detection | Short audio clips you explicitly enable | Upload clips |
| Usage statistics | Anonymous event counts (no audio) | Share usage stats |
| Personalised model | Detection events + your volume corrections | Let SoNex learn my home |
| Voice control | Nothing — wake word + commands run on-device | Wake word |

The app is fully functional with all toggles off.

## Your rights (DPDP 2023 / GDPR)
- **Access / portability:** Settings → export, or `GET /v1/data/export` —
  returns everything we hold about your devices and home.
- **Erasure:** Settings → "Delete all my data", or `POST /v1/data/delete` —
  removes database rows *and* stored audio objects. Local deletion wipes the
  on-device account, pairing, calibration, and consents.
- **Consent withdrawal:** any toggle, any time, no penalty.

## Storage & processing
Consented clips are stored with Cloudinary (primary) or Cloudflare R2
(fallback); metadata in Postgres. Per-home models are trained server-side only
from consented data of that home. Data is not sold or shared with third
parties. Retention: clips kept until deleted by you or superseded by training;
events retained ≤ 12 months. *(Retention numbers TBC by legal.)*

## Children
SoNex is a household utility not directed at children; household audio may
incidentally include children's voices, which is why on-device processing and
consent-gated upload are the default. *(DPDP §9 verifiable-consent analysis
needed — legal.)*

## Contact / grievance officer
*(Required under DPDP — to be appointed.)*
