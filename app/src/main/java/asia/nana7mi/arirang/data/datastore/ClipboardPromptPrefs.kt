package asia.nana7mi.arirang.data.datastore

import android.content.Context
import android.os.Process
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private const val PREFS_NAME = "clipboard_prompt_policy_prefs"
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = PREFS_NAME)

object ClipboardPromptPrefs {
    private const val PER_USER_RANGE = 100_000
    private const val APP_POLICY_PREFIX = "app_policy_"
    private const val USER_SCOPED_POLICY_PREFIX = "${APP_POLICY_PREFIX}u"

    enum class Policy {
        ALLOW,
        DENY,
        ASK
    }

    enum class AppFilter{
        ALL,
        USER,
        SYSTEM
    }

    fun scopedPolicyId(userId: Int, pkg: String): String = "u$userId:$pkg"

    private fun currentUserId(): Int = Process.myUid() / PER_USER_RANGE

    private fun appPolicyKey(pkg: String) = stringPreferencesKey("${APP_POLICY_PREFIX}$pkg")

    private fun appPolicyKeyForUser(userId: Int, pkg: String) =
        stringPreferencesKey("${USER_SCOPED_POLICY_PREFIX}${userId}_$pkg")

    private fun parsePolicy(value: Any?): Policy? {
        val raw = value as? String ?: return null
        return runCatching { Policy.valueOf(raw) }.getOrNull()
    }

    private fun isUserScopedPolicyName(name: String): Boolean = name.startsWith(USER_SCOPED_POLICY_PREFIX)

    private fun extractPolicyIdentity(name: String, legacyUserId: Int): Pair<Int, String>? {
        if (!name.startsWith(APP_POLICY_PREFIX)) {
            return null
        }

        val suffix = name.removePrefix(APP_POLICY_PREFIX)
        if (suffix.isBlank()) {
            return null
        }

        if (!suffix.startsWith("u")) {
            return legacyUserId to suffix
        }

        val separatorIndex = suffix.indexOf('_')
        if (separatorIndex <= 1 || separatorIndex >= suffix.lastIndex) {
            return null
        }

        val userId = suffix.substring(1, separatorIndex).toIntOrNull() ?: return null
        val pkg = suffix.substring(separatorIndex + 1)
        if (pkg.isBlank()) {
            return null
        }

        return userId to pkg
    }

    private fun extractScopedPolicies(
        prefs: Preferences,
        legacyUserId: Int
    ): Map<String, Policy> {
        val legacyPolicies = LinkedHashMap<String, Policy>()
        val userScopedPolicies = LinkedHashMap<String, Policy>()

        for ((key, value) in prefs.asMap()) {
            val policy = parsePolicy(value) ?: continue
            val identity = extractPolicyIdentity(key.name, legacyUserId) ?: continue
            val scopedKey = scopedPolicyId(identity.first, identity.second)
            if (isUserScopedPolicyName(key.name)) {
                userScopedPolicies[scopedKey] = policy
            } else {
                legacyPolicies[scopedKey] = policy
            }
        }

        legacyPolicies.putAll(userScopedPolicies)
        return legacyPolicies
    }

    suspend fun getAppPolicy(context: Context, pkg: String): Policy {
        val currentUserId = currentUserId()
        val value = context.dataStore.data.map { preferences ->
            preferences[appPolicyKeyForUser(currentUserId, pkg)] ?: preferences[appPolicyKey(pkg)]
        }.first()
        return value?.let { Policy.valueOf(it) } ?: Policy.ASK
    }

    suspend fun setAppPolicy(context: Context, pkg: String, policy: Policy){
        setAppPolicyForUser(context, currentUserId(), pkg, policy)
    }

    suspend fun setAppPolicyForUser(context: Context, userId: Int, pkg: String, policy: Policy) {
        context.dataStore.edit { preferences ->
            preferences[appPolicyKeyForUser(userId, pkg)] = policy.name
            if (userId == currentUserId()) {
                preferences.remove(appPolicyKey(pkg))
            }
        }
    }

    private val APP_FILTER = stringPreferencesKey("app_app_filter")

    suspend fun getAppFilter(context: Context): AppFilter {
        val value = context.dataStore.data.map { preferences -> preferences[APP_FILTER] }.first()
        return value?.let { AppFilter.valueOf(it) } ?: AppFilter.ALL
    }

    suspend fun setAppFilter(context: Context, appFilter: AppFilter){
        context.dataStore.edit { preferences -> preferences[APP_FILTER] = appFilter.name }
    }

    suspend fun getAppPolicies(context: Context): Map<String, Policy> {
        val currentUserId = currentUserId()
        val scopedPolicies = extractScopedPolicies(context.dataStore.data.first(), currentUserId)
        val currentUserPrefix = "u$currentUserId:"
        return scopedPolicies.mapNotNull { (scopedKey, policy) ->
            if (scopedKey.startsWith(currentUserPrefix)) {
                scopedKey.removePrefix(currentUserPrefix) to policy
            } else {
                null
            }
        }.toMap()
    }

    fun getAppPoliciesFlow(context: Context): kotlinx.coroutines.flow.Flow<Map<String, Policy>> {
        val currentUserId = currentUserId()
        val currentUserPrefix = "u$currentUserId:"
        return context.dataStore.data.map { prefs ->
            extractScopedPolicies(prefs, currentUserId).mapNotNull { (scopedKey, policy) ->
                if (scopedKey.startsWith(currentUserPrefix)) {
                    scopedKey.removePrefix(currentUserPrefix) to policy
                } else {
                    null
                }
            }.toMap()
        }
    }

    fun getAllAppPoliciesFlow(context: Context): kotlinx.coroutines.flow.Flow<Map<String, Policy>> {
        val currentUserId = currentUserId()
        return context.dataStore.data.map { prefs ->
            extractScopedPolicies(prefs, currentUserId)
        }
    }

    private val DEFAULT_POLICY = stringPreferencesKey("default_policy")

    suspend fun getDefaultPolicy(context: Context): Policy {
        val value = context.dataStore.data.map { preferences -> preferences[DEFAULT_POLICY] }.first()
        return value?.let { Policy.valueOf(it) } ?: Policy.ASK
    }

    fun getDefaultPolicyFlow(context: Context): kotlinx.coroutines.flow.Flow<Policy> {
        return context.dataStore.data.map { preferences ->
            preferences[DEFAULT_POLICY]?.let { Policy.valueOf(it) } ?: Policy.ASK
        }
    }

    suspend fun setDefaultPolicy(context: Context, policy: Policy){
        context.dataStore.edit { preferences -> preferences[DEFAULT_POLICY] = policy.name }
    }

    private val IS_FEATURE_ENABLED = androidx.datastore.preferences.core.booleanPreferencesKey("is_feature_enabled")

    suspend fun isFeatureEnabled(context: Context): Boolean {
        return context.dataStore.data.map { preferences -> preferences[IS_FEATURE_ENABLED] ?: true }.first()
    }

    fun isFeatureEnabledFlow(context: Context): kotlinx.coroutines.flow.Flow<Boolean> {
        return context.dataStore.data.map { preferences -> preferences[IS_FEATURE_ENABLED] ?: true }
    }

    suspend fun setFeatureEnabled(context: Context, enabled: Boolean) {
        context.dataStore.edit { preferences -> preferences[IS_FEATURE_ENABLED] = enabled }
    }

    suspend fun resetAll(context: Context) {
        context.dataStore.edit { preferences ->
            preferences.clear()
        }
    }
}
