package asia.nana7mi.arirang.hook;

import android.telephony.SubscriptionInfo;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import asia.nana7mi.arirang.BuildConfig;
import asia.nana7mi.arirang.model.SimInfo;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class FuckSIM implements IXposedHookLoadPackage {

    private static final String PREFS_NAME = "sim_config_prefs";
    private static final String ENABLED_KEY = "enabled";
    private static final String MODE_KEY = "mode";
    private static final String WHITELIST_KEY = "whitelist";
    private static final String BLACKLIST_KEY = "blacklist";
    private static final String SIM_INFO_LIST_KEY = "sim_info_list";
    private static final String LAST_MODIFIED_KEY = "last_modified";

    public enum Mode { WHITELIST, BLACKLIST }

    private static Set<String> PACKAGE_SET = Collections.emptySet();
    private static boolean ENABLED = false;
    private static Mode MODE = Mode.WHITELIST;
    private static List<SimInfo> SIM_INFO_SET = Collections.emptyList();
    private static long lastLoadedTimestamp = -1;

    private static final Gson gson = new Gson();

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"com.android.phone".equals(lpparam.packageName)) return;
        XposedBridge.log("SIM hook loading for package: " + lpparam.packageName + ", process: " + lpparam.processName);

        try {
            Class<?> subscriptionManagerService = XposedHelpers.findClassIfExists("com.android.internal.telephony.subscription.SubscriptionManagerService", lpparam.classLoader);
            if (subscriptionManagerService == null) {
                XposedBridge.log("Class not found subscriptionManagerService");
                return;
            }
            XposedBridge.log("Class found");
            hookMethods(subscriptionManagerService);
        } catch (Throwable t) {
            XposedBridge.log("Hook failed: " + t.getMessage() + "\n" + Log.getStackTraceString(t));
        }
    }

    private void hookMethods(Class<?> computerEngine) {
        try {
            XposedHelpers.findAndHookMethod(computerEngine,"getActiveSubscriptionInfoList", String.class, String.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            loadConfigIfUpdated();

                            if (!ENABLED) return;

                            String callingPackage = (String) param.args[0];

                            if (shouldFilter(callingPackage)) {
                                param.setResult(Collections.emptyList());
                                return;
                            }

//                            Object subscriptionInfoList = param.getResult();
//                            List<?> list = (List<?>) subscriptionInfoList;
//                            List<SubscriptionInfo> newSubscriptionInfoList = Collections.emptyList();
//                            for (int i = 0; i < list.size(); i++) {
//                                Object item = list.get(i);
//                                SimInfo simInfo = SIM_INFO_SET.get(i);
//                                SubscriptionInfo subInfo = (SubscriptionInfo) item;
//                                newSubscriptionInfoList.add(subInfo);
//                            }
//                            param.setResult(Collections.emptyList());
                        }
                    });
        }catch (Exception e) {
            XposedBridge.log("Error hooking methods: " + e.getMessage() + "\n" + Log.getStackTraceString(e));
        }
    }

    private boolean shouldFilter(String callingPackage) {
        if (callingPackage == null) return true;

        switch (MODE) {
            case WHITELIST:
                return !PACKAGE_SET.contains(callingPackage);
            case BLACKLIST:
                return PACKAGE_SET.contains(callingPackage);
            default:
                return true;
        }
    }

    public static List<SimInfo> parseSimInfo(String json) {
        if (json != null) {
            Type listType = new TypeToken<List<SimInfo>>() {}.getType();
            return gson.fromJson(json, listType); // 转成 Set
        } else {
            return Collections.emptyList();
        }
    }


    private static XSharedPreferences getPref() {
        XSharedPreferences pref = new XSharedPreferences(BuildConfig.APPLICATION_ID, PREFS_NAME);
        return pref.getFile().canRead() ? pref : null;
    }

    private static void loadConfigIfUpdated() {
        XSharedPreferences pref = getPref();
        if (pref == null) return;

        pref.reload();
        long newTimestamp = pref.getLong(LAST_MODIFIED_KEY, -1);
        if (newTimestamp == lastLoadedTimestamp) return;

        lastLoadedTimestamp = newTimestamp;

        ENABLED = pref.getBoolean(ENABLED_KEY, false);
        int modeInt = pref.getInt(MODE_KEY, 0);  // 0 = WHITELIST, 1 = BLACKLIST
        MODE = (modeInt == 1) ? Mode.BLACKLIST : Mode.WHITELIST;

        Set<String> rawSet = pref.getStringSet(MODE == Mode.WHITELIST ? WHITELIST_KEY : BLACKLIST_KEY, null);
        PACKAGE_SET = rawSet != null ? new HashSet<>(rawSet) : Collections.emptySet();

        String simInfoListJson = pref.getString(SIM_INFO_LIST_KEY, null);
        SIM_INFO_SET = parseSimInfo(simInfoListJson);
    }

}