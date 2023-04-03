package io.agora.agorauikit_android
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.logging.Level
import java.util.logging.Logger

class SitebillToken {
    init {
    }

    fun fetchData(completion: (String) -> Unit) {
        val log: Logger = Logger.getLogger("AgoraVideoUIKit")
        val client = OkHttpClient()
        // val url = "$urlBase/rtc/$channelName/publisher/uid/$userId/"
        val url = "https://agora-token-service.sitebill.site/rtc/test4/publisher/uid/0/"
        val request: okhttp3.Request = Request.Builder()
            .url(url)
            .method("GET", null)
            .build()
        try {
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    log.log(Level.WARNING, "Unexpected code ${e.localizedMessage}")
                    // completion.onError(TokenError.INVALIDDATA)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.body?.string()?.let {
                        val jObject = JSONObject(it)
                        val token = jObject.getString("rtcToken")
                        if (token.isNotEmpty()) {
                            completion(token)
                            return
                        }
                    }
                    // completion.onError(TokenError.NODATA)
                }
            }
            )
        } catch (e: IOException) {
            log.log(Level.WARNING, e.localizedMessage)
            // completion.onError(TokenError.INVALIDURL)
        } catch (e: JSONException) {
            log.log(Level.WARNING, e.localizedMessage)
            // completion.onError(TokenError.INVALIDDATA)
        }
    }

}