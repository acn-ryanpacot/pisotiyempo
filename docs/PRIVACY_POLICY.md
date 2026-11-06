# Piso Tiyempo Privacy Policy

_Last updated: [publication date]_

Piso Tiyempo ("the app") is an educational stock-analysis app for the Philippine Stock Exchange. This policy explains what data the app handles. It is written to meet Google Play's requirements and the Philippine Data Privacy Act of 2012 (RA 10173). **Have it reviewed by counsel and fill in the bracketed details before publishing.**

## Data that stays on your phone

- Your trades, holdings, watchlist, alerts and settings are stored only in the app's local database on your device.
- They are not uploaded to our servers or shared with anyone.
- They are excluded from Android cloud backup and device-to-device transfer. Uninstalling the app deletes them.

## Data sent to our servers

To show prices and analysis, the app contacts our backend at [your Worker domain]:

- **Market data requests:** the stock symbols you view. These requests contain no personal information.
- **AI analysis requests:** a stock's symbol and the technical indicators the app computed for it (prices, indicator values, detected patterns). Your portfolio, quantities and trade history are never included.
- **Disclosure summaries:** the ID of the public PSE disclosure you asked to summarize.
- **App verification:** a random ID created when you install the app, plus a Google Play Integrity token that confirms the app is genuine and unmodified. The ID is not linked to your name, account or device identifiers.
- **Premium:** if you subscribe, the Google Play purchase token, so our server can confirm your subscription with Google. We don't receive your payment details.

Our backend runs on Cloudflare Workers. Cloudflare may process standard request metadata, such as IP addresses, to operate and protect the service. We do not build user profiles, use advertising identifiers, or sell data.

## Third parties

- **Anthropic (Claude API):** receives the technical features described above, to write analysis text. Anthropic does not receive any information that identifies you. See https://www.anthropic.com/legal/privacy.
- **Market data providers** (EODHD, PSE Edge, phisix, Yahoo Finance): contacted by our backend, or by the app directly when the backend is unreachable, to fetch public market data.

## Your own EODHD key

If you add your own EODHD API key, it is stored encrypted on your device and sent only to EODHD when the app fetches prices. It is never sent to our servers.

## Crash reports

If crash reporting is on (Settings > Crash reports), the app sends anonymous technical details to Google Firebase Crashlytics when it crashes: device model, Android version and the error. It does not include your portfolio or personal information. You can turn this off at any time.

## Notifications

If you allow notifications, the app shows price and pattern alerts. These are generated on your device.

## Children

The app is not directed at anyone under 18.

## Your rights

You can delete all app data at any time by clearing the app's storage or uninstalling it. For questions or data-privacy requests, contact [contact email].

## Changes

We will post updates to this policy at [policy URL] and update the date above.
