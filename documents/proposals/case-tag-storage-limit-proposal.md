# Case Tag Storage Limit — Proposal

**Status:** Draft, for review
**Author:** Generated with Claude Code, reviewed by ecc.hsu@gmail.com
**Related code:** `CaseTagController` / `CaseTagService` / `CaseTagRepository` / `MockTagSyncService` (arex-web-api, arex-web-core)

## 1. Problem statement

`POST /api/tag/addByOperation` lets a caller tag replay records (currently `"pr"` and `"nightly"` tag types) so they're excluded from normal cleanup. Once a record is tagged:

- It is written into the `case_tag` collection in the web DB (a small pointer/index document).
- The underlying mock record is kept alive in the **AREX Storage** service (the actual HTTP/DB/RPC capture — request, response, headers, etc.).

Tagged records are never deleted by the standard retention job. There is currently **no upper bound** on how many records — or how many bytes — a single `appId` can accumulate this way. Given the current environment holds **~30–50GB** of data, an unbounded per-app tagging path is a real capacity risk: a single noisy app (or a misconfigured "nightly" job that runs indefinitely) can consume a disproportionate and ever-growing share of that budget with no backpressure.

This document evaluates two candidate limit designs — **count-based** and **storage-size-based** — and recommends one.

## 2. Where the disk cost actually lives

This matters for choosing the right unit to cap:

| Data | Lives in | Typical size | Excluded from every tag-path Mongo query? |
|---|---|---|---|
| `case_tag` doc (the pointer we write) | Web DB | Small — a handful of string fields + two longs, no payload | N/A |
| `ReplayCompareResult` doc (the diff we read to find candidates) | Web DB | Small — `baseMsg`/`testMsg` are Zstd-compressed, `logs` compressed | Yes — `baseMsg`, `testMsg`, `logs` are explicitly excluded from every read in `CaseTagService`/`ReplayCompareResultRepositoryImpl`, precisely because they're the large fields |
| The actual mock record (what gets kept alive) | **AREX Storage** service (separate `Pinned*Mocker` / `Rolling*Mocker` collections) | Uncompressed request/response capture — can range from single-digit KB to hundreds of KB depending on payload | — |

**The `case_tag` write itself is cheap.** The real cost of tagging is that it prevents the Storage-side mock record from ever being cleaned up. So whatever limit we pick, the thing we actually care about bounding is the aggregate size of records an app has pinned via tags — `case_tag` document count is only a *proxy* for that.

The good news: `MockTagSyncService.addTagsBatch()` already receives a size per record from Storage (`recordSizes` in the response), and the current implementation already threads it into `CaseTagDto.recordSizeBytes` on every insert. But *what* that size needs to represent takes some care — see below.

### 2.1 A `recordId` is a traffic capture, not a single document

One `recordId` does **not** correspond to one mock document in Storage. It's a correlation key across an entire recorded traffic capture: one entry-point mocker (the inbound Servlet call) plus every dependency mocker recorded during that same execution — HTTP client calls, DB queries, dynamic class invocations, MQ, Redis, Dubbo, Netty, config file reads, and so on. Each of those lives in its own category collection in Storage (`Pinned*Mocker` / `Rolling*Mocker` / `AutoPinned*Mocker` — `HttpClientMocker`, `DatabaseMocker`, `DynamicClassMocker`, `QMessageConsumerMocker`, `RedisMocker`, `ServletMocker`, `NettyProviderMocker`, `DubboConsumerMocker`/`DubboProviderMocker`, `ConfigFileMocker`), all sharing the same `recordId`.

This isn't a guess — it's already visible in this codebase. `StorageCase.getViewRecord(recordId)` fetches **a list** of mockers for one `recordId` and has to filter to find the entry point among them:
```java
List<AREXMocker> mockers = response.getRecordResult();
Optional<AREXMocker> entryPoint = mockers.stream()
    .filter(m -> m.getCategoryType() != null && m.getCategoryType().isEntryPoint())
    .findFirst();
```

**Consequence for this proposal:** "the size of a record" must mean *the sum of every mocker document sharing that `recordId`, across every category collection* — not just the entry-point Servlet mocker. If a Storage-side sizing implementation only measured the entry point, the byte cap would silently and substantially undercount real disk usage, by a factor that varies per endpoint depending on fan-out (how many downstream calls that traffic made) — completely independent of any single payload's size. This is a hard requirement on the Storage endpoint contract in §6.3.2, not an implementation detail to leave implicit.

It also further weakens count-based (Option A) as a fit: two records can now differ not just by payload size but by fan-out multiplier (a thin proxy endpoint with 1 downstream call vs. an orchestrator with 15) — another dimension a raw document-count cap has no visibility into at all.

## 3. Option A — Count-based limit

Cap the number of tagged records an `appId` (optionally per `tagType`) may hold.

```
if (currentTaggedCount(appId, tagType) + candidates.size() > MAX_TAGGED_RECORDS) {
  reject or truncate candidates to the remaining budget
}
```

### Mechanics
- The count is known **before** any Storage call is made — `recordIds.size()` is just the candidate list size after in-memory filtering.
- Enforcement can happen as a **hard, pre-flight cap**: trim the candidate list to `remaining budget` before `MockTagSyncService.addTagsBatch()` is ever called, so Storage is never asked to tag more than allowed. No overshoot is possible.
- Cheapest possible usage check: `caseTagRepository.countByAppIdAndTagType(appId, tagType)` (indexed `appId + tagType` query) or a maintained counter.

### Pros
- **Simple to implement and reason about.** One integer, one comparison, no ordering subtleties.
- **Zero overshoot risk** — because count is known before Storage is ever touched, the limit can be enforced exactly, not approximately.
- **Cheap to check** — a `countDocuments` query on an indexed field, or a simple counter increment.
- **Easy to communicate** to API callers/users: "you can tag up to N records per app."

### Cons
- **Doesn't track the actual constrained resource.** Record size varies enormously — a `"pr"` tag on a small JSON API response might be 2–5KB; a `"nightly"` tag on a payload-heavy DB or SOAP capture (with full result sets) could be 50–200KB+. A count cap chosen to protect one app can be wildly wrong for another:
  - Too loose for large-payload apps → still allows uncontrolled disk growth (the thing we're trying to prevent).
  - Too tight for small-payload apps → blocks legitimate use for no disk-capacity reason.
- A single global count limit either has to be conservative (penalizing small-payload apps) or generous (failing to protect against large-payload apps) — there's no value that's simultaneously fair and safe across a heterogeneous set of apps.
- Doesn't shrink automatically as record sizes drift over time (e.g., a service starts returning larger responses) — the same count cap now represents a much larger disk footprint, silently.

### Worked example
Suppose we picked a single global cap of **50,000 tagged records per app**. Depending on the app's average record size, that one number means wildly different things:

| Avg record size | Disk footprint at the cap |
|---|---|
| 5 KB (small JSON API) | ~250 MB |
| 20 KB (typical mixed payload) | ~1 GB |
| 100 KB (DB capture w/ result sets, or verbose SOAP/XML) | ~5 GB |

With 22 apps currently in this environment (this repo's local sandbox count — production count may differ), a handful of apps sitting at the 100KB-average end of that range could alone consume **20–30%+ of the entire 30–50GB budget**, while the count-based rule treats them identically to an app whose records are 20x smaller. That's the core mismatch.

## 4. Option B — Total storage size limit

Cap the cumulative `recordSizeBytes` an `appId` (optionally per `tagType`) has tagged.

```
if (currentTaggedBytes(appId, tagType) >= MAX_TAGGED_BYTES) {
  reject the whole batchAddByOperation request before calling Storage
}
```

### Mechanics
There are two ways to enforce this, depending on how much of Storage's API surface we're willing to touch (we're already asking for a new endpoint here — `addTagsBatch` doesn't exist on the deployed Storage service yet, confirmed by a live 404 during testing — so this is genuinely open, not fixed by legacy constraints):

**A. Soft cap (single Storage call — today's `addTagsBatch` shape).** `MockTagSyncService.addTagsBatch()` only learns each record's size in its *response*, as a side effect of the write. Size literally cannot be known before that one call returns, because reporting it was bundled into the same call as the write. So the cap can only be checked *before the batch starts*, not per record:
  1. At the start of `batchAddByOperation`, check the app's current cumulative usage.
  2. If already at/over the cap, reject the whole request — no Storage calls made.
  3. If under the cap, proceed normally. The batch may push usage **over** the cap by at most one chunk's worth (chunks are 100 records — bounded, known overshoot, not unbounded).
  This is the same pattern most real-world storage quotas use (cloud object storage buckets, Git LFS quotas, container registry quotas) — checked at the start of an operation, not enforced byte-by-byte mid-write.

**B. Hard cap (two Storage calls — recommended, see §6.3.2).** Split "learn the size" and "write the tag" into two separate calls: a new *read-only* batch sizing endpoint, called first, then `addTagsBatch` only for whatever fits the remaining budget. Because size is now known *before* any write happens, the cap can be enforced exactly — no overshoot, ever. The cost is one extra HTTP round-trip per batch and a second (small, read-only) endpoint on the Storage side.

Design B removes the one structural weakness soft-cap-by-default byte limits have — it's why the recommendation in §6 leads with it.

### Pros
- **Directly measures the thing we actually care about** — disk consumption — rather than a proxy for it. This maps 1:1 onto "the DB needs more storage."
- **Fair across apps with different payload profiles**, and now also fair across different *fan-out* profiles (§2.1) — a byte cap is blind to neither payload size nor how many downstream mockers a traffic capture produced, unlike a count cap.
- **Can be a true hard, zero-overshoot cap** (design B above), same as count-based — this is no longer count-based's exclusive advantage.
- **Minimal new instrumentation on the app side** — `recordSizeBytes` is already threaded through `CaseTagDto`/`CaseTagCollection` today; design B mainly adds one more Storage call, not new app-side plumbing.
- Scales naturally with the stated concern: thresholds can be set directly as a percentage of the known 30–50GB budget (see §6), which isn't possible to do precisely with a count cap.

### Cons
- **Design A only: soft limit.** If we ship without the new sizing endpoint (design A), a single request can overshoot by up to one chunk (100 records × worst-case record size). Bounded and well-understood, but still a real trade-off if that's the path taken.
- **Design B requires a new Storage endpoint**, and that endpoint must implement the fan-out-aware sizing contract from §2.1/§6.3.2 correctly, or the whole cap is silently wrong. This is an external dependency the app team doesn't fully control the timeline of.
- **More expensive to check cheaply at scale.** A live `SUM(recordSizeBytes)` aggregation over `case_tag` gets slower as the collection grows into the millions of documents. This is solvable (see §6.3) but requires a small amount of additional state (a running-usage counter), not just a query.
- **Slightly less intuitive for API consumers** — "you have 500MB left" is a less immediately graspable unit than "you have 5,000 tags left," though this is a minor UX concern, not a technical one.

### Worked example
If we reserve, say, **20% of the current 30–50GB budget** for tagged/pinned data as a governance target (illustrative — see §6 for how to pick this), that's **6–10GB** across all apps. Spread evenly across the current 22-app sandbox count, that's roughly **270–450MB per app** — but in practice this shouldn't be divided evenly up front; it should be a per-app default (with headroom) plus an aggregate ceiling that's monitored and alerted on, exactly as most cloud storage quota systems work.

## 5. Side-by-side comparison

| | Count-based | Storage-size-based |
|---|---|---|
| Tracks the real constrained resource (bytes) | ❌ only a proxy | ✅ directly |
| Fair across apps with different payload *and* fan-out profiles (§2.1) | ❌ no | ✅ yes |
| Can be enforced as a hard pre-flight cap (zero overshoot) | ✅ yes, with no extra work | ✅ yes, if the new read-only sizing endpoint is built (§4 design B / §6.3.2) — otherwise ⚠️ soft cap, bounded overshoot (≤1 chunk) |
| Cheap to check at scale | ✅ trivial `countDocuments` or counter | ⚠️ needs a maintained running-total counter to stay cheap |
| New instrumentation required | None | App-side: minimal (already flows through the pipeline). Storage-side: a new read-only batch sizing endpoint, for the hard-cap design |
| Maps directly to "we need more disk space" | ❌ indirectly, imprecisely | ✅ directly |
| Easy for API callers to reason about | ✅ "N records" | ⚠️ "N MB" — still simple, less viscerally intuitive |
| Threshold can be derived directly from the known 30–50GB budget | ❌ requires an assumed avg record size, which drifts and varies per app | ✅ yes, directly as a % of budget |

## 6. Recommendation

**Use a storage-byte cap as the primary, binding limit, backed by a cheap count-based backstop as a secondary safety net.**

### 6.1 Why bytes wins as primary
The stated problem is disk capacity, not document count. Bytes is the only one of the two options that measures the actual constrained resource, is fair across apps with very different payload *and* fan-out profiles (§2.1), and can be set as a direct function of the real 30–50GB budget. And with the two-call design (§4 design B, §6.3.2), it gives up nothing to count-based on precision either — it can be enforced as a true hard, zero-overshoot cap, not just an approximation. The only added cost is one more read-only endpoint on the Storage side, which is a small ask given `addTagsBatch` itself is already a new endpoint being introduced for this feature.

### 6.2 Why keep a count backstop
Bytes alone doesn't protect against a pathological case: an app tagging an enormous number of *tiny* records. Aggregate bytes could stay low while `case_tag` document count (and its index size, and query latency against it) balloons into the millions. A secondary, generous count cap (e.g. 100,000 tagged records per `appId`, combined across tag types — see §6.3) costs almost nothing to add and closes that gap. It should never be the binding constraint in the normal case — bytes should trip first for any realistic payload size.

### 6.3 Concrete design

**One combined limit per `appId`** (decision — see §6.5): `"pr"` and `"nightly"` tags share a single byte budget and a single count backstop per app, not one each. This keeps the mental model simple ("this app has used X of its Y GB") and avoids having to explain two independent quotas to API consumers.

**New collection: `case_tag_usage`** — one document per `appId`, combined across tag types:
```json
{
  "appId": "eceb2863502afa2d",
  "usedBytes": 734003200,
  "usedCount": 41822,
  "limitBytes": 1073741824,
  "usagePercent": 68.37,
  "dataChangeCreateTime": 1788267736340,
  "dataChangeUpdateTime": 1788267736340
}
```
- `usedBytes` / `usedCount` — running totals, updated via `$inc` in the same write path as `CaseTagRepositoryImpl.batchAdd()`, so the running total never requires a live aggregation over the (potentially huge) `case_tag` collection.
- `limitBytes` — the *effective* limit snapshotted at the last update (global default, or the app's override — see §6.3.1). Stored redundantly alongside usage specifically so a Grafana panel can plot "used vs. limit" and compute "% used" from one document, with no join required.
- `usagePercent` — `usedBytes / limitBytes * 100`, recomputed and re-persisted every time either `usedBytes` or `limitBytes` changes (i.e. on every successful tag batch, and whenever an admin changes that app's override — see §6.3.1). This is the field the Grafana dashboard (§6.7) reads directly.

**Enforcement point:** inserted into `CaseTagService.batchAddByOperation()` between candidate filtering (today's step 3) and the Storage tag call (today's step 4):
1. Resolve the effective limit for `appId` (§6.3.1).
2. Read `case_tag_usage` for `appId` (treat a missing doc as zero usage). If `usedBytes >= limitBytes` **or** `usedCount >= MAX_TAGGED_RECORDS_BACKSTOP` (global, not overridable — see §5/§6.2) already, reject the whole request immediately — no sizing call, no tag call.
3. Otherwise, call the new read-only sizing endpoint (§6.3.2) for the filtered candidate `recordIds`, getting back a per-`recordId` size (already fan-out-summed per §2.1/§6.3.2).
4. Walk the candidates, accumulating a running total against `remaining = limitBytes - usedBytes`. A candidate is included only while `runningTotal + size(candidate) <= remaining`. This yields an exact, hard cap — never exceeds budget, by construction.
5. Call `MockTagSyncService.addTagsBatch()` (today's write call) only for the candidates that fit. `CaseTagRepositoryImpl.batchAdd()` proceeds exactly as today for whatever Storage confirms as matched.
6. `$inc` `usedBytes`/`usedCount` on `case_tag_usage` by the batch's actual inserted count and summed size, then recompute and store `usagePercent` (and refresh `limitBytes` from the currently-effective limit, in case it changed since the doc was last touched).
7. If the freshly recomputed `usagePercent` crosses the configured alert threshold, emit the alert (§6.4).

**Response behavior:** because size is now known *before* the write (step 3), truncating to fit the remaining budget is a precise, principled choice rather than a guess — so the recommendation changes from the original draft: **tag as many candidates as fit within the remaining budget, and report how many were skipped for quota** (extend `BatchAddCaseTagsByOperationResponseType` with a `skippedForQuota` count) rather than rejecting the whole request over a partial overage. Reject the *whole* request only in the one case where the app is already at/over its limit before this batch starts (step 2) — there, "tag zero and say why" is unambiguous and requires no partial-fit logic. This is a UX choice, not a technical constraint; flip it to always-reject-if-any-would-exceed if a stricter posture is preferred.

**Fallback (design A, §4):** if the new Storage sizing endpoint isn't available in time, the same enforcement collapses to the pre-write soft check described in §4 design A (check usage before the batch, accept bounded overshoot, always reject-the-whole-request since there's no way to know which candidates would fit ahead of time). Everything else in this section — `case_tag_usage`, the override, the alerting — is unchanged either way.

**No backfill needed.** Since `case_tag` doesn't exist yet in this codebase (it's the feature I just implemented), the limit can ship from day one with `case_tag_usage` starting at zero for every app — there's no historical over-quota data to reconcile, unlike the scenario implied by the original reference implementation.

#### 6.3.1 Admin override (decision — see §6.6)

Default behavior: every app shares the same global limit, `arex.tag.storage.defaultMaxBytes` (a `@Value`-backed config, consistent with every other threshold in this codebase — e.g. `arex.jwt.secret`, `arex.prometheus.port`).

For the (expected to be rare) case where a specific app legitimately needs more headroom, a small dedicated collection holds the exceptions rather than touching every app's config:

**New collection: `case_tag_limit_override`** — only apps with a non-default limit have a document here:
```json
{
  "appId": "eceb2863502afa2d",
  "limitBytes": 5368709120,
  "reason": "elevated for regression baseline capture",
  "updatedBy": "ecc.hsu@gmail.com",
  "dataChangeCreateTime": 1788267000000,
  "dataChangeUpdateTime": 1788267000000
}
```

Effective limit resolution: `case_tag_limit_override` doc for this `appId` if one exists, else `arex.tag.storage.defaultMaxBytes`. This mirrors the precedent already in this codebase for keyed, DB-stored config (`SystemConfiguration`, e.g. the `auth_switch` document) rather than inventing a new pattern.

There's no admin API endpoint for managing overrides in this proposal's scope — to start, this is a document an operator writes directly (matching how `SystemConfiguration.auth_switch` is managed today). A thin admin endpoint can be added later if hand-editing Mongo becomes a real workflow pain point; it isn't needed for the limit mechanism itself to work.

#### 6.3.2 Storage endpoint contract (new — required for the hard-cap design)

Two batch endpoints, same request shape (a list of `recordId`s), same chunking convention (100 per call, matching `MockTagSyncService.RECORD_IDS_CHUNK_SIZE` today):

| Endpoint | Purpose | Side effect | Sizing requirement |
|---|---|---|---|
| `addTagsBatch` (today, not yet deployed) | Confirm existence + write the tag | Yes — tags the record | If it still reports `recordSizes`, must follow the same rule as below |
| **New:** batch record-size lookup (e.g. `POST /api/storage/query/recordSizesBatch`) | Confirm existence + report size, for the pre-flight check | **None** — read-only | **Must sum every mocker document sharing that `recordId`** across all category collections (Servlet + HttpClient + Database + DynamicClass + Redis + MQ + Dubbo + Netty + ConfigFile, and their Pinned/Rolling/AutoPinned variants) — never just the entry-point mocker (§2.1) |

Both endpoints must use the *same* sizing definition, so a size learned during the pre-flight check (step 3 in §6.3) and a size later reported by `addTagsBatch` (or reconciled by any future audit) never disagree for the same `recordId`. This is worth stating explicitly as a shared contract rather than leaving each endpoint to define "size" independently.

The exact endpoint name/path above is illustrative — the actual contract needs to be agreed with whoever owns the Storage service; what's load-bearing for this proposal is the shape (read-only, batched, same chunking, fan-out-summed) and the guarantee that both endpoints agree on what "size" means.

#### 6.3.3 Performance characteristics under high-volume batches

A batch operation can tag anywhere from a handful to several thousand records at once (`batchAddByOperation` has no upper bound on candidate count other than the quota itself). It's worth being explicit that the usage-tracking machinery in this design doesn't degrade as batch size — or an app's historical accumulated usage — grows:

- **Reading current usage is O(1).** `db.case_tag_usage.findOne({appId})` (step 2 in §6.3) is a single indexed point-lookup on one small document. Its cost depends on neither how many records this batch is about to tag, nor how many the app has accumulated across its whole history.
- **Updating usage after a batch is O(1) at the database level, regardless of batch size.** The batch's total contribution — sum of `size(recordId)` across however many thousand records were just tagged — is accumulated **once, in application memory** while walking the candidate list (step 4), then applied as a **single** `$inc: { usedBytes: totalDelta, usedCount: totalCountDelta }` (step 6). Tagging 5 records or 5,000 issues exactly one `$inc` call either way — never one write per tagged record.
- **This is precisely why `case_tag_usage` exists as a maintained counter rather than a live aggregation.** The rejected alternative — computing current usage via `SUM(recordSizeBytes)` over `case_tag` filtered by `appId` on every check — would be O(number of records that app has tagged historically), and would keep getting slower as that collection grows into the millions. A running counter is O(1) for the entire lifetime of the collection, not just while it's still small.
- **Summing a new batch's own sizes is still O(batch size), but that cost is unavoidable and cheap.** Something has to look at each of the thousands of newly-learned sizes at least once to add them up — that's an in-memory loop over data already in hand from the sizing call (§6.3.2), not an extra database round-trip, and it doesn't scale with the app's historical usage, only with the size of *this* batch.

Two correctness details that make the O(1) claim actually hold, not just look like it does:
1. **Must be `$inc`, never read-modify-write.** If two batches for the same `appId` ran concurrently and each did "read `usedBytes`, add locally, write back," the second write could silently clobber the first (a lost update). `$inc` is atomic at the single-document level in MongoDB, so concurrent batches for the same app always accumulate correctly regardless of interleaving.
2. **`case_tag_usage` must stay a fixed-size document.** `usedBytes`/`usedCount`/`limitBytes`/`usagePercent` are plain scalar fields being overwritten in place, never an array that grows — so incrementing them never changes the document's BSON size. That avoids MongoDB ever having to relocate the document on disk (the classic penalty when a document outgrows its originally allocated space), which is what keeps this O(1) over the collection's entire lifetime rather than degrading later as usage accumulates.

### 6.4 Alerting (decision — see §6.6)

One global, adjustable threshold — no per-app custom alerting. Concretely:

- **Threshold storage:** a new keyed document in the existing `SystemConfiguration` collection (same pattern as `auth_switch`), so it's adjustable at runtime without a redeploy:
  ```json
  { "key": "case_tag_alert_threshold", "alertThresholdPercent": 80 }
  ```
- **Mechanism:** whenever `case_tag_usage.usagePercent` is recomputed (step 5 in §6.3) and the new value is at or above `alertThresholdPercent`, emit a single `LogUtils.warn(...)` line (the same warn/error logging convention already used throughout this codebase) identifying the `appId` and its current utilization. This is uniform by construction — every app is checked against the exact same stored threshold, there's no per-app alert configuration to drift out of sync.
- **Grafana:** because `usagePercent` is already persisted per app (§6.7), Grafana's own alerting can be layered on top of the same dashboard query using the same threshold value — one alert rule, evaluated per `appId` series, rather than a second bespoke alerting pipeline. The app-side log warning and the Grafana alert are two views of the same single number and the same single threshold, not two independent mechanisms.

### 6.5 Setting the actual numbers

I don't have real average Storage-record-size data from this environment (the local sandbox's mocker collections are currently empty — nothing to sample). Before hard-coding a threshold:

1. **Measure first.** Once tagging has run in a real environment for a short period, `AVG(recordSizeBytes)` over `case_tag` gives a real number to plan against, per tag type (expect `"nightly"` to run larger than `"pr"` — it isn't filtered to success-only, and may include heavier diagnostic payloads on failures). Because `recordSizeBytes` is fan-out-summed per §2.1/§6.3.2, this average already reflects whole-traffic-capture cost, not just entry-point payload size — no separate fan-out adjustment needed once the sizing contract is implemented correctly.
2. **Pick a budget share.** Decide what fraction of the 30–50GB is acceptable to dedicate to tagged/pinned data as a matter of policy (this is a product/ops decision, not a technical one) — e.g. 15–25% is a reasonable starting range for a feature whose entire purpose is long-term retention of a curated subset.
3. **Set the default `limitBytes` per `appId`** (§6.3) as a single global configurable value (Spring `@Value`, like the rest of this codebase's thresholds), generous enough that well-behaved apps rarely hit it, with the *aggregate* sum across apps monitored and alerted on separately from any individual app's cap. Grant per-app overrides (§6.3.1) only for confirmed exceptions.
4. **Set `MAX_TAGGED_RECORDS_BACKSTOP`** generously and globally, not overridable (backstop only) — e.g. large enough that only a pathological tiny-record scenario would ever trip it before bytes does.

### 6.6 Decisions

The open questions from the original draft have been resolved:

| Question | Decision |
|---|---|
| Per-`tagType` limits or one combined limit per `appId`? | **One combined limit per `appId`** — `"pr"` and `"nightly"` share a single budget (§6.3). |
| Admin override? | **Yes** — default is the same global limit for every app, with an optional per-app override for exceptions (§6.3.1). |
| Alerting granularity? | **One global, adjustable threshold, same mechanism for every app** — no per-app alert config (§6.4). |
| Utilization visibility? | **Persisted in the DB** on every usage update (`case_tag_usage.usagePercent`), specifically so it's directly queryable by Grafana (§6.7). |
| What does "size of a record" mean? | **Sum across every mocker sharing a `recordId`** (all category collections — Servlet, HttpClient, Database, DynamicClass, Redis, MQ, Dubbo, Netty, ConfigFile), never just the entry point (§2.1, §6.3.2). |
| Soft or hard cap? | **Hard, zero-overshoot cap**, via a new read-only Storage sizing endpoint queried before the write (§4 design B, §6.3.2) — falls back to the soft/bounded-overshoot design (§4 design A) only if that endpoint isn't available (§6.3). |
| Does usage tracking scale with batch size / historical volume? | **No — O(1) per check and per batch update**, via a maintained counter (`case_tag_usage`, atomic `$inc`) rather than a live aggregation (§6.3.3). |

### 6.7 Grafana / observability

`case_tag_usage` is intentionally shaped to be dashboard-ready without any additional aggregation or a second export step:

| Field | What it's for on the dashboard |
|---|---|
| `appId` | Series label — one line/row per app. |
| `usagePercent` | The primary panel: utilization gauge/time-series per app. This is the same number the alert threshold (§6.4) is compared against. |
| `usedBytes`, `limitBytes` | Secondary panel: absolute "used vs. limit" bars, and lets a "top N apps by usage" panel be built without recomputing percentages. |
| `usedCount` | Secondary panel: tagged-record count per app, useful for spotting the count-backstop (§6.2) edge case (high count, low bytes) separately from the primary byte metric. |
| `dataChangeUpdateTime` | Lets a panel show staleness — when an app's usage was last recalculated (i.e., when it last tagged something, or last had its override changed). |

Since `usagePercent` is recomputed and re-persisted synchronously on every tag write (§6.3) and on every override change (§6.3.1), a Grafana panel querying this collection directly always reflects current state — there's no separate batch/cron export job to keep in sync, and no risk of the dashboard showing a stale percentage against a since-changed limit.

The `case_tag_alert_threshold` value in `SystemConfiguration` (§6.4) can be surfaced on the same dashboard (e.g. as a fixed threshold line on the utilization panel) so the visual cap and the actual enforced/alerted-on cap are always the same number, not two values that can drift apart.
