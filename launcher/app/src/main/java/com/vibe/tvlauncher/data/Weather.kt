package com.vibe.tvlauncher.data

import org.json.JSONObject

data class Weather(val temp: Int, val unit: String, val code: Int, val isDay: Boolean, val place: String?)

/** WMO weather code buckets, used to pick a glyph. */
enum class Sky { Clear, PartlyCloudy, Cloudy, Fog, Rain, Snow, Storm }

fun Weather.sky(): Sky = when (code) {
    0 -> Sky.Clear
    1, 2 -> Sky.PartlyCloudy
    3 -> Sky.Cloudy
    45, 48 -> Sky.Fog
    in 51..67, in 80..82 -> Sky.Rain
    in 71..77, 85, 86 -> Sky.Snow
    in 95..99 -> Sky.Storm
    else -> Sky.Cloudy
}

object WeatherApi {
    data class Place(val lat: Double, val lon: Double, val name: String?, val fahrenheit: Boolean)

    /** Rough location from the public IP (city level). */
    fun locate(): Place? = runCatching {
        val o = JSONObject(Http.get("https://ipwho.is/?fields=success,latitude,longitude,city,country_code"))
        if (!o.optBoolean("success", true)) return null
        Place(
            o.getDouble("latitude"),
            o.getDouble("longitude"),
            o.optString("city").ifEmpty { null },
            o.optString("country_code") in setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW"),
        )
    }.getOrNull()

    /** A place typed in settings: "lat,lon" or a city name (Open-Meteo geocoding). */
    fun place(query: String, fahrenheit: Boolean): Place? {
        val nums = query.split(",").mapNotNull { it.trim().toDoubleOrNull() }
        if (nums.size == 2) return Place(nums[0], nums[1], null, fahrenheit)
        return runCatching {
            val r = JSONObject(
                Http.get("https://geocoding-api.open-meteo.com/v1/search?count=1&name=" + java.net.URLEncoder.encode(query.trim(), "UTF-8")),
            ).optJSONArray("results")?.optJSONObject(0) ?: return null
            Place(r.getDouble("latitude"), r.getDouble("longitude"), r.optString("name").ifEmpty { null }, fahrenheit)
        }.getOrNull()
    }

    fun current(p: Place): Weather {
        val unit = if (p.fahrenheit) "fahrenheit" else "celsius"
        val o = JSONObject(
            Http.get(
                "https://api.open-meteo.com/v1/forecast?latitude=${p.lat}&longitude=${p.lon}" +
                    "&current=temperature_2m,weather_code,is_day&temperature_unit=$unit",
            ),
        ).getJSONObject("current")
        return Weather(
            temp = Math.round(o.getDouble("temperature_2m")).toInt(),
            unit = if (p.fahrenheit) "F" else "C",
            code = o.optInt("weather_code"),
            isDay = o.optInt("is_day", 1) == 1,
            place = p.name,
        )
    }
}
