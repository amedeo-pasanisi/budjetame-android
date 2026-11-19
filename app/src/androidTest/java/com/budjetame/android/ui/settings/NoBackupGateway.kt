package com.budjetame.android.ui.settings

import com.budjetame.android.data.backup.BackupFile
import com.budjetame.android.data.backup.BackupGateway
import com.budjetame.android.data.backup.RestoreResult

/** A Backup gateway for the shell tests that never open Settings: Export
 * all and Restore are never tapped there, so either call is a test bug. */
class NoBackupGateway : BackupGateway {
    override suspend fun download(): BackupFile = error("the shell tests never download a Backup")

    override suspend fun restore(filename: String, content: ByteArray): RestoreResult =
        error("the shell tests never restore a Backup")
}
