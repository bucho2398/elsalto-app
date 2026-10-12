package io.github.bucho2398.elsalto

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ConditionsWidget.schedule(this)
        setContent { App(openUrl = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }) }
    }
}

class MainVm(private val app: Application) : AndroidViewModel(app) {
    val prefs = Prefs(app)
    val log = RouteLog(app)
    /** Bumped when a route photo changes, so the screen reloads it. */
    var photoVersion by mutableIntStateOf(0); private set
    var conditions by mutableStateOf<Conditions?>(null); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var radar by mutableStateOf<RadarResult?>(null); private set
    var radarLoading by mutableStateOf(false); private set

    init { refresh(false) }

    /** Fetches the weather (cached 15 min unless [force]) and runs the model. */
    fun refresh(force: Boolean) {
        viewModelScope.launch {
            loading = true
            val c = withContext(Dispatchers.IO) {
                runCatching { Repo.conditions(app, force) }.onFailure { error = it.message }.getOrNull()
            }
            if (c != null) { conditions = c; error = null } else if (error == null) error = "Couldn't reach Open-Meteo"
            loading = false
            withContext(Dispatchers.IO) { runCatching { ConditionsWidget.render(app, c ?: conditions) } }
        }
    }

    fun loadRadar(force: Boolean = false) {
        if (radarLoading || (radar != null && !force)) return
        viewModelScope.launch {
            radarLoading = true
            val r = withContext(Dispatchers.IO) { runCatching { Radar.load() }.getOrNull() }
            if (r != null) radar = r
            radarLoading = false
        }
    }

    fun setPhoto(id: String, uri: Uri) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { log.setPhoto(id, uri) }
            if (ok) { log.update(id) { it.copy(photo = true) }; photoVersion++ }
        }
    }

    fun removePhoto(id: String) { log.removePhoto(id); photoVersion++ }

    private fun toast(text: String) = Toast.makeText(app, text, Toast.LENGTH_LONG).show()

    fun exportLog(uri: Uri) {
        viewModelScope.launch {
            val n = withContext(Dispatchers.IO) {
                runCatching { app.contentResolver.openOutputStream(uri, "wt")!!.use { log.export(it) } }.getOrNull()
            }
            toast(if (n == null) "Couldn't write the route log" else "Exported $n routes")
        }
    }

    fun importLog(uri: Uri) {
        viewModelScope.launch {
            val read = withContext(Dispatchers.IO) {
                runCatching { app.contentResolver.openInputStream(uri)!!.use { log.readImport(it) } }.getOrNull()
            }
            if (read == null) { toast("That file isn't an El Salto route log"); return@launch }
            log.applyImport(read)
            photoVersion++
            toast("Imported ${read.size} routes")
        }
    }

    private fun changed(block: () -> Unit) { block(); refresh(false) }
    fun toggleFav(id: String) = changed { prefs.toggleFav(id) }
    fun setAspect(id: String, dir: String?) = changed { prefs.setAspect(id, dir) }
    fun addShade(id: String, from: Int, to: Int) = changed { prefs.addShade(id, from, to) }
    fun clearShade(id: String) = changed { prefs.clearShade(id) }
}
