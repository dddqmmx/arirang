package asia.nana7mi.arirang.hook;

interface IArirangService {
    /* clipboard */
    int requestClipboardRead(String pkgName, int uid, int userId, long timeoutMs);
    void onPermissionUsed(String pkgName, int uid, int userId, String opName);

    /* config */
    long readConfigVersion(String configName);
    String readConfigSnapshot(String configName);
}
