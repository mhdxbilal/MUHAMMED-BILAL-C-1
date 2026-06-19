package com.continental.player

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.continental.player.base.BaseActivity
import com.continental.player.data.LibraryRow
import com.continental.player.data.ResumeStore
import com.continental.player.data.SortOrder
import com.continental.player.data.VideoItem
import com.continental.player.databinding.ActivityMainBinding
import com.continental.player.download.DownloadActivity
import com.continental.player.library.LibraryAdapter
import com.continental.player.library.LibraryAdapterListener
import com.continental.player.library.VideoRepository
import com.continental.player.util.PermissionsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : BaseActivity(), LibraryAdapterListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var resumeStore: ResumeStore
    private lateinit var videoRepository: VideoRepository

    private var allVideos: List<VideoItem> = emptyList()
    private var currentRows: List<LibraryRow> = emptyList()
    private var searchQuery: String = ""

    private val adapter by lazy {
        LibraryAdapter(lifecycleScope, resumeStore, this)
    }

    // ── permission request ──────────────────────────────────────────────
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) scanLibrary()
        else showPermissionDenied()
    }

    // ── SAF file picker (for manual single-file open) ───────────────────
    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            val title = uri.lastPathSegment ?: "Video"
            PlayerActivity.startWithUri(this, uri, title)
        }
    }

    // ───────────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        resumeStore = ResumeStore.getInstance(this)
        videoRepository = VideoRepository(this)
        searchQuery = settings.lastSearchQuery

        setupToolbar()
        setupRecyclerView()
        setupSwipeRefresh()
        setupFab()

        requestScanOrShowPermissionRationale()
    }

    override fun onResume() {
        super.onResume()
        // Re-render so resume progress bars reflect any just-completed playback session.
        renderRows(currentRows)
    }

    // ── UI setup ────────────────────────────────────────────────────────

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)

        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem?.actionView as? SearchView
        searchView?.queryHint = getString(R.string.search_hint)
        if (searchQuery.isNotBlank()) {
            searchItem?.expandActionView()
            searchView?.setQuery(searchQuery, false)
        }
        searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = false
            override fun onQueryTextChange(newText: String?): Boolean {
                searchQuery = newText ?: ""
                settings.lastSearchQuery = searchQuery
                rebuildAndRender()
                return true
            }
        })
        searchItem?.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem) = true
            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                searchQuery = ""
                settings.lastSearchQuery = ""
                rebuildAndRender()
                return true
            }
        })
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_downloads -> {
            startActivity(Intent(this, DownloadActivity::class.java))
            true
        }
        R.id.action_sort -> { showSortDialog(); true }
        R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
        R.id.action_about -> { showAboutDialog(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun setupRecyclerView() {
        binding.rvLibrary.layoutManager = LinearLayoutManager(this)
        binding.rvLibrary.adapter = adapter
        binding.rvLibrary.setHasFixedSize(false)
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setColorSchemeColors(getColor(R.color.continental_gold))
        binding.swipeRefresh.setProgressBackgroundColorSchemeColor(getColor(R.color.continental_surface_elevated))
        binding.swipeRefresh.setOnRefreshListener { scanLibrary() }
    }

    private fun setupFab() {
        binding.fabBrowse.setOnClickListener {
            filePicker.launch(arrayOf("video/*"))
        }
    }

    // ── Permission handling ──────────────────────────────────────────────

    private fun requestScanOrShowPermissionRationale() {
        if (PermissionsHelper.hasMediaPermission(this)) {
            scanLibrary()
        } else if (shouldShowRequestPermissionRationale(PermissionsHelper.mediaPermission())) {
            AlertDialog.Builder(this)
                .setTitle(R.string.permission_rationale_title)
                .setMessage(R.string.permission_rationale_body)
                .setPositiveButton(R.string.action_grant_permission) { _, _ ->
                    permissionLauncher.launch(PermissionsHelper.mediaPermission())
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        } else {
            permissionLauncher.launch(PermissionsHelper.mediaPermission())
        }
    }

    private fun showPermissionDenied() {
        binding.llEmptyState.isVisible = true
        binding.tvEmptyTitle.text = getString(R.string.permission_rationale_title)
        binding.tvEmptyBody.text = getString(R.string.permission_denied_message)
        binding.btnEmptyAction.text = getString(R.string.action_browse_files)
        binding.btnEmptyAction.setOnClickListener {
            filePicker.launch(arrayOf("video/*"))
        }
        binding.swipeRefresh.isVisible = false
    }

    // ── Library scanning ────────────────────────────────────────────────

    private fun scanLibrary() {
        binding.swipeRefresh.isRefreshing = true
        lifecycleScope.launch {
            allVideos = videoRepository.scanVideos()
            rebuildAndRender()
            withContext(Dispatchers.Main) {
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun rebuildAndRender() {
        val rows = VideoRepository.buildLibraryRows(
            allVideos = allVideos,
            sortOrder = settings.sortOrder,
            hiddenFolders = settings.hiddenFolders,
            collapsedFolders = settings.collapsedFolders,
            searchQuery = searchQuery
        )
        currentRows = rows
        renderRows(rows)
    }

    private fun renderRows(rows: List<LibraryRow>) {
        lifecycleScope.launch {
            binding.llEmptyState.isVisible = rows.isEmpty() && allVideos.isEmpty()
            binding.rvLibrary.isVisible = rows.isNotEmpty()
            adapter.submitRows(rows)
        }
    }

    // ── LibraryAdapterListener ───────────────────────────────────────────

    override fun onVideoClick(video: VideoItem) {
        val queue = VideoRepository.flattenPlayableQueue(currentRows)
        val index = queue.indexOfFirst { it.id == video.id }.takeIf { it >= 0 } ?: 0
        PlayerActivity.startWithQueue(
            this,
            queue.map { it.uri },
            index,
            video.displayName
        )
    }

    override fun onFolderToggle(folderPath: String) {
        val collapsed = !settings.isFolderCollapsed(folderPath)
        settings.setFolderCollapsed(folderPath, collapsed)
        rebuildAndRender()
    }

    override fun onFolderLongPress(folderPath: String, folderName: String, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "Hide folder from library")
        popup.menu.add(0, 2, 0, "Remove folder from library")
        val isCollapsed = settings.isFolderCollapsed(folderPath)
        popup.menu.add(0, 3, 0, if (isCollapsed) "Expand folder" else "Collapse folder")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    settings.hideFolder(folderPath)
                    rebuildAndRender()
                    android.widget.Toast.makeText(this, "\"$folderName\" hidden from library", android.widget.Toast.LENGTH_SHORT).show()
                    true
                }
                2 -> {
                    AlertDialog.Builder(this)
                        .setTitle("Remove \"$folderName\"?")
                        .setMessage("This hides all videos in this folder from the library. The files on disk are not deleted.")
                        .setPositiveButton("Remove") { _, _ ->
                            settings.hideFolder(folderPath)
                            rebuildAndRender()
                        }
                        .setNegativeButton(R.string.action_cancel, null)
                        .show()
                    true
                }
                3 -> {
                    settings.setFolderCollapsed(folderPath, !isCollapsed)
                    rebuildAndRender()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    // ── Sort dialog ───────────────────────────────────────────────────────

    private fun showSortDialog() {
        val orders = SortOrder.entries
        val labels = orders.map { it.label }.toTypedArray()
        var selected = orders.indexOf(settings.sortOrder).takeIf { it >= 0 } ?: 0
        AlertDialog.Builder(this)
            .setTitle(R.string.action_sort)
            .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                settings.sortOrder = orders[selected]
                rebuildAndRender()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ── About dialog ──────────────────────────────────────────────────────

    private fun showAboutDialog() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setMessage(getString(R.string.about_message, version))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
