package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.models.*
import com.streamflixreborn.streamflix.utils.DnsResolver
import kotlinx.coroutines.CancellationException
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Url
import java.io.File
import java.util.concurrent.TimeUnit

abstract class BaseProvider : Provider {

    // ============================================
    // CONFIGURACIÓN SOBRESCRIBIBLE POR CADA PROVIDER
    // ============================================
    protected open val userAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    protected open val timeoutSeconds: Long = 30L
    protected open val cacheSizeMB: Long = 0L
    protected open val cacheDir: File? = null
    protected open val useCache: Boolean = false
    protected open val useDoh: Boolean = true

    // ============================================
    // CLIENTE HTTP COMPARTIDO (LAZY)
    // ============================================
    protected val baseClient: OkHttpClient by lazy { buildClient() }

    protected open fun buildClient(): OkHttpClient {
        return OkHttpClient.Builder().apply {
            if (useCache && cacheDir != null && cacheSizeMB > 0) {
                cache(Cache(cacheDir!!, cacheSizeMB * 1024 * 1024))
            }
            readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            if (useDoh) {
                dns(DnsResolver.doh)
            }
            addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", userAgent)
                    .build()
                chain.proceed(request)
            }
        }.build()
    }

    // ============================================
    // RETROFIT Y SERVICIO COMPARTIDO
    // ============================================
    protected val baseRetrofit: Retrofit by lazy { buildRetrofit() }

    protected open fun buildRetrofit(): Retrofit {
        val formattedBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return Retrofit.Builder()
            .baseUrl(formattedBaseUrl)
            .addConverterFactory(JsoupConverterFactory.create())
            .client(baseClient)
            .build()
    }

    // Servicio interno para peticiones genéricas
    protected interface BaseService {
        @GET
        suspend fun getPage(@Url url: String): Document
    }

    protected val baseService: BaseService by lazy {
        baseRetrofit.create(BaseService::class.java)
    }

    protected suspend fun getPage(url: String): Document {
        return baseService.getPage(url)
    }

    // ============================================
    // MANEJO DE ERRORES CENTRALIZADO
    // ============================================

    protected suspend fun <T> safeFetch(
        operation: String,
        default: T,
        block: suspend () -> T
    ): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(name, "[$operation] Error: ${e.message}", e)
        default
    }

    protected suspend fun <T> safeFetchList(
        operation: String,
        block: suspend () -> List<T>
    ): List<T> = safeFetch(operation, emptyList(), block)

    // ============================================
    // MÉTODOS DEFAULT
    // ============================================

    override suspend fun getPeople(id: String, page: Int): People {
        throw UnsupportedOperationException("$name no soporta getPeople")
    }
}
