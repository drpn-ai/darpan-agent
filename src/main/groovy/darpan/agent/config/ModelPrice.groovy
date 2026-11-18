package darpan.agent.config

import groovy.transform.Immutable

/** DAR-BE-307. A model's price in dollars per million tokens (equal to micro-dollars per token). */
@Immutable
class ModelPrice {
    BigDecimal input
    BigDecimal output
    BigDecimal cacheRead
    BigDecimal cacheWrite

    /** The dearest price: the maximum of each component, so a response from an unpriced model is never under-counted. Null for none. */
    static ModelPrice max(Collection<ModelPrice> prices) {
        List<ModelPrice> list = prices?.findAll { it != null }
        if (!list) return null
        return new ModelPrice(
                list.collect { it.input }.max(), list.collect { it.output }.max(),
                list.collect { it.cacheRead }.max(), list.collect { it.cacheWrite }.max())
    }
}
