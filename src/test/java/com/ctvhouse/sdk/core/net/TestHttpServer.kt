package com.ctvhouse.sdk.core.net

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Loopback HTTP/1.1 server for client tests: one request per connection, always `Connection: close`.
 * Deliberately dependency-free — the library ships no HTTP stack of its own.
 */
internal class TestHttpServer : Closeable {

    data class Request(val path: String, val headers: Map<String, String>)

    data class Response(
        val code: Int = 200,
        val body: ByteArray = ByteArray(0),
        val headers: Map<String, String> = emptyMap(),
        val delayMs: Long = 0L,
    )

    private val socket = ServerSocket(0, BACKLOG, InetAddress.getByName("127.0.0.1"))
    private val routes = ConcurrentHashMap<String, (Request) -> Response>()
    private val received = Collections.synchronizedList(mutableListOf<Request>())

    val requests: List<Request> get() = received.toList()

    init {
        Thread({ acceptLoop() }, "test-http").apply { isDaemon = true }.start()
    }

    fun url(path: String): String = "http://127.0.0.1:${socket.localPort}$path"

    fun on(path: String, handler: (Request) -> Response) {
        routes[path] = handler
    }

    fun text(path: String, body: String, code: Int = 200) {
        on(path) { Response(code = code, body = body.toByteArray()) }
    }

    fun redirect(from: String, to: String) {
        on(from) { Response(code = 302, headers = mapOf("Location" to to)) }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    private fun acceptLoop() {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: Throwable) {
                return
            }
            Thread({ serve(client) }, "test-http-conn").apply { isDaemon = true }.start()
        }
    }

    private fun serve(client: Socket) {
        client.use {
            runCatching {
                val request = readRequest(it) ?: return
                received.add(request)
                val response = routes[request.path]?.invoke(request) ?: Response(code = 404)
                if (response.delayMs > 0) Thread.sleep(response.delayMs)
                writeResponse(it, response)
            }
        }
    }

    private fun readRequest(client: Socket): Request? {
        val input = client.getInputStream()
        val line = StringBuilder()
        val headerLines = mutableListOf<String>()
        var requestLine: String? = null
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (b != '\n'.code) {
                if (b != '\r'.code) line.append(b.toChar())
                continue
            }
            val text = line.toString()
            line.setLength(0)
            if (requestLine == null) {
                requestLine = text
                continue
            }
            if (text.isEmpty()) break
            headerLines.add(text)
        }
        val path = requestLine?.split(' ')?.getOrNull(1) ?: return null
        val headers = headerLines.mapNotNull { header ->
            val name = header.substringBefore(':', "")
            if (name.isEmpty()) null else name.lowercase() to header.substringAfter(':').trim()
        }.toMap()
        return Request(path, headers)
    }

    private fun writeResponse(client: Socket, response: Response) {
        val head = StringBuilder("HTTP/1.1 ${response.code} X\r\n")
        response.headers.forEach { (name, value) -> head.append("$name: $value\r\n") }
        // A handler that declares its own length wins, so a test can lie about the body size.
        if (response.headers.keys.none { it.equals("Content-Length", ignoreCase = true) }) {
            head.append("Content-Length: ${response.body.size}\r\n")
        }
        head.append("Connection: close\r\n\r\n")
        val out = client.getOutputStream()
        out.write(head.toString().toByteArray())
        out.write(response.body)
        out.flush()
    }

    private companion object {
        const val BACKLOG = 16
    }
}
