package com.nvr.camnory

import android.content.Context

internal object TransferImport {
    fun apply(context: Context, proposal: ConfigurationTransfer, replace: Boolean) {
        val checked = ConfigurationTransfer.decode(proposal.encode())
        val original = TvConfiguration.fromPhone(context)
        val store = CameraCatalogStore(context)
        val oldCatalog = store.load()
        val oldAppearance = TransferAppearance.read(context)
        // Compute and validate every conflict before writing any section.
        val config = checked.config?.let { if (replace) it else ConfigurationTransfer.mergeConfig(original, it) }
        val catalog = checked.catalog?.let { if (replace) it else ConfigurationTransfer.mergeCatalog(oldCatalog, it) }
        try {
            checked.appearance?.let { TransferAppearance.write(context, it, replace) }
            catalog?.let { next -> store.update { next } }
            config?.let { TvConfigurationStore(context).save(it) }
        } catch (failure: Exception) {
            runCatching { TransferAppearance.write(context, oldAppearance, true) }
            runCatching { store.update { oldCatalog } }
            throw failure
        }
    }
}
