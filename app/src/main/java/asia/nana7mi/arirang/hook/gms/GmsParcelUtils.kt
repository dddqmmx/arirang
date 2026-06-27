package asia.nana7mi.arirang.hook.gms

import android.os.Parcel
import android.os.Parcelable

internal fun Parcel.interfaceTokenOrNull(): String? {
    val oldPosition = dataPosition()
    return runCatching {
        setDataPosition(0)
        readString()
    }.also {
        setDataPosition(oldPosition)
    }.getOrNull()
}

internal fun Parcel.replaceWithStringResult(value: String) {
    setDataSize(0)
    setDataPosition(0)
    writeNoException()
    writeString(value)
    setDataPosition(0)
}

internal fun <T> Parcel.readParcelableAfterInterfaceToken(creator: Parcelable.Creator<T>): T? {
    setDataPosition(0)
    readString()
    val present = readInt()
    return if (present != 0) creator.createFromParcel(this) else null
}

internal fun Parcel.writeParcelableCompat(value: Parcelable?, flags: Int) {
    if (value == null) {
        writeInt(0)
    } else {
        writeInt(1)
        value.writeToParcel(this, flags)
    }
}
