package asia.nana7mi.arirang.ui.activity.packagelist

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioGroup
import androidx.activity.enableEdgeToEdge
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.data.datastore.PackageVisibilityPrefs
import asia.nana7mi.arirang.data.datastore.PackageVisibilityPrefs.DisplayMode
import asia.nana7mi.arirang.data.datastore.PackageVisibilityPrefs.Template
import asia.nana7mi.arirang.data.datastore.PackageVisibilityPrefs.TemplateListMode
import asia.nana7mi.arirang.ui.activity.BaseActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class PackageTemplateManagerActivity : BaseActivity() {

    private lateinit var listView: ListView
    private lateinit var adapter: ArrayAdapter<String>
    private var templates = emptyList<Template>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_package_template_manager)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        listView = findViewById(R.id.templateListView)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            templates.getOrNull(position)?.let { showTemplateActionDialog(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTemplates()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_package_template_manager, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_new_template -> {
                showCreateTemplateDialog { openTemplateEditor(it) }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun refreshTemplates() {
        templates = PackageVisibilityPrefs.loadTemplates(this)
        adapter.clear()
        if (templates.isEmpty()) {
            adapter.add(getString(R.string.template_empty))
        } else {
            adapter.addAll(templates.map { template ->
                val count = PackageVisibilityPrefs.resolvedTemplatePackages(template, templates).size
                val parentName = templates.firstOrNull { it.id == template.parentId }?.name
                val modeName = getString(
                    if (template.listMode == TemplateListMode.WHITELIST) {
                        R.string.template_mode_whitelist
                    } else {
                        R.string.template_mode_blacklist
                    }
                )
                if (parentName == null) {
                    "${template.name} ($modeName, ${getString(R.string.selected_count, count)})"
                } else {
                    "${template.name} ($modeName, ${getString(R.string.inherits_from, parentName)}, ${getString(R.string.selected_count, count)})"
                }
            })
        }
        adapter.notifyDataSetChanged()
    }

    private fun showTemplateActionDialog(template: Template) {
        val options = arrayOf(
            getString(R.string.template_edit_list),
            getString(R.string.template_list_mode),
            getString(R.string.template_rename),
            getString(R.string.template_set_parent),
            getString(R.string.template_delete)
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(template.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openTemplateEditor(template)
                    1 -> showTemplateModeDialog(template)
                    2 -> showRenameTemplateDialog(template)
                    3 -> showTemplateParentDialog(template)
                    4 -> deleteTemplate(template)
                }
            }
            .show()
    }

    private fun showCreateTemplateDialog(onCreated: (Template) -> Unit) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }
        val input = TextInputEditText(this).apply {
            setSingleLine(true)
        }
        val inputLayout = TextInputLayout(this).apply {
            hint = getString(R.string.template_name)
            addView(input)
        }
        val whitelistId = android.view.View.generateViewId()
        val blacklistId = android.view.View.generateViewId()
        val modeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            addView(MaterialRadioButton(this@PackageTemplateManagerActivity).apply {
                id = whitelistId
                text = getString(R.string.template_mode_whitelist)
            })
            addView(MaterialRadioButton(this@PackageTemplateManagerActivity).apply {
                id = blacklistId
                text = getString(R.string.template_mode_blacklist)
            })
            check(whitelistId)
        }
        container.addView(inputLayout)
        container.addView(modeGroup)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.template_new)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim().ifBlank {
                    getString(R.string.template_new)
                }
                val mode = if (modeGroup.checkedRadioButtonId == blacklistId) {
                    TemplateListMode.BLACKLIST
                } else {
                    TemplateListMode.WHITELIST
                }
                val template = PackageVisibilityPrefs.createTemplate(this, name, listMode = mode)
                refreshTemplates()
                onCreated(template)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRenameTemplateDialog(template: Template) {
        val input = EditText(this).apply {
            setText(template.name)
            setSingleLine(true)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.template_rename)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim().ifBlank { template.name }
                updateTemplate(template.copy(name = name))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showTemplateModeDialog(template: Template) {
        val options = arrayOf(
            getString(R.string.template_mode_whitelist),
            getString(R.string.template_mode_blacklist)
        )
        val modes = arrayOf(TemplateListMode.WHITELIST, TemplateListMode.BLACKLIST)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.template_list_mode)
            .setSingleChoiceItems(options, modes.indexOf(template.listMode)) { dialog, which ->
                updateTemplate(template.copy(listMode = modes[which]))
                dialog.dismiss()
            }
            .show()
    }

    private fun showTemplateParentDialog(template: Template) {
        val candidates = templates.filter { it.id != template.id }
        val options = buildList {
            add(ParentOption(getString(R.string.template_none), null))
            add(ParentOption(getString(R.string.template_new), NEW_TEMPLATE_PARENT_ID))
            candidates.forEach { add(ParentOption(it.name, it.id)) }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.template_parent)
            .setItems(options.map { it.title }.toTypedArray()) { _, which ->
                val selected = options[which]
                if (selected.templateId == NEW_TEMPLATE_PARENT_ID) {
                    showCreateTemplateDialog { parent ->
                        updateTemplate(template.copy(parentId = parent.id))
                    }
                } else {
                    updateTemplate(template.copy(parentId = selected.templateId))
                }
            }
            .show()
    }

    private fun updateTemplate(updated: Template) {
        val updatedTemplates = PackageVisibilityPrefs.loadTemplates(this).map {
            if (it.id == updated.id) updated else it
        }
        PackageVisibilityPrefs.saveTemplates(this, updatedTemplates)
        refreshTemplates()
    }

    private fun deleteTemplate(template: Template) {
        val updatedTemplates = PackageVisibilityPrefs.loadTemplates(this)
            .filterNot { it.id == template.id }
            .map { if (it.parentId == template.id) it.copy(parentId = null) else it }
        val updatedRules = PackageVisibilityPrefs.loadAppRules(this).filterNot { it.templateId == template.id }
        val config = PackageVisibilityPrefs.loadConfig(this)

        PackageVisibilityPrefs.saveTemplates(this, updatedTemplates)
        PackageVisibilityPrefs.saveAppRules(this, updatedRules)
        if (config.defaultTemplateId == template.id) {
            PackageVisibilityPrefs.setDefaultSelection(this, DisplayMode.ALL_VISIBLE, null)
        }
        refreshTemplates()
    }

    private fun openTemplateEditor(template: Template) {
        startActivity(PackageCustomListActivity.forTemplate(this, template.id, template.name))
    }

    private data class ParentOption(val title: String, val templateId: String?)

    companion object {
        private const val NEW_TEMPLATE_PARENT_ID = "__new_template__"
    }
}
