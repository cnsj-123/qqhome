package com.qq.closie.life.finance

/** One tree interpretation for picking, filtering and analytics. Malformed parents cannot loop. */
class FinanceCategoryTree(categories: List<FinanceCategoryEntity>) {
    private val byId = categories.associateBy { it.id }
    private val children = categories.groupBy { it.parentId }
    fun node(id: String?) = byId[id]
    fun children(id: String) = children[id].orEmpty().sortedBy { it.name }
    fun path(id: String?): List<FinanceCategoryEntity> {
        val result = mutableListOf<FinanceCategoryEntity>()
        val seen = mutableSetOf<String>()
        var current = node(id)
        while (current != null && seen.add(current.id)) {
            result.add(current)
            current = node(current.parentId)
        }
        return result.asReversed()
    }
    fun fullPath(id: String?): String? = path(id).takeIf { it.isNotEmpty() }?.joinToString(" › ") { it.name }
    fun root(id: String?): FinanceCategoryEntity? {
        val chain = path(id)
        val first = chain.firstOrNull() ?: return null
        // A cycle has no root: choose the same representative for all members.
        val repeatedAt = chain.indexOfFirst { it.id == first.parentId }
        return if (repeatedAt >= 0) chain.take(repeatedAt + 1).minByOrNull { it.id } else first
    }
    fun descendants(id: String): Set<String> {
        if (node(id) == null) return emptySet()
        val seen = linkedSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(id)
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (seen.add(next)) children(next).forEach { queue.add(it.id) }
        }
        return seen
    }
    fun roots(): List<FinanceCategoryEntity> = byId.keys.mapNotNull(::root).distinctBy { it.id }.sortedBy { it.name }
    fun validateParents() {
        val checked = mutableSetOf<String>()
        byId.keys.forEach { id ->
            val chain = mutableSetOf<String>()
            var next: String? = id
            while (next != null && next !in checked) {
                require(chain.add(next)) { "分类层级存在循环" }
                val node = requireNotNull(byId[next]) { "分类父级缺失" }
                next = node.parentId
            }
            checked.addAll(chain)
        }
    }
    fun find(parent: String, child: String = ""): FinanceCategoryEntity? {
        val direct = byId.values.find { it.parentId == null && it.name == parent }
        if (direct != null) return if (child.isBlank()) direct else children(direct.id).find { it.name == child }
        val names = (parent + if (child.isBlank()) "" else " › $child").split(Regex("\\s*[›/]\\s*")).filter(String::isNotBlank)
        return byId.values.singleOrNull { row -> path(row.id).map { it.name } == names }
    }
}
