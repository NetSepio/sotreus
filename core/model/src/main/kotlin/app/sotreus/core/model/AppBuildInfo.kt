package app.sotreus.core.model

/** Static facts about the running build, shown in Diagnostics and the Settings footer. */
data class AppBuildInfo(
    val versionName: String,
    val versionCode: Long,
    val applicationId: String,
    val buildType: String,
    val distribution: Distribution,
    val databaseSchemaVersion: Int,
)
