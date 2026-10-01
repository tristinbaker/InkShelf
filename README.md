# InkShelf

A native, e-ink-first Android client for [Audiobookshelf](https://www.audiobookshelf.org/),
built for the Mudita Kompakt.

This is a fresh client, not a fork of the Audiobookshelf web app. It speaks the Audiobookshelf
REST API directly.

|   |   |   |
|---|---|---|
| <img width="480" height="800" alt="1" src="https://github.com/user-attachments/assets/642fe551-aeb9-4cc0-b24c-3b387544c222" /> | <img width="480" height="800" alt="2" src="https://github.com/user-attachments/assets/f1b6e380-40ab-46e4-adfb-82eb4a56fa13" /> | <img width="480" height="800" alt="3" src="https://github.com/user-attachments/assets/89fed2d6-6050-43f6-829e-8a141ffc9665" /> |
| <img width="480" height="800" alt="3actually" src="https://github.com/user-attachments/assets/96ab047a-9e81-468d-bdfb-fec7f028a1d1" /> | <img width="480" height="800" alt="5" src="https://github.com/user-attachments/assets/5404a2db-b50c-4830-b67b-fa46cdfacb4f" /> | <img width="480" height="800" alt="6" src="https://github.com/user-attachments/assets/286ebc93-7f86-4fce-a602-85e9b17de332" /> |



## Build

Requires JDK 17 and an Android SDK with platform 35.

```sh
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew :app:assembleDebug
```

The debug APK is signed with the standard debug key and can be sideloaded
directly. The release APK is unsigned; add a `signingConfig` before shipping it.

```sh
./gradlew :app:testDebugUnitTest   # 114 unit tests over the pure logic
```

## Toolchain

| | |
|---|---|
| Gradle | 8.9 (wrapper; the system `gradle` is 9.x and will not work) |
| AGP | 8.7.3 |
| Kotlin | 2.0.20 |
| compileSdk / targetSdk / minSdk | 35 / 34 / 29 |
| Compose | UI 1.7.3, Material3 1.3.1 |
| Design system | `com.mudita:MMD-android:1.0.2` |

Compose and Material3 are pinned to the exact versions MMD 1.0.2 was compiled
against, to avoid drift.

> **Note on the MMD coordinate.** Use `com.mudita:MMD-android`, **not**
> `com.mudita:MMD`. The latter is a 683-byte Kotlin Multiplatform stub that
> publishes no Android code; `MMD-android` is the real 1.96 MB AAR.

## Audiobookshelf API notes

The public Audiobookshelf API docs are stale. Everything below was verified
against the `v2.37.0` source (`server/controllers/LibraryController.js`,
`server/utils/queries/libraryItemsBookFilters.js`, `server/utils/queries/seriesFilters.js`).

- **Auth.** `POST /login` must send `X-Return-Tokens: true`, otherwise the
  refresh token is only an httpOnly cookie that a native client discards, leaving
  you re-authenticating every two hours. Access tokens last 2h, refresh tokens
  30d, and refresh is `POST /auth/refresh` with an `X-Refresh-Token` header.
- **Rate limits.** Auth is limited to 40 requests per 10 minutes per IP and
  *successful* attempts count towards it. Token refresh is therefore
  single-flight and retried at most once, never in a loop.
- **Authors shape.** `GET /api/libraries/:id/authors` returns `{authors: [...]}`
  unless *both* `limit` and `page` are present as numbers, in which case it
  returns `{results, total, ...}`. We always send both.
- **`minified=1` is dead.** The flag is parsed in four places and then read
  nowhere, so every list response is minified regardless. We send it anyway,
  which is harmless.
- **Filter values are ids.** `filter=series.<base64(id)>` and
  `filter=authors.<base64(id)>` match on `id`, not display name. The
  `tags`/`genres`/`languages`/`publishers` groups match on name.
- **`sort=sequence` is series-only.** The server ignores it unless the query is
  filtered by `series` (`libraryItemsBookFilters.js:404`).
- **Item ids.** A library item's top-level `id` is the `libraryItemId` that
  `/api/items/:id` expects. `media.id` is the book id. Minified rows carry no
  `audioFiles` and no `tracks`; those appear only on the expanded item.
- **Direct play** is `GET /api/items/:id/file/:fileid`, where `:fileid` is the
  filesystem inode **as a string**. Coercing it to a number loses 64-bit
  precision and yields a 404.

## Licence

GPL-3.0. MMD is Apache-2.0 and is used as a dependency.
