# Offline writes

## The problem

A user taps "save" on the underground. The request cannot go out. What happens
next is a design decision most apps make by accident.

The usual answer is to show an error and lose the edit. The next answer is to
retry in memory — which works until Android kills the process, which it does
routinely and without warning. A write held only in memory is a write you will
sometimes lose, and the user has no way to know which time it was.

Kwery queues writes **on disk before attempting them**, replays them when
connectivity returns, and survives the process dying in between.

## The simplest thing that works

```kotlin
// A durable write: variables must be serializable, because a queued write
// outlives the process that created it.
@Serializable
data class AddTodo(val title: String)

object AddTodoKey : DurableMutationKey<AddTodo> {
    override val parts get() = listOf("todos", "add")
    override val serializer get() = serializer<AddTodo>()
}

// Register at startup, in your Application — not on a screen.
val queue = OfflineQueue(
    scope = applicationScope,
    options = OfflineQueueOptions(
        store = FileMutationQueueStore(File(filesDir, "kwery-queue.json")),
    ),
    onlineManager = AndroidOnlineManager(this),
    timeSource = TimeSource.System,
) {
    register(AddTodoKey) { input -> api.addTodo(input, idempotencyKey) }
}

// Anywhere: returns as soon as the write is on disk.
queue.submit(AddTodoKey, AddTodo("Buy milk"))

// After a cold start, once the cache has been restored.
queue.resume()
```

`submit` returns when the write is **durable**, not when it is delivered. A user
tapping "save" offline should not have their coroutine parked until the network
comes back, possibly for hours.

## Why registration happens at startup

This is the one piece of ceremony, and it is not arbitrary.

Serialized state cannot carry code. A queued write stores its *variables* and
the *identity* of the function to run — never the function itself. When the app
restarts, the screen that created the write is gone, so the queue has nowhere to
get the function from unless it was registered somewhere screen-independent.

TanStack Query hits the same wall and surfaces it as a runtime error,
`No mutationFn found`, after the fact. Kwery makes `register` the only way to
create a durable write, so an unregistered key fails immediately at the call
site instead.

## Options

| | Default | |
|---|---|---|
| `maxAttempts` | 5 | Attempts before a write is dead-lettered |
| `maxAge` | 7 days | How long a write may wait before it is dead-lettered |
| `scopeId` on `submit` | none | Writes sharing a scope replay in submission order |

**Scopes** matter when order does. Two edits to the same document must not
arrive out of order; two edits to unrelated documents need not wait for each
other. Pass a `scopeId` for the former and nothing for the latter.

```kotlin
queue.submit(RenameKey, Rename(docId, "new name"), scopeId = "doc:$docId")
```

**Dead-lettering** is what stops one bad write from blocking everything behind
it. A write that will never succeed — a 400, or one whose handler no longer
exists after an app update — moves out of the active queue and is surfaced
rather than retried forever:

```kotlin
queue.deadLettered().forEach { showUser(it) }
queue.discard(it.id)
```

**Pending count**, for the indicator users actually want:

```kotlin
val pending by queue.pending.collectAsState()
if (pending > 0) Text("$pending change(s) pending")
```

## Offline reads

Writes queue; reads **pause**. A query with no connectivity does not fail — it
reports `FetchStatus.Paused` and waits, keeping whatever data it already had on
screen. A phone in a lift has not encountered an error, and showing one is both
wrong and unhelpful.

```kotlin
client.query(FeedKey, QueryOptions(networkMode = NetworkMode.Online)) { api.feed() }
```

| `NetworkMode` | Offline behaviour |
|---|---|
| `Online` *(default)* | Pause before every attempt. Retries pause too and **continue** where they left off, so a query paused on attempt 2 resumes at attempt 3 with the right backoff. |
| `Always` | Ignore connectivity entirely. Never pauses. |
| `OfflineFirst` | Run the fetcher **once**, then pause retries. |

**`Always` is for fetchers that do not need the network** — reading a local
database, say. Those would otherwise be paused for a resource they never use.

**`OfflineFirst` is for a fetcher whose first attempt might be served without
the network** — an HTTP cache or an interceptor. Retrying it is pointless: if
the cache did not have it, hammering a dead network will not help either.

Two details worth knowing:

- **A paused query that is cancelled does not resume.** Leaving a screen while
  offline means no request when connectivity returns.
- **Under `Always`, reconnecting still refetches** unless you say otherwise.
  TanStack derives `refetchOnReconnect: false` from `networkMode: 'always'`;
  Kwery cannot cheaply, because a Kotlin data class default cannot tell "unset"
  from "explicitly the default". Set it yourself:

  ```kotlin
  QueryOptions(networkMode = NetworkMode.Always, refetchOnReconnect = RefetchOn.Never)
  ```

## What goes wrong

**Delivery is at-least-once, not exactly-once.** A write may have reached the
server before the process died, with the response lost. On replay the server
sees it twice. Kwery cannot fix this alone — no client can — so every queued
write carries a stable id that survives restarts:

```kotlin
register(AddTodoKey) { input ->
    api.addTodo(input, idempotencyKey = idempotencyKey)
}
```

The handler runs with a `DurableMutationScope` receiver, so `idempotencyKey`
and `attempt` are in scope without threading anything through.

Send it as an idempotency key. If your endpoint is not idempotent and you do not
send one, a replay will duplicate the write. This is the single most important
thing to get right on this page.

**A week-old write probably should not be sent.** Replaying an edit against
server state that has moved on is often worse than dropping it and telling the
user. That is what `maxAge` is for, and it is checked *again* after the write
finishes waiting for connectivity — not only when it was queued. A write that
sat offline for eight days is dead-lettered when the network returns, rather
than replayed.

**Resume after hydration, not before.** If you also persist the query cache,
restore it first:

```kotlin
client.persist(applicationScope, persistOptions)   // restores the cache
queue.resume()                                     // then replays writes
```

Reversed, an optimistic write replays against an empty cache and writes into
nothing.

**Optimistic values do not currently survive a cold start.** If the process dies
between the tap and delivery, the write is safely queued and *will* be sent, but
the screen shows server state until it lands. The write is not lost; the
preview of it is. This is a known gap rather than a design choice — see
the project's roadmap, which is kept outside the published repository.

**A failing write does not block its scope forever.** The scope lock is released
even when delivery throws, so one poison record cannot deadlock everything
queued behind it. Worth knowing because the opposite is a very natural
implementation, and the failure only appears in production.

## Related

- [Mutations](mutations.md) — writes that do not need to be durable
- [Optimistic updates](optimistic-updates.md) — showing a write before it lands
- [Persistence](persistence.md) — the query cache across process death
