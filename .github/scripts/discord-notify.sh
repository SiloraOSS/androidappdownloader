#!/usr/bin/env bash
# Posts a plain-language release announcement to a Discord webhook.
# Env: DISCORD_WEBHOOK (required unless DRY_RUN=1), VERSION, RELEASE_URL,
#      DOWNLOAD_URL, NOTES_FILE, AFTV_CODE (optional), DRY_RUN (optional)
set -euo pipefail

notes=$(cat "$NOTES_FILE")
# Keep well inside Discord's 4096-character embed description limit.
if [ "${#notes}" -gt 2500 ]; then
  notes="${notes:0:2500}…

Full details: ${RELEASE_URL}"
fi

how="**How to get it**"
if [ -n "${AFTV_CODE:-}" ]; then
  how+=$'\n'"📺 **Fire TV / Android TV:** open the **Downloader** app and enter code **${AFTV_CODE}**"
  how+=$'\n'"🔗 **Short link:** https://aftv.news/${AFTV_CODE}"
fi
how+=$'\n'"⬇️ **Direct download:** ${DOWNLOAD_URL}"

description="**What's new**"$'\n'"${notes}"$'\n\n'"${how}"$'\n\n'"Already using App Downloader? Just open it and it will offer to update itself."

payload=$(jq -n \
  --arg title "App Downloader ${VERSION} is out!" \
  --arg url "$RELEASE_URL" \
  --arg description "$description" \
  '{
     username: "App Downloader",
     embeds: [{ title: $title, url: $url, description: $description, color: 1999845 }]
   }')

if [ "${DRY_RUN:-}" = "1" ]; then
  printf '%s\n' "$payload"
  exit 0
fi
curl --fail-with-body -sS -H "Content-Type: application/json" -d "$payload" "$DISCORD_WEBHOOK"
echo "Posted release announcement to Discord."
