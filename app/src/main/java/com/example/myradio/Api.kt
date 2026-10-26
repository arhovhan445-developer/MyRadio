package com.example.myradio

import android.util.Xml
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.URLEncoder

object Http {
    private val client = OkHttpClient()
    fun get(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", "MyRadioTest/1.0 (Android)").build()
        client.newCall(req).execute().use { r ->
            check(r.isSuccessful) { "HTTP ${r.code}" }
            return r.body!!.string()
        }
    }
}

data class Station(val name: String, val url: String)

/** Бесплатные интернет-радиостанции, база radio-browser.info (без ключа). */
object Radio {
    fun load(): List<Station> {
        for (host in listOf("de1", "at1", "nl1")) {
            try {
                val arr = JSONArray(Http.get(
                    "https://$host.api.radio-browser.info/json/stations/search" +
                    "?countrycode=AM&hidebroken=true&order=clickcount&reverse=true&limit=40"))
                val list = (0 until arr.length()).map { arr.getJSONObject(it) }
                    .filter { it.optString("url_resolved").isNotBlank() }
                    .map { Station(it.optString("name").trim(), it.getString("url_resolved")) }
                if (list.isNotEmpty()) return list
            } catch (e: Exception) { /* пробуем следующий сервер */ }
        }
        return emptyList()
    }
}

/** Новости: Google News RSS для Армении. Измените темы под себя. */
object News {
    val topics = listOf("Армения", "технологии", "футбол", "экономика Армении")

    fun headlines(): List<String> {
        val out = mutableListOf<String>()
        out += fetch("https://news.google.com/rss?hl=ru&gl=AM&ceid=AM:ru", 3)
        for (t in topics) {
            val q = URLEncoder.encode("$t when:1d", "UTF-8")
            out += fetch("https://news.google.com/rss/search?q=$q&hl=ru&gl=AM&ceid=AM:ru", 2)
        }
        return out.distinct()
    }

    private fun fetch(url: String, limit: Int): List<String> = try {
        val p = Xml.newPullParser()
        p.setInput(Http.get(url).reader())
        val res = mutableListOf<String>()
        var inItem = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT && res.size < limit) {
            if (ev == XmlPullParser.START_TAG) {
                if (p.name == "item") inItem = true
                else if (inItem && p.name == "title") res += p.nextText().substringBeforeLast(" - ")
            } else if (ev == XmlPullParser.END_TAG && p.name == "item") inItem = false
            ev = p.next()
        }
        res
    } catch (e: Exception) { emptyList() }
}

/** Камеры контроля скорости из OpenStreetMap через Overpass (без ключа). */
object Cameras {
    data class Cam(val id: Long, val lat: Double, val lon: Double, val maxspeed: String?)

    fun near(lat: Double, lon: Double, radius: Int): List<Cam> = try {
        val q = "[out:json][timeout:10];node(around:$radius,$lat,$lon)[highway=speed_camera];out;"
        val j = JSONObject(Http.get("https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(q, "UTF-8")))
        val els = j.getJSONArray("elements")
        (0 until els.length()).map { els.getJSONObject(it) }.map {
            Cam(it.getLong("id"), it.getDouble("lat"), it.getDouble("lon"),
                it.optJSONObject("tags")?.optString("maxspeed")?.ifBlank { null })
        }
    } catch (e: Exception) { emptyList() }
}
