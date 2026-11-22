package io.github.valerioisufi.pageboard.sync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.pageboard.sync.lan.DeviceCheck
import io.github.valerioisufi.pageboard.sync.lan.DiscoveryProblem
import io.github.valerioisufi.pageboard.sync.lan.LanCheck
import io.github.valerioisufi.pageboard.sync.lan.LanDevice
import io.github.valerioisufi.pageboard.sync.ui.generated.resources.Res
import io.github.valerioisufi.pageboard.sync.ui.generated.resources.sync_check_announced
import io.github.valerioisufi.pageboard.sync.ui.generated.resources.sync_check_announced_stale
import io.github.valerioisufi.pageboard.sync.ui.generated.resources.sync_check_copy
import io.github.valerioisufi.pageboard.sync.ui.generated.resources.sync_check_device_failed
import io.github.valerioisufi.pageboard.sync.ui.generated.resources.sync_check_device_ok
import io.github.valerioisufi.pageboard.sync.ui.generated.resources.sync_check_device_other_version
import io.github.valerioisufi.pageboard.sync.ui.generated.resources.sync_check_device_unshared
import io.github.valerioisufi.pageboard.sync.ui.generated.resources.sync_check_listening
import io.github.valerioisufi.pageboard.sync.ui.generated.resources.sync_check_nobody
import io.github.valerioisufi.pageboard.sync.ui.generated.resources.sync_check_not_announced
import io.github.valerioisufi.pageboard.sync.ui.generated.resources.sync_check_not_listening
import io.github.valerioisufi.pageboard.sync.ui.generated.resources.sync_check_not_started
import io.github.valerioisufi.pageboard.sync.ui.generated.resources.sync_check_running
import io.github.valerioisufi.pageboard.sync.ui.generated.resources.sync_check_title
import io.github.valerioisufi.pageboard.sync.ui.generated.resources.sync_close
import io.github.valerioisufi.pageboard.sync.ui.generated.resources.sync_device_unnamed
import io.github.valerioisufi.pageboard.sync.ui.generated.resources.sync_problem_configuration
import io.github.valerioisufi.pageboard.sync.ui.generated.resources.sync_problem_discovery
import io.github.valerioisufi.pageboard.sync.ui.generated.resources.sync_problem_discovery_settings
import io.github.valerioisufi.pageboard.sync.ui.generated.resources.sync_problem_no_network
import io.github.valerioisufi.pageboard.sync.ui.generated.resources.sync_problem_nobody
import io.github.valerioisufi.pageboard.sync.ui.generated.resources.sync_problem_nobody_internet
import io.github.valerioisufi.pageboard.sync.ui.generated.resources.sync_problem_nobody_settings
import io.github.valerioisufi.pageboard.sync.ui.generated.resources.sync_problem_not_answering
import io.github.valerioisufi.pageboard.sync.ui.generated.resources.sync_problem_permission
import io.github.valerioisufi.pageboard.sync.ui.generated.resources.sync_problem_permission_generic
import io.github.valerioisufi.pageboard.ui.theme.PageboardIcons
import org.jetbrains.compose.resources.stringResource

/**
 * Why the devices do not work together yet, as the sharing and joining dialogs say it, in the
 * order the user should see them (what stops everything first). The words are chosen by the
 * interface ([ProblemNotice]).
 */
internal sealed interface SharingProblem {
    /** The local network refused to this application. */
    data object PermissionDenied : SharingProblem

    /** The application does not declare the local network: the [missing] keys of its `Info.plist`. */
    data class MissingConfiguration(val missing: List<String>) : SharingProblem

    /** This device is on no local network. */
    data object NoLocalNetwork : SharingProblem

    /** Announcing this device or looking for the others failed: the [detail] of the system. */
    data class DiscoveryFailed(val detail: String) : SharingProblem

    /** Devices found sharing something with this one, that do not answer where they are announced. */
    data class NotAnswering(val devices: List<LanDevice>) : SharingProblem

    /** Nobody sharing something with this device found for a while; [internetOff]: the sync over the internet could help. */
    data class NobodyFound(val internetOff: Boolean) : SharingProblem

    /** Whether nothing works on the local network because of it (told before sharing too). */
    val blocksEverything: Boolean get() = this is PermissionDenied || this is MissingConfiguration || this is NoLocalNetwork
}

/** How many connections fail in a row before a device is said not to answer (one may be a device going away). */
internal const val FAILURES_BEFORE_TOLD = 2

/**
 * The problems to tell, from what the discovery reports ([problems]), the [devices] of the local
 * network, whether some device is connected ([connected], over any network) and whether the
 * devices have been looked for long enough ([lookedLong]) to say that nobody was found.
 * [internetOff]: the application gives the sync over the internet and it is off.
 */
internal fun sharingProblems(
    problems: List<DiscoveryProblem>,
    devices: List<LanDevice>,
    connected: Boolean,
    lookedLong: Boolean,
    internetOff: Boolean
): List<SharingProblem> = buildList {
    for (problem in problems) {
        when (problem) {
            DiscoveryProblem.PermissionDenied -> add(SharingProblem.PermissionDenied)
            is DiscoveryProblem.MissingConfiguration -> add(SharingProblem.MissingConfiguration(problem.missing))
            DiscoveryProblem.NoLocalNetwork -> add(SharingProblem.NoLocalNetwork)
            is DiscoveryProblem.AdvertiseFailed -> add(SharingProblem.DiscoveryFailed(detailOf(problem.code, problem.message)))
            is DiscoveryProblem.BrowseFailed -> add(SharingProblem.DiscoveryFailed(detailOf(problem.code, problem.message)))
        }
    }
    sortWith(compareBy { order(it) })
    // Only one failure of the discovery: the first one says enough
    val failures = filterIsInstance<SharingProblem.DiscoveryFailed>()
    if (failures.size > 1) removeAll(failures.drop(1))
    val silent = devices.filter { it.shared && !it.connected && it.failures >= FAILURES_BEFORE_TOLD }
    if (silent.isNotEmpty()) add(SharingProblem.NotAnswering(silent))
    val blocked = any { it.blocksEverything }
    if (lookedLong && !connected && !blocked && devices.none { it.shared }) add(SharingProblem.NobodyFound(internetOff))
}

private fun order(problem: SharingProblem): Int = when (problem) {
    SharingProblem.PermissionDenied -> 0
    is SharingProblem.MissingConfiguration -> 1
    SharingProblem.NoLocalNetwork -> 2
    is SharingProblem.DiscoveryFailed -> 3
    is SharingProblem.NotAnswering -> 4
    is SharingProblem.NobodyFound -> 5
}

private fun detailOf(code: Int?, message: String?): String = listOfNotNull(message, code?.toString()).distinct().joinToString(", ").ifEmpty { "?" }

/** The problems, one box each. */
@Composable
internal fun ProblemNotices(problems: List<SharingProblem>) {
    for (problem in problems) ProblemNotice(problem)
}

@Composable
private fun ProblemNotice(problem: SharingProblem) {
    when (problem) {
        is SharingProblem.NotAnswering -> for (device in problem.devices) {
            Notice(PageboardIcons.TriangleAlert, stringResource(Res.string.sync_problem_not_answering, deviceName(device), device.address))
        }
        is SharingProblem.NobodyFound -> {
            val settings = localNetworkSettings()?.let { stringResource(it) }
            val text = if (settings != null) stringResource(Res.string.sync_problem_nobody_settings, settings) else stringResource(Res.string.sync_problem_nobody)
            val internet = if (problem.internetOff) " " + stringResource(Res.string.sync_problem_nobody_internet) else ""
            Notice(PageboardIcons.Info, text + internet)
        }
        SharingProblem.PermissionDenied, SharingProblem.NoLocalNetwork -> Notice(PageboardIcons.WifiOff, problemLine(problem), error = problem == SharingProblem.PermissionDenied)
        is SharingProblem.MissingConfiguration, is SharingProblem.DiscoveryFailed -> Notice(PageboardIcons.TriangleAlert, problemLine(problem), error = true)
    }
}

@Composable
private fun deviceName(device: LanDevice): String = device.name.ifBlank { stringResource(Res.string.sync_device_unnamed) }

/**
 * The result of the check of the connection ([report], `null` while it runs; [started] false:
 * the local network could not be used), each line copyable as text for whoever helps.
 */
@Composable
internal fun CheckDialog(report: LanCheck?, started: Boolean, onDismiss: () -> Unit) {
    val lines = if (report == null) emptyList() else checkLines(report, started)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.sync_check_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (report == null && started) {
                    CircularProgressIndicator(modifier = Modifier.heightIn(max = 24.dp))
                    Text(stringResource(Res.string.sync_check_running), style = MaterialTheme.typography.bodyMedium)
                }
                for ((text, ok) in lines) {
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.sync_close)) } },
        dismissButton = {
            if (lines.isNotEmpty()) {
                @Suppress("DEPRECATION") // The Clipboard API has no common plain text entry
                val clipboard = LocalClipboardManager.current
                TextButton(onClick = { clipboard.setText(AnnotatedString(lines.joinToString("\n") { it.first })) }) {
                    Text(stringResource(Res.string.sync_check_copy))
                }
            }
        }
    )
}

/** The lines of [report], each with whether it is fine. */
@Composable
private fun checkLines(report: LanCheck, started: Boolean): List<Pair<String, Boolean>> = buildList {
    if (!started) {
        add(stringResource(Res.string.sync_check_not_started) to false)
    }
    // What the discovery reports, in the words of the notices
    for (problem in sharingProblems(report.problems, emptyList(), connected = true, lookedLong = false, internetOff = false)) {
        add(problemLine(problem) to false)
    }
    if (!started) return@buildList
    add(
        if (report.listening) stringResource(Res.string.sync_check_listening, report.port?.toString() ?: "?") to true
        else stringResource(Res.string.sync_check_not_listening) to false
    )
    val at = report.announcedAt
    add(
        when {
            at == null -> stringResource(Res.string.sync_check_not_announced) to false
            report.announcedReachable == true -> stringResource(Res.string.sync_check_announced, at) to true
            else -> stringResource(Res.string.sync_check_announced_stale, at) to false
        }
    )
    if (report.devices.isEmpty()) add(stringResource(Res.string.sync_check_nobody) to false)
    for (check in report.devices) add(deviceLine(check))
}

@Composable
private fun deviceLine(check: DeviceCheck): Pair<String, Boolean> {
    val device = check.device
    val name = deviceName(device)
    return when {
        device.otherVersion -> stringResource(Res.string.sync_check_device_other_version, name, device.address) to false
        !check.reachable -> stringResource(Res.string.sync_check_device_failed, name, device.address, check.failure ?: "?") to false
        !device.shared -> stringResource(Res.string.sync_check_device_unshared, name, device.address) to false
        else -> stringResource(Res.string.sync_check_device_ok, name, device.address) to true
    }
}

@Composable
private fun problemLine(problem: SharingProblem): String {
    val settings = localNetworkSettings()?.let { stringResource(it) }
    return when (problem) {
        SharingProblem.PermissionDenied ->
            if (settings != null) stringResource(Res.string.sync_problem_permission, settings) else stringResource(Res.string.sync_problem_permission_generic)
        is SharingProblem.MissingConfiguration -> stringResource(Res.string.sync_problem_configuration, problem.missing.joinToString(", "))
        SharingProblem.NoLocalNetwork -> stringResource(Res.string.sync_problem_no_network)
        is SharingProblem.DiscoveryFailed ->
            if (settings != null) stringResource(Res.string.sync_problem_discovery_settings, problem.detail, settings)
            else stringResource(Res.string.sync_problem_discovery, problem.detail)
        // Told per device by the notices; the check lists the devices itself
        is SharingProblem.NotAnswering -> problem.devices.joinToString("\n") { "${it.name} (${it.address})" }
        is SharingProblem.NobodyFound -> stringResource(Res.string.sync_problem_nobody)
    }
}
