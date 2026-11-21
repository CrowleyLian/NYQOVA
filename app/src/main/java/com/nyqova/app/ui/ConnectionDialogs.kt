package com.nyqova.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.*
import com.nyqova.app.storage.*

class ConnectionDialogs(private val activity: Activity, private val connected: () -> Unit) : java.io.Closeable {
    private var connectionDialog: AlertDialog?=null
    private var discoveryDialog: AlertDialog?=null
    private var discovery: SourceDiscovery?=null
    fun show(): AlertDialog {
        val current = SmbSource.settings
        val form = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(36,12,36,12) }
        fun field(hint: String, value: String, secret: Boolean = false) = EditText(activity).apply {
            this.hint = hint; setText(value); isSingleLine = true; textSize = 16f
            inputType = if(secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NEXT
            form.addView(this)
        }
        val address = field("SMB / WebDAV 地址", MusicSource.label())
        val username = field("用户名（SMB 留空使用访客）", if(MusicSource.webDavBase != null) MusicSource.username else current.username)
        val password = field("密码", if(MusicSource.webDavBase != null) MusicSource.password else current.password, true)
        val domain = field("域（可选）", current.domain)
        form.addView(TvStyle.button(activity, "发现局域网设备") { discover { address.setText(it.url) } })
        val dialog = TvStyle.dialog(activity).setTitle("音乐源").setView(ScrollView(activity).apply { addView(form) })
            .setNegativeButton("取消", null).setPositiveButton("保存并连接", null).create()
        connectionDialog=dialog
        dialog.setOnDismissListener { discoveryDialog?.dismiss(); discovery?.close(); connectionDialog=null }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val uri = Uri.parse(address.text.toString().trim())
            val dav = uri.scheme in listOf("http", "https")
            if((!dav && uri.scheme != "smb") || uri.host.isNullOrBlank() || (!dav && uri.pathSegments.size != 1) || uri.userInfo != null || uri.query != null || uri.fragment != null) {
                address.error = "请输入有效的 SMB 或 WebDAV 地址"; return@setOnClickListener
            }
            val profile = ConnectionProfile(if(dav) uri.toString().trimEnd('/') + "/" else uri.toString(), username.text.toString(), password.text.toString(), domain.text.toString())
            runCatching { ConnectionStore.save(activity, profile) }.onSuccess {
                profile.activate(); dialog.dismiss(); connected()
            }.onFailure { address.error = "保存失败，请重试" }
        }
        return dialog
    }
    override fun close() { discovery?.close(); discoveryDialog?.dismiss(); connectionDialog?.dismiss() }
    private fun discover(select: (DiscoveredSource) -> Unit) {
        discoveryDialog?.dismiss(); discovery?.close()
        val found = mutableListOf<DiscoveredSource>()
        val adapter = ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1, mutableListOf())
        val list = ListView(activity).apply { this.adapter = adapter }
        val dialog = TvStyle.dialog(activity).setTitle("正在发现局域网设备…").setView(list).setNegativeButton("关闭", null).create()
        val current = SourceDiscovery(activity, { found += it; adapter.add(it.label) }, {
            if(dialog.isShowing) dialog.setTitle(if(found.isEmpty()) "未发现设备，可手动填写地址" else "选择音乐源")
        })
        list.setOnItemClickListener { _, _, index, _ -> select(found[index]); dialog.dismiss() }
        dialog.setOnDismissListener { current.close(); if(discovery===current) { discovery=null; discoveryDialog=null } }
        discovery=current; discoveryDialog=dialog
        dialog.show(); current.start()
    }
}
