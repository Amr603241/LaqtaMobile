# لقطة | Laqta

Laqta is a customized derivative of existing open-source media software. Original license notices and source history are retained.

## Downloads

Android releases will be published on [GitHub Releases](https://github.com/Amr603241/LaqtaMobile/releases).

In Android Full, **Settings > About > Update channel** selects Stable or Beta. Stable offers stable releases only; Beta also offers prereleases. The updater searches the 100 most recently published GitHub releases for a newer version with an APK for the device. A manual check starts its download, including in debug builds; background checks remain disabled in debug builds. Android asks the user to confirm installation, and an update can replace an installed app only when the package name and signing certificate are compatible. Play Store builds do not use the in-app updater.

## Community add-ons

In the full distribution, open **Settings > Addons > Browse** to search the community directory. Add-ons requiring configuration open their own third-party setup page; after configuring, select Laqta for the install link or copy the configured manifest URL into the Installed tab. Optional configuration is also available before installation. Individual services may be unavailable or require their own accounts.

## Build from source

```bash
git clone https://github.com/Amr603241/LaqtaMobile.git
cd LaqtaMobile
```

### Android

Android development requires Android Studio and the Android SDK.

```bash
./gradlew :androidApp:assembleFullDebug
```

For account sign-in and sync, configure a Supabase project that you own. Add its project URL and publishable key to the untracked `local.properties` file:

```properties
LAQTA_SUPABASE_URL=https://tvqtmfksipzfksuyjwrx.supabase.co
LAQTA_SUPABASE_ANON_KEY=your-publishable-key
LAQTA_GOOGLE_OAUTH_ENABLED=true
```

Without the URL and publishable key, account sign-in remains unavailable; local-only use is still available. The Google button is shown only when `LAQTA_GOOGLE_OAUTH_ENABLED=true`.

### Supabase setup

1. Create a Supabase project in an account you control.
2. Run the migrations in order in the Supabase SQL Editor: [`202611160001_core_account_sync.sql`](./supabase/migrations/202611160001_core_account_sync.sql), [`202611170001_collections_home_catalog_sync.sql`](./supabase/migrations/202611170001_collections_home_catalog_sync.sql), [`202611170002_library_sync.sql`](./supabase/migrations/202611170002_library_sync.sql), [`202611170003_addon_sync.sql`](./supabase/migrations/202611170003_addon_sync.sql), [`202611170004_library_keyset.sql`](./supabase/migrations/202611170004_library_keyset.sql), [`202611170005_watch_party_foundation.sql`](./supabase/migrations/202611170005_watch_party_foundation.sql), and [`202611170006_watch_party_rpc.sql`](./supabase/migrations/202611170006_watch_party_rpc.sql).
3. In Supabase Auth, configure the Google provider and enable manual identity linking.
4. In Google Cloud, create a Web OAuth client. Set its authorized redirect URI to `https://tvqtmfksipzfksuyjwrx.supabase.co/auth/v1/callback`, then add the client ID and secret to the Google provider settings in Supabase. Keep the OAuth secret in Supabase only.
5. Add `laqta://auth/callback` to the Supabase Auth redirect URL allowlist.
6. Put the Supabase URL and publishable key in `local.properties` or the matching GitHub Actions secrets before building.

The migrations implement profile, profile-settings, watch-progress, watched-history, collections, home-catalog-settings, library, and add-on sync. The first nonempty add-on list initializes a new profile's server list; once initialized, the server list takes precedence when pulled, including an intentionally empty list. Add-on updates replace the entire list, and failed pushes are not persisted for retry, so avoid editing add-ons offline or concurrently on multiple devices. Some add-on URLs contain credentials and are stored in the account's private server data. The library snapshot uses keyset paging on the Laqta server after the keyset migration is applied; custom servers and servers missing that migration still use offset paging, which can miss unchanged items during concurrent deletions. Other existing sync features require their own backend functions before they can sync on a new Laqta server. The mobile app must never contain a Supabase `service_role` key. Do not commit `local.properties` or share credentials in chat.

The account settings can open a separate, memory-only email/password session with the official Nuvio backend using its [public client configuration](https://github.com/NuvioMedia/self-host/blob/main/scripts/import-account.sh). It does not replace the Laqta session and must be reconnected after the app process closes. Choose an unprotected Nuvio profile explicitly to merge its library with the current Laqta profile. This is a manual, additive two-way merge, not continuous sync: it does not propagate deletions, and items removed on one side may be restored on a later merge. PIN-protected Nuvio profiles cannot be selected. A failed operation may have copied some items; retry after checking both libraries. Avoid editing either library during a merge: Nuvio offset pagination can miss items, and concurrent changes can conflict with outgoing updates.

Watch parties run on the Laqta server through the `watch_party_*` RPC functions and private Realtime channels (`watch-party:<id>`) defined by the two watch-party migrations. They support up to three signed-in (non-anonymous) members per party, host-only control, synced playback, and chat. Parties work only between users of the same Laqta server, and invite links use `laqta://party/<code>`.

### iOS

iOS development requires macOS and Xcode.

```bash
env NUVIO_IOS_DISTRIBUTION=full xcodebuild \
  -project iosApp/iosApp.xcodeproj \
  -scheme iosApp \
  -configuration Debug \
  -sdk iphonesimulator \
  -derivedDataPath build/ios-derived-full-simulator \
  CODE_SIGNING_ALLOWED=NO \
  build
```

The shared app is built with Kotlin Multiplatform and Compose Multiplatform.

## License

This project is distributed under the [GNU General Public License v3.0](./LICENSE). Preserve the license and third-party notices when redistributing.
