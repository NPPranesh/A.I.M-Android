package com.example.aim_android

import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

// 1. Data model for backend telemetry response
data class TelemetryResponse(
    val status: String,
    val telemetry: TelemetryDetails?
)

data class TelemetryDetails(
    val sample_count: Int,
    val rms_amplitude: Float,
    val zero_crossing_rate: Float,
    val spectral_centroid_hz: Float
)

// 2. Retrofit Interface
interface AimApiService {
    @Multipart
    @POST("api/telemetry/process-chunk")
    suspend fun uploadAudioChunk(
        @Part file: MultipartBody.Part
    ): Response<TelemetryResponse>
}

// 3. Singleton Network Client
object NetworkClient {
    private const val BASE_URL = "http://172.20.10.13:8001/"

    val apiService: AimApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AimApiService::class.java)
    }
}