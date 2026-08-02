create table if not exists price_exclusion (
    import_run_id text not null,
    source text not null,
    epic text not null,
    resolution text not null,
    snapshot_time_utc text not null,
    reason text not null,
    detected_at_utc text not null,
    primary key (import_run_id, source, epic, resolution, snapshot_time_utc, reason)
);

create index if not exists price_exclusion_epic_resolution_timestamp
    on price_exclusion (epic, resolution, snapshot_time_utc);
