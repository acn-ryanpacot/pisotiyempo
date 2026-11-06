import { googleAccessToken, SCOPE_ANDROID_PUBLISHER } from "./integrity";

// Premium is a Google Play subscription. The app sends its purchase token; we confirm it with the Play
// Developer API (subscriptionsv2) so a modified app can't unlock premium on its own.

export const PREMIUM_PRODUCT_IDS = ["piso_premium"];

/** The parts of a SubscriptionPurchaseV2 resource we use. */
export interface SubscriptionPurchase {
  subscriptionState?: string;
  lineItems?: Array<{ productId?: string; expiryTime?: string }>;
}

const ENTITLED_STATES = new Set(["SUBSCRIPTION_STATE_ACTIVE", "SUBSCRIPTION_STATE_IN_GRACE_PERIOD"]);

/** Epoch ms until which the purchase grants premium, or null if it grants nothing right now. */
export function premiumUntil(p: SubscriptionPurchase, now = Date.now()): number | null {
  if (!ENTITLED_STATES.has(p.subscriptionState ?? "")) return null;
  const expiries = (p.lineItems ?? [])
    .filter((li) => li.productId && PREMIUM_PRODUCT_IDS.includes(li.productId))
    .map((li) => Date.parse(li.expiryTime ?? ""))
    .filter((t) => Number.isFinite(t) && t > now);
  return expiries.length ? Math.max(...expiries) : null;
}

export async function fetchSubscription(packageName: string, purchaseToken: string, serviceAccountJson: string): Promise<SubscriptionPurchase> {
  const access = await googleAccessToken(serviceAccountJson, SCOPE_ANDROID_PUBLISHER);
  const url = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${encodeURIComponent(packageName)}/purchases/subscriptionsv2/tokens/${encodeURIComponent(purchaseToken)}`;
  const res = await fetch(url, { headers: { Authorization: `Bearer ${access}` } });
  if (!res.ok) throw new Error(`subscription lookup failed: HTTP ${res.status}`);
  return (await res.json()) as SubscriptionPurchase;
}
