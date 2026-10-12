package com.qq.closie.life.finance

/** One tree interpretation for picking, filtering and analytics. Malformed parents cannot loop. */
class FinanceCategoryTree(categories: List<FinanceCategoryEntity>) {
    private val byId = categories.associateBy { it.id }
    private val children = categories.groupBy { it.parentId }
    fun node(id: String?) = byId[id]
    fun children(id: String?) = children[id].orEmpty().sortedBy { it.name }
    fun matchingChildren(parentId: String?, name: String) = children(parentId).filter { it.name.trim() == name.trim() }
    fun hasValidAncestry(id: String): Boolean = path(id).firstOrNull()?.parentId == null && node(id) != null
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
        return proposalPath(parent, child)?.let(::resolvePath)
    }
    fun resolvePath(value: String): FinanceCategoryEntity? = splitPath(value)?.let(::resolvePath)
    fun resolvePath(segments: List<String>): FinanceCategoryEntity? {
        if (segments.isEmpty()) return null
        val seen = mutableSetOf<String>()
        var selected: FinanceCategoryEntity? = null
        for (segment in segments) {
            val name = singleName(segment) ?: return null
            val next = matchingChildren(selected?.id, name).singleOrNull() ?: return null
            if (!seen.add(next.id) || !hasValidAncestry(next.id)) return null
            selected = next
        }
        return selected
    }

    companion object {
        private val separator = Regex("[/›]")
        fun splitPath(value: String): List<String>? {
            if (value.isBlank()) return emptyList()
            return value.split(separator).map(String::trim).takeIf { names -> names.all(String::isNotBlank) }
        }
        fun singleName(value: String): String? = splitPath(value)?.singleOrNull()
        /** Existing v4 fields remain a first segment plus the rest; no new persisted path field. */
        fun proposalPath(parent: String, child: String = ""): List<String>? {
            val root = splitPath(parent) ?: return null
            val rest = splitPath(child) ?: return null
            return if (root.isEmpty() && rest.isNotEmpty()) null else root + rest
        }
        fun proposalFields(path: String): Pair<String, String> {
            val segments = requireNotNull(splitPath(path)) { "分类路径含有空层级" }
            require(segments.isNotEmpty()) { "请填写分类路径" }
            return segments.first() to segments.drop(1).joinToString("/")
        }
        fun displayProposalPath(parent: String, child: String = ""): String? =
            proposalPath(parent, child)?.takeIf { it.isNotEmpty() }?.joinToString(" › ")
    }
}
