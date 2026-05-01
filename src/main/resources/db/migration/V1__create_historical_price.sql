create table if not exists historical_price (
    id varchar(36) primary key not null,
    epic varchar(255),
    resolution varchar(255),
    snapshot_time_utc timestamp,
    open_bid float not null,
    open_ask float not null,
    high_bid float not null,
    high_ask float not null,
    low_bid float not null,
    low_ask float not null,
    close_bid float not null,
    close_ask float not null,
    last_traded_volume integer not null,
    source varchar(255),
    ingestion_time_utc timestamp
);
