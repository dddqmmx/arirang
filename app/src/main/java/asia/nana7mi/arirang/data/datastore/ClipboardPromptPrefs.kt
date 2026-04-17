package asia.nana7mi.arirang.data.datastore

import android.content.Context
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
    enum class Policy {
        ALLOW,
        DENY,
        ASK
    }

    private fun appPolicyKey(pkg: String) = stringPreferencesKey("app_policy_$pkg")

    suspend fun getAppPolicy(context: Context, pkg: String): Policy {
        val value = context.dataStore.data.map { preferences -> preferences[appPolicyKey(pkg)] }.first()
        return value?.let { Policy.valueOf(it) } ?: Policy.ASK
    }

    suspend fun setAppPolicy(context: Context, pkg: String, policy: Policy){
        context.dataStore.edit { preferences -> preferences[appPolicyKey(pkg)] = policy.name }
    }

    suspend fun getAppPolicies(context: Context): Map<String, Policy> {
        val prefs = context.dataStore.data.first()
        return prefs.asMap().mapNotNull { (key, value) ->
            val name = key.name
            if (name.startsWith("app_policy_")) {
                val pkg = name.removePrefix("app_policy_")
                val policy = (value as? String)?.let { Policy.valueOf(it) } ?: Policy.ASK
                pkg to policy
            }else null
        }.toMap()
    }

    fun getAppPoliciesFlow(context: Context): kotlinx.coroutines.flow.Flow<Map<String, Policy>> {
        return context.dataStore.data.map { prefs ->
            prefs.asMap().mapNotNull { (key, value) ->
                val name = key.name
                if (name.startsWith("app_policy_")) {
                    val pkg = name.removePrefix("app_policy_")
                    val policy = (value as? String)?.let { Policy.valueOf(it) } ?: Policy.ASK
                    pkg to policy
                } else null
            }.toMap()
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
}
