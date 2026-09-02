package dev.tlong.biodex.data.db

import androidx.room.TypeConverter
import dev.tlong.biodex.domain.SpeciesSource
import dev.tlong.biodex.domain.TaxClass
import kotlinx.serialization.json.Json

/**
 * ARCHITECTURE.md 3.1: enums store as TEXT, `List<String>` as a JSON array, and
 * timestamps stay raw `Long` epoch millis — there is deliberately no Date converter
 * and therefore no time-zone ambiguity anywhere in the schema.
 *
 * Enums store their *wire* name (`other_invertebrate`), not the Kotlin constant name, so
 * a row read straight out of SQLite matches the catalogue asset's vocabulary.
 */
class Converters {

    @TypeConverter
    fun taxClassToString(value: TaxClass): String = value.wireName

    @TypeConverter
    fun stringToTaxClass(value: String): TaxClass = TaxClass.fromWireName(value)

    @TypeConverter
    fun sourceToString(value: SpeciesSource): String = value.wireName

    @TypeConverter
    fun stringToSource(value: String): SpeciesSource = SpeciesSource.fromWireName(value)

    @TypeConverter
    fun stringListToJson(value: List<String>): String = json.encodeToString(value)

    @TypeConverter
    fun jsonToStringList(value: String): List<String> =
        runCatching { json.decodeFromString<List<String>>(value) }.getOrDefault(emptyList())

    private companion object {
        val json = Json
    }
}
