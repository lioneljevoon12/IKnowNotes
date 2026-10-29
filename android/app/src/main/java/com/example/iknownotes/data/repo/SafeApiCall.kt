package com.example.iknownotes.data.repo

import retrofit2.HttpException
import java.io.IOException

/**
 * Runs [block] and turns network/HTTP failures into a Result so the UI layer
 * can just do `.onSuccess { }.onFailure { }` instead of sprinkling try/catch
 * everywhere.
 */
suspend fun <T> safeApiCall(block: suspend () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (e: HttpException) {
        val message = when (e.code()) {
            401 -> "Sesi kamu udah habis, silakan login lagi."
            404 -> "Data tidak ditemukan."
            in 500..599 -> "Server lagi bermasalah, coba lagi nanti."
            else -> e.response()?.errorBody()?.string()?.take(200) ?: "Terjadi kesalahan (${e.code()})."
        }
        Result.failure(Exception(message, e))
    } catch (e: IOException) {
        Result.failure(Exception("Gagal terhubung ke server. Cek koneksi/URL backend kamu.", e))
    } catch (e: Exception) {
        Result.failure(e)
    }
}
