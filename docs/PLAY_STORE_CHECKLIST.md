# Google Play release checklist — Piso Tiyempo

## Blocking: legal and licensing

- [ ] **Market data licence.** Upgrade EODHD to a plan that allows display to app users (contact EODHD sales), or license PSE data from the exchange or an authorized vendor. Don't rely on phisix, Yahoo or PSE Edge scraping for a public app without permission.
- [ ] **PSE Edge content.** Ask PSE whether showing disclosure titles and links, and summarizing disclosures, is permitted. Linking out is lower-risk than reproducing text.
- [ ] **Foreign flow.** Connect a licensed source through `POST /v1/admin/foreign-flow`, or leave the card in its "not available" state.
- [ ] **SEC Philippines review.** Have counsel confirm that educational technical signals with disclaimers don't require registration as an investment adviser, or adjust the wording and features (e.g. call them "signals", not "suggestions").
- [ ] **Privacy policy.** Fill in `docs/PRIVACY_POLICY.md`, host it at a public URL and link it in the Play Console.
- [ ] **Anthropic usage policy.** Confirm the use case fits Anthropic's usage policies for financial content (the app already uses educational framing and conditional language).

## Play Console

- [ ] App category: Finance. Complete the **financial features declaration** (the app gives no loans, holds no funds and executes no trades).
- [ ] **Data safety form:** no data collected by the developer; data shared with Anthropic = "app activity / other" (technical indicators only, not linked to the user); encrypted in transit.
- [ ] Content rating questionnaire.
- [ ] Target audience: 18+.
- [ ] Store listing: name "Piso Tiyempo", short description "PSE charts, patterns and AI analysis. Buy right. Sell right.", feature graphic, 4–8 screenshots, and a "not investment advice" line in the full description.
- [ ] Target API level: the project targets API 35. Raise it to whatever Play requires at submission time (Android Studio → Upgrade Assistant).

## Build and security

- [ ] Create an upload keystore and enable Play App Signing. Never commit the keystore.
- [ ] Set real `PISO_API_BASE_URL` / `PISO_APP_KEY` in `~/.gradle/gradle.properties` or CI secrets.
- [ ] Build a release bundle: `./gradlew :app:bundleRelease`. R8 rules for kotlinx.serialization are in `app/proguard-rules.pro`; smoke-test the release build on a real device.
- [ ] Turn on Play Integrity: link the Cloud project, set `PISO_CLOUD_PROJECT_NUMBER`, set `SESSION_SECRET` and `GOOGLE_SERVICE_ACCOUNT_JSON`, run `INTEGRITY_MODE = "monitor"` for a week, then `"enforce"` (see README).
- [ ] Premium: create the `piso_premium` subscription in Play Console, test it with a license-testing account, and decide whether to set `PREMIUM_AI = "true"`.
- [ ] Crash reports: add `app/google-services.json` to the release build, and declare "Crash logs" (collected, not shared, optional) in the Data safety form.
- [ ] Data safety form: declare journal notes ("Other user-generated content", shared with Anthropic only when the user requests coaching, not stored).
- [ ] Have a native speaker review the Filipino and Taglish text (res/values-tl, the rules-engine phrases in domain/analysis/Narratives.kt) before release.
- [ ] Data safety form: also declare the install ID ("Device or other IDs", for app functionality and fraud prevention) and the purchase token ("Purchase history", for app functionality).
- [ ] Set a monthly spend limit in the Anthropic Console.

## Quality

- [ ] Run `npm run probe -- <EODHD key>` and confirm every source returns data.
- [ ] Test the first-run flow on a fresh install: disclaimer, first sync (a few minutes on free sources), dashboard filling in.
- [ ] Test with AI disabled (unset `ANTHROPIC_API_KEY` or set `AI_ENABLED=false`): every AI card should show "Rules engine".
- [ ] Test offline: the app should show the last synced data.
- [ ] Internal testing track, then closed testing (Play requires a 14-day test with at least 12 testers for new personal developer accounts).
