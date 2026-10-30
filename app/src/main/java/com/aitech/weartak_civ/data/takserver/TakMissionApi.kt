
package com.aitech.weartak_civ.data.takserver

import com.aitech.weartak_civ.data.model.TakMission
import com.aitech.weartak_civ.data.model.TakMissionChange
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import com.fasterxml.jackson.databind.ObjectMapper
import android.util.Log
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

// Handles communication between WearTAK and the TAK Server Data Sync API
// Data Sync missions allow users to share routes and other map items
// Retrieves available missions, manages subscriptions, and requests mission content
// Handles server requests while other parts of the app process and display the data
class TakMissionApi(

    // Address of the TAK Server
    private val address: String,

    // SSL settings used to establish a secure server connection
    private val sslContext: SSLContext,

    // Trust manager used to validate the server certificate
    private val trustManager: X509TrustManager,

    // Unique identifier for the TAK Server
    private val serverId: String,

    // Display name of the TAK Server
    private val serverName: String,

    // HTTP client used to send requests to the TAK Server
    client: OkHttpClient = OkHttpClient.Builder()

        // Configure the secure connection using the provided SSL settings
        .sslSocketFactory(sslContext.socketFactory, trustManager)

        // Verify that the server hostname matches its certificate
        .hostnameVerifier(TakMissionHostnameVerifier())

        // Limit the time allowed to establish a connection
        .connectTimeout(15, TimeUnit.SECONDS)

        // Limit the time allowed to wait for a server response
        .readTimeout(15, TimeUnit.SECONDS)

        .build(),
    private val requestsAllowed: suspend () -> Boolean = { true },
    private val session: TakHttpSession? = null,
    strictHostname: Boolean = false,
) {
    private val client = if (strictHostname) client.newBuilder()
        .hostnameVerifier(OkHttpClient().hostnameVerifier)
        .followRedirects(false).followSslRedirects(false).build() else client

    // Converts JSON responses into Kotlin data objects
    private val objectMapper = ObjectMapper()


    // Builds the base URL for TAK Server mission requests
    // Uses HTTPS and port 8443 for the TAK Server REST API
    // Adds the requested API path to the server address
    private fun takMissionsEndpoint(
        address: String,
        path: String
    ): HttpUrl.Builder =
        HttpUrl.Builder()
            .scheme("https")
            .host(address)
            .port(8443)
            .addPathSegments(path)


    // Builds the URL used to retrieve all available missions
    // Points to the main Data Sync missions endpoint
    internal fun takMissionsURL(address: String): HttpUrl =
        takMissionsEndpoint(address, "Marti/api/missions")
            .addQueryParameter("defaultRole", "true")
            .build()


    // Builds the URL used to retrieve one specific mission
    // Adds the mission name to identify the requested mission
    internal fun takMissionURL(
        address: String,
        missionName: String
    ): HttpUrl =
        takMissionsEndpoint(address, "Marti/api/missions")
            .addPathSegment(missionName)
            .build()


    // Retrieves all available missions from the TAK Server
    // Sends a GET request to the missions endpoint
    // Reads the returned JSON and converts each mission into a TakMission object
    // Returns the completed list for use throughout the app
    suspend fun getAllMissions(): List<TakMission> {

        // Create a GET request for the available missions
        val request = Request.Builder()
            .url(takMissionsURL(address))
            .get()
            .addHeader("Accept", "*/*")
            .build()

        // Send the request and read the server response
        // Automatically close the response after processing
        ensureRequestsAllowed()
        (session?.route(client) ?: client).newCall(request).withSessionResponse(session) { response ->
            currentCoroutineContext().ensureActive()

            // Check whether the server returned a successful response
            // Throw an error if the request failed
            if (!response.isSuccessful) {
                throw IOException(
                    "Failed to get missions: HTTP ${response.code}"
                )
            }

            // Read the response body containing the mission information
            // Throw an error if the server returned no response body
            val body = response.body?.string()
                ?: throw IOException("Mission response body was empty")

            // Log the raw response to help identify issues during testing
            Log.d(
                "TakMissionApi",
                "getAllMissions response=$body"
            )

            // Convert the response text into a JSON structure
            val responseJson = objectMapper.readTree(body)

            // Extract the mission list from the data field
            val missions = responseJson.path("data")

            // Confirm that the returned mission data is an array
            // Stop processing if the response format is incorrect
            if (!missions.isArray) {
                throw IOException("Mission response data was not an array")
            }

            // Convert each JSON entry into a TakMission object
            // Return the list of mission objects
            return missions.map { mission ->
                objectMapper.treeToValue(
                    mission,
                    TakMission::class.java
                )
            }
        }
    }


    // Builds the URL used to subscribe to or unsubscribe from a mission
    // Includes the mission name and unique client identifier
    // Uses the same endpoint for both actions with different HTTP methods
    internal fun takMissionSubscriptionURL(
        address: String,
        missionName: String,
        clientUid: String
    ): HttpUrl =
        takMissionsEndpoint(address, "Marti/api/missions")
            .addPathSegment(missionName)
            .addPathSegment("subscription")
            .addQueryParameter("uid", clientUid)
            .build()


    // Builds the URL used to retrieve mission changes
    // The changes endpoint provides information about updates to mission content
    internal fun takMissionChangesURL(
        address: String,
        missionName: String
    ): HttpUrl =
        takMissionsEndpoint(address, "Marti/api/missions")
            .addPathSegment(missionName)
            .addPathSegment("changes")
            .build()


    // Builds the URL used to retrieve mission CoT data
    // CoT stands for Cursor on Target
    // CoT is the format TAK uses to share routes and other map information
    internal fun takMissionCotURL(
        address: String,
        missionName: String
    ): HttpUrl =
        takMissionsEndpoint(address, "Marti/api/missions")
            .addPathSegment(missionName)
            .addPathSegment("cot")
            .build()


    // Builds the URL used to add content to a mission
    // The contents endpoint accepts identifiers for CoT items
    internal fun takMissionContentsURL(
        address: String,
        missionName: String
    ): HttpUrl =
        takMissionsEndpoint(address, "Marti/api/missions")
            .addPathSegment(missionName)
            .addPathSegment("contents")
            .build()


    // Retrieves information about one specific mission
    // Uses the mission name to request its details from the server
    // Returns a TakMission object when mission information is available
    // Returns null if the mission is missing or the returned list is empty
    suspend fun getOneMission(
        missionName: String
    ): TakMission? {

        // Create a GET request for the selected mission
        val request = Request.Builder()
            .url(takMissionURL(address, missionName))
            .get()
            .addHeader("Accept", "*/*")
            .build()

        // Send the request and read the server response
        ensureRequestsAllowed()
        (session?.route(client) ?: client).newCall(request).withSessionResponse(session) { response ->
            currentCoroutineContext().ensureActive()

            // Return null when the server reports that the mission does not exist
            if (response.code == 404) {
                return null
            }

            // Throw an error for other unsuccessful responses
            // HTTP 403 can indicate that the server denied access
            if (!response.isSuccessful) {
                throw IOException(
                    "Failed to get mission $missionName: HTTP ${response.code}"
                )
            }

            // Read the mission information returned by the server
            val body = response.body?.string()
                ?: throw IOException("Mission response body was empty")

            // Convert the response body into JSON
            val responseJson = objectMapper.readTree(body)

            // Extract the mission information from the data field
            val missions = responseJson.path("data")

            // Return null if the response contains no mission information
            if (!missions.isArray || missions.isEmpty) {
                return null
            }

            // Convert the first returned mission into a TakMission object
            return objectMapper.treeToValue(
                missions.first(),
                TakMission::class.java
            )
        }
    }


    // Subscribes the watch to a selected Data Sync mission
    // Sends a PUT request containing the mission name and client UID
    // Allows the watch to access content shared through the mission
    // Mission content retrieval is handled separately
    suspend fun subscribeToMission(
        missionName: String,
        clientUid: String
    ) {

        // Create a PUT request for the mission subscription endpoint
        // Use an empty request body because the required values are in the URL
        val request = Request.Builder()
            .url(takMissionSubscriptionURL(address, missionName, clientUid))
            .put("".toRequestBody())
            .addHeader("Accept", "*/*")
            .build()

        // Send the subscription request
        // Check the server response through the shared execute function
        execute(
            request = request,
            operation = "subscribe-mission"
        )
    }


    // Unsubscribes the watch from a selected Data Sync mission
    // Sends a DELETE request to remove the client subscription
    // Stops the watch from being subscribed to that mission on the server
    // Local route removal is handled separately
    suspend fun unsubscribeFromMission(
        missionName: String,
        clientUid: String
    ) {

        // Create a DELETE request using the mission subscription URL
        val request = Request.Builder()
            .url(
                takMissionSubscriptionURL(
                    address,
                    missionName,
                    clientUid
                )
            )
            .delete()
            .addHeader("Accept", "*/*")
            .build()

        // Send the unsubscribe request to the TAK Server
        ensureRequestsAllowed()
        (session?.route(client) ?: client).newCall(request).withSessionResponse(session) { response ->
            currentCoroutineContext().ensureActive()

            // Check whether the server accepted the unsubscribe request
            // Throw an error if the request failed
            if (!response.isSuccessful) {
                throw IOException(
                    "Failed to unsubscribe from mission $missionName: HTTP ${response.code}"
                )
            }
        }
    }


    // Retrieves the list of changes associated with a mission
    // Sends a GET request to the mission changes endpoint
    // Converts the returned JSON into TakMissionChange objects
    // Returns the list so other parts of the app can process mission updates
    suspend fun getMissionChanges(
        missionName: String
    ): List<TakMissionChange> {

        // Create a GET request for mission changes
        val request = Request.Builder()
            .url(takMissionChangesURL(address, missionName))
            .get()
            .addHeader("Accept", "*/*")
            .build()

        // Send the request and retrieve the response body
        val body = execute(
            request = request,
            operation = "get-mission-changes"
        )
        Log.d("MissionChanges", "Response: $body")

        // Convert the response text into JSON
        val responseJson = objectMapper.readTree(body)

        // Extract the changes from the data field
        val changes = responseJson.path("data")

        // Confirm that the returned changes are stored in an array
        // Throw a parsing error if the response has an unexpected format
        if (!changes.isArray) {
            throw TakMissionParseException(
                "Mission changes response data was not an array"
            )
        }

        // Convert each JSON change into a TakMissionChange object
        // Return the list of mission changes
        return changes.map { change ->
            objectMapper.treeToValue(
                change,
                TakMissionChange::class.java
            )
        }
    }


    // Retrieves the CoT content associated with a specific mission
    // CoT contains map information shared through TAK
    // Mission CoT can contain routes and other supported map items
    // Returns the raw response without converting it into route objects
    suspend fun getMissionCot(
        missionName: String
    ): String {

        // Create a GET request for the mission CoT endpoint
        val request = Request.Builder()
            .url(takMissionCotURL(address, missionName))
            .get()
            .addHeader("Accept", "*/*")
            .build()

        // Send the request and return the raw CoT response
        // Route parsing is handled by another part of the app
        return execute(
            request = request,
            operation = "get-mission-cot"
        )
    }


    // Adds selected CoT items to a Data Sync mission
    // Accepts the mission name and a list of CoT item UIDs
    // Sends the identifiers to the server in a JSON request
    // Allows existing TAK content to be associated with the mission
    suspend fun addMissionContent(
        missionName: String,
        uids: List<String>
    ) {

        // Create a JSON object containing the selected CoT item identifiers
        // Store the identifiers in an array named uids
        val payload = objectMapper.createObjectNode().apply {
            putArray("uids").apply {

                // Add each UID from the provided list to the JSON array
                uids.forEach { add(it) }
            }
        }

        // Create a PUT request for the mission contents endpoint
        // Convert the JSON payload into the request body
        // Specify that the request contains JSON data
        val request = Request.Builder()
            .url(takMissionContentsURL(address, missionName))
            .put(
                payload.toString()
                    .toRequestBody("application/json".toMediaType())
            )
            .addHeader("Accept", "*/*")
            .build()

        // Send the request to add the selected content
        // Check whether the server accepted the request
        execute(
            request = request,
            operation = "add-mission-content"
        )
    }


    // Shared helper function used to execute mission API requests
    // Sends the provided HTTP request to the TAK Server
    // Checks the HTTP response and returns the response body
    // Throws an exception when the server returns an unsuccessful status
    private suspend fun execute(
        request: Request,
        operation: String
    ): String {
        ensureRequestsAllowed()

        try {

            // Send the request and automatically close the response afterward
            return (session?.route(client) ?: client).newCall(request).withSessionResponse(session) { response ->
                currentCoroutineContext().ensureActive()

                // Read the response body as text
                // Use an empty string when no response body is available
                val body = response.body?.string().orEmpty()

                // Check whether the server returned a successful status
                // Include the HTTP status, request method, and URL path in the error
                // This helps identify which API request failed
                if (!response.isSuccessful) {
                    throw TakMissionHttpException(
                        "TAK mission request failed: " +
                                "${response.code} ${request.method} ${request.url.encodedPath}"
                    )
                }

                // Return the response body to the calling function
                body
            }

        } catch (error: Exception) {

            // Pass the exception back to the calling function
            // Error handling is performed by the caller
            throw error
        }
    }

    private suspend fun ensureRequestsAllowed() {
        currentCoroutineContext().ensureActive()
        if (!requestsAllowed()) throw TakMissionPausedException()
    }
}

internal class TakMissionPausedException : IOException("Data Sync is paused")

// Base exception type for errors related to the mission API
// Allows mission API errors to be identified separately from other errors
internal open class TakMissionApiException(
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)


// Exception used when a mission API request fails with an HTTP error
// Examples include HTTP 403 for denied access and HTTP 500 for server errors
internal class TakMissionHttpException(
    message: String
) : TakMissionApiException(message)


// Exception used when mission data is not in the expected format
// Helps identify problems with parsing server responses
internal class TakMissionParseException(
    message: String,
    cause: Throwable? = null
) : TakMissionApiException(message, cause)
