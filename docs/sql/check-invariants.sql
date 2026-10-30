-- A correct committed database returns zero rows. Retained released allocations still sum to the request.
WITH active_totals AS (
    SELECT a.batch_id, SUM(a.quantity) AS quantity
    FROM reservation_allocation a JOIN reservation r ON r.id = a.reservation_id
    WHERE r.status = 'ACTIVE' GROUP BY a.batch_id
), reservation_totals AS (
    SELECT reservation_id, SUM(quantity) AS quantity FROM reservation_allocation GROUP BY reservation_id
)
SELECT 'batch_reserved' AS violation, b.id::text AS entity_id,
       COALESCE(a.quantity, 0) AS expected, b.reserved::bigint AS actual
FROM stock_batch b LEFT JOIN active_totals a ON a.batch_id = b.id
WHERE b.reserved <> COALESCE(a.quantity, 0)
UNION ALL
SELECT 'reservation_total', r.id::text, r.quantity::bigint, COALESCE(a.quantity, 0)
FROM reservation r LEFT JOIN reservation_totals a ON a.reservation_id = r.id
WHERE r.quantity <> COALESCE(a.quantity, 0)
UNION ALL
SELECT 'allocation_eligibility', a.reservation_id::text || '/' || a.batch_id, NULL::bigint, NULL::bigint
FROM reservation_allocation a JOIN reservation r ON r.id = a.reservation_id
JOIN stock_batch b ON b.id = a.batch_id
WHERE b.sku <> r.sku OR b.last_usable_on < r.required_on OR a.quantity <= 0
UNION ALL
SELECT 'batch_bounds', b.id::text, b.on_hand::bigint, b.reserved::bigint FROM stock_batch b
WHERE b.reserved < 0 OR b.on_hand < 0 OR b.reserved > b.on_hand
UNION ALL
SELECT 'reservation_state', r.id::text, NULL::bigint, NULL::bigint FROM reservation r
WHERE NOT ((r.status = 'ACTIVE' AND r.released_at IS NULL) OR
           (r.status = 'RELEASED' AND r.released_at IS NOT NULL AND r.released_at >= r.created_at))
ORDER BY violation, entity_id;
