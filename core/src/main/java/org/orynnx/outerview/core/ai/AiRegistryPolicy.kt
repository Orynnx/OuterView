package org.orynnx.outerview.core.ai

data class AiRegistryRecord(val id: String, val fields: Map<String, String?>)

/** Pure guards for complete registry snapshots; a missing entry is never assumed to be stale. */
object AiRegistryPolicy {
    fun requireReady(disk: List<AiRegistryRecord>, runtime: List<AiRegistryRecord>) {
        requireValidRecords(disk)
        requireValidRecords(runtime)
        check(disk == runtime) {
            "系统应用索引内容或顺序尚未同步，未执行修改；请稍后刷新"
        }
    }

    fun requireTransition(
        before: List<AiRegistryRecord>,
        after: List<AiRegistryRecord>,
        addedId: String? = null,
        removedId: String? = null,
    ) {
        requireValidRecords(before)
        requireValidRecords(after)
        addedId?.let { requireValidIds(setOf(it)) }
        removedId?.let { requireValidIds(setOf(it)) }
        require(addedId == null || addedId != removedId) { "新增与移除不能指定同一应用 ID" }
        if (addedId != null) {
            require(before.none { it.id == addedId }) { "新增应用 ID 已存在" }
            check(after.count { it.id == addedId } == 1) { "新增应用未被唯一登记，操作未确认成功" }
        }
        val retainedBefore = before.filterNot { it.id == removedId }
        val retainedAfter = after.filterNot { it.id == addedId }
        check(retainedBefore == retainedAfter) {
            "系统应用变更未完整保留既有应用的内容或顺序，操作未确认成功"
        }
    }

    fun requireReady(diskIds: Set<String>, runtimeIds: Set<String>) {
        requireValidIds(diskIds)
        requireValidIds(runtimeIds)
        check(diskIds == runtimeIds) {
            "系统应用索引尚未同步，未执行修改；请稍后刷新"
        }
    }

    fun requireTransition(
        beforeIds: Set<String>,
        afterIds: Set<String>,
        addedId: String? = null,
        removedId: String? = null,
    ) {
        requireValidIds(beforeIds)
        requireValidIds(afterIds)
        addedId?.let { requireValidIds(setOf(it)) }
        removedId?.let { requireValidIds(setOf(it)) }
        val expected = beforeIds.toMutableSet()
        addedId?.let(expected::add)
        removedId?.let(expected::remove)
        check(afterIds == expected) {
            "系统应用变更影响了预期之外的记录，操作未确认成功"
        }
    }

    private fun requireValidIds(ids: Set<String>) {
        require(ids.all(String::isNotBlank)) { "应用 ID 不能为空" }
    }

    private fun requireValidRecords(records: List<AiRegistryRecord>) {
        val ids = records.map { it.id }
        requireValidIds(ids.toSet())
        require(ids.size == ids.toSet().size) { "应用 ID 重复" }
    }
}
