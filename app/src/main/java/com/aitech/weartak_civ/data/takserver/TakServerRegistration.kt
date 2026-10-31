package com.aitech.weartak_civ.data.takserver

import java.util.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A UI request outlives the runtime detail that a configuration refresh may replace. */
class TakServerRegistration {
    enum class Status { SAVING, CONNECTING, CONNECTED, FAILED, DUPLICATE }
    enum class SaveResult { SAVED, DUPLICATE, INVALID }
    enum class Step {
        SERVICE_INSTANCE, PERSISTED_LIST_RELOAD, ID_NORMALIZATION,
        RUNTIME_RECONCILIATION, DETAIL_LOOKUP, CONNECTION_DISPATCH
    }
    internal class StepFailure(val step: Step, cause: Exception) :
        Exception("Registration step failed", cause)
    data class State(
        val serverId: String? = null,
        val status: Status = Status.FAILED,
        val failure: TakConnectionFailure? = null,
        val requestId: Long = 0,
    ) {
        val isLoading get() = status == Status.SAVING || status == Status.CONNECTING
    }

    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private var request = 0L
    private var runtime: Any? = null

    @Synchronized
    fun prepare(serverId: String): Long {
        runtime = null
        val ticket = ++request
        mutableState.value = State(serverId, Status.SAVING, requestId = ticket)
        return ticket
    }

    suspend fun saveAndConnect(
        serverId: String,
        save: suspend () -> SaveResult,
        ticket: Long = prepare(serverId),
        connect: suspend () -> Unit,
    ) {
        var phase = "save"
        try {
            when (save()) {
                SaveResult.SAVED -> if (isPending(ticket)) {
                    phase = "connect"
                    connect()
                }
                SaveResult.DUPLICATE -> finishSave(ticket, Status.DUPLICATE)
                SaveResult.INVALID -> finishSave(ticket, Status.FAILED, unavailable())
            }
        } catch (error: CancellationException) {
            finishSave(ticket, Status.FAILED, unavailable())
            throw error
        } catch (error: Exception) {
            val causes = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
            val failure = causes.filterIsInstance<TakEnrollmentException>().firstOrNull()?.failure
            val step = causes.filterIsInstance<StepFailure>().firstOrNull()?.step
            val types = causes.joinToString(" -> ") { it.javaClass.simpleName.take(80) }
            Logger.getLogger("WearTAK-Registration")
                .severe("Registration failed phase=$phase" +
                    (step?.let { " registrationStep=$it" } ?: "") + " causes=$types" +
                    (failure?.let { " stage=${it.stage} category=${it.category} httpCode=${it.httpCode}" } ?: ""))
            finishSave(ticket, Status.FAILED, failure ?: unavailable())
        }
    }

    internal suspend fun <T> atStep(step: Step, action: suspend () -> T): T = try {
        action()
    } catch (error: CancellationException) {
        throw error
    } catch (error: StepFailure) {
        throw error
    } catch (error: Exception) {
        throw StepFailure(step, error)
    }

    fun failPending(ticket: Long) = finishSave(ticket, Status.FAILED, unavailable())

    @Synchronized
    private fun isPending(ticket: Long) = request == ticket && mutableState.value.isLoading

    @Synchronized
    private fun finishSave(ticket: Long, status: Status, failure: TakConnectionFailure? = null) {
        if (request == ticket && mutableState.value.isLoading) {
            mutableState.value = mutableState.value.copy(status = status, failure = failure)
        }
    }

    @Synchronized
    fun bind(serverId: String, detail: Any) {
        if (mutableState.value.serverId == serverId && mutableState.value.isLoading) {
            runtime = detail
            mutableState.value = mutableState.value.copy(status = Status.CONNECTING, failure = null)
        }
    }

    @Synchronized
    fun replaceRuntime(serverId: String, previous: Any, replacement: Any) {
        if (runtime === previous) bind(serverId, replacement)
    }

    @Synchronized
    fun isPendingRuntime(detail: Any) = runtime === detail && mutableState.value.isLoading

    @Synchronized
    fun publish(serverId: String, detail: Any, status: Status, failure: TakConnectionFailure? = null) {
        if (runtime === detail && mutableState.value.serverId == serverId && mutableState.value.isLoading) {
            mutableState.value = mutableState.value.copy(status = status, failure = failure)
        }
    }

    fun unavailable() = TakConnectionFailure(TakEnrollmentStage.CONNECTION, TakFailureCategory.CONNECTIVITY)
}
