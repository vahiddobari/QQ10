package com.hoosha.qx10pro

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

data class ExposureCompensationInfo(
    val current: Int,
    val max: Int,
    val min: Int,
    val stepIndex: Int
) {
    fun toEvString(): String {
        val step = if (stepIndex == 2) 2.0 else 3.0 // 1 = 1/3 EV, 2 = 1/2 EV
        val ev = current / step
        return if (ev > 0) String.format("+%.1f EV", ev) else if (ev < 0) String.format("%.1f EV", ev) else "±0.0 EV"
    }
}

object SonyCamera {
    var endpoint: String = ""

    fun discover(onResult: (String?) -> Unit) {
        Thread {
            var found: String? = null
            try {
                val socket = DatagramSocket()
                socket.soTimeout = 4000
                val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 3\r\nST: urn:schemas-sony-com:service:ScalarWebAPI:1\r\n\r\n").toByteArray()
                socket.send(DatagramPacket(msg, msg.size, InetAddress.getByName("239.255.255.250"), 1900))
                val buf = ByteArray(2048)
                val pkt = DatagramPacket(buf, buf.size)
                socket.receive(pkt)
                val text = String(buf, 0, pkt.length)
                val loc = Regex("LOCATION:\\s*(\\S+)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
                if (loc != null) found = resolveEndpoint(loc)
                socket.close()
            } catch (_: Exception) { }
            onResult(found ?: "http://10.0.0.1:10000/sony/camera")
        }.start()
    }

    private fun resolveEndpoint(locationUrl: String): String? {
        return try {
            val c = URL(locationUrl).openConnection() as HttpURLConnection
            c.connectTimeout = 4000; c.readTimeout = 4000
            val xml = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()
            val base = Regex("<URLBase>(.*?)</URLBase>", RegexOption.IGNORE_CASE).find(xml)?.groupValues?.get(1)?.trim()
            val url = if (!base.isNullOrBlank()) base else "http://" + URL(locationUrl).host
            if (url.endsWith("/sony/camera")) url else "$url/sony/camera"
        } catch (_: Exception) { null }
    }

    fun rpc(method: String, params: JSONArray = JSONArray()): JSONObject? {
        if (endpoint.isBlank()) return null
        return try {
            val body = JSONObject().put("method", method).put("params", params).put("id", 1).put("version", "1.0")
            val c = URL(endpoint).openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true
            c.connectTimeout = 6000; c.readTimeout = 15000
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val resp = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()
            JSONObject(resp)
        } catch (_: Exception) { null }
    }

    fun startLiveview(): String? = rpc("startLiveview")?.optJSONArray("result")?.optString(0)
    fun stopLiveview() { rpc("stopLiveview") }
    fun actTakePicture(): JSONArray? = rpc("actTakePicture")?.optJSONArray("result")?.optJSONArray(0)
    fun setZoom(direction: String) { rpc("actZoom", JSONArray().put(direction).put("1shot")) }

    /**
     * Touch AF: Sends coordinates as percentage (0.0 to 100.0) relative to the liveview frame.
     */
    fun setTouchAFPosition(xPercent: Double, yPercent: Double): Boolean {
        val clampedX = xPercent.coerceIn(0.0, 100.0)
        val clampedY = yPercent.coerceIn(0.0, 100.0)
        val params = JSONArray().put(clampedX).put(clampedY)
        val res = rpc("setTouchAFPosition", params)
        if (res != null && !res.has("error")) {
            val inner = res.optJSONArray("result")?.optJSONObject(0)
            return inner?.optBoolean("AFResult", true) ?: true
        }
        return false
    }

    fun cancelTouchAFPosition(): Boolean {
        val res = rpc("cancelTouchAFPosition")
        return res != null && !res.has("error")
    }

    /**
     * Exposure Compensation (EV)
     */
    fun getAvailableExposureCompensation(): ExposureCompensationInfo? {
        val res = rpc("getAvailableExposureCompensation")
        val result = res?.optJSONArray("result") ?: return null
        val current = result.optInt(0, 0)
        val max = result.optInt(1, 6)
        val min = result.optInt(2, -6)
        val step = result.optInt(3, 1)
        return ExposureCompensationInfo(current, max, min, step)
    }

    fun setExposureCompensation(valueIndex: Int): Boolean {
        val res = rpc("setExposureCompensation", JSONArray().put(valueIndex))
        return res != null && !res.has("error")
    }

    /**
     * ISO & Shutter Speed & Aperture
     */
    fun setFNumber(f: String): Boolean {
        rpc("setExposureMode", JSONArray().put("Aperture"))
        val r = rpc("setFNumber", JSONArray().put(f))
        return r != null && !r.has("error")
    }

    fun setIso(iso: String): Boolean {
        rpc("setExposureMode", JSONArray().put("Program Auto"))
        val r = rpc("setIsoSpeedRate", JSONArray().put(iso))
        return r != null && !r.has("error")
    }

    fun setShutterSpeed(v: String): Boolean {
        rpc("setExposureMode", JSONArray().put("Shutter"))
        val r = rpc("setShutterSpeed", JSONArray().put(v))
        return r != null && !r.has("error")
    }

    fun setExposureMode(mode: String): Boolean {
        val r = rpc("setExposureMode", JSONArray().put(mode))
        return r != null && !r.has("error")
    }

    fun getAvailableApiList(): List<String> {
        val res = rpc("getAvailableApiList")
        val list = mutableListOf<String>()
        val arr = res?.optJSONArray("result")?.optJSONArray(0) ?: return list
        for (i in 0 until arr.length()) {
            list.add(arr.optString(i))
        }
        return list
    }
}

class LiveviewStream(private val url: String, private val onFrame: (ByteArray) -> Unit) {
    @Volatile var running = false

    fun start() {
        running = true
        Thread {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 5000
                val input = c.inputStream
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(4096)
                var inJpeg = false
                while (running) {
                    val n = input.read(chunk)
                    if (n <= 0) break
                    for (i in 0 until n) {
                        buffer.write(chunk[i].toInt())
                        val arr = buffer.toByteArray()
                        if (!inJpeg && arr.size >= 2 && arr[arr.size - 2] == 0xFF.toByte() && arr[arr.size - 1] == 0xD8.toByte()) {
                            inJpeg = true
                        } else if (inJpeg && arr.size >= 2 && arr[arr.size - 2] == 0xFF.toByte() && arr[arr.size - 1] == 0xD9.toByte()) {
                            onFrame(arr.clone())
                            buffer.reset()
                            inJpeg = false
                        }
                    }
                }
                c.disconnect()
            } catch (_: Exception) { }
        }.start()
    }

    fun stop() { running = false }
}
