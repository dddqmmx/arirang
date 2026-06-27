package asia.nana7mi.arirang.hook.util

import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Field

internal fun findFieldInHierarchy(clazz: Class<*>, fieldName: String): Field? {
    var current: Class<*>? = clazz
    while (current != null && current != Any::class.java) {
        runCatching {
            return current.getDeclaredField(fieldName)
        }
        current = current.superclass
    }
    return null
}

internal fun getFieldValue(owner: Any?, fieldName: String): Any? {
    owner ?: return null
    return runCatching {
        val field = findFieldInHierarchy(owner.javaClass, fieldName) ?: return@runCatching null
        field.isAccessible = true
        field.get(owner)
    }.getOrNull()
}

internal fun setFieldValueIfExists(instance: Any, fieldName: String, value: Any?) {
    runCatching {
        val field = findFieldInHierarchy(instance.javaClass, fieldName) ?: return@runCatching
        val normalizedValue = field.type.coerceReflectiveValue(value)
        XposedHelpers.setObjectField(instance, fieldName, normalizedValue)
    }
}

internal fun callNoArg(owner: Any?, methodName: String): Any? {
    owner ?: return null
    var current: Class<*>? = owner.javaClass
    while (current != null && current != Any::class.java) {
        val method = current.declaredMethods.firstOrNull {
            it.name == methodName && it.parameterTypes.isEmpty()
        }
        if (method != null) {
            return runCatching {
                method.isAccessible = true
                method.invoke(owner)
            }.getOrNull()
        }
        current = current.superclass
    }
    return null
}

internal fun Any.callOneArgIfCompatible(methodName: String, value: Any?) {
    val method = javaClass.methods.firstOrNull { method ->
        method.name == methodName &&
            method.parameterTypes.size == 1 &&
            method.parameterTypes[0].isCompatibleWith(value)
    } ?: return
    runCatching { method.invoke(this, value) }
}

internal fun Class<*>.isCompatibleWith(value: Any?): Boolean {
    if (value == null) return !isPrimitive
    val boxedType = boxed()
    return boxedType.isAssignableFrom(value.javaClass) ||
        (boxedType == CharSequence::class.java && value is String)
}

internal fun Array<Any?>.firstIntOrNull(): Int? {
    return firstOrNull { it is Int } as? Int
}

internal fun Any?.asIntOrNull(): Int? {
    return when (this) {
        is Int -> this
        is Number -> toInt()
        is String -> toIntOrNull()
        else -> null
    }
}

private fun Class<*>.boxed(): Class<*> {
    return when (this) {
        Int::class.javaPrimitiveType -> Int::class.javaObjectType
        Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
        Long::class.javaPrimitiveType -> Long::class.javaObjectType
        Float::class.javaPrimitiveType -> Float::class.javaObjectType
        Double::class.javaPrimitiveType -> Double::class.javaObjectType
        else -> this
    }
}

private fun Class<*>.coerceReflectiveValue(value: Any?): Any? {
    if (value == null) return null
    return when (this) {
        Int::class.javaPrimitiveType, Int::class.javaObjectType ->
            value.toString().toIntOrNull() ?: 0
        Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType ->
            value.toString().toBooleanStrictOrNull() ?: false
        CharSequence::class.java, String::class.java -> value.toString()
        else -> value
    }
}
