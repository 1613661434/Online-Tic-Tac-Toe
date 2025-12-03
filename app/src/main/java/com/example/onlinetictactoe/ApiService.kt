package com.example.onlinetictactoe

import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

// 在线匹配响应模型（JSON解析用）
data class MatchResponse(
    val code: Int,
    val msg: String,
    val data: MatchData?
)

data class MatchData(
    val opponent: String,
    val matchId: String
)

// Retrofit接口
interface ApiService {
    // 模拟在线匹配接口
    @GET("match")
    suspend fun startMatch(@Query("mode") mode: String): Response<MatchResponse>

    companion object {
        // 模拟BaseURL
        private const val BASE_URL = "https://mock.tic-tac-toe.com/"

        fun create(): ApiService {
            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(ApiService::class.java)
        }
    }
}