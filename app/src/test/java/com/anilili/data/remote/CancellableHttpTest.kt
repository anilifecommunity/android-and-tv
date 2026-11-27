package com.anilili.data.remote

import com.anilili.data.backgroundMetadataGate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.BufferedSource
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CancellableHttpTest {
    private val server = MockWebServer()
    private val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()

    @Before fun start() = server.start()
    @After fun stop() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        server.shutdown()
        client.dispatcher.executorService.shutdownNow()
    }

    @Test fun `cancelling stalled headers closes the actual HTTP call`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val call = client.newCall(Request.Builder().url(server.url("/metadata")).build())
        val job = launch(start = CoroutineStart.UNDISPATCHED) { call.readCancellable { it.body!!.string() } }
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        job.cancelAndJoin()
        assertTrue(call.isCanceled())
        awaitIdle()
    }

    @Test fun `cancelling a stalled body releases the shared enrichment slot`() = runBlocking {
        server.enqueue(MockResponse().setBody("a".repeat(128)).throttleBody(1, 1, TimeUnit.SECONDS))
        val reading = CompletableDeferred<Unit>()
        val call = client.newCall(Request.Builder().url(server.url("/artwork")).build())
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            backgroundMetadataGate.withPermit {
                call.readCancellable { reading.complete(Unit); it.body!!.string() }
            }
        }
        withTimeout(5_000) { reading.await() }
        job.cancelAndJoin()
        assertTrue(call.isCanceled())
        assertEquals(2, backgroundMetadataGate.availablePermits)
        awaitIdle()
    }

    @Test fun `successful and failed parsers both close their response body`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("metadata"))
        server.enqueue(MockResponse().setBody("invalid"))
        var source: BufferedSource? = null
        val request = Request.Builder().url(server.url("/detail")).build()
        assertEquals("metadata", client.newCall(request).readCancellable {
            source = it.body!!.source()
            it.body!!.string()
        })
        assertThrows(IllegalStateException::class.java) { source!!.readByte() }
        val result = runCatching {
            client.newCall(request).readCancellable<String> {
                source = it.body!!.source()
                error("Malformed metadata")
            }
        }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertThrows(IllegalStateException::class.java) { source!!.readByte() }
    }

    private suspend fun awaitIdle() = withTimeout(5_000) {
        while (client.dispatcher.runningCallsCount() != 0) delay(10)
    }
}
