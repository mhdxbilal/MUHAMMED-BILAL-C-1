package com.continental.player.download

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.continental.player.PlayerActivity
import com.continental.player.R
import com.continental.player.base.BaseActivity
import com.continental.player.databinding.ActivityDownloadBinding
import com.continental.player.databinding.DialogAddDownloadBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class DownloadActivity : BaseActivity(), DownloadAdapterListener {

    private lateinit var binding: ActivityDownloadBinding
    private lateinit var repository: DownloadRepository

    private val adapter by lazy { DownloadAdapter(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDownloadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = DownloadRepository.getInstance(this)

        setupToolbar()
        setupRecyclerView()
        setupFab()
        observeDownloads()

        // Pre-fill URL if launched via a share intent
        val sharedUrl = intent?.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent?.data?.toString()
        if (!sharedUrl.isNullOrBlank()) {
            showAddDownloadDialog(prefilledUrl = sharedUrl)
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menu.add(0, MENU_CLEAR, 0, getString(R.string.action_clear_history))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_CLEAR) {
            repository.clearCompletedHistory()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun setupRecyclerView() {
        binding.rvDownloads.layoutManager = LinearLayoutManager(this)
        binding.rvDownloads.adapter = adapter
    }

    private fun setupFab() {
        binding.fabAddDownload.setOnClickListener { showAddDownloadDialog() }
    }

    private fun observeDownloads() {
        lifecycleScope.launch {
            repository.tasks.collectLatest { tasks ->
                val sorted = tasks.sortedWith(
                    compareBy<DownloadTask> { if (it.isActive) 0 else 1 }
                        .thenByDescending { it.createdAtMs }
                )
                adapter.submitList(sorted)
                binding.llDownloadsEmpty.isVisible = tasks.isEmpty()
                binding.rvDownloads.isVisible = tasks.isNotEmpty()
            }
        }
    }

    // ── Add-download dialog ───────────────────────────────────────────────

    private fun showAddDownloadDialog(prefilledUrl: String = "") {
        val dialogBinding = DialogAddDownloadBinding.inflate(layoutInflater)

        if (prefilledUrl.isNotBlank()) {
            dialogBinding.etUrl.setText(prefilledUrl)
        }

        // Pre-select the user's preferred quality
        val defaultQualityId = when (settings.downloadDefaultQuality) {
            "Q2160" -> R.id.rbQ2160
            "Q720"  -> R.id.rbQ720
            "Q480"  -> R.id.rbQ480
            "BEST"  -> R.id.rbBest
            "AUDIO_ONLY" -> R.id.rbAudioOnly
            else    -> R.id.rbQ1080
        }
        dialogBinding.rgQuality.check(defaultQualityId)

        AlertDialog.Builder(this)
            .setTitle(R.string.action_new_download)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.action_play) { _, _ ->
                val url = dialogBinding.etUrl.text?.toString()?.trim() ?: ""
                if (url.isBlank() || (!url.startsWith("http://") && !url.startsWith("https://"))) {
                    Toast.makeText(this, R.string.toast_invalid_url, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val quality = when (dialogBinding.rgQuality.checkedRadioButtonId) {
                    R.id.rbQ2160    -> DownloadQuality.Q2160
                    R.id.rbQ720     -> DownloadQuality.Q720
                    R.id.rbQ480     -> DownloadQuality.Q480
                    R.id.rbBest     -> DownloadQuality.BEST
                    R.id.rbAudioOnly -> DownloadQuality.AUDIO_ONLY
                    else            -> DownloadQuality.Q1080
                }
                settings.downloadDefaultQuality = quality.name
                enqueueDownload(url, quality)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun enqueueDownload(url: String, quality: DownloadQuality) {
        val app = applicationContext as com.continental.player.ContinentalApp
        if (!app.downloadEngineReady) {
            Toast.makeText(this, "Download engine is still initialising — try again in a moment", Toast.LENGTH_LONG).show()
            return
        }
        repository.enqueue(url, quality)
        DownloadService.start(this)
        Toast.makeText(this, R.string.toast_download_queued, Toast.LENGTH_SHORT).show()
    }

    // ── DownloadAdapterListener ────────────────────────────────────────────

    override fun onCancel(taskId: String) {
        DownloadService.cancel(this, taskId)
    }

    override fun onRemove(taskId: String) {
        repository.removeTask(taskId)
    }

    override fun onPlayCompleted(task: DownloadTask) {
        val uri = task.finalUri ?: return
        PlayerActivity.startWithUri(this, Uri.parse(uri), task.displayTitle)
    }

    companion object {
        private const val MENU_CLEAR = 1001
    }
}
