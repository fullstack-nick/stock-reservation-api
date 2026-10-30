-- Supply a psql variable: -v external_request_id=ORDER-1042-L1
SELECT r.id, r.external_request_id, r.sku, r.quantity, r.required_on,
       r.status, r.created_at, r.released_at,
       a.batch_id, b.last_usable_on, a.quantity AS allocated_cases,
       b.on_hand, b.reserved, b.on_hand - b.reserved AS available_cases
FROM reservation r
LEFT JOIN reservation_allocation a ON a.reservation_id = r.id
LEFT JOIN stock_batch b ON b.id = a.batch_id
WHERE r.external_request_id = :'external_request_id'
ORDER BY b.last_usable_on, b.id;
