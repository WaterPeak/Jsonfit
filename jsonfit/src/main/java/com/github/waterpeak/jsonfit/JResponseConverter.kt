package com.github.waterpeak.jsonfit

import okhttp3.Callback
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.io.InterruptedIOException
import java.lang.Exception
import java.lang.ref.WeakReference
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

interface IResponseListener<T : JResponse> {
    fun onResponse(response: T)
}

class ResponseWeakWrapperListener<T : JResponse>(listener: IResponseListener<T>) : IResponseListener<T> {
    private val ref = WeakReference(listener)
    override fun onResponse(response: T) {
        ref.get()?.onResponse(response)
    }
}

class JCallback<T : JResponse>(private val returnType: Type, private val listener: IResponseListener<T>) : Callback {

    constructor(call: JCall<T>, listener: IResponseListener<T>) : this(call.getReturnType(), listener)

    private fun createResponse(code: Int, http: Int, msg: String?): JResponse {
        return when (returnType) {
            JResponse::class.java -> JResponse(code, http, msg)
            JResponseRawString::class.java -> JResponseRawString(code, http, msg)
            else -> JResponseTyped<Any>(code, http, msg)
        }
    }


    override fun onFailure(call: okhttp3.Call, e: IOException) {
        callOnMain(
            when (e) {
                is InterruptedIOException -> createResponse(-1, RESPONSE_HTTP_OVERTIME, "连接超时")
                else -> createResponse(-1, RESPONSE_HTTP_EXCEPTION, e.javaClass.simpleName)
            }
        )
    }

    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
        try {
            if (response.isSuccessful) {
                val responseString = response.body()?.string()
                if (returnType == JResponseRawString::class.java) { //raw类型
                    callOnMain(JResponseRawString(RESPONSE_CODE_SUCCESS, response.code(), responseString))
                } else if (responseString != null) {
                    val json = JSONTokener(responseString).nextValue() as JSONObject
                    if (returnType == JResponse::class.java) {
                        callOnMain(
                            JResponse(
                                json.optInt(JpiHandler.jsonKeyCode, RESPONSE_CODE_UNKNOWN),
                                response.code(),
                                json.optString(JpiHandler.jsonKeyMessage, null)
                            )
                        )
                    } else {
                        val contentType = (returnType as ParameterizedType).actualTypeArguments[0]
                        val rp = JResponseTyped<Any>(
                            json.optInt(JpiHandler.jsonKeyCode, -1),
                            response.code(),
                            json.optString(JpiHandler.jsonKeyMessage, null)
                        )

                        if (rp.businessSuccess) {
                            val contentKey = JpiHandler.jsonKeyContent
                            android.util.Log.i("Jsonfit", "contentkey=$contentKey")
                            rp.content = when (contentType) {
                                Void::class.java -> null
                                String::class.java,
                                CharSequence::class.java -> json.optString(contentKey, null)
                                JSONObject::class.java -> json.optJSONObject(contentKey)
                                JSONArray::class.java -> json.optJSONArray(contentKey)
                                Int::class.java -> if (json.has(contentKey)) {
                                    json.getInt(contentKey)
                                } else {
                                    null
                                }
                                Double::class.java -> if (json.has(contentKey)) {
                                    json.getDouble(contentKey)
                                } else {
                                    null
                                }
                                Object::class.java,
                                Any::class.java,
                                Unit.javaClass -> Unit
                                else -> json.optString(contentKey)?.let {
                                    JpiHandler.jsonConverter?.fromJson(it, contentType)
                                }
                            }
                        }
                        callOnMain(rp)
                    }
                } else {
                    callOnMain(createResponse(-1, response.code(), "Response body is empty"))
                }
            } else {
                callOnMain(createResponse(-1, response.code(), "HTTP:${response.code()}"))

            }
        } catch (e: Exception) {
            android.util.Log.e("Jsonfit", "parse error", e)
            callOnMain(createResponse(-1, RESPONSE_HTTP_EXCEPTION, "Error occur:${e.message}"))
        }
    }

    private fun callOnMain(response: JResponse) {
        JpiHandler.mainExecutor?.execute {
            listener.onResponse(response as T)
            JpiHandler.jResponseInterceptor?.invoke(response)
        }

    }

}