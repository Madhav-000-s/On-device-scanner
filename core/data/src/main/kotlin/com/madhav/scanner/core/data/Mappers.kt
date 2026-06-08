package com.madhav.scanner.core.data

import com.madhav.scanner.core.data.entity.LineItemEntity
import com.madhav.scanner.core.data.entity.ScanEntity
import com.madhav.scanner.core.model.LineItem
import com.madhav.scanner.core.model.Money
import com.madhav.scanner.core.model.RowType
import com.madhav.scanner.core.model.Scan

fun ScanEntity.toDomain(items: List<LineItemEntity>): Scan = Scan(
    id = id,
    createdAt = createdAt,
    imagePath = imagePath,
    merchant = merchant,
    currency = currency,
    subtotal = subtotalCents?.let(::Money),
    tax = taxCents?.let(::Money),
    total = totalCents?.let(::Money),
    reconciled = reconciled,
    detectorVariant = detectorVariant,
    detectorSha = detectorSha,
    items = items.map { it.toDomain() },
)

fun LineItemEntity.toDomain(): LineItem = LineItem(
    id = id,
    ordinal = ordinal,
    // Persisted rows are always accepted item rows; classification of header/total/etc.
    // rows happens upstream in :core:parse and never reaches this table.
    rowType = RowType.ITEM,
    name = name,
    quantity = quantity,
    unitPrice = unitPriceCents?.let(::Money),
    totalPrice = totalPriceCents?.let(::Money),
    ocrConfidence = ocrConfidence,
    userEdited = userEdited,
)

fun Scan.toEntity(): ScanEntity = ScanEntity(
    id = id,
    createdAt = createdAt,
    imagePath = imagePath,
    merchant = merchant,
    currency = currency,
    subtotalCents = subtotal?.cents,
    taxCents = tax?.cents,
    totalCents = total?.cents,
    reconciled = reconciled,
    detectorVariant = detectorVariant,
    detectorSha = detectorSha,
)

fun LineItem.toEntity(scanId: String): LineItemEntity = LineItemEntity(
    id = id,
    scanId = scanId,
    ordinal = ordinal,
    name = name,
    quantity = quantity,
    unitPriceCents = unitPrice?.cents,
    totalPriceCents = totalPrice?.cents,
    ocrConfidence = ocrConfidence,
    userEdited = userEdited,
)
