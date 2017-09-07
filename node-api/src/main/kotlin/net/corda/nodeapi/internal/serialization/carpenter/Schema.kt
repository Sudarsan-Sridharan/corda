package net.corda.nodeapi.internal.serialization.carpenter

import kotlin.collections.LinkedHashMap

/**
 * A Schema represents a desired class.
 */
abstract class Schema(
        val name: String,
        var fields: Map<String, Field>,
        val superclass: Schema? = null,
        val interfaces: List<Class<*>> = emptyList(),
        updater : (String, Field) -> Unit)
{
    private fun Map<String, Field>.descriptors() =
            LinkedHashMap(this.mapValues { it.value.descriptor })

    init {
        println (fields)
        println (updater)
        fields.forEach { updater (it.key, it.value) }

        // Fix the order up front if the user didn't, inject the name into the field as it's
        // neater when iterating
        fields = LinkedHashMap(fields)
    }

    fun fieldsIncludingSuperclasses(): Map<String, Field> =
            (superclass?.fieldsIncludingSuperclasses() ?: emptyMap()) + LinkedHashMap(fields)

    fun descriptorsIncludingSuperclasses(): Map<String, String?> =
            (superclass?.descriptorsIncludingSuperclasses() ?: emptyMap()) + fields.descriptors()

    val jvmName: String
        get() = name.replace(".", "/")
}

class ClassSchema(
        name: String,
        fields: Map<String, Field>,
        superclass: Schema? = null,
        interfaces: List<Class<*>> = emptyList()
) : Schema(name, fields, superclass, interfaces, { name, field -> field.name = name })

class InterfaceSchema(
        name: String,
        fields: Map<String, Field>,
        superclass: Schema? = null,
        interfaces: List<Class<*>> = emptyList()
) : Schema(name, fields, superclass, interfaces, { name, field -> field.name = name })

class EnumSchema(
        name: String,
        fields: Map<String, Field>
) : Schema(name, fields, null, emptyList(), { fieldName, field ->
        (field as EnumField).name = fieldName
        field.descriptor = "L${name.replace(".", "/")};"
})

object CarpenterSchemaFactory {
    fun newInstance(
            name: String,
            fields: Map<String, Field>,
            superclass: Schema? = null,
            interfaces: List<Class<*>> = emptyList(),
            isInterface: Boolean = false
    ) : Schema =
            if (isInterface) InterfaceSchema (name, fields, superclass, interfaces)
            else ClassSchema (name, fields, superclass, interfaces)
}

