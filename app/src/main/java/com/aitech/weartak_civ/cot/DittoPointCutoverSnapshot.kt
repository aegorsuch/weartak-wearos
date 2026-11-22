package com.aitech.weartak_civ.cot

import android.content.SharedPreferences

/** Callers must supply an actual completed engine and observer quiescence check. */
internal class DittoPointCutoverSnapshot(
    private val ledgerPreferences: SharedPreferences,
    private val watermarkPreferences: SharedPreferences,
    private val fence: DittoPointMutationFence,
    private val quiesce: suspend () -> Boolean,
) {
    internal class Pair(
        val ledger: DittoPointLedger.MigrationSnapshot,
        val watermarks: DittoPointIngress.MigrationSnapshot,
    )

    suspend fun capture(): Pair {
        check(quiesce()) { "Ditto engine quiescence not confirmed" }
        check(fence.fence()) { "Ditto point writer fence not confirmed" }
        return Pair(
            DittoPointLedger(ledgerPreferences, fence).snapshotForMigration(),
            DittoPointIngress(watermarkPreferences, fence).snapshotForMigration(),
        )
    }

    fun <T : Any> withCurrentPair(pair: Pair, action: () -> T): T? =
        fence.withConfirmedFence {
            val ledger = pair.ledger.records.associate { it.key to it.json }
            val watermarks = pair.watermarks.records.associate { it.key to it.json }
            if (ledger.size != pair.ledger.records.size || watermarks.size != pair.watermarks.records.size ||
                ledgerPreferences.all != ledger || watermarkPreferences.all != watermarks) null else action()
        }
}
