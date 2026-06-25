package asia.nana7mi.arirang.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.model.PackageVisibilityAppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PackageVisibilityAppAdapter(
    private val appList: MutableList<PackageVisibilityAppInfo>,
    private val onAppClick: (PackageVisibilityAppInfo) -> Unit
) : RecyclerView.Adapter<PackageVisibilityAppAdapter.ViewHolder>() {

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val appIcon: ImageView = itemView.findViewById(R.id.appIcon)
        val appName: TextView = itemView.findViewById(R.id.appName)
        val packageName: TextView = itemView.findViewById(R.id.packageName)
        val statusText: TextView = itemView.findViewById(R.id.permissionStatusText)
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
        holder.statusText.text = app.statusText

        holder.itemView.setOnClickListener { onAppClick(app) }

        if (app.icon == null) {
            holder.appIcon.setImageDrawable(null)
            (holder.itemView.context as? AppCompatActivity)?.lifecycleScope?.launch(Dispatchers.IO) {
                runCatching {
                    holder.itemView.context.packageManager.getApplicationIcon(app.packageName)
                }.onSuccess { icon ->
                    withContext(Dispatchers.Main) {
                        app.icon = icon
                        if (holder.bindingAdapterPosition == position) {
                            holder.appIcon.setImageDrawable(icon)
                        }
                    }
                }
            }
        } else {
            holder.appIcon.setImageDrawable(app.icon)
        }
    }

    fun setList(newList: List<PackageVisibilityAppInfo>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = appList.size
            override fun getNewListSize() = newList.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return appList[oldItemPosition].packageName == newList[newItemPosition].packageName
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val old = appList[oldItemPosition]
                val new = newList[newItemPosition]
                return old.appName == new.appName &&
                    old.statusText == new.statusText &&
                    old.isSystemApp == new.isSystemApp &&
                    old.isConfigured == new.isConfigured
            }
        })
        appList.clear()
        appList.addAll(newList)
        diff.dispatchUpdatesTo(this)
    }
}
