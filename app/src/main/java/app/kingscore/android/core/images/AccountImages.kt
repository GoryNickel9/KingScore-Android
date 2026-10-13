package app.kingscore.android.core.images

import android.content.Context
import app.kingscore.android.core.auth.AuthProblem
import app.kingscore.android.core.network.NetworkMonitor
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.network.ConnectivityChecker
import coil3.intercept.Interceptor
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.maxBitmapSize
import coil3.size.Size
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer

@OptIn(ExperimentalCoilApi::class)
class AccountImages(context: Context, val endpoint: String, network: NetworkMonitor,
    verifyAccount: suspend () -> Unit, onAccessFailure: (AuthProblem) -> Unit,
    transport: okhttp3.Interceptor? = null) : AutoCloseable {
    private val guard: ImageAccessGuard = ImageAccessGuard(network, verifyAccount) { problem ->
        withContext(Dispatchers.Main.immediate) { guard.checkOpen(); onAccessFailure(problem) }
    }
    private val client = OkHttpClient.Builder().cache(null).followSslRedirects(false)
        .callTimeout(15, TimeUnit.SECONDS).connectTimeout(10, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            guard.checkAccess()
            if (httpsImageUrl(chain.request().url.toString()) == null) throw IOException("Invalid image URL")
            chain.proceed(chain.request().newBuilder().header("Cache-Control", "no-store")
                .removeHeader("Authorization").removeHeader("apikey").removeHeader("Cookie").build())
        }
        .addInterceptor { chain ->
            guard.checkAccess()
            val response = chain.proceed(chain.request())
            val body = response.body ?: return@addInterceptor response
            if (body.contentLength() > MAX_IMAGE_BYTES) { response.close(); throw IOException("Image too large") }
            val source = object : ForwardingSource(body.source()) {
                private var total = 0L
                override fun read(sink: Buffer, byteCount: Long): Long {
                    guard.checkAccess()
                    val read = super.read(sink, minOf(byteCount, MAX_IMAGE_BYTES - total + 1))
                    if (read > 0) total += read
                    if (total > MAX_IMAGE_BYTES) throw IOException("Image too large")
                    guard.checkAccess()
                    return read
                }
            }.buffer()
            response.newBuilder().body(object : ResponseBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun source() = source
            }).build()
        }.apply { if (transport != null) addInterceptor(transport) }.build()

    val loader: ImageLoader = ImageLoader.Builder(context.applicationContext)
        .memoryCache(null).diskCache(null)
        .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED)
        .maxBitmapSize(Size(1024, 1024))
        .components {
            add(Interceptor { chain -> guard.checked { chain.proceed() } })
            add(OkHttpNetworkFetcherFactory(callFactory = { client }, connectivityChecker = { ConnectivityChecker { network.connected.value } }))
        }.build()

    override fun close() {
        guard.close()
        loader.shutdown()
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
    }

    private companion object { const val MAX_IMAGE_BYTES = 8L * 1024 * 1024 }
}
