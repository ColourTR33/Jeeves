package com.jeeves.shared.time

import com.jeeves.shared.domain.*

/**
 * Generates the weekly export prompt for Gemini.
 *
 * Core logic:
 * 1. Sums actual time for all completed tasks across all projects → TotalBillableHours
 * 2. For each project, sums its time entries → ProjectBillableHours
 * 3. Calculates prorated timecard overhead: (ProjectBillableHours / TotalBillableHours) * weeklyTimecards
 * 4. Adds prorated overhead to project's variable time → TotalProjectTime
 *
 * Uses BOTH time entries (from the timesheet) AND sprint items (from backlog planning).
 */
class WeeklyExportGenerator {

    data class ExportTask(
        val description: String,
        val estimatedHours: Double,
        val actualHours: Double,
        val status: String  // "COMPLETED" or "PLANNED"
    )

    data class ProjectExportData(
        val projectName: String,
        val completedTasks: List<ExportTask>,
        val plannedTasks: List<ExportTask>,
        val totalVariableHours: Double,
        val proratedOverheadHours: Double,
        val totalProjectHours: Double
    )

    /**
     * Generate the complete Gemini prompt from time entries and sprint data.
     *
     * @param projects All projects
     * @param timeEntries All time entries for the week (from the timesheet)
     * @param sprintItems Sprint items for the current week (from backlog)
     * @param backlogItems All backlog items (to find PLANNED items for next week)
     * @param settings Overhead settings
     * @param weekEndDate Friday date string for the subject line
     */
    fun generate(
        projects: List<Project>,
        timeEntries: List<TimeEntry>,
        sprintItems: List<SprintItem>,
        backlogItems: Map<String, List<BacklogItem>>,
        settings: TimeReminderSettings,
        weekEndDate: String
    ): String {
        val projectMap = projects.associateBy { it.id }
        val projectData = mutableListOf<ProjectExportData>()

        // Step 1: Calculate total billable hours from TIME ENTRIES (the actual logged hours)
        val entriesByProject = timeEntries
            .filter { entry ->
                val project = projectMap[entry.projectId]
                project != null && !project.isDistributed
            }
            .groupBy { it.projectId }

        val totalBillableHours = entriesByProject.values.sumOf { entries ->
            entries.sumOf { (it.durationMs ?: 0) / 3_600_000.0 }
        }

        // Step 2-4: For each project with logged hours, build export data
        for ((projectId, entries) in entriesByProject) {
            val project = projectMap[projectId] ?: continue

            val projectBillableHours = entries.sumOf { (it.durationMs ?: 0) / 3_600_000.0 }

            // Prorated timecard overhead
            val proratedOverhead = if (totalBillableHours > 0) {
                (projectBillableHours / totalBillableHours) * settings.weeklyTimecardsHours
            } else 0.0

            val totalProjectHours = projectBillableHours + proratedOverhead

            // Completed tasks: merge time entries with sprint items for richer descriptions
            val sprintForProject = sprintItems.filter { it.projectId == projectId && it.elapsedMinutes > 0 }
            val completed = mutableListOf<ExportTask>()

            // Add sprint items (which have estimates)
            for (sprint in sprintForProject) {
                completed.add(ExportTask(
                    description = sprint.title,
                    estimatedHours = sprint.allocatedMinutes / 60.0,
                    actualHours = sprint.elapsedMinutes / 60.0,
                    status = "COMPLETED"
                ))
            }

            // Add time entries that aren't linked to sprint items (direct timer/manual entries)
            val sprintTitles = sprintForProject.map { it.title.lowercase() }.toSet()
            for (entry in entries) {
                val desc = entry.taskDescription.ifEmpty { "General work" }
                // Skip if already covered by a sprint item with similar title
                if (sprintTitles.any { desc.lowercase().contains(it) || it.contains(desc.lowercase()) }) continue
                completed.add(ExportTask(
                    description = desc,
                    estimatedHours = 0.0,  // No estimate for ad-hoc entries
                    actualHours = (entry.durationMs ?: 0) / 3_600_000.0,
                    status = "COMPLETED"
                ))
            }

            // Deduplicate: if we have both sprint items and time entries, prefer sprint (has estimates)
            // Group by similar description, keep the one with the most info
            val deduped = completed.groupBy { it.description.lowercase().take(30) }
                .map { (_, tasks) -> tasks.maxByOrNull { it.estimatedHours + it.actualHours } ?: tasks.first() }

            // Planned for next week (backlog items still in BACKLOG or PLANNED status)
            val nextWeekItems = backlogItems[projectId]
                ?.filter { it.status == BacklogStatus.BACKLOG || it.status == BacklogStatus.PLANNED }
                ?.sortedBy { it.priority }
                ?.take(5)
                ?: emptyList()

            val planned = nextWeekItems.map { item ->
                ExportTask(
                    description = item.title,
                    estimatedHours = item.estimateMinutes / 60.0,
                    actualHours = 0.0,
                    status = "PLANNED"
                )
            }

            projectData.add(ProjectExportData(
                projectName = project.name,
                completedTasks = deduped,
                plannedTasks = planned,
                totalVariableHours = projectBillableHours,
                proratedOverheadHours = proratedOverhead,
                totalProjectHours = totalProjectHours
            ))
        }

        // Also include projects with only planned items (no work this week)
        for ((projectId, items) in backlogItems) {
            if (entriesByProject.containsKey(projectId)) continue
            val project = projectMap[projectId] ?: continue
            if (project.isDistributed) continue
            val planned = items
                .filter { it.status == BacklogStatus.BACKLOG || it.status == BacklogStatus.PLANNED }
                .sortedBy { it.priority }
                .take(5)
            if (planned.isEmpty()) continue

            projectData.add(ProjectExportData(
                projectName = project.name,
                completedTasks = emptyList(),
                plannedTasks = planned.map { ExportTask(it.title, it.estimateMinutes / 60.0, 0.0, "PLANNED") },
                totalVariableHours = 0.0,
                proratedOverheadHours = 0.0,
                totalProjectHours = 0.0
            ))
        }

        return buildPrompt(projectData, settings, weekEndDate)
    }

    /**
     * Backwards-compatible overload for callers that don't pass timeEntries.
     */
    fun generate(
        projects: List<Project>,
        sprintItems: List<SprintItem>,
        backlogItems: Map<String, List<BacklogItem>>,
        settings: TimeReminderSettings,
        weekEndDate: String
    ): String = generate(projects, emptyList(), sprintItems, backlogItems, settings, weekEndDate)

    private fun buildPrompt(
        projectData: List<ProjectExportData>,
        settings: TimeReminderSettings,
        weekEndDate: String
    ): String {
        val sb = StringBuilder()

        sb.appendLine("Act as an expert project manager and communications specialist. I am providing my tracked time and task list for the week. Draft a professional End-of-Week Summary Email for each project.")
        sb.appendLine()
        sb.appendLine("Format each email with:")
        sb.appendLine("- Subject Line: [Project Name] - Weekly Summary - $weekEndDate")
        sb.appendLine("- Total Time Logged: [Calculated total including prorated admin]")
        sb.appendLine("- Completed This Week: [List]")
        sb.appendLine("- Planned for Next Week: [List]")
        sb.appendLine()
        sb.appendLine("RAW DATA: Fixed Daily Admin: Standups (${settings.dailyStandupMinutes} mins/day), Email (${settings.dailyEmailMinutes} mins/day).")
        sb.appendLine()

        for (data in projectData.sortedByDescending { it.totalProjectHours }) {
            sb.appendLine("Project ${data.projectName}:")

            if (data.completedTasks.isNotEmpty()) {
                val completedStr = data.completedTasks.joinToString(", ") { task ->
                    if (task.estimatedHours > 0) {
                        "${task.description} (Est: ${formatHours(task.estimatedHours)}h | Act: ${formatHours(task.actualHours)}h)"
                    } else {
                        "${task.description} (Act: ${formatHours(task.actualHours)}h)"
                    }
                }
                sb.appendLine("- Completed: $completedStr")
            } else {
                sb.appendLine("- Completed: None")
            }

            sb.appendLine("- Total Project Time (Variable + Prorated Admin): ${formatHours(data.totalProjectHours)} hours")

            if (data.plannedTasks.isNotEmpty()) {
                val plannedStr = data.plannedTasks.joinToString(", ") { it.description }
                sb.appendLine("- Next Week: $plannedStr")
            } else {
                sb.appendLine("- Next Week: TBD")
            }

            sb.appendLine()
        }

        return sb.toString().trimEnd()
    }

    private fun formatHours(hours: Double): String = String.format("%.1f", hours)
}
