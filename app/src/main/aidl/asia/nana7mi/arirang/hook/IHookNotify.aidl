package asia.nana7mi.arirang.hook;

interface IHookNotify {
    int requestClipboardRead(String pkgName, int uid, int userId, long timeoutMs);
    void onPermissionUsed(String pkgName, int uid, int userId, String opName);
    long readSimConfigVersion();
    String readSimConfigSnapshot();
    long readUniqueIdentifierConfigVersion();
    String readUniqueIdentifierConfigSnapshot();
    long readHookLogConfigVersion();
    String readHookLogConfigSnapshot();
    long readWifiConfigVersion();
    String readWifiConfigSnapshot();
    long readLocationConfigVersion();
    String readLocationConfigSnapshot();
}
