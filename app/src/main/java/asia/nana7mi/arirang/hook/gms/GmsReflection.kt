package asia.nana7mi.arirang.hook.gms

import android.os.Binder
import asia.nana7mi.arirang.hook.core.HookLog
import java.lang.reflect.Method

internal fun Class<*>.findOnTransactClass(): Class<*>? {
    var current: Class<*>? = this
    while (current != null && current != Binder::class.java) {
        if (current.declaredMethods.any { it.name == "onTransact" }) {
            return current
        }
        current = current.superclass
    }
    return null
}

internal fun Class<*>.findDeclaredMethodInHierarchy(
    predicate: (Method) -> Boolean
): Method? {
    var current: Class<*>? = this
    while (current != null && current != Any::class.java) {
        current.declaredMethods.firstOrNull(predicate)?.let { return it }
        current = current.superclass
    }
    return null
}

internal fun Method.isAppSetRequestMethod(): Boolean {
    val params = parameterTypes
    return returnType == Void.TYPE &&
        params.size == 2 &&
        params[0].name == "com.google.android.gms.appset.AppSetIdRequestParams" &&
        params[1].declaredMethods.any { method ->
            method.returnType == Void.TYPE &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes[0].name == "com.google.android.gms.common.api.Status" &&
                method.parameterTypes[1].name == "com.google.android.gms.appset.AppSetInfoParcel"
        }
}

internal fun Class<*>.newAppSetInfoParcel(appSetId: String): Any {
    val constructor = declaredConstructors.firstOrNull { constructor ->
        val params = constructor.parameterTypes
        params.size == 2 && params[0] == String::class.java && params[1] == Int::class.javaPrimitiveType
    } ?: run {
        logConstructors("App Set info parcel", this)
        error("AppSetInfoParcel(String,int) not found on $name")
    }
    constructor.isAccessible = true
    return constructor.newInstance(appSetId, 1)
}

internal fun Class<*>.successStatus(): Any {
    runCatching {
        val field = getField("RESULT_SUCCESS")
        return field.get(null) ?: error("Status.RESULT_SUCCESS is null on $name")
    }
    declaredConstructors.firstOrNull { constructor ->
        val params = constructor.parameterTypes
        params.size == 1 && params[0] == Int::class.javaPrimitiveType
    }?.let { constructor ->
        constructor.isAccessible = true
        return constructor.newInstance(0)
    }
    error("Status.RESULT_SUCCESS or Status(int) not found on $name")
}

internal fun logClassHierarchyMethods(label: String, ownerClass: Class<*>) {
    var current: Class<*>? = ownerClass
    while (current != null && current != Any::class.java) {
        val signatures = current.declaredMethods
            .joinToString("; ") { method ->
                val params = method.parameterTypes.joinToString(",") { it.name }
                "${method.returnType.name} ${method.name}($params)"
            }
        HookLog.i(HookLog.Module.GMS, "$label class ${current.name} methods=[$signatures]")
        current = current.superclass
    }
}

internal fun logConstructors(label: String, ownerClass: Class<*>) {
    val signatures = ownerClass.declaredConstructors.joinToString("; ") { constructor ->
        val params = constructor.parameterTypes.joinToString(",") { it.name }
        "${ownerClass.name}($params)"
    }
    HookLog.i(HookLog.Module.GMS, "$label class ${ownerClass.name} constructors=[$signatures]")
}
