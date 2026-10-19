# Spotify Setup (BitChord)

Spotify sync reads the listener's own library through Spotify's internal
web-player endpoints — the same door the web player itself uses. There is no
OAuth, no client id, and no Spotify dashboard setup.

## 1. Connect with your `sp_dc` cookie

1. Open `https://open.spotify.com` in a desktop or mobile browser and sign in.
2. Open devtools → Application (Chrome) or Storage (Firefox) → Cookies →
   `https://open.spotify.com`.
3. Copy the value of the `sp_dc` cookie (or the whole cookie string — the app
   extracts `sp_dc` from it).
4. In BitFuse: Library → Spotify → paste it → **Connect with cookie**.

The cookie is stored encrypted on the device and never logged. It grants
read access to your library (playlists, Liked Songs). Disconnect anytime:
the cookie, tokens, flags and the sync cache are all wiped.

## 2. What syncs

- Playlists you own or collaborate on (full track lists).
- Other playlists as metadata rows (Spotify only returns items for owned or
  collaborative playlists).
- Liked Songs via the saved-tracks collection.
- Playback of any row resolves through your own provider sources (add-ons,
  modules) — never as a Spotify stream, and never counted as Spotify
  listening history.

## 3. Honest limits

These are unofficial endpoints. Spotify can rotate them at any time, which
breaks sync until the app's query hashes and parsing are updated — a failed
sync says so instead of showing stale data. A dead or pasted-wrong cookie
fails validation immediately ("Spotify rejected that cookie") without
storing anything. Rate limiting is honored with backoff and retry.
