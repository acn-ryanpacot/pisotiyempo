package com.pisotiyempo.data.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import com.pisotiyempo.data.local.AppDatabase
import com.pisotiyempo.data.local.KvEntity
import com.pisotiyempo.data.remote.SessionProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class PremiumState(
    /** Billing is reachable on this device (false on emulators without Play, or sideloaded builds). */
    val available: Boolean = false,
    /** An active, paid subscription exists on this Google account. */
    val active: Boolean = false,
    /** Localized price per period, e.g. "₱99.00/month". */
    val price: String? = null,
    val message: String? = null,
)

/**
 * The "piso_premium" Google Play subscription. The app only shows and buys it; the backend confirms the
 * purchase with Google (via the purchase token in the session request) before unlocking premium features.
 */
class BillingRepository(context: Context, private val db: AppDatabase, private val sessions: SessionProvider) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(PremiumState())
    val state: StateFlow<PremiumState> = _state
    private var product: ProductDetails? = null

    private val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) scope.launch { handle(purchases.orEmpty()) }
            else if (result.responseCode != BillingClient.BillingResponseCode.USER_CANCELED) {
                _state.value = _state.value.copy(message = "Purchase didn't complete (${result.debugMessage.ifBlank { "code ${result.responseCode}" }}).")
            }
        }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    private suspend fun connect(): Boolean {
        if (client.isReady) return true
        return suspendCancellableCoroutine { cont ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (cont.isActive) cont.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
                }

                override fun onBillingServiceDisconnected() {
                    if (cont.isActive) cont.resume(false)
                }
            })
        }
    }

    /** Loads the product's price and the user's existing purchases. Safe to call often. */
    suspend fun refresh() {
        if (!connect()) {
            _state.value = PremiumState(available = false, message = "Google Play billing isn't available on this device.")
            return
        }
        val details = client.queryProductDetails(
            QueryProductDetailsParams.newBuilder().setProductList(
                listOf(QueryProductDetailsParams.Product.newBuilder().setProductId(PRODUCT_ID).setProductType(BillingClient.ProductType.SUBS).build()),
            ).build(),
        )
        product = details.productDetailsList?.firstOrNull()
        val phase = product?.subscriptionOfferDetails?.firstOrNull()?.pricingPhases?.pricingPhaseList?.lastOrNull()
        _state.value = _state.value.copy(available = product != null, price = phase?.formattedPrice?.let { "$it/${period(phase.billingPeriod)}" })
        val purchases = client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build())
        handle(purchases.purchasesList)
    }

    fun purchase(activity: Activity) {
        val details = product
        val offer = details?.subscriptionOfferDetails?.firstOrNull()
        if (details == null || offer == null) {
            _state.value = _state.value.copy(message = "Premium isn't available right now. Try again later.")
            return
        }
        val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(
            listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details).setOfferToken(offer.offerToken).build()),
        ).build()
        client.launchBillingFlow(activity, params)
    }

    private suspend fun handle(purchases: List<Purchase>) {
        val premium = purchases.firstOrNull { PRODUCT_ID in it.products && it.purchaseState == Purchase.PurchaseState.PURCHASED }
        if (premium != null && !premium.isAcknowledged) {
            // Unacknowledged purchases are refunded by Google after three days.
            val ack = client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(premium.purchaseToken).build())
            if (ack.responseCode != BillingClient.BillingResponseCode.OK) Log.w(TAG, "acknowledge failed: ${ack.debugMessage}")
        }
        val previous = db.kv().get(PURCHASE_TOKEN_KEY)?.value.orEmpty()
        val token = premium?.purchaseToken.orEmpty()
        if (token != previous) {
            db.kv().put(KvEntity(PURCHASE_TOKEN_KEY, token, System.currentTimeMillis()))
            // Get a fresh backend session so the server sees the new premium status.
            sessions.invalidate()
        }
        _state.value = _state.value.copy(active = premium != null, message = null)
    }

    private fun period(iso: String): String = when (iso) {
        "P1M" -> "month"
        "P3M" -> "3 months"
        "P6M" -> "6 months"
        "P1Y" -> "year"
        "P1W" -> "week"
        else -> iso
    }

    companion object {
        /** Create a subscription with this id in Play Console > Monetize > Subscriptions. */
        const val PRODUCT_ID = "piso_premium"
        const val PURCHASE_TOKEN_KEY = "premium-purchase-token"
        private const val TAG = "BillingRepository"
    }
}
