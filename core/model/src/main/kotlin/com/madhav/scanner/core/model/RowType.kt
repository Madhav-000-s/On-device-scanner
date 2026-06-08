package com.madhav.scanner.core.model

/** Row classification used by the parsing pipeline (DESIGN.md §7.3 step 4). */
enum class RowType {
    HEADER,
    ITEM,
    MODIFIER,
    SUBTOTAL,
    TAX,
    TIP,
    TOTAL,
    FOOTER,
}
