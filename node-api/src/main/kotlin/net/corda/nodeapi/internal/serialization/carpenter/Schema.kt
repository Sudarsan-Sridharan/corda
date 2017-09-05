package net.corda.nodeapi.internal.serialization.carpenter

import jdk.internal.org.objectweb.asm.Opcodes.*
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Type
import java.util.*

/**
 * A Schema represents a desired class.
 */
abstract class Schema(
        val name: String,
        fields: Map<String, Field>,
        val superclass: Schema? = null,
        val interfaces: List<Class<*>> = emptyList())
{
    private fun Map<String, Field>.descriptors() =
            LinkedHashMap(this.mapValues { it.value.descriptor })

    /* Fix the order up front if the user didn't, inject the name into the field as it's
       neater when iterating */
    val fields = LinkedHashMap(fields.mapValues { it.value.copy(it.key, it.value.field) })

    fun fieldsIncludingSuperclasses(): Map<String, Field> =
            (superclass?.fieldsIncludingSuperclasses() ?: emptyMap()) + LinkedHashMap(fields)

    fun descriptorsIncludingSuperclasses(): Map<String, String> =
            (superclass?.descriptorsIncludingSuperclasses() ?: emptyMap()) + fields.descriptors()

    val jvmName: String
        get() = name.replace(".", "/")
}

class ClassSchema(
        name: String,
        fields: Map<String, Field>,
        superclass: Schema? = null,
        interfaces: List<Class<*>> = emptyList()
) : Schema(name, fields, superclass, interfaces)

class InterfaceSchema(
        name: String,
        fields: Map<String, Field>,
        superclass: Schema? = null,
        interfaces: List<Class<*>> = emptyList()
) : Schema(name, fields, superclass, interfaces)

class EnumSchema(
        name: String,
        fields: Map<String, Field>
) : Schema(name, fields, null, emptyList())

object CarpenterSchemaFactory {
    fun newInstance (
            name: String,
            fields: Map<String, Field>,
            superclass: Schema? = null,
            interfaces: List<Class<*>> = emptyList(),
            isInterface: Boolean = false
    ) : Schema =
            if (isInterface) InterfaceSchema (name, fields, superclass, interfaces)
            else ClassSchema (name, fields, superclass, interfaces)
}

