package com.budjetame.android.ui.settings

import com.budjetame.android.data.backup.BackupFile
import com.budjetame.android.data.backup.BackupGateway

/** A Backup gateway for the shell tests that never open Settings: Export
 * all is never tapped there, so a download is a test bug. */
class NoBackupGateway : BackupGateway {
    override suspend fun exportAll(): BackupFile = error("the shell tests never export a Backup")
}
