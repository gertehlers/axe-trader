# Staging Lease Safety Design

## Goal

Guarantee that at most one cooperative Axe Trader stage process can reach pending provider work for one SQLite
staging database, without depending on `BasicFileAttributes.fileKey().toString()` or `java.io.tmpdir`.

## Supported storage contract

Staging is supported only when the database's `FileStore` exposes the Java `unix` attribute view and the resolved
database has exactly one hard link (`unix:nlink == 1`). Any unsupported attribute view, unreadable link count, or
link count other than one is refused before SQLite opens the database or pending work is read.

Symbolic links are supported because `Path.toRealPath()` resolves them to one canonical database pathname.
Hard links are deliberately unsupported. SQLite documents that opening one database through multiple hard-link
names is undefined and can create different rollback-journal or WAL names, defeating recovery. See
[SQLite: How To Corrupt An SQLite Database File, section 2.6](https://www.sqlite.org/howtocorrupt.html#_multiple_links_to_the_same_file_).

## Architecture

For a new stage, create the empty database file with `Files.createFile` before opening JDBC. If another process
wins the creation race, treat the file as existing and continue through the same safety/lease checks. For both new
and existing stages:

1. Resolve the requested path with `toRealPath()`.
2. Require the resolved file store to support the `unix` attribute view.
3. Read `unix:nlink` and require the literal value `1`.
4. Derive one lock path beside the resolved database: `<canonical-database-name>.stage.lock`.
5. Open that lock file and acquire one exclusive `FileLock`. Never unlink the lock file.
6. Re-read `unix:nlink` while holding the lock and again require `1`, closing the check/acquire race.
7. Open SQLite through the canonical database path, then perform foreign-key setup, schema initialization or
   resume validation, and pending-work access.

The lock is held for the `HistoryStagingStore` lifetime. Every accepted symlink spelling resolves to the same
canonical sibling lock regardless of each JVM's `java.io.tmpdir`. A hard link created before acquisition causes
both original and alias opens to fail closed. A hard link created after an owner starts causes every later
cooperative opener to fail closed; the owner continues through its already-open canonical name.

## Durability and error handling

The lease remains outside SQLite transactions, so each processed work item still commits durably in its own
transaction. A crash releases the OS file lock while preserving committed queue/page/price state. The stable lock
file remains and is safely relocked by a later owner.

Unsafe filesystem identity errors are reported distinctly and preserved by `openForStage`; they must not be
wrapped as generic malformed-stage metadata. Opening a hard-linked database is refused before any JDBC connection
or `nextPending()` call.

## Test design

Cross-JVM helpers receive deliberately different `-Djava.io.tmpdir` values.

- An owner using the canonical path and a contender using a symlink must converge on the canonical sibling lock;
  the contender is blocked before `nextPending()`.
- Once an owner is active, creating a hard link and starting a contender through it must produce an unsafe-hard-
  link refusal and no work signal.
- A database that already has a hard link must be refused through both names before either process reaches work.
- During A-to-B symlink handoff, B waits until A releases; a C symlink contender remains blocked while B owns the
  lease; the sibling lock file's filesystem identity does not change.
- Existing same-JVM ownership, run scoping, durable resume, and focused history/import tests remain green.

## Non-goals and operational constraint

This protocol coordinates cooperative Axe Trader processes on local Unix-like filesystems with functioning native
file locks. It does not make SQLite safe on filesystems with broken locking (notably some network/NFS setups), nor
can it prevent a non-cooperative external program from opening a newly-created hard link. Operators must not hard
link a staging database; use symlinks only when an alias is necessary.
