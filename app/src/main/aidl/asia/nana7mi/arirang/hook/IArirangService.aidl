package asia.nana7mi.arirang.hook;

import asia.nana7mi.arirang.hook.IClipboardDecisionCallback;
import asia.nana7mi.arirang.hook.IConfigSnapshotCallback;

interface IArirangService {
    /* clipboard */
    int requestClipboardRead(String pkgName, int uid, int userId);
    void onPermissionUsed(String pkgName, int uid, int userId, String opName);

    /* config */
    long readConfigVersion(String configName);
    String readConfigSnapshot(String configName);

    /* Appended async methods preserve transaction codes of the original Binder ABI. */
    oneway void requestClipboardReadAsync(
        String pkgName,
        int uid,
        int userId,
        IClipboardDecisionCallback callback
    );
    oneway void readConfigAsync(String configName, IConfigSnapshotCallback callback);
}
