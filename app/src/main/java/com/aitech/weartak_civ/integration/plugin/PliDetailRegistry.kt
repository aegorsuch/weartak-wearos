package com.aitech.weartak_civ.integration.plugin

import android.os.Bundle
import android.util.Log
import com.weartak.plugin.sdk.pli.v1.PliDetailContractV1
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Bounded host-side PLI detail collection. One registry belongs to one plugin manager. */
internal class PliDetailRegistry {
    enum class Registration { REGISTERED, INVALID, BUSY, UNAVAILABLE }

    private data class Provider(
        val owner: Any,
        val tags: Set<String>,
        val isActive: () -> Boolean,
        val build: (Bundle) -> Bundle,
    )

    private val lock = Any()
    private val providers = mutableListOf<Provider>()
    private val executor = ThreadPoolExecutor(
        0, 2, 60, TimeUnit.SECONDS, SynchronousQueue(),
        { task -> Thread(task, "weartak-pli-provider").apply { isDaemon = true } },
    )
    private var closed = false

    fun register(
        owner: Any,
        tags: List<String>,
        isActive: () -> Boolean,
        build: (Bundle) -> Bundle,
    ): Registration {
        if (tags.isEmpty() || tags.size > PliDetailXml.MAX_TAGS ||
            tags.toSet().size != tags.size || tags.any { !PliDetailXml.validTag(it) }
        ) return Registration.INVALID
        synchronized(lock) {
            if (closed) return Registration.UNAVAILABLE
            if (providers.any { it.owner === owner } || providers.size >= MAX_PROVIDERS ||
                providers.any { provider -> tags.any(provider.tags::contains) }
            ) return Registration.BUSY
            providers += Provider(owner, tags.toSet(), isActive, build)
        }
        return Registration.REGISTERED
    }

    fun unregister(owner: Any) {
        synchronized(lock) { providers.removeAll { it.owner === owner } }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            providers.clear()
        }
        executor.shutdownNow()
    }

    fun build(snapshot: Bundle): String {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEADLINE_MS)
        val captured = synchronized(lock) { providers.toList() }
        val output = StringBuilder()
        var outputBytes = 0
        for (provider in captured) {
            if (System.nanoTime() >= deadline) break
            if (!current(provider)) continue
            val future = try {
                executor.submit<Bundle> {
                    if (!current(provider)) throw IllegalStateException("Provider inactive")
                    provider.build(Bundle(snapshot))
                }
            } catch (_: RejectedExecutionException) {
                Log.w(TAG, "PLI provider workers unavailable")
                break
            }
            val remaining = deadline - System.nanoTime()
            val result = try {
                if (remaining <= 0) throw TimeoutException()
                future.get(remaining, TimeUnit.NANOSECONDS)
            } catch (_: TimeoutException) {
                future.cancel(true)
                Log.w(TAG, "PLI provider deadline exceeded")
                break
            } catch (_: InterruptedException) {
                future.cancel(true)
                Thread.currentThread().interrupt()
                Log.w(TAG, "PLI provider request interrupted")
                break
            } catch (error: ExecutionException) {
                Log.w(TAG, "PLI provider failed: ${error.cause?.javaClass?.simpleName}")
                continue
            }
            if (!current(provider) || System.nanoTime() >= deadline) continue
            try {
                val status = result.get(PliDetailContractV1.KEY_STATUS) as? Int
                    ?: throw IllegalArgumentException("Invalid provider status")
                if (status != PliDetailContractV1.OK) {
                    require(status in PliDetailContractV1.DISABLED..PliDetailContractV1.ERROR) {
                        "Invalid provider status"
                    }
                    continue
                }
                val raw = result.get(PliDetailContractV1.KEY_DETAILS) as? ArrayList<*>
                    ?: throw IllegalArgumentException("Invalid provider details")
                require(raw.size <= PliDetailXml.MAX_TAGS && raw.all { it is Bundle }) {
                    "Invalid provider detail"
                }
                val details = raw.map { it as Bundle }
                val xml = PliDetailXml.encode(details, provider.tags)
                if (xml.isEmpty()) continue
                val encodedBytes = xml.toByteArray(Charsets.UTF_8).size
                if (current(provider) && System.nanoTime() < deadline &&
                    outputBytes + encodedBytes <= PliDetailXml.MAX_BYTES
                ) {
                    synchronized(lock) {
                        if (!closed && providers.any { it === provider } &&
                            outputBytes + encodedBytes <= PliDetailXml.MAX_BYTES
                        ) {
                            output.append(xml)
                            outputBytes += encodedBytes
                        }
                    }
                }
            } catch (error: RuntimeException) {
                Log.w(TAG, "Rejected PLI provider response: ${error.javaClass.simpleName}")
            }
        }
        return output.toString()
    }

    private fun current(provider: Provider): Boolean {
        if (!synchronized(lock) { !closed && providers.any { it === provider } }) return false
        return try {
            provider.isActive()
        } catch (error: RuntimeException) {
            Log.w(TAG, "PLI provider eligibility failed: ${error.javaClass.simpleName}")
            false
        }
    }

    private companion object {
        const val MAX_PROVIDERS = 4
        const val DEADLINE_MS = 200L
        const val TAG = "WearTAK-PliDetails"
    }
}
