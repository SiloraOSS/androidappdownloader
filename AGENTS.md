# Silora Updater release announcements

Matt authorized one Discord announcement as part of each future GitHub release of Silora Updater that you publish. This applies to `SiloraOSS/androidappdownloader`. This standing approval covers the format and destination below.

1. Verify that the exact tag is public and its signed `SiloraUpdater-tv.apk` is downloadable. Read the published notes for that tag.
2. Draft a single SigmaUtils embed for `1568502343631114260` (#📜・silo-daily-changelog). Match the [v1.1.0 announcement](https://discord.com/channels/1457559352717086917/1568502343631114260/1573956055153647709) with a 📢 Silora Updater version heading, Pacific publication date, and a `### What changed` section with concise bullets. End with the APK link for that specific release and the full GitHub release notes link. Use no buttons, role pings, plaintext introduction, or audit post.
3. Use the workspace's `Tools/discord-tools` and its read-only announcement renderer to verify the complete embed and live destination. Then post with `announce post ... --embed --no-roles --nonce ID` using the reviewed arguments. Base the nonce on the GitHub release ID. Use the normal review and explicit approval workflow for any departure from this standing authorization.
4. Check for a prior post for the exact release before sending. Keep private per-release state keyed by GitHub release ID under the workspace's `Staging/discord-announcements/`, reserve the send once, and record the verified Discord message link. If a send outcome is unclear, reconcile it against live channel history before any retry. Report the GitHub release and Discord message links together.

GitHub release `454258567` (v1.1.0) has already been announced at the linked example above.
