# Persistence

## The problem

Android kills processes routinely — backgrounded, low memory, or just because.
An in-memory cache is therefore cold exactly when users notice: the app they
were reading a minute ago opens on a spinner.

Persisting the cache means a cold start renders immediately from disk and
refreshes underneath, instead of blocking on the network.

## The simplest thing that works

Persistence is **opt in per key**, by carrying the serializer on the key:

```kotlin
@Serializable
data class Todo(val id: String, val title: String)

object TodosKey : PersistableQueryKey<List<Todo>> {
    override val parts get() = listOf("todos")
    override val serializer get() = ListSerializer(Todo.serializer())
}
```

Then, once at startup:

```kotlin
val persisted = client.persist(
    scope = applicationScope,
    options = PersistOptions(
        persister = FilePersister(File(filesDir, "kwery-cache.json")),
        keys = listOf(TodosKey),
        buster = BuildConfig.VERSION_NAME,
    ),
)
```

A key that should never touch disk — a search containing personal data, a
one-off — simply stays a plain `QueryKey`. There is nothing to switch off.

## Why keys must be listed

A stored entry arrives as bytes with no type, and only its key knows how to
decode them. Listing keys is what lets a restore find the right serializer. It
is the same constraint that makes [offline writes](offline.md) register their
handlers: serialized state cannot carry code.

## Options

| | Default | |
|---|---|---|
| `maxAge` | 24 hours | How old a snapshot may be before it is discarded |
| `buster` | `""` | Change it to discard everything — e.g. on app release |
| `throttle` | 1 second | Minimum interval between writes |
| `exclude` | none | Skip specific keys at write time |

`gcTime` **must** be at least `maxAge`, and Kwery throws at startup if it is
not:

```kotlin
QueryOptions(gcTime = 24.hours)   // matching a 24-hour maxAge
```

Otherwise entries are evicted from memory long before the stored copy expires,
so the cache is written on every change and almost never read — the feature
quietly does nothing. TanStack documents this constraint and lets you violate
it; Kwery refuses to start.

## What goes wrong

**Restored data keeps its original age.** A cache restored two minutes after it
was written, with a five-minute `staleTime`, is still fresh and does **not**
refetch. That is the whole point — if restoring reset the clock, every cold
start would refetch everything and the disk write would have bought nothing.

**Queries wait during a restore.** Reading from disk is asynchronous, so queries
created while it is in progress hold in `Idle` rather than firing a request for
data that is about to arrive. Use it for a splash screen if you want one:

```kotlin
val restoring by rememberIsRestoring()
```

**A bad snapshot is discarded whole; a bad entry is not.** Expired, busted,
schema-mismatched, or unreadable snapshots are thrown away entirely and deleted.
But a single entry that no longer decodes — because a response shape changed in
a release — is dropped while the rest survives. Losing an entire cache over one
renamed field would be a needless cold start.

The reason is reported rather than swallowed:

```kotlin
when (persisted.discardReason) {
    DiscardReason.BusterMismatch -> Log.i(TAG, "cache reset by a new app version")
    DiscardReason.Unreadable -> Log.w(TAG, "cache was corrupt")
    else -> Unit
}
```

**Unconfirmed optimistic writes are never stored.** They would come back after a
cold start looking like something the server accepted.

**Writes are atomic.** The snapshot goes to a temp file and is renamed over the
target, so a process killed mid-write leaves the previous file wholly intact
rather than a half-written mixture. If you implement your own `QueryPersister`,
do the same — writing in place is the obvious approach and it fails in exactly
the situation persistence exists for.

**Consider what you are writing to disk.** Cached API responses routinely
contain personal data, and this file is plain JSON in your app's private
storage. Use `exclude`, or keep sensitive queries on non-persistable keys.
Encryption is not built in.

**One file per concern.** Do not point a `FilePersister` and a
`FileMutationQueueStore` at the same file. The cache is disposable and gets
discarded wholesale on a `buster` change; pending writes must never be.

## Related

- [Offline writes](offline.md) — the write-side equivalent, and why it uses a
  separate store
- [Caching](caching.md) — `staleTime` and `gcTime`
- [Query state](query-state.md) — what a restored-but-refreshing query looks like
