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
import asia.nana7mi.arirang.model.PackageCustomListItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PackageCustomListAdapter(
    private val items: MutableList<PackageCustomListItem>,
    private val onItemClick: (PackageCustomListItem) -> Unit
) : RecyclerView.Adapter<PackageCustomListAdapter.ViewHolder>() {

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val appIcon: ImageView = itemView.findViewById(R.id.appIcon)
        val appName: TextView = itemView.findViewById(R.id.appName)
        val packageName: TextView = itemView.findViewById(R.id.packageName)
        val checkIcon: ImageView = itemView.findViewById(R.id.checkIcon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_package_custom_list_app, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.appName.text = item.appName
        holder.packageName.text = item.packageName
        holder.checkIcon.visibility = if (item.isSelected) View.VISIBLE else View.INVISIBLE
        holder.itemView.setOnClickListener { onItemClick(item) }

        if (item.icon == null) {
            holder.appIcon.setImageDrawable(null)
            (holder.itemView.context as? AppCompatActivity)?.lifecycleScope?.launch(Dispatchers.IO) {
                runCatching {
                    holder.itemView.context.packageManager.getApplicationIcon(item.packageName)
                }.onSuccess { icon ->
                    withContext(Dispatchers.Main) {
                        item.icon = icon
                        if (holder.bindingAdapterPosition == position) {
                            holder.appIcon.setImageDrawable(icon)
                        }
                    }
                }
            }
        } else {
            holder.appIcon.setImageDrawable(item.icon)
        }
    }

    fun setList(newItems: List<PackageCustomListItem>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newItems.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return items[oldItemPosition].packageName == newItems[newItemPosition].packageName
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val old = items[oldItemPosition]
                val new = newItems[newItemPosition]
                return old.appName == new.appName &&
                    old.packageName == new.packageName &&
                    old.isSelected == new.isSelected
            }
        })
        items.clear()
        items.addAll(newItems)
        diff.dispatchUpdatesTo(this)
    }
}
