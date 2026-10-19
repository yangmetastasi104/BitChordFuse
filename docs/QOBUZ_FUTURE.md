# Qobuz — Future Integration (Documented, Not Implemented)

Status (Oct 2026): **no verified self-serve public API/SDK** for independent
open-source Android players. `open.qobuz.com` exists but serves a JS shell;
no fetchable developer dashboard or streaming contract was found during the
audit. The established model is **partner-gated**: application approval,
issued `app_id` (+ secret), authenticated user with an active Qobuz
subscription, signed file URLs under Qobuz API terms (expected NDA /
commercial agreement for production streaming).

## Why MetroFuse's approach was NOT ported

MetroFuse's `QobuzAudioProvider` streams via user-supplied third-party
"Kenny" mirror instances (`GET {instance}/api/get-music`,
`GET {instance}/api/download-music`). Those are unofficial relays, not
Qobuz authorization. Porting them would bypass access controls and inherit
their availability/legal risk. BitChord does not implement this.

## What a legitimate integration needs

1. Partnership approval + `app_id` from Qobuz (contact via
   <https://open.qobuz.com> / partnerships channel).
2. Subscriber-entitlement check (streaming only for paying users).
3. Signed-URL playback (no URL scraping, no credential sharing).
4. A `QobuzSource : MusicSource` with `ProviderCapabilities` declaring only
   granted rights (`playback=false` until streaming is entitled).
5. Credential storage in `AuthStore`, never plaintext.

## Recommendation

Leave Qobuz as a documented optional provider. Revisit only with written
partner approval. Hi-Res playback today stays served by user-configured
addon/module sources the listener hosts or was given access to.
