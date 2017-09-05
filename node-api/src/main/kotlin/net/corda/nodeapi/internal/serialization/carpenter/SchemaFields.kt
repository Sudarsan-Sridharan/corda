package net.corda.nodeapi.internal.serialization.carpenter

import jdk.internal.org.objectweb.asm.Opcodes.*
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Type
import java.util.*

abstract class Field(val field: Class<out Any?>) {
    abstract val descriptor: String

    companion object {
        const val unsetName = "Unset"
    }

    var name: String = unsetName

    abstract fun copy(name: String, field: Class<out Any?>): Field
}

abstract class ClassField(field: Class<out Any?>) : Field(field) {
    abstract val nullabilityAnnotation: String

    override val descriptor: String get() = Type.getDescriptor(this.field)

    open val type: String get() = if (this.field.isPrimitive) this.descriptor else "Ljava/lang/Object;"

    open fun generateField(cw: ClassWriter) {
        cw.visitField(ACC_PROTECTED + ACC_FINAL, name, descriptor, null, null).visitAnnotation(
                nullabilityAnnotation, true).visitEnd()
    }

    fun addNullabilityAnnotation(mv: MethodVisitor) {
        mv.visitAnnotation(nullabilityAnnotation, true).visitEnd()
    }

    fun visitParameter(mv: MethodVisitor, idx: Int) {
        with(mv) {
            visitParameter(name, 0)
            if (!field.isPrimitive) {
                visitParameterAnnotation(idx, nullabilityAnnotation, true).visitEnd()
            }
        }
    }

    abstract fun nullTest(mv: MethodVisitor, slot: Int)
}

/**
 *
 */
open class NonNullableField(field: Class<out Any?>) : ClassField(field) {
    override val nullabilityAnnotation = "Ljavax/annotation/Nonnull;"

    constructor(name: String, field: Class<out Any?>) : this(field) {
        this.name = name
    }

    override fun copy(name: String, field: Class<out Any?>) = NonNullableField(name, field)

    override fun nullTest(mv: MethodVisitor, slot: Int) {
        assert(name != unsetName)

        if (!field.isPrimitive) {
            with(mv) {
                visitVarInsn(ALOAD, 0) // load this
                visitVarInsn(ALOAD, slot) // load parameter
                visitLdcInsn("param \"$name\" cannot be null")
                visitMethodInsn(INVOKESTATIC,
                        "java/util/Objects",
                        "requireNonNull",
                        "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false)
                visitInsn(POP)
            }
        }
    }
}

class NullableField(field: Class<out Any?>) : ClassField(field) {
    override val nullabilityAnnotation = "Ljavax/annotation/Nullable;"

    constructor(name: String, field: Class<out Any?>) : this(field) {
        if (field.isPrimitive) {
            throw NullablePrimitiveException (
                    "Field $name is primitive type ${Type.getDescriptor(field)} and thus cannot be nullable")
        }

        this.name = name
    }

    override fun copy(name: String, field: Class<out Any?>) = NullableField(name, field)

    override fun nullTest(mv: MethodVisitor, slot: Int) {
        assert(name != unsetName)
    }
}

/**
 *
 */
class EnumField() : Field(Enum::class.java) {
    override val descriptor: String get() = Type.getDescriptor(this.field)

    override val nullabilityAnnotation = "L/ERROR/SHOULD/NOT/BE/SET"

    override val type: String
        get() = if (this.field.isPrimitive) this.descriptor else "Ljava/lang/Object;"

    constructor(name: String) : this() {
        this.name = name
    }

    override fun copy(name: String, field: Class<out Any?>) = EnumField(name)

    override fun nullTest(mv: MethodVisitor, slot: Int) {
        assert(name != unsetName)
    }

    override fun generateField(cw: ClassWriter) {
        println(descriptor)
        println(type)
        cw.visitField(ACC_PROTECTED + ACC_FINAL + ACC_STATIC + ACC_ENUM, name,
                descriptor, null, null).visitEnd()
    }
}

/**
 * Constructs a Field Schema object of the correct type depending weather
 * the AMQP schema indicates it's mandatory (non nullable) or not (nullable)
 */
object FieldFactory {
    fun newInstance (mandatory: Boolean, name: String, field: Class<out Any?>) =
            if (mandatory) NonNullableField (name, field) else NullableField (name, field)

}
