package com.example.finance

/**
 * Keyword rules ("swiggy" → Food). Since tagging is the owner's call, a keyword match
 * is only a hint that puts that category first in the suggestions; it never tags.
 */
class AutoCategorizer(private val dao: FinanceDao) {

    suspend fun keywordHint(sender: String, body: String): Long? {
        val matches = mutableSetOf<Long>()
        val haystack = "${sender.lowercase()} ${body.lowercase()}"
        for (rule in dao.getKeywordRules()) {
            if (haystack.contains(rule.pattern.lowercase())) matches.add(rule.categoryId)
        }
        return matches.singleOrNull()
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
