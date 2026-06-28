package com.github.tomseanmy.githelper.ai

import com.github.tomseanmy.githelper.GitHelper
import com.intellij.openapi.diagnostic.logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Sends a JSON body and returns the raw response. Centralised so providers
 * share timeouts, headers and error logging.
 */
object HttpExecutor {
    private val LOG = logger<HttpExecutor>()

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    fun postJson(
        url: String,
        body: String,
        headers: Map<String, String>,
        timeoutSeconds: Int,
    ): HttpResponse<String> {
        LOG.debug("POST $url")
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(timeoutSeconds.toLong()))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        headers.forEach { (k, v) -> builder.header(k, v) }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    /**
     * Opens a streaming connection and feeds each line of the body to
     * [onLine] as it arrives. Used for SSE parsing.
     */
    fun postStream(
        url: String,
        body: String,
        headers: Map<String, String>,
        timeoutSeconds: Int,
        onLine: (String) -> Unit,
        isCanceled: () -> Boolean = { false },
    ): Int {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(timeoutSeconds.toLong()))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        headers.forEach { (k, v) -> builder.header(k, v) }

        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofLines())
        // Iterate lines manually so we can stop early when the user cancels.
        val iterator = response.body().iterator()
        while (iterator.hasNext()) {
            if (isCanceled()) break
            onLine(iterator.next())
        }
        return response.statusCode()
    }

    fun errorBody(status: Int, body: String): String {
        val trimmed = body.trim()
        return if (trimmed.isEmpty()) "HTTP $status" else "HTTP $status: ${trimmed.take(500)}"
    }
}
