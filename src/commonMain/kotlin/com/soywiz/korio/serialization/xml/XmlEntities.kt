package com.soywiz.korio.serialization.xml

import com.soywiz.kds.*
import com.soywiz.korio.lang.*
import com.soywiz.korio.util.*

object XmlEntities {
	// Predefined entities in XML 1.0
	private val charToEntity = lmapOf('"' to "&quot;", '\'' to "&apos;", '<' to "&lt;", '>' to "&gt;", '&' to "&amp;")
	private val entities = StrReader.Literals.fromList(charToEntity.values.toTypedArray())
	private val entityToChar = charToEntity.flip()

	fun encode(str: String): String = str.transform { charToEntity[it] ?: "$it" }
	fun decode(str: String): String {
		val r = StrReader(str)
		var out = ""

		while (!r.eof) {
			val c = r.readChar()
			when (c) {
				'&' -> {
					val value = r.readUntilIncluded(';') ?: ""
					val full = "&$value"
					out += if (value.startsWith('#')) {
						"${value.substring(1, value.length - 1).toInt().toChar()}"
					} else if (entityToChar.contains(full)) {
						"${entityToChar[full]}"
					} else {
						full
					}
				}
				else -> out += c
			}
		}

		return out
	}
}