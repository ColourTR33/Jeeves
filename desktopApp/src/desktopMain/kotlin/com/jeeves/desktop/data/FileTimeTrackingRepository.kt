package com.jeeves.desktop.data

import com.jeeves.shared.domain.*
import com.jeeves.shared.time.TimeTrackingManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

class FileTimeTrackingRepository : TimeTrackingRepository {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val timeDir: File get() { val d = File(System.getProperty("user.home"), "Jeeves/time"); d.mkdirs(); return d }
    private val projectsFile get() = File(timeDir, "projects.json")
    private val clientsFile get() = File(timeDir, "clients.json")
    private val settingsFile get() = File(timeDir, "reminder_settings.json")
    private fun entriesFile(date: String) = File(timeDir, "entries_$date.json")

    override suspend fun getProjects(): List<Project> = readList(projectsFile) { json.decodeFromString<PList>(it).items }
    override suspend fun saveProject(project: Project) {
        val list = getProjects().toMutableList()
        val i = list.indexOfFirst { it.id == project.id }
        if (i >= 0) list[i] = project else list.add(project)
        projectsFile.writeText(json.encodeToString(PList.serializer(), PList(list)))
    }
    override suspend fun deleteProject(id: String) { projectsFile.writeText(json.encodeToString(PList.serializer(), PList(getProjects().filter { it.id != id }))) }

    override suspend fun getClients(): List<Client> = readList(clientsFile) { json.decodeFromString<CList>(it).items }
    override suspend fun saveClient(client: Client) {
        val list = getClients().toMutableList()
        val i = list.indexOfFirst { it.id == client.id }
        if (i >= 0) list[i] = client else list.add(client)
        clientsFile.writeText(json.encodeToString(CList.serializer(), CList(list)))
    }

    override suspend fun getTimeEntries(startDate: String, endDate: String): List<TimeEntry> {
        val all = mutableListOf<TimeEntry>()
        var cur = startDate
        repeat(7) { all.addAll(getTimeEntriesForDate(cur)); cur = addDay(cur) }
        return all
    }

    override suspend fun getTimeEntriesForDate(date: String): List<TimeEntry> = readList(entriesFile(date)) { json.decodeFromString<EList>(it).items }

    override suspend fun getRunningEntry(): TimeEntry? {
        val today = TimeTrackingManager.epochToDateString(System.currentTimeMillis())
        return getTimeEntriesForDate(today).find { it.isRunning }
    }

    override suspend fun saveTimeEntry(entry: TimeEntry) {
        val list = getTimeEntriesForDate(entry.date).toMutableList()
        list.add(entry)
        entriesFile(entry.date).writeText(json.encodeToString(EList.serializer(), EList(list)))
    }

    override suspend fun updateTimeEntry(entry: TimeEntry) {
        val list = getTimeEntriesForDate(entry.date).toMutableList()
        val i = list.indexOfFirst { it.id == entry.id }
        if (i >= 0) { list[i] = entry; entriesFile(entry.date).writeText(json.encodeToString(EList.serializer(), EList(list))) }
    }

    override suspend fun deleteTimeEntry(id: String) {
        // Search all entry files for this ID (entries are stored per-day)
        val files = timeDir.listFiles()?.filter { it.name.startsWith("entries_") && it.name.endsWith(".json") } ?: return
        for (file in files) {
            try {
                val entries = json.decodeFromString<EList>(file.readText()).items
                if (entries.any { it.id == id }) {
                    val filtered = entries.filter { it.id != id }
                    file.writeText(json.encodeToString(EList.serializer(), EList(filtered)))
                    return
                }
            } catch (_: Exception) { /* skip corrupted files */ }
        }
    }

    override suspend fun getReminderSettings(): TimeReminderSettings = try {
        if (settingsFile.exists()) json.decodeFromString(settingsFile.readText()) else TimeReminderSettings()
    } catch (_: Exception) { TimeReminderSettings() }

    override suspend fun saveReminderSettings(settings: TimeReminderSettings) {
        settingsFile.writeText(json.encodeToString(TimeReminderSettings.serializer(), settings))
    }

    // Weekly planning
    private val plansFile get() = File(timeDir, "weekly_plans.json")

    override suspend fun getWeeklyPlan(weekStartDate: String): WeeklyPlan? {
        return getAllWeeklyPlans().find { it.weekStartDate == weekStartDate }
    }

    override suspend fun saveWeeklyPlan(plan: WeeklyPlan) {
        val plans = getAllWeeklyPlans().toMutableList()
        val i = plans.indexOfFirst { it.weekStartDate == plan.weekStartDate }
        if (i >= 0) plans[i] = plan else plans.add(plan)
        plansFile.writeText(json.encodeToString(WPList.serializer(), WPList(plans)))
    }

    override suspend fun deleteWeeklyPlan(weekStartDate: String) {
        val plans = getAllWeeklyPlans().filter { it.weekStartDate != weekStartDate }
        plansFile.writeText(json.encodeToString(WPList.serializer(), WPList(plans)))
    }

    override suspend fun getAllWeeklyPlans(): List<WeeklyPlan> =
        readList(plansFile) { json.decodeFromString<WPList>(it).items }

    // Backlog & Sprint
    private fun backlogFile(projectId: String) = File(timeDir, "backlog_$projectId.json")
    private fun sprintFile(weekStartDate: String) = File(timeDir, "sprint_$weekStartDate.json")

    override suspend fun getBacklogItems(projectId: String): List<BacklogItem> =
        readList(backlogFile(projectId)) { json.decodeFromString<BLList>(it).items }

    override suspend fun saveBacklogItems(projectId: String, items: List<BacklogItem>) {
        backlogFile(projectId).writeText(json.encodeToString(BLList.serializer(), BLList(items)))
    }

    override suspend fun getSprintItems(weekStartDate: String): List<SprintItem> =
        readList(sprintFile(weekStartDate)) { json.decodeFromString<SIList>(it).items }

    override suspend fun saveSprintItems(weekStartDate: String, items: List<SprintItem>) {
        sprintFile(weekStartDate).writeText(json.encodeToString(SIList.serializer(), SIList(items)))
    }

    private fun <T> readList(file: File, parse: (String) -> List<T>): List<T> = try { if (file.exists()) parse(file.readText()) else emptyList() } catch (_: Exception) { emptyList() }

    private fun addDay(d: String): String {
        val p = d.split("-").map { it.toInt() }
        var y = p[0]; var m = p[1]; var day = p[2]
        val dim = intArrayOf(0,31,28,31,30,31,30,31,31,30,31,30,31)
        if (y%4==0 && (y%100!=0||y%400==0)) dim[2]=29
        day++; if (day>dim[m]) { day=1; m++ }; if (m>12) { m=1; y++ }
        return String.format("%04d-%02d-%02d", y, m, day)
    }
}

@Serializable data class PList(val items: List<Project>)
@Serializable data class CList(val items: List<Client>)
@Serializable data class EList(val items: List<TimeEntry>)
@Serializable data class WPList(val items: List<WeeklyPlan>)
@Serializable data class BLList(val items: List<BacklogItem>)
@Serializable data class SIList(val items: List<SprintItem>)
