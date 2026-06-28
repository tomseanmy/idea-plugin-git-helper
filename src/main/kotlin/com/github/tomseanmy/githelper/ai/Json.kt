package com.github.tomseanmy.githelper.ai

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Shared Gson instance. Pretty-printing is off to keep request bodies compact.
 */
val GSON: Gson = GsonBuilder().disableHtmlEscaping().create()

/** Safe string access helper for navigating response JSON. */
fun JsonObject?.str(member: String): String? =
    this?.takeIf { it.has(member) && !it.get(member).isJsonNull }
        ?.get(member)?.asString

fun JsonObject?.obj(member: String): JsonObject? =
    this?.takeIf { it.has(member) && !it.get(member).isJsonNull }
        ?.get(member)?.asJsonObject

/** Parse a JSON string defensively, returning null on failure. */
fun parseJson(raw: String): JsonObject? = runCatching {
    JsonParser.parseString(raw).asJsonObject
}.getOrNull()
