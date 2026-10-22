-- Money is stored as whole minor units in a bigint. Never a float, real or double column.
create table payments (
    id            varchar(36)              primary key,
    merchant_id   varchar(64)              not null,
    amount_minor  bigint                   not null,
    currency      varchar(3)               not null,
    recorded_at   timestamp with time zone not null
);
