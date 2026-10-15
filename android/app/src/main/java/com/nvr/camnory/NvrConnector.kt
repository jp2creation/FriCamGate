package com.nvr.camnory

/** Common discovery contract; media/control capabilities are migrated separately. */
internal interface NvrConnector {
    fun login(username: String, password: String, callback: (Result<NativeLogin>) -> Unit)
    fun loadDashboard(token: String, callback: (Result<DashboardData>) -> Unit)
    fun loadNvrVersion(token: String, callback: (Result<String>) -> Unit)
}
