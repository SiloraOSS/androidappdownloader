# Past installs

`Past installs` beside Settings reads the device's saved install history. Opening the list
does not contact GitHub. A restore uses a retained local APK when present; otherwise it
fetches exactly `/repos/SiloraOSS/silora-android/releases/tags/{tag}` and downloads the phone
or TV asset. It never enumerates older release pages or checks history in the background.

The APK is staged outside the disposable cache and validated before the explicit
**Uninstall & restore** confirmation. Android's uninstall screen is launched only after that
confirmation and an installation-access check. The installer starts only after the package
is absent. The confirmation warns about loss of local player data, sign-in, settings and
downloads. Restoring a version does not restore player data.

Automated release tests cover local preparation without a release tag/network access,
package/build/hash validation, unavailable device assets, old history migration, retained
files, persisted progress, missing install access, changed device state, cancellation,
blocked uninstalls, installation after removal, stale installer callbacks, failed-install
retries, history recording, and automatic-installation suppression.

Run the maintained signed release checks with `./build.sh`.

## Device smoke test

The new flow has not yet been exercised through a physical device's system uninstall and
install screens. Use a disposable Silora installation/profile for that check because an
accepted restore deletes local player data.

1. Install two Silora builds through the updater with APK cleanup off. Confirm the history
   lists both builds, dates, release tags, build numbers and local availability. Check phone
   and TV remote navigation, including a history row with no restorable artifact.
2. Select an older build while offline. Verify preparation leaves the current installation
   intact and shows the full data-loss warning. Cancel; confirm Silora still opens normally.
3. Select it again and choose **Uninstall & restore**, then cancel Android's uninstall.
   Verify no installation starts and the current build remains installed.
4. Accept the uninstall and finish installation. Verify the selected build, fresh player
   setup, a downgrade in history, and automatic installation switched off.
5. Cancel the installation after removal. Reopen the updater and explicitly retry the staged
   build while offline. Also check recreation/restart while the system prompts are open.
6. Repeat with a release-backed record whose local APK was removed. An available exact tag
   should restore; a missing release/asset must report unavailable before any uninstall.
7. Repeat the install portion with authorized Shizuku. If Shizuku stops or permission is
   missing, verify setup guidance or retry appears and the staged APK remains available.

API references: [Android uninstall intents](https://developer.android.com/reference/android/content/Intent#ACTION_UNINSTALL_PACKAGE),
[GitHub release by tag](https://docs.github.com/en/rest/releases/releases#get-a-release-by-tag-name).
