package com.example.finance

import com.example.DebitParser

class AutoCategorizer(private val dao: FinanceDao) {

    data class CategoryMatch(
        val categoryId: Long,
        val autoCategorized: Boolean
    )

    suspend fun resolveCategory(sender: String, body: String): CategoryMatch {
        val uncategorizedId = dao.getCategoryByName("Uncategorized")?.id
            ?: return CategoryMatch(0, false)

        val senderKey = DebitParser.normalizeSenderKey(sender)
        if (senderKey.isNotEmpty()) {
            val memoryCategoryId = dao.getSenderCategory(senderKey)
            if (memoryCategoryId != null) {
                return CategoryMatch(memoryCategoryId, true)
            }
        }

        val keywordMatches = mutableSetOf<Long>()
        val haystack = "${sender.lowercase()} ${body.lowercase()}"
        val rules = dao.getKeywordRules()
        for (rule in rules) {
            if (haystack.contains(rule.pattern.lowercase())) {
                keywordMatches.add(rule.categoryId)
            }
        }
        seededKeywordMap.forEach { (pattern, categoryName) ->
            if (haystack.contains(pattern)) {
                dao.getCategoryByName(categoryName)?.id?.let { keywordMatches.add(it) }
            }
        }

        if (keywordMatches.size == 1) {
            return CategoryMatch(keywordMatches.first(), true)
        }

        return CategoryMatch(uncategorizedId, false)
    }

    suspend fun rememberSenderCategory(sender: String, categoryId: Long) {
        val senderKey = DebitParser.normalizeSenderKey(sender)
        if (senderKey.isEmpty()) return
        dao.upsertSenderMemory(
            SenderCategoryMemoryEntity(
                senderKey = senderKey,
                categoryId = categoryId,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun seedKeywordRulesIfEmpty() {
        if (dao.getKeywordRules().isNotEmpty()) return
        seededKeywordMap.forEach { (pattern, categoryName) ->
            val categoryId = dao.getCategoryByName(categoryName)?.id ?: return@forEach
            dao.insertKeywordRule(KeywordRuleEntity(pattern = pattern, categoryId = categoryId))
        }
    }

    companion object {
        val seededKeywordMap = mapOf(
            "swiggy" to "Food",
            "zomato" to "Food",
            "dominos" to "Food",
            "blinkit" to "Groceries",
            "zepto" to "Groceries",
            "bigbasket" to "Groceries",
            "petrol" to "Fuel",
            "fuel" to "Fuel",
            "hpcl" to "Fuel",
            "iocl" to "Fuel",
            "bpcl" to "Fuel",
            "bescom" to "Home",
            "electricity" to "Home",
            "broadband" to "Home",
            "rent" to "Home",
            "service center" to "Bike Maintenance",
            "garage" to "Bike Maintenance",
            "tyre" to "Bike Maintenance"
        )
    }
}
