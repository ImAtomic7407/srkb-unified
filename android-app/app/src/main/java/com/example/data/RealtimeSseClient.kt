package com.example.data

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random

/**
 * Realtime SSE Client for SRKB Unified.
 * Implements REALTIME_CONTRACT.md v2.0 (Server-Sent Events over GET {callingBackendUrl}/events).
 */
object RealtimeSseClient {
    private const val TAG = "RealtimeSseClient"

    private var eventSource: EventSource? = null
    private var sseClient: OkHttpClient? = null
    private var clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var isConnecting = false
    private var isConnectedInternal by mutableStateOf(false)
    val isConnected: Boolean get() = isConnectedInternal

    private var lastBytesReceived = 0L
    private var livenessJob: Job? = null
    private var reconnectJob: Job? = null
    private var backoffMs = 1000L
    private var stopReconnectingPermanently = false
    private var connectedDurationJob: Job? = null

    fun updateState() {
        val jwt = AppDatabase.jwtToken
        val isOnline = AppDatabase.isOnline
        if (!jwt.isNullOrBlank() && isOnline && !stopReconnectingPermanently) {
            // reconnectJob active means scheduleReconnect() is mid-backoff. Without this check the
            // 5-second poll walks straight past the ladder: it sees eventSource == null (true for
            // the whole sleep) and calls connect() itself, so a server that is down gets hammered
            // every 5s forever and the 1→2→5→10→30s backoff never has any effect at all.
            if (eventSource == null && !isConnecting && reconnectJob?.isActive != true) {
                connect()
            }
        } else {
            disconnect()
        }
    }

    @Synchronized
    fun connect() {
        val jwt = AppDatabase.jwtToken
        if (jwt.isNullOrBlank()) return
        val baseUrl = AppDatabase.callingBackendUrl.ifBlank { "https://calling.example.com" }.trimEnd('/')
        val url = "$baseUrl/events"

        if (isConnecting || eventSource != null) return
        isConnecting = true

        if (sseClient == null) {
            sseClient = OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS) // Indefinite SSE connection
                .connectTimeout(15, TimeUnit.SECONDS)
                .build()
        }

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $jwt")
            .header("Accept", "text/event-stream")
            .build()

        val factory = EventSources.createFactory(sseClient!!)
        
        Log.d(TAG, "Connecting to SSE endpoint: $url")

        eventSource = factory.newEventSource(request, object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                Log.i(TAG, "SSE Connected successfully (HTTP ${response.code})")
                isConnecting = false
                isConnectedInternal = true
                lastBytesReceived = System.currentTimeMillis()

                // Every connect / reconnect forces a full refresh (REALTIME_CONTRACT §6)
                AppDatabase.bumpDataRevision()
                AppDatabase.bumpStructureRevision()

                startLivenessChecker()

                // Reset backoff to 1s after connection remains healthy for 60s
                connectedDurationJob?.cancel()
                connectedDurationJob = clientScope.launch {
                    delay(60_000L)
                    backoffMs = 1000L
                }
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                lastBytesReceived = System.currentTimeMillis()
                Log.d(TAG, "SSE Event received: type=$type, data=$data")

                when (type) {
                    "connected" -> {
                        AppDatabase.bumpDataRevision()
                        // Just (re)joined, and we cannot know what moved while we were away.
                        AppDatabase.bumpStructureRevision()
                    }
                    "data.changed" -> {
                        handleDataChanged(data)
                    }
                    "dropdowns.changed" -> {
                        AppDatabase.bumpDataRevision()
                        // An edited response list changes what every picker offers.
                        AppDatabase.bumpStructureRevision()
                    }
                    else -> {
                        // Unknown event names must be ignored (REALTIME_CONTRACT §5)
                    }
                }
            }

            override fun onClosed(eventSource: EventSource) {
                Log.w(TAG, "SSE Connection closed by server")
                handleDisconnect(reconnect = true)
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val code = response?.code ?: -1
                Log.e(TAG, "SSE Connection failure: code=$code, err=${t?.message}")

                if (code == 401 || code == 403) {
                    Log.e(TAG, "Auth failure (HTTP $code). Permanent stop until re-auth.")
                    stopReconnectingPermanently = true
                    handleDisconnect(reconnect = false)
                } else {
                    handleDisconnect(reconnect = true)
                }
            }
        })
    }

    private fun handleDataChanged(data: String) {
        try {
            val json = JSONObject(data)
            val centreCode = if (json.isNull("centreCode")) null else json.optString("centreCode", null)
            val category = if (json.isNull("category")) null else json.optString("category", null)

            val currentCentre = AppDatabase.selectedFlaskCentreId
            val currentCategory = AppDatabase.selectedCategory

            // REALTIME_CONTRACT §7 Filtering:
            if (centreCode != null && centreCode.isNotEmpty() && centreCode != "null" && centreCode != currentCentre) {
                Log.d(TAG, "Ignoring data.changed event for non-selected centre: $centreCode")
                return
            }

            if (category != null && category.isNotEmpty() && category != "null" && !category.equals(currentCategory, ignoreCase = true)) {
                Log.d(TAG, "Ignoring data.changed event for non-selected category: $category")
                return
            }

            Log.i(TAG, "data.changed matched scope (centre=$centreCode, cat=$category). Bumping revision.")
            AppDatabase.bumpDataRevision()
            // A change to the centre’s SHAPE, as opposed to the flow of marks through it. The
            // server already names the kind (lib/ae-broadcast.ts BROADCAST_KINDS).
            if (isStructuralKind(json.optString("kind", ""))) AppDatabase.bumpStructureRevision()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse data.changed payload: ${e.message}")
            AppDatabase.bumpDataRevision() // Best effort fallback
            AppDatabase.bumpStructureRevision()
        }
    }

    /**
     * The three high-frequency kinds are the flow of attendance through a class; every other kind
     * changes what the pickers and lists CONTAIN. Written as an exclusion so a kind added to the
     * server later counts as structural by default — a screen refreshing when it need not have is
     * invisible, one that never refreshes is the bug being fixed here.
     */
    internal fun isStructuralKind(kind: String): Boolean = when (kind) {
        "attendance.persisted", "session_attendance_recorded", "roster.injected" -> false
        else -> true
    }

    private fun startLivenessChecker() {
        livenessJob?.cancel()
        livenessJob = clientScope.launch {
            while (isActive && isConnectedInternal) {
                delay(10_000L)
                // Liveness check: If no bytes received for > 60s, reconnect (REALTIME_CONTRACT §6)
                if (System.currentTimeMillis() - lastBytesReceived > 60_000L) {
                    Log.w(TAG, "Liveness timeout: No bytes received for 60s. Force reconnecting.")
                    handleDisconnect(reconnect = true)
                    break
                }
            }
        }
    }

    /**
     * Stop the stream. Does NOT clear [stopReconnectingPermanently] — clearing it here is what
     * made a 401 an infinite loop rather than a stop:
     *
     *   401 -> flag = true -> 5s later the poll calls updateState() -> the flag routes it to the
     *   else branch -> disconnect() -> flag cleared -> next tick reconnects -> 401 ...
     *
     * i.e. the guard erased itself every five seconds, forever. The flag is only cleared by
     * [resetAuthStop], which AppData now calls on a successful login, and by logout(). Removing
     * the line without those callers would turn a recoverable storm into a permanently dead
     * stream, so the two changes belong together.
     */
    @Synchronized
    fun disconnect() {
        handleDisconnect(reconnect = false)
    }

    private fun handleDisconnect(reconnect: Boolean) {
        isConnecting = false
        isConnectedInternal = false
        connectedDurationJob?.cancel()
        connectedDurationJob = null
        livenessJob?.cancel()
        livenessJob = null

        eventSource?.cancel()
        eventSource = null

        if (reconnect && !stopReconnectingPermanently && AppDatabase.isOnline && !AppDatabase.jwtToken.isNullOrBlank()) {
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = clientScope.launch {
            // Apply ±20% jitter
            val jitter = (backoffMs * (Random.nextDouble(-0.2, 0.2))).toLong()
            val effectiveDelay = (backoffMs + jitter).coerceAtLeast(1000L)

            Log.i(TAG, "Scheduling SSE reconnect in ${effectiveDelay}ms (backoff base=${backoffMs}ms)")
            delay(effectiveDelay)

            // Increase backoff for next attempt: 1s -> 2s -> 5s -> 10s -> 30s
            backoffMs = when {
                backoffMs < 2000L -> 2000L
                backoffMs < 5000L -> 5000L
                backoffMs < 10000L -> 10000L
                else -> 30000L
            }

            if (AppDatabase.isOnline && !AppDatabase.jwtToken.isNullOrBlank() && !stopReconnectingPermanently) {
                connect()
            }
        }
    }

    fun resetAuthStop() {
        stopReconnectingPermanently = false
        backoffMs = 1000L
        updateState()
    }
}
