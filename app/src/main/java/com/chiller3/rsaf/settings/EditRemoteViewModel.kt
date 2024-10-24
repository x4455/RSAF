/*
 * SPDX-FileCopyrightText: 2023-2024 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chiller3.rsaf.binding.rcbridge.Rcbridge
import com.chiller3.rsaf.rclone.RcloneConfig
import com.chiller3.rsaf.rclone.RcloneRpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EditRemoteActivityActions(
    val refreshRoots: Boolean,
    val editNewRemote: String?,
    val finish: Boolean,
)

data class RemoteConfigState(
    val allowExternalAccess: Boolean? = null,
    val dynamicShortcut: Boolean? = null,
    val vfsCaching: Boolean? = null,
    val canStream: Boolean? = null,
)

class EditRemoteViewModel : ViewModel() {
    companion object {
        private val TAG = EditRemoteViewModel::class.java.simpleName
    }

    private lateinit var remote: String

    private val _remotes = MutableStateFlow<Map<String, Map<String, String>>>(emptyMap())
    val remotes = _remotes.asStateFlow()

    private val _remoteConfig = MutableStateFlow(RemoteConfigState())
    val remoteConfig = _remoteConfig.asStateFlow()

    private val _alerts = MutableStateFlow<List<EditRemoteAlert>>(emptyList())
    val alerts = _alerts.asStateFlow()

    private val _activityActions = MutableStateFlow(EditRemoteActivityActions(false, null, false))
    val activityActions = _activityActions.asStateFlow()

    fun setRemote(remote: String) {
        this.remote = remote
        refreshRemotes(false)
    }

    private suspend fun refreshRemotesInternal(force: Boolean) {
        try {
            if (_remotes.value.isEmpty() || force) {
                withContext(Dispatchers.IO) {
                    _remotes.update { RcloneRpc.remotes }
                }
            }

            val config = remotes.value[remote]

            if (config != null) {
                _remoteConfig.update {
                    it.copy(
                        allowExternalAccess = !RcloneRpc.getCustomBoolOpt(
                            config,
                            RcloneRpc.CUSTOM_OPT_BLOCKED,
                        ),
                        dynamicShortcut = RcloneRpc.getCustomBoolOpt(
                            config,
                            RcloneRpc.CUSTOM_OPT_DYNAMIC_SHORTCUT,
                        ),
                        vfsCaching = RcloneRpc.getCustomBoolOpt(
                            config,
                            RcloneRpc.CUSTOM_OPT_VFS_CACHING,
                        ),
                    )
                }

                // Only calculate this once since the value can't change and it requires
                // initializing the backend, which may perform network calls.
                if (_remoteConfig.value.canStream == null) {
                    withContext(Dispatchers.IO) {
                        _remoteConfig.update {
                            it.copy(canStream = Rcbridge.rbCanStream("$remote:"))
                        }
                    }
                }
            } else {
                // This will happen after renaming or deleting the remote.
                _remoteConfig.update { RemoteConfigState() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh remotes", e)
            _alerts.update { it + EditRemoteAlert.ListRemotesFailed(e.toString()) }
        }
    }

    private fun refreshRemotes(@Suppress("SameParameterValue") force: Boolean) {
        viewModelScope.launch {
            refreshRemotesInternal(force)
        }
    }

    fun setExternalAccess(remote: String, allow: Boolean) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    RcloneRpc.setRemoteOptions(
                        remote, mapOf(
                            RcloneRpc.CUSTOM_OPT_BLOCKED to (!allow).toString(),
                        )
                    )
                }
                refreshRemotesInternal(true)
                _activityActions.update { it.copy(refreshRoots = true) }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set $remote external access to $allow", e)
                _alerts.update { it + EditRemoteAlert.UpdateExternalAccessFailed(remote, e.toString()) }
            }
        }
    }

    fun setDynamicShortcut(remote: String, enabled: Boolean) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    RcloneRpc.setRemoteOptions(
                        remote, mapOf(
                            RcloneRpc.CUSTOM_OPT_DYNAMIC_SHORTCUT to enabled.toString(),
                        )
                    )
                }
                refreshRemotesInternal(true)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set remote $remote shortcut state to $enabled", e)
                _alerts.update { it + EditRemoteAlert.UpdateDynamicShortcutFailed(remote, e.toString()) }
            }
        }
    }

    fun setVfsCaching(remote: String, enabled: Boolean) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    RcloneRpc.setRemoteOptions(
                        remote, mapOf(
                            RcloneRpc.CUSTOM_OPT_VFS_CACHING to enabled.toString(),
                        )
                    )
                }
                refreshRemotesInternal(true)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set remote $remote VFS caching state to $enabled", e)
                _alerts.update { it + EditRemoteAlert.UpdateVfsCachingFailed(remote, e.toString()) }
            }
        }
    }

    private fun copyRemote(oldRemote: String, newRemote: String, delete: Boolean) {
        if (oldRemote == newRemote) {
            throw IllegalStateException("Old and new remote names are the same")
        }

        val failure = if (delete) {
            EditRemoteAlert::RemoteRenameFailed
        } else {
            EditRemoteAlert::RemoteDuplicateFailed
        }

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    RcloneConfig.copyRemote(oldRemote, newRemote)
                    if (delete) {
                        RcloneRpc.deleteRemote(oldRemote)
                    }
                }
                refreshRemotesInternal(true)
                _activityActions.update {
                    it.copy(
                        refreshRoots = true,
                        editNewRemote = newRemote,
                        finish = true,
                    )
                }
            } catch (e: Exception) {
                val action = if (delete) { "rename" } else { "duplicate" }
                Log.e(TAG, "Failed to $action remote $oldRemote to $newRemote", e)
                _alerts.update { it + failure(oldRemote, newRemote, e.toString()) }
            }
        }
    }

    fun renameRemote(oldRemote: String, newRemote: String) {
        copyRemote(oldRemote, newRemote, true)
    }

    fun duplicateRemote(oldRemote: String, newRemote: String) {
        copyRemote(oldRemote, newRemote, false)
    }

    fun deleteRemote(remote: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    RcloneRpc.deleteRemote(remote)
                }
                refreshRemotesInternal(true)
                _activityActions.update { it.copy(refreshRoots = true, finish = true) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete remote $remote", e)
                _alerts.update { it + EditRemoteAlert.RemoteDeleteFailed(remote, e.toString()) }
            }
        }
    }

    fun acknowledgeFirstAlert() {
        _alerts.update { it.drop(1) }
    }

    fun interactiveConfigurationCompleted(remote: String) {
        viewModelScope.launch {
            refreshRemotesInternal(true)
            _alerts.update { it + EditRemoteAlert.RemoteEditSucceeded(remote) }
        }
    }

    fun activityActionCompleted() {
        _activityActions.update { EditRemoteActivityActions(false, null, false) }
    }
}