package asia.nana7mi.arirang.ui.adapter

import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.model.AppInfo
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import asia.nana7mi.arirang.data.datastore.ClipboardPromptPrefs
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppListAdapter(
    private val appList: MutableList<AppInfo>,
    private val onPermissionChange: (AppInfo, ClipboardPromptPrefs.Policy) -> Unit
) : RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

    var defaultPolicy: ClipboardPromptPrefs.Policy = ClipboardPromptPrefs.Policy.ASK

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val appIcon: ImageView = itemView.findViewById(R.id.appIcon)
        val appName: TextView = itemView.findViewById(R.id.appName)
        val packageName: TextView = itemView.findViewById(R.id.packageName)
        val permissionStatusText: TextView = itemView.findViewById(R.id.permissionStatusText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_info, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount() = appList.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = appList[position]
        holder.appName.text = app.appName
        holder.packageName.text = app.packageName

        updatePermissionText(holder.permissionStatusText, app.permissionState)

        holder.itemView.setOnClickListener {
            showPermissionDialog(holder.itemView.context, app) { newState ->
                app.permissionState = newState
                app.isConfigured = newState != defaultPolicy
                updatePermissionText(holder.permissionStatusText, newState)
                onPermissionChange(app, newState)
            }
        }

        // 异步加载图标
        if (app.icon == null) {
            (holder.itemView.context as? AppCompatActivity)?.lifecycleScope?.launch(Dispatchers.IO) {
                try {
                    val icon = holder.itemView.context.packageManager
                        .getApplicationIcon(app.packageName)
                    withContext(Dispatchers.Main) {
                        app.icon = icon
                        if (holder.adapterPosition == position) {
                            holder.appIcon.setImageDrawable(icon)
                        }
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }
        } else {
            holder.appIcon.setImageDrawable(app.icon)
        }
    }

    private fun updatePermissionText(textView: TextView, state: ClipboardPromptPrefs.Policy) {
        val context = textView.context
        textView.text = when (state) {
            ClipboardPromptPrefs.Policy.ALLOW -> context.getString(R.string.permission_allow)
            ClipboardPromptPrefs.Policy.DENY -> context.getString(R.string.permission_deny)
            else -> context.getString(R.string.permission_ask)
        }
    }

    private fun showPermissionDialog(context: android.content.Context, app: AppInfo, onSelected: (ClipboardPromptPrefs.Policy) -> Unit) {
        val options = arrayOf(
            context.getString(R.string.permission_allow),
            context.getString(R.string.permission_deny),
            context.getString(R.string.permission_ask)
        )
        val policies = arrayOf(
            ClipboardPromptPrefs.Policy.ALLOW,
            ClipboardPromptPrefs.Policy.DENY,
            ClipboardPromptPrefs.Policy.ASK
        )
        MaterialAlertDialogBuilder(context)
            .setTitle(app.appName)
            .setItems(options) { _, which ->
                onSelected(policies[which])
            }
            .show()
    }

    fun setList(newListRaw: List<AppInfo>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = appList.size
            override fun getNewListSize() = newListRaw.size

            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
                return appList[oldPos].packageName == newListRaw[newPos].packageName
            }

            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                val old = appList[oldPos]
                val new = newListRaw[newPos]
                return old.appName == new.appName && 
                       old.permissionState == new.permissionState &&
                       old.isSystemApp == new.isSystemApp &&
                       old.isConfigured == new.isConfigured
            }
        })

        appList.clear()
        appList.addAll(newListRaw)
        diff.dispatchUpdatesTo(this)
    }
}