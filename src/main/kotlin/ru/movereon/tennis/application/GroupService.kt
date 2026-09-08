package ru.movereon.tennis.application

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import ru.movereon.tennis.core.*
import ru.movereon.tennis.storage.*
import java.sql.Connection
import java.sql.ResultSet
import java.time.Clock
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId

/** Application boundary: the Telegram adapter must provide verified membership, never user-supplied claims. */
class GroupService(private val accounting: SqliteAccountingStore, private val clock: Clock = Clock.systemUTC()) {
    private val json = Json { encodeDefaults = true }
    private val attendanceCache = object : LinkedHashMap<String,Pair<Long,List<String>>>(16,0.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String,Pair<Long,List<String>>>?) = size > 64
    }
    private data class Change(val entityId: String, val version: Long, val before: String?, val after: String, val financial: Receipt? = null)

    fun execute(member: VerifiedGroupMember, commandId: String, input: WorkflowCommand,
        absentMember: VerifiedAbsentMember? = null): WorkflowReceipt {
        checkId(commandId)
        val payload = json.encodeToString(input)
        val command = json.decodeFromString<WorkflowCommand>(payload)
        return accounting.writeTransaction { connection ->
            val previous = query(connection, "SELECT actor_user_id,payload_json,result_json FROM workflow_commands WHERE group_id=? AND command_id=?", member.groupId, commandId) {
                Triple(it.getLong(1), it.getString(2), it.getString(3))
            }.singleOrNull()
            if (previous != null) {
                checkAccounting(previous.first == member.userId && previous.second == payload, ErrorCode.COMMAND_CONFLICT, "Workflow command was reused with different input")
                return@writeTransaction json.decodeFromString<WorkflowReceipt>(previous.third)
            }
            if (command !is WorkflowCommand.CreateGroup) groupZone(connection, member.groupId)
            val change = apply(connection, member, commandId, command, absentMember)
            update(connection, "INSERT INTO workflow_audit(group_id,command_id,actor_user_id,kind,entity_id,before_json,after_json,recorded_at) VALUES(?,?,?,?,?,?,?,?)",
                member.groupId, commandId, member.userId, json.parseToJsonElement(payload).jsonObject.getValue("type").jsonPrimitive.content,
                change.entityId, change.before, change.after, clock.instant().toString())
            val auditId = query(connection, "SELECT last_insert_rowid()") { it.getLong(1) }.single()
            val result = WorkflowReceipt(auditId, change.version, change.financial?.sequence, change.financial?.entityVersion)
            update(connection, "INSERT INTO workflow_commands(group_id,command_id,actor_user_id,payload_json,result_json,audit_id) VALUES(?,?,?,?,?,?)",
                member.groupId, commandId, member.userId, payload, json.encodeToString(result), auditId)
            result
        }
    }

    fun participants(member: VerifiedGroupMember, includeInactive: Boolean = false): List<ParticipantBalance> = accounting.readTransaction { connection ->
        groupZone(connection, member.groupId)
        val balances = accounting.balancesInTransaction(connection, member.groupId)
        query(connection, "SELECT * FROM participants WHERE group_id=? ORDER BY display_name,participant_id", member.groupId, map = ::profile)
            .filter { includeInactive || it.active }.map { ParticipantBalance(it, balances.getOrDefault(ParticipantId(it.id), 0)) }
    }

    fun settlementPlan(member: VerifiedGroupMember): List<SuggestedTransfer> = accounting.readTransaction { connection ->
        groupZone(connection, member.groupId)
        suggestTransfers(accounting.balancesInTransaction(connection, member.groupId))
    }

    fun timeZone(member: VerifiedGroupMember): String = accounting.readTransaction { groupZone(it, member.groupId) }
    fun canManage(member: VerifiedGroupMember): Boolean = accounting.readTransaction { groupZone(it, member.groupId); isOrganizer(it, member) }

    fun draft(member: VerifiedGroupMember, id: String): TrainingDraft = accounting.readTransaction { connection ->
        groupZone(connection, member.groupId)
        draft(connection, member.groupId, id)
    }

    fun drafts(member: VerifiedGroupMember, includeFinished: Boolean = false): List<TrainingDraft> = accounting.readTransaction { connection ->
        groupZone(connection, member.groupId)
        query(connection, "SELECT * FROM training_drafts WHERE group_id=? ORDER BY occurred_on,draft_id", member.groupId, map = ::draftRow)
            .filter { includeFinished || it.status in setOf(DraftStatus.DRAFT, DraftStatus.EDITING) }
    }

    /** Revision and source data are read in the same SQLite snapshot. */
    fun attendanceOrder(member: VerifiedGroupMember): List<String> = accounting.readTransaction { c ->
        groupZone(c,member.groupId)
        val revision = query(c,"SELECT COALESCE(MAX(audit_id),0) FROM workflow_audit WHERE group_id=? AND kind IN ('add_participant','rename_participant','post_draft','cancel_draft','commit_draft')",member.groupId) { it.getLong(1) }.single()
        synchronized(attendanceCache) { attendanceCache[member.groupId]?.takeIf { it.first==revision }?.second }
            ?.let { return@readTransaction it }
        val counts = query(c,"SELECT published_json FROM training_drafts WHERE group_id=? AND status IN ('POSTED','EDITING') AND published_json IS NOT NULL",member.groupId) {
            json.decodeFromString<DraftContent>(it.getString(1))
        }.flatMap { it.players.filterNot { p -> p.plusOne }.map { p -> p.participantId }.distinct() }.groupingBy { it }.eachCount()
        val collator = java.text.Collator.getInstance(java.util.Locale.forLanguageTag("ru"))
        val ids = query(c,"SELECT * FROM participants WHERE group_id=?",member.groupId,map=::profile)
            .sortedWith(compareByDescending<ParticipantProfile> { counts[it.id] ?: 0 }
                .thenComparator { a,b -> collator.compare(a.name,b.name) }.thenBy { it.id }).map { it.id }
        synchronized(attendanceCache) { attendanceCache[member.groupId] = revision to ids }
        ids
    }

    fun audit(member: VerifiedGroupMember): List<WorkflowAudit> = accounting.readTransaction { connection ->
        groupZone(connection, member.groupId)
        query(connection, "SELECT * FROM workflow_audit WHERE group_id=? ORDER BY audit_id", member.groupId) {
            WorkflowAudit(it.getLong("audit_id"), it.getLong("actor_user_id"), it.getString("kind"), it.getString("entity_id"),
                it.getString("before_json"), it.getString("after_json"), it.getString("recorded_at"))
        }
    }

    private fun apply(c: Connection, m: VerifiedGroupMember, commandId: String, command: WorkflowCommand, absent: VerifiedAbsentMember?): Change = when (command) {
        is WorkflowCommand.CreateGroup -> {
            checkAccounting(m.isTelegramAdmin, ErrorCode.FORBIDDEN, "Only a verified chat administrator can initialize the group")
            zone(command.timeZone)
            checkAccounting(query(c, "SELECT group_id FROM app_groups WHERE group_id=?", m.groupId) { it.getString(1) }.isEmpty(), ErrorCode.INVALID_STATE, "Group already exists")
            update(c, "INSERT INTO app_groups(group_id,time_zone,created_by) VALUES(?,?,?)", m.groupId, command.timeZone, m.userId)
            update(c, "INSERT INTO organizers(group_id,user_id) VALUES(?,?)", m.groupId, m.userId)
            // Existing version-1 accounting IDs are preserved; the organizer can rename these profiles.
            accounting.balancesInTransaction(c, m.groupId).keys.forEach { id ->
                update(c, "INSERT INTO participants VALUES(?,?,?,?,?,?)", m.groupId, id.value, id.value.take(100), null, 1, 1)
            }
            Change(m.groupId, 1, null, json.encodeToString(command))
        }
        is WorkflowCommand.AddParticipant -> {
            checkId(command.id); name(command.name)
            checkAccounting(findProfile(c, m.groupId, command.id) == null, ErrorCode.INVALID_STATE, "Participant already exists")
            val profile = ParticipantProfile(command.id, command.name.trim(), null, true, 1)
            update(c, "INSERT INTO participants VALUES(?,?,?,?,?,?)", m.groupId, profile.id, profile.name, null, 1, 1)
            Change(profile.id, 1, null, json.encodeToString(profile))
        }
        is WorkflowCommand.RenameParticipant -> {
            val old = participant(c, m.groupId, command.id, command.expectedVersion)
            checkAccounting(old.telegramUserId == m.userId || isOrganizer(c, m), ErrorCode.FORBIDDEN, "Only the participant or an organizer may rename this profile")
            name(command.name)
            saveProfile(c, m.groupId, old, old.copy(name = command.name.trim(), version = next(old.version)))
        }
        is WorkflowCommand.LinkSelf -> {
            val old = participant(c, m.groupId, command.id, command.expectedVersion)
            checkAccounting(old.telegramUserId == null, ErrorCode.INVALID_STATE, "Participant is already linked")
            checkLinkAvailable(c, m.groupId, m.userId, old.id)
            saveProfile(c, m.groupId, old, old.copy(telegramUserId = m.userId, version = next(old.version)))
        }
        is WorkflowCommand.CorrectLink -> {
            organizer(c, m)
            val old = participant(c, m.groupId, command.id, command.expectedVersion)
            command.userId?.let { user ->
                checkAccounting(user > 0, ErrorCode.INVALID_INPUT, "User ID must be positive")
                checkLinkAvailable(c, m.groupId, user, old.id)
            }
            saveProfile(c, m.groupId, old, old.copy(telegramUserId = command.userId, version = next(old.version)))
        }
        is WorkflowCommand.SetActive -> {
            organizer(c, m)
            val old = participant(c, m.groupId, command.id, command.expectedVersion)
            saveProfile(c, m.groupId, old, old.copy(active = command.active, version = next(old.version)))
        }
        is WorkflowCommand.AppointOrganizer -> {
            organizer(c, m)
            checkAccounting(linkedProfile(c, m.groupId, command.userId) != null, ErrorCode.INVALID_INPUT, "Select a linked participant in this group")
            val existed = query(c, "SELECT user_id FROM organizers WHERE group_id=? AND user_id=?", m.groupId, command.userId) { it.getLong(1) }.isNotEmpty()
            update(c, "INSERT OR IGNORE INTO organizers(group_id,user_id) VALUES(?,?)", m.groupId, command.userId)
            Change("user:${command.userId}", 1, if (existed) json.encodeToString(command) else null, json.encodeToString(command))
        }
        is WorkflowCommand.CreateDraft -> {
            checkId(command.id); date(command.date)
            checkAccounting(findDraft(c, m.groupId, command.id) == null, ErrorCode.INVALID_STATE, "Draft already exists")
            val created = TrainingDraft(command.id, m.userId, 1, DraftStatus.DRAFT, 0, DraftContent(command.date), null)
            update(c, "INSERT INTO training_drafts VALUES(?,?,?,?,?,?,?,?,?)", m.groupId, created.id, created.createdBy, created.version,
                created.status.name, created.financialVersion, created.content.date, json.encodeToString(created.content), null)
            Change(created.id, created.version, null, json.encodeToString(created))
        }
        is WorkflowCommand.SaveDraft -> {
            val old = draft(c, m.groupId, command.id, command.expectedVersion)
            checkAccounting(old.status != DraftStatus.CANCELLED, ErrorCode.INVALID_STATE, "Cancelled draft cannot be edited")
            validateContent(c, m.groupId, command.content)
            val byKey = command.content.players.associateBy { it.participantId to it.plusOne }
            val oldKeys = old.content.players.map { it.participantId to it.plusOne }
            val ordered = oldKeys.mapNotNull { byKey[it] } + command.content.players.filter { (it.participantId to it.plusOne) !in oldKeys }
            val status = if (old.financialVersion > 0) DraftStatus.EDITING else DraftStatus.DRAFT
            saveDraft(c, m.groupId, old, old.copy(version = next(old.version), status = status, content = command.content.copy(players = ordered)))
        }
        is WorkflowCommand.CommitDraft -> {
            val old = findDraft(c,m.groupId,command.id)
            version(command.expectedVersion,old?.version ?: 0)
            val before = old?.let { json.encodeToString(it) }
            if(old == null) apply(c,m,commandId,WorkflowCommand.CreateDraft(command.id,command.content.date),absent)
            var current = draft(c,m.groupId,command.id)
            checkAccounting(current.status != DraftStatus.CANCELLED,ErrorCode.INVALID_STATE,"Draft is cancelled")
            validateContent(c,m.groupId,command.content)
            if(current.content != command.content) {
                apply(c,m,commandId,WorkflowCommand.SaveDraft(command.id,current.version,command.content),absent)
                current = draft(c,m.groupId,command.id)
            }
            val result = if(command.post) apply(c,m,commandId,WorkflowCommand.PostDraft(command.id,current.version),absent)
                else Change(current.id,current.version,before,json.encodeToString(current))
            result.copy(before=before)
        }
        is WorkflowCommand.PostDraft -> {
            val old = draft(c, m.groupId, command.id, command.expectedVersion)
            // Any verified member of this group may correct a posted training.
            if(old.financialVersion == 0L) draftOwner(c, m, old)
            checkAccounting(old.status in setOf(DraftStatus.DRAFT, DraftStatus.EDITING), ErrorCode.INVALID_STATE, "No draft changes to post")
            validateContent(c, m.groupId, old.content)
            notFuture(c, m.groupId, old.content.date)
            val financial = accounting.executeInTransaction(c, m.groupId, "workflow:$commandId", actor(c, m),
                Command.PostTraining(old.id, old.content.training(), old.financialVersion), OperationDetails(date(old.content.date)))
            saveDraft(c, m.groupId, old, old.copy(version = next(old.version), status = DraftStatus.POSTED,
                financialVersion = financial.entityVersion, publishedContent = old.content), financial)
        }
        is WorkflowCommand.DiscardChanges -> {
            val old = draft(c, m.groupId, command.id, command.expectedVersion)
            draftOwner(c, m, old)
            checkAccounting(old.status == DraftStatus.EDITING, ErrorCode.INVALID_STATE, "No working changes to discard")
            saveDraft(c, m.groupId, old, old.copy(version = next(old.version), status = DraftStatus.POSTED,
                content = requireNotNull(old.publishedContent)))
        }
        is WorkflowCommand.CancelDraft -> {
            val old = draft(c, m.groupId, command.id, command.expectedVersion)
            draftOwner(c, m, old)
            checkAccounting(old.status != DraftStatus.CANCELLED, ErrorCode.INVALID_STATE, "Draft is already cancelled")
            val financial = if (old.financialVersion == 0L) null else accounting.executeInTransaction(c, m.groupId, "workflow:$commandId",
                actor(c, m), Command.CancelTraining(old.id, old.financialVersion), OperationDetails(date(old.publishedContent!!.date)))
            saveDraft(c, m.groupId, old, old.copy(version = next(old.version), status = DraftStatus.CANCELLED,
                financialVersion = financial?.entityVersion ?: 0), financial)
        }
        is WorkflowCommand.RecordTransfer -> {
            val from = participant(c, m.groupId, command.from)
            val to = participant(c, m.groupId, command.to)
            notFuture(c, m.groupId, command.date)
            val author = transferActor(c, m, from, to, command.onBehalfOf, absent)
            if (!command.allowSimilar) {
                val similar = query(c, "SELECT command_json,details_json FROM operations WHERE group_id=? AND kind='transfer_recorded'", m.groupId) {
                    StorageCodec.decodeCommand(it.getString(1)) to StorageCodec.decodeDetails(it.getString(2))
                }.mapNotNull { (input, details) ->
                    val old = input as? Command.RecordTransfer
                    if (old != null && old.from.value == from.id && old.to.value == to.id && old.amount == command.amount &&
                        details.occurredOn == date(command.date) && accounting.transferInTransaction(c, m.groupId, old.entityId)?.status != TransferStatus.CANCELLED) old.entityId else null
                }
                if (similar.isNotEmpty()) throw SimilarTransferFound(similar)
            }
            val financial = accounting.executeInTransaction(c, m.groupId, "workflow:$commandId", author,
                Command.RecordTransfer(command.id, ParticipantId(from.id), ParticipantId(to.id), command.amount),
                OperationDetails(date(command.date), command.note))
            Change(command.id, financial.entityVersion, null, json.encodeToString(command), financial)
        }
        is WorkflowCommand.ChangeTransfer -> {
            val old = accounting.transferInTransaction(c, m.groupId, command.id)
                ?: throw AccountingException(ErrorCode.INVALID_STATE, "Unknown transfer")
            val from = participant(c, m.groupId, old.from.value)
            val to = participant(c, m.groupId, old.to.value)
            val author = transferActor(c, m, from, to, command.onBehalfOf, absent,
                allowExistingReviewer = command.action != TransferIntent.REVIEW && old.reviewInitiator == "telegram:${m.userId}")
            val financial = accounting.executeInTransaction(c, m.groupId, "workflow:$commandId", author,
                Command.ChangeTransfer(command.id, command.expectedVersion, TransferAction.valueOf(command.action.name)))
            val updated = requireNotNull(accounting.transferInTransaction(c, m.groupId, command.id))
            fun state(value: TransferSnapshot) = buildJsonObject {
                put("status", value.status.name); put("version", value.version); put("reviewInitiator", value.reviewInitiator)
                put("from", value.from.value); put("to", value.to.value); put("amount", value.amount)
            }.toString()
            Change(command.id, financial.entityVersion, state(old), state(updated), financial)
        }
    }

    private fun transferActor(c: Connection, m: VerifiedGroupMember, from: ParticipantProfile, to: ParticipantProfile,
        onBehalfOf: String?, absent: VerifiedAbsentMember?, allowExistingReviewer: Boolean = false): Actor {
        val own = linkedProfile(c, m.groupId, m.userId)
        if (onBehalfOf == null || onBehalfOf == own?.id) {
            checkAccounting(own != null && own.id in listOf(from.id, to.id), ErrorCode.FORBIDDEN, "You must be a transfer party or explicitly represent one")
            return Actor("telegram:${m.userId}", ParticipantId(own!!.id))
        }
        organizer(c, m)
        val represented = listOf(from, to).singleOrNull { it.id == onBehalfOf }
            ?: throw AccountingException(ErrorCode.FORBIDDEN, "Representative must select a transfer party")
        val verifiedAbsent = absent?.groupId == m.groupId && absent?.userId == represented.telegramUserId
        checkAccounting(represented.telegramUserId == null || verifiedAbsent || allowExistingReviewer, ErrorCode.FORBIDDEN, "Representation requires an unlinked or verified absent participant")
        return Actor("telegram:${m.userId}", ParticipantId(represented.id))
    }

    private fun validateContent(c: Connection, group: String, content: DraftContent) {
        date(content.date)
        validateDraftTraining(content.training())
        (content.players.map { it.participantId } + content.payments.map { it.participantId }).distinct().forEach { participant(c, group, it) }
    }

    private fun saveProfile(c: Connection, group: String, old: ParticipantProfile, next: ParticipantProfile): Change {
        update(c, "UPDATE participants SET display_name=?,telegram_user_id=?,active=?,version=? WHERE group_id=? AND participant_id=?",
            next.name, next.telegramUserId, if (next.active) 1 else 0, next.version, group, next.id)
        return Change(next.id, next.version, json.encodeToString(old), json.encodeToString(next))
    }

    private fun saveDraft(c: Connection, group: String, old: TrainingDraft, next: TrainingDraft, financial: Receipt? = null): Change {
        update(c, "UPDATE training_drafts SET version=?,status=?,financial_version=?,occurred_on=?,content_json=?,published_json=? WHERE group_id=? AND draft_id=?",
            next.version, next.status.name, next.financialVersion, next.content.date, json.encodeToString(next.content),
            next.publishedContent?.let { json.encodeToString(it) }, group, next.id)
        return Change(next.id, next.version, json.encodeToString(old), json.encodeToString(next), financial)
    }

    private fun participant(c: Connection, group: String, id: String, expected: Long? = null): ParticipantProfile {
        checkId(id)
        val profile = findProfile(c, group, id) ?: throw AccountingException(ErrorCode.INVALID_INPUT, "Unknown participant in this group")
        if (expected != null) version(expected, profile.version)
        return profile
    }
    private fun findProfile(c: Connection, group: String, id: String): ParticipantProfile? =
        query(c, "SELECT * FROM participants WHERE group_id=? AND participant_id=?", group, id, map = ::profile).singleOrNull()
    private fun linkedProfile(c: Connection, group: String, user: Long): ParticipantProfile? =
        query(c, "SELECT * FROM participants WHERE group_id=? AND telegram_user_id=?", group, user, map = ::profile).singleOrNull()
    private fun profile(row: ResultSet) = ParticipantProfile(row.getString("participant_id"), row.getString("display_name"),
        row.getString("telegram_user_id")?.toLong(), row.getInt("active") == 1, row.getLong("version"))
    private fun checkLinkAvailable(c: Connection, group: String, user: Long, id: String) =
        checkAccounting(linkedProfile(c, group, user)?.id.let { it == null || it == id }, ErrorCode.INVALID_STATE, "User is already linked to another participant")

    private fun draft(c: Connection, group: String, id: String, expected: Long? = null): TrainingDraft {
        checkId(id)
        val draft = findDraft(c, group, id) ?: throw AccountingException(ErrorCode.INVALID_INPUT, "Unknown training draft")
        if (expected != null) version(expected, draft.version)
        return draft
    }
    private fun findDraft(c: Connection, group: String, id: String): TrainingDraft? =
        query(c, "SELECT * FROM training_drafts WHERE group_id=? AND draft_id=?", group, id, map = ::draftRow).singleOrNull()
    private fun draftRow(row: ResultSet) = TrainingDraft(row.getString("draft_id"), row.getLong("created_by"), row.getLong("version"),
        DraftStatus.valueOf(row.getString("status")), row.getLong("financial_version"), json.decodeFromString(row.getString("content_json")),
        row.getString("published_json")?.let { json.decodeFromString<DraftContent>(it) })
    private fun draftOwner(c: Connection, m: VerifiedGroupMember, draft: TrainingDraft) =
        checkAccounting(draft.createdBy == m.userId || isOrganizer(c, m), ErrorCode.FORBIDDEN, "Only the creator or an organizer may post or cancel")
    private fun actor(c: Connection, m: VerifiedGroupMember) = Actor("telegram:${m.userId}", linkedProfile(c, m.groupId, m.userId)?.id?.let(::ParticipantId))
    private fun isOrganizer(c: Connection, m: VerifiedGroupMember) = query(c, "SELECT user_id FROM organizers WHERE group_id=? AND user_id=?", m.groupId, m.userId) { it.getLong(1) }.isNotEmpty()
    private fun organizer(c: Connection, m: VerifiedGroupMember) = checkAccounting(isOrganizer(c, m), ErrorCode.FORBIDDEN, "Organizer role is required")
    private fun groupZone(c: Connection, group: String): String = query(c, "SELECT time_zone FROM app_groups WHERE group_id=?", group) { it.getString(1) }.singleOrNull()
        ?: throw AccountingException(ErrorCode.INVALID_STATE, "Group is not initialized")
    private fun version(expected: Long, actual: Long) = checkAccounting(expected == actual, ErrorCode.STALE_VERSION, "Expected version $expected, current version $actual")
    private fun next(version: Long): Long { checkAccounting(version < Long.MAX_VALUE, ErrorCode.OUT_OF_RANGE, "Version limit reached"); return version + 1 }
    private fun name(value: String) = checkAccounting(value.trim().length in 1..100 && value.none { it.isISOControl() }, ErrorCode.INVALID_INPUT, "Name must contain 1 to 100 visible characters")
    private fun date(value: String): LocalDate = try { LocalDate.parse(value) } catch (_: DateTimeException) { throw AccountingException(ErrorCode.INVALID_INPUT, "Use an ISO date") }
    private fun zone(value: String): ZoneId = try { ZoneId.of(value) } catch (_: DateTimeException) { throw AccountingException(ErrorCode.INVALID_INPUT, "Unknown time zone") }
    private fun notFuture(c: Connection, group: String, value: String) =
        checkAccounting(date(value) <= LocalDate.now(clock.withZone(zone(groupZone(c, group)))), ErrorCode.INVALID_INPUT, "Future events cannot be posted")
    private fun update(c: Connection, sql: String, vararg args: Any?): Int = c.prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }; statement.executeUpdate()
    }
    private fun <T> query(c: Connection, sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> = c.prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(map(rows)) } }
    }
}
