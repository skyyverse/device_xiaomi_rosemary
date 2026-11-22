/*
 * SPDX-FileCopyrightText: 2025 kenway214
 * SPDX-License-Identifier: Apache-2.0
 */


package com.android.gamebar

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import org.lineageos.settings.R

import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

import com.android.settingslib.widget.SettingsBasePreferenceFragment

class GameBarPerAppConfigFragment : SettingsBasePreferenceFragment() {
    
    companion object {
        const val PREF_AUTO_APPS = "game_bar_auto_apps"
    }

    private data class CachedAppEntry(
        val packageName: String,
        val label: CharSequence,
        val labelLower: String,
        val pkgLower: String,
        val icon: Drawable
    )

    private var searchBar: EditText? = null
    private var category: PreferenceCategory? = null
    private var cachedApps: List<CachedAppEntry> = emptyList()
    private val autoApps = mutableSetOf<String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var loaderExecutor: ExecutorService? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = super.onCreateView(inflater, container, savedInstanceState)
        val context = requireContext()
        val layout = LinearLayout(context)
        layout.orientation = LinearLayout.VERTICAL
        searchBar = EditText(context).apply {
            id = View.generateViewId()
            hint = "Search apps..."
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setBackgroundResource(R.drawable.bg_search_rounded)
            setPadding(24, 24, 24, 24)
            setTextColor(context.getColor(R.color.app_name_text_selector))
            setHintTextColor(context.getColor(R.color.app_package_text_selector))
        }
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        val margin = (context.resources.displayMetrics.density * 16).toInt() // 16dp
        params.setMargins(margin, 0, margin, 0)
        layout.addView(searchBar, params)
        root?.let { layout.addView(it) }
        
        searchBar?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                populateAppList(s.toString().lowercase(Locale.getDefault()))
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        return layout
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()
        preferenceScreen = preferenceManager.createPreferenceScreen(context)
        category = PreferenceCategory(context).apply {
            title = "Configure Per-App GameBar"
        }
        preferenceScreen.addPreference(category!!)

        val storedApps = PreferenceManager.getDefaultSharedPreferences(context)
            .getStringSet(PREF_AUTO_APPS, emptySet())
        autoApps.clear()
        if (storedApps != null) {
            autoApps.addAll(storedApps)
        }

        val pm = context.packageManager
        val selfPkg = context.packageName
        loaderExecutor = Executors.newSingleThreadExecutor()
        loaderExecutor?.execute {
            val loaded = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .asSequence()
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && it.packageName != selfPkg }
                .map { app ->
                    val label = app.loadLabel(pm)
                    CachedAppEntry(
                        packageName = app.packageName,
                        label = label,
                        labelLower = label.toString().lowercase(Locale.getDefault()),
                        pkgLower = app.packageName.lowercase(Locale.getDefault()),
                        icon = app.loadIcon(pm)
                    )
                }
                .sortedBy { it.labelLower }
                .toList()
            mainHandler.post {
                if (!isAdded) return@post
                cachedApps = loaded
                val filter = searchBar?.text?.toString()?.lowercase(Locale.getDefault()).orEmpty()
                populateAppList(filter)
            }
        }
    }

    override fun onDestroy() {
        loaderExecutor?.shutdownNow()
        loaderExecutor = null
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun populateAppList(filter: String) {
        val ctx = context ?: return
        val cat = category ?: return
        cat.removeAll()
        for (app in cachedApps) {
            if (filter.isNotEmpty() && !(app.labelLower.contains(filter) || app.pkgLower.contains(filter))) continue

            val pref = SwitchPreferenceCompat(ctx).apply {
                isPersistent = false
                title = app.label
                summary = app.packageName
                key = "gamebar_${app.packageName}"
                isChecked = autoApps.contains(app.packageName)
                icon = app.icon
                onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                    if (newValue as Boolean) {
                        autoApps.add(app.packageName)
                    } else {
                        autoApps.remove(app.packageName)
                    }
                    PreferenceManager.getDefaultSharedPreferences(ctx)
                        .edit().putStringSet(PREF_AUTO_APPS, autoApps.toSet()).apply()
                    true
                }
            }
            cat.addPreference(pref)
        }
    }
}
