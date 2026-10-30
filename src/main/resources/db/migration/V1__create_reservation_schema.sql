CREATE TABLE stock_batch (
    id bigint CONSTRAINT pk_stock_batch PRIMARY KEY,
    sku varchar(64) NOT NULL,
    last_usable_on date NOT NULL,
    on_hand integer NOT NULL,
    reserved integer NOT NULL DEFAULT 0,
    CONSTRAINT ck_batch_id CHECK (id > 0),
    CONSTRAINT ck_batch_sku CHECK (sku ~ '^[A-Z0-9][A-Z0-9_-]{0,63}$'),
    CONSTRAINT ck_batch_date CHECK (last_usable_on BETWEEN DATE '0001-01-01' AND DATE '9999-12-31'),
    CONSTRAINT ck_batch_on_hand CHECK (on_hand >= 0),
    CONSTRAINT ck_batch_reserved CHECK (reserved >= 0 AND reserved <= on_hand)
);

CREATE TABLE reservation (
    id uuid CONSTRAINT pk_reservation PRIMARY KEY,
    external_request_id varchar(64) NOT NULL,
    sku varchar(64) NOT NULL,
    quantity integer NOT NULL,
    required_on date NOT NULL,
    status varchar(8) NOT NULL,
    created_at timestamptz NOT NULL,
    released_at timestamptz,
    CONSTRAINT uq_reservation_external_request UNIQUE (external_request_id),
    CONSTRAINT ck_reservation_request_id CHECK (external_request_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    CONSTRAINT ck_reservation_sku CHECK (sku ~ '^[A-Z0-9][A-Z0-9_-]{0,63}$'),
    CONSTRAINT ck_reservation_quantity CHECK (quantity > 0),
    CONSTRAINT ck_reservation_date CHECK (required_on BETWEEN DATE '0001-01-01' AND DATE '9999-12-31'),
    CONSTRAINT ck_reservation_status CHECK (status IN ('ACTIVE', 'RELEASED')),
    CONSTRAINT ck_reservation_release_time CHECK (
        (status = 'ACTIVE' AND released_at IS NULL) OR
        (status = 'RELEASED' AND released_at IS NOT NULL AND released_at >= created_at)
    )
);

CREATE TABLE reservation_allocation (
    reservation_id uuid NOT NULL,
    batch_id bigint NOT NULL,
    quantity integer NOT NULL,
    CONSTRAINT pk_reservation_allocation PRIMARY KEY (reservation_id, batch_id),
    CONSTRAINT fk_allocation_reservation FOREIGN KEY (reservation_id) REFERENCES reservation(id) ON DELETE RESTRICT,
    CONSTRAINT fk_allocation_batch FOREIGN KEY (batch_id) REFERENCES stock_batch(id) ON DELETE RESTRICT,
    CONSTRAINT ck_allocation_quantity CHECK (quantity > 0)
);

CREATE INDEX ix_batch_fefo ON stock_batch(sku, last_usable_on, id);
CREATE INDEX ix_allocation_batch ON reservation_allocation(batch_id);
