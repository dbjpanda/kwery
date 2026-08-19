package dev.kwery.sample

import android.content.Context
import com.chuckerteam.chucker.api.ChuckerInterceptor
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * A real HTTP client, hitting a real server.
 *
 * The sample used to fake this, which made the interesting number — how many
 * requests actually happened — something the sample asserted about itself.
 * Chucker sits in the OkHttp chain instead, so the request log is produced by
 * a third-party tool that has no idea Kwery exists.
 *
 * This is also the clearest possible statement of what Kwery is: Retrofit does
 * the networking, exactly as it would without Kwery, and Kwery decides when
 * Retrofit gets called.
 */
interface TodoApi {

    @GET("todos")
    suspend fun todos(@Query("_limit") limit: Int = 5): List<RemoteTodo>

    @GET("todos/{id}")
    suspend fun todo(@Path("id") id: Int): RemoteTodo

    @PATCH("todos/{id}")
    suspend fun setCompleted(@Path("id") id: Int, @Body body: CompletedPatch): RemoteTodo
}

fun buildTodoApi(context: Context): TodoApi {
    val json = Json { ignoreUnknownKeys = true }

    val client = OkHttpClient.Builder()
        // Every call the app makes, visible on the device, whether or not it
        // came from Kwery. That is the whole point: the evidence is not ours.
        .addInterceptor(ChuckerInterceptor.Builder(context).build())
        .build()

    return Retrofit.Builder()
        .baseUrl("https://jsonplaceholder.typicode.com/")
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(TodoApi::class.java)
}
