# ![LumineLog Logo](src/main/resources/ch/eitchnet/luminelog/assets/LumineLog.svg) LumineLog

LumineLog is a modern, cross-platform log viewer application built with **JavaFX**. It provides a real-time "tail -f" experience with powerful highlighting and multi-file support.

## Features

- **Real-time Tailing**: Automatically follows the end of log files as they grow. The "Follow Tail" toggle allows jumping to the latest log entry instantly.
- **Virtualized Log List**: Efficiently handles large log files by loading only visible lines, supported by an LRU cache.
- **Search & Dynamic Highlighting**: 
  - Quickly search for terms and add them as highlights on the fly.
  - Supports **Regex** or plain text rules with customizable colors.
- **Powerful Highlighting Groups**:
  - Define **Highlight Groups** for different log formats (e.g., Slf4j, standard).
  - Add, rename, or delete highlight groups and rules on the fly.
  - Interactive highlights bar: see occurrence counts and jump to specific matches in a dedicated view.
- **Multi-tab Interface**: Open and monitor multiple log files simultaneously in separate tabs. Supports closing tabs and confirmation on exit.
- **File History**: Quickly reopen recently used files from the dedicated history menu.
- **Drag-and-Drop**: Easily open log files by dragging them onto the application window.
- **Font Size Customization**: Adjust the log display font size on the fly with a dedicated UI control.
- **Multi-selection & Clipboard**: Select multiple log lines and copy them to your clipboard via the context menu.
- **File Change Detection**: Automatically detects when a log file is replaced or truncated and reloads it.
- **Application Icon & About Dialog**: Professional look with a custom icon and an informative About dialog.
- **Cross-platform Configuration**: Automatically saves your preferences (last opened file, highlights) in standard OS locations:
  - **Linux**: `~/.config/LumineLog/` (respects `XDG_CONFIG_HOME`)
  - **Windows**: `%APPDATA%\LumineLog\`
  - **macOS**: `~/Library/Application Support/LumineLog/`

## Technology Stack

- **Java 25**
- **JavaFX 23** (Controls, FXML)
- **Maven** for build management
- **Gson** for configuration persistence
- **SLF4J / Logback** for internal logging
- **JUnit 5** for testing

## Getting Started

### Prerequisites

- Java Development Kit (JDK) 25 or newer.

### Building from source

```bash
mvn clean install
```

### Running the application

```bash
mvn javafx:run
```

### Log view behavior and testing

The log view retains JavaFX `ListView` virtualization. Background indexing publishes versioned,
immutable index snapshots; pending updates are coalesced before they reach the JavaFX thread.
Following is remembered per file tab. Scrolling upward, navigating backward, or dragging a
selection pauses it; scrolling to the bottom, pressing End, or enabling Follow Tail resumes it.
Drag selection supports edge autoscrolling, and rows stay single-line at the selected font size.

Run regression tests with `mvn test`. JavaFX interaction tests need a graphical display; on
headless Linux, use `xvfb-run -a mvn test` with Xvfb installed.

Index snapshots preserve offsets and read boundaries, not historical file contents. If a file
is replaced or overwritten before a pending read, that read may fail and the next update reloads
the view. Change detection uses file identity, timestamps, size, and a bounded tail sample;
rewrites preserving all of these can evade detection. Initial indexing runs on bounded background
workers while the tab shows its opening state. Occurrence identifiers remain memory-backed, but matching
text is loaded lazily into bounded per-result caches. Expensive regular expressions can remain costly
on very long lines.

#### Oversized-line safety (B01)

File-backed reads support at most **1 MiB (1,048,576 bytes) per UTF-8 encoded line**, including
any CR/LF terminators. Batch payloads are also capped at 1 MiB. Snapshot offsets are checked
before allocating the payload or decoding text; the encoded limit also bounds a decoded line
to at most 1,048,576 UTF-16 code units. Content is never silently shortened.

An unsupported line appears as an explicit unavailable row, separate from log text and excluded
from highlighting. Copying a selection containing an unavailable row fails without replacing
the clipboard. Highlight counts become unavailable, and occurrence searches report an incomplete
search without publishing partial results. Split oversized records upstream to read/search/copy
them; ordinary rows before and after an oversized line remain individually readable.

This is an allocation-safety limit, not a global memory budget: the offset index, decoded cache,
multiple tabs, concurrent scans, and occurrence results still have the limitations described above.

Allocation-safety evidence: on Linux 7.2.11 amd64, btrfs, Azul OpenJDK 27+35, with `-Xmx32m`
(33,554,432-byte max heap), a sparse 128 MiB newline-free file (zero allocated filesystem blocks)
indexed successfully both before and after B01. Reading it previously raised `OutOfMemoryError`;
now it raises `OversizedLineException` before allocating its payload. This demonstrates rejection,
not a throughput improvement or a bound on total application heap. Reproduce after `mvn test` with:

```bash
java -Xmx32m -cp target/classes:target/test-classes \
  ch.eitchnet.luminelog.model.LogFileModelHeapProbe target/storage-guard-probe.log IO_EXCEPTION
```

#### Internal batch ownership (B02)

File-backed batches take ownership of their freshly allocated payload instead of cloning it.
Public `Batch` inputs and `bytes()` output remain defensively copied, decoded lines remain
immutable, and snapshot validation is unchanged.

Allocation evidence: on Linux 7.2.11 amd64, btrfs, Azul OpenJDK 27+35, using the command below
(128 MiB heap settings, Serial GC, escape analysis disabled), 100 repeated reads after 100 warmup
reads of one 1 MiB ASCII line allocated **315,487,200 bytes before** and **210,628,000 bytes after**
B02 on the reading thread. The reduction is **1,048,592 bytes per read**: one payload array plus
its header/alignment on this JVM. These counters include decoding and validation allocations;
they do not measure throughput, retained heap, or a global memory bound. The probe uses an
automatically deleted fixture under `target`, does not call `bytes()`, and requires a JDK with
thread allocation counters. Reproduce after `mvn test` with:

```bash
java -Xms128m -Xmx128m -XX:+UseSerialGC -XX:-DoEscapeAnalysis -cp target/classes \
  src/test/probes/BatchAllocationProbe.java
```

#### Weighted decoded cache (B03)

Each `VirtualLogList` now has a combined **8 MiB accounted-data budget** for its decoded LRU
and retained current batch, plus the existing **1,000 cached-row ceiling**. Both defaults are
named constants (`DEFAULT_CACHE_BYTES`, `DEFAULT_CACHE_ENTRIES`). Eviction follows access order;
the requested row is admitted last so batch preloading cannot evict it before it is returned.
A batch that exceeds the available budget is not retained; individual rows are admitted if they fit.
Since B05, a row that cannot fit a deliberately reduced cache budget reports an explicit unavailable
state rather than repeatedly loading or retaining data outside the budget. The default budget fits
every B01-supported individual line. Content is never shortened. Publication releases the batch and removes the old
final row's weight; replacement resets cache accounting.

Accounting deliberately overestimates shared/compact text: a string costs 64 bytes plus its
UTF-16 length times two, rounded up to eight bytes. Each cached row adds 192 bytes for the row,
key, map entry and amortized table overhead; error messages are charged as strings too. A batch
adds 128 bytes, its aligned raw payload, eight bytes per line reference, and all decoded string
weights. Text shared between a batch and cache is charged twice, avoiding fragile identity-based
accounting. These are conservative estimates, **not exact JVM heap sizes**.

The budget is per list, not global: tabs multiply it. Fixed list/map objects, indexes and retained
snapshots, listener-held removed-item lists, read/decode temporaries, rendered/selected rows,
concurrent scans and occurrence results are outside this accounting. During reset notification,
the removed-items wrapper can retain the prior cache as well as the new cache; listeners may
retain it longer. Since B05, cache-miss reads run on bounded background workers.

Comparable retained-data counter evidence on Linux 7.2.11 amd64, btrfs, Azul OpenJDK 27+35,
with `-Xms128m -Xmx128m -XX:+UseSerialGC` (reported max heap 134,152,192 bytes): two lists sharing
one model sequentially read a 12,583,056-byte UTF-8 fixture containing 24 half-MiB ASCII lines
interleaved with 24 short lines and a final empty row. Using the same accounting formula before
and after B03, combined peak retained-data weight fell from **53,503,968 to 15,736,768 bytes**
(26,751,984 to 7,868,384 per list), including both retained batches. The shared model had 49
offsets (392 logical offset bytes, excluding index chunk/directory overhead), one current
snapshot, no historical snapshots, pending tasks, scans or occurrence results. Read temporaries
were excluded. This is historical B03 evidence, not a heap profile or throughput claim. The probe
now awaits asynchronous B05 loading; prefetch can change the observed cache weights. Run after `mvn test`:

```bash
mvn -q dependency:build-classpath -Dmdep.outputFile=target/cache-probe-classpath.txt
java -Xms128m -Xmx128m -XX:+UseSerialGC \
  -cp "target/classes:$(<target/cache-probe-classpath.txt)" src/test/probes/WeightedCacheProbe.java
```

#### Bounded background scans (B04)

Occurrence searches and highlight counts share one `ScanService` with **two daemon workers**
and **eight waiting jobs** (`DEFAULT_WORKERS`, `DEFAULT_QUEUE_CAPACITY`). Overload never runs
a scan on the JavaFX thread: the view reports capacity exhaustion instead. Searches show
queued/running/cancelled/error states and offer a **Cancel search** button; click the highlight
again to refresh or retry. Counts show queued/running/unavailable states. Superseded count jobs
are coalesced, and queued cancellations immediately free their queue slots.

Workers capture snapshot boundaries and separate rule inputs. Per-job tokens are checked when
callbacks reach the FX thread, so refreshed/closed tabs cannot receive stale results. Closing
source or occurrence tabs cancels their work, including programmatic tab removal. Orderly
application shutdown cancels all scans and releases queued work without waiting on matching.
Snapshot read failures publish no partial search results or successful counts.

Cancellation is cooperative between reads/matches and interrupts running workers, but **Java
regex matching may ignore interruption**. A stuck match continues occupying its worker; no
replacement thread is created. Thus B04 bounds worker/queue counts, **not regex execution time**
or total memory. Adversarial patterns can still occupy both scan workers, and visible-row regex
highlighting still runs on the FX thread. A safe regex policy remains B18. Matching result text
is still retained in memory until B07/B08; this service does not establish a global heap budget.

Scheduling-counter evidence on Linux 7.2.11 amd64, Azul OpenJDK 27+35, using
`-Xms64m -Xmx64m` (67,108,864-byte max heap): 100 independent latch-blocked requests produced
**100 active threads** with a reproduction of the former one-thread-per-search launch policy.
The same workload through `ScanService` produced **2 active workers, 8 queued jobs, and 90
explicit rejections**. Work deliberately ignores interruption until released. This compares
scheduling policies, not end-to-end scan throughput, regex safety, or retained heap; there is no
file I/O or decoded-result fixture, so storage is not involved. Reproduce after `mvn test`:

```bash
java -Xms64m -Xmx64m -cp target/classes src/test/probes/ScanConcurrencyProbe.java
```

#### Asynchronous visible-line loading (B05)

`VirtualLogList.get()` now only looks up cached data or submits a request; it does **no file I/O**.
`LineLoadingService` reads and decodes on **two daemon workers**, independent of long scans, with
**16 waiting slots** (`DEFAULT_WORKERS`, `DEFAULT_QUEUE_CAPACITY`). Admission also counts decoded
results awaiting FX delivery: at most **18 live requests/results**, with one coalesced delivery
callback per service. A blocked read keeps its physical worker until it exits, even after cancellation.

Each list admits at most **two pending requests** (`MAX_PENDING_READS`), coalesces nearby misses
within **64 rows** (`NEARBY_REQUEST_LINES`), and abandons obsolete pending work on large viewport
jumps. Demand reads precede queued prefetch. A successful demand read can prefetch **one adjacent
batch** (up to B01's 1 MiB/1,000-row limits); prefetch never chains. Saturated demand requests retry
on the FX thread after **100 ms** (`RETRY_DELAY_MILLIS`) without performing reads there.

Loading and unavailable rows are nonselectable presentation states, not searchable/copyable text.
Range selection checks only already-loaded content in both source and occurrence tabs; it does not
request offscreen rows or cancel viewport reads. Speculative prefetch uses spare cache capacity only,
without evicting demand-loaded rows or replacing their retained batch. A visible request for a pending
prefetch promotes it to demand work. Regression tests cover large selections without new read jobs
and preservation of loaded visible cells across selection/layout and adjacent prefetch completion.
Failed reads show their error; use **Retry unavailable lines** in the context menu to retry.
Occurrence navigation selects its target only once loaded, unless superseded by navigation or
a file reset. Cache admission, list updates, and rendering remain on the owning FX thread.
Generation resets cancel outstanding reads. Append publications restrict each pending read to its
demonstrably unchanged prefix, excluding every formerly final row; request tokens reject obsolete
completions. This avoids starving stable rows during continuous appends. Unchanged cached rows
survive appends, while the old final row is invalidated. Closing a tab releases its cache and cancels reads; application shutdown closes the
shared controller-owned loader without waiting for slow storage.

Reset notifications retain cached removed rows; uncached historical rows explicitly report
unavailability rather than doing synchronous historical reads or substituting replacement content.
The 8 MiB accounted-data cache budget remains **per list**, not a global heap bound. Two reads may
temporarily coexist with each tab's cache; the service-wide admission bound also covers completed
payloads awaiting publication. Each admitted batch is limited to 1 MiB encoded bytes plus decoded
strings and object overhead; decode temporaries, indexes, selected/rendered rows, scans, and occurrence
results remain outside cache accounting. Initial indexing has a separate worker pool (B06).

JFR counter evidence on Linux 7.2.11 amd64, btrfs, Azul OpenJDK 27+35, `-Xms128m -Xmx128m`
(134,217,728-byte maximum heap): one tab visited 20 batch starts in a 5,120,000-byte ASCII fixture
(20,000 256-byte rows plus an empty final row). Reproducing the former synchronous miss policy
produced **60 calling-thread file reads**. The B05 list path produced **zero calling-thread reads**
and **60 worker reads**. `jdk.FileRead` used a zero threshold; indexing was excluded and local storage
was warm. This verifies read placement, not FX latency, exact heap usage, or faster disk throughput.
The probe cleans up its fixture/recordings under `target`. Reproduce after `mvn test`:

```bash
mvn -q dependency:build-classpath -Dmdep.outputFile=target/line-read-probe-classpath.txt
java -Xms128m -Xmx128m \
  -cp "target/classes:$(<target/line-read-probe-classpath.txt)" src/test/probes/AsyncLineReadProbe.java
```

#### Asynchronous file opening (B06)

Opening a file immediately creates a tab with queued/indexing progress and **Cancel opening**.
`FileOpenService` constructs and initially indexes the model on a separate bounded pool: by default
**2 workers and 8 queued jobs**, independent of scans and visible-line loading. A full queue produces
an inline error, never caller-thread indexing. Cancelled, failed, or rejected tabs offer **Retry opening**;
closing a loading tab removes its queued work or cancels its active job. Shutdown cancels all opening
work without waiting on the FX thread. Late callbacks cannot attach content to closed or disposed tabs.
Following and highlight-group choices remain per tab during opening; counts, searches, and tail polling
start only after the ready model is attached on the FX thread.

Indexing checks cancellation between reads, every 1,024 scanned bytes, and before publishing its index.
Cancellation throws a controlled `InterruptedIOException` and never publishes partial offsets.
This is cooperative cancellation, **not a timeout for a blocked OS/filesystem read**. Identity, size,
timestamp, and bounded-tail validation reject detected truncation/replacement/rewrite races during
indexing with a visible error; retry opens the current file. Appends beyond the captured boundary can
be handled by normal tailing after attachment. Snapshot and metadata-preserving-rewrite limitations
above still apply. Offset-index memory still grows with line count; background opening does not impose
a global heap budget. Configuration/history disk operations remain synchronous until B14.

JFR counter evidence on Linux 7.2.11 amd64, btrfs, Azul OpenJDK 27+35, `-Xms128m -Xmx128m`
(134,217,728-byte maximum heap): one initial open of a sparse 67,108,864-byte newline-free fixture
(one indexed row, no content decoding). Reproducing the former calling-thread construction policy
with the current model produced **4,099 calling-thread file reads**. B06 produced **zero calling-thread
reads** and **4,099 worker reads**. `jdk.FileRead` used a zero threshold. This measures placement of
indexing reads, not UI latency, exact heap use, or improved disk throughput; the caller in the probe
stands in for the FX thread. JavaFX regression tests separately verify a responsive animation while
opening work is blocked, plus cancellation, overload, retry, follow preservation, and late-result guards.
The probe cleans up its fixture/recordings under `target`. Reproduce after `mvn test`:

```bash
mvn -q dependency:build-classpath -Dmdep.outputFile=target/file-open-probe-classpath.txt
java -Xms128m -Xmx128m \
  -cp "target/classes:$(<target/file-open-probe-classpath.txt)" src/test/probes/AsyncFileOpenProbe.java
```

#### Identifier-backed occurrence results (B07)

Occurrence scans retain **zero-based original line identifiers**, not matching `LogLine` objects or
their text. `ChunkedOccurrenceIndex` stores primitive integers in **4,096-entry chunks**. Completed
chunks transfer from a single-use builder; only the final partial chunk is trimmed. Publication
attaches the immutable index on the FX thread without copying one reference per match. Counts and
original line numbers remain exact, including captured empty/final rows; appends do not extend an
existing result set or its captured final-line text.

Each result tab lazily reads its captured snapshot through the shared bounded `LineLoadingService`,
using the same **8 MiB accounted cache / 1,000-entry ceiling** as a file view. Loading/error rows are
nonselectable, cannot be copied, and cannot navigate. Once loaded, whole-line selection/copy and
double-click navigation use the original line numbers. Copying a selection that includes an unloaded
or unavailable row fails explicitly rather than omitting it. The context-menu retry action retries
read failures, but an invalidated snapshot requires a new search.

Occurrence caches retain requested matching rows only, not surrounding file rows or whole batches.
At most **two reads per result view** are pending; additional demands wait in a deduplicated queue
bounded by the **1,000-entry ceiling**, with oldest deferred demands dropped on overflow. Adjacent
source batches are not prefetched for occurrence views. This prevents distant visible matches from
cancelling or evicting each other's loads. Completion notifications cover cached rows only; ordinary
appends do not refresh already-loaded results. The sparse/dense regression visits 12 rows spaced
2,000 source lines apart while appending, then checks stability across 20 further appends.

Detected generation changes discard cached result text, cancel reads, and mark all result rows
unavailable; a failed historical read never substitutes replacement-file text. The existing bounded
metadata/tail detection limitations still apply. Refresh, result/source-tab close, and shutdown release
the result index and cache and suppress pending completions. Failed/cancelled searches publish no
partial index. Scan decoding still allocates transient batches, but matching text is not retained by
the result store.

The original B07 index uses **4 bytes per match**, plus chunk/directory/object overhead; searches now
use B08's spillable store below instead of retaining an unbounded chunk directory. The existing `ListView` and
model integer line-count ceiling remains. Multiple result tabs each own a cache and an identifier
index; in-flight reads, model indexes, snapshots, selection/rendering, and scan temporaries remain
outside any one cache's accounted budget. This is not a global heap bound.

Reproducible counter evidence on Linux 7.2.11 amd64, Azul OpenJDK 27+35, Serial GC,
`-Xms512m -Xmx512m -XX:-DoEscapeAnalysis` (536,805,376-byte maximum heap): for **1,000,000 generated
all-match rows**, the former text-bearing accumulation retains **187,888,890 source-text characters**;
the primitive result store retains **zero** source-text characters and **4,000,000 bytes of integer
payload**, falling to zero after close. Thread allocation counters for generation/matching/accumulation
were **430,586,264 bytes** for the former list and **396,025,864 bytes** for the index. These are
allocation counters and retained-content counts, **not measured retained JVM heap or throughput**.
The synthetic workload has no disk I/O and excludes the model index, FX publication and visible-row
caches. Reproduce after `mvn test` (requires JDK thread-allocation counters):

```bash
java -Xms512m -Xmx512m -XX:+UseSerialGC -XX:-DoEscapeAnalysis -cp target/classes \
  src/test/probes/OccurrenceIndexProbe.java
```

#### Spillable occurrence identifiers (B08)

Searches use `SpillableOccurrenceIndex`: up to **65,536 identifiers (256 KiB primitive payload)**
stay in memory. Beyond that threshold the builder streams identifiers, not log text, into owned
temporary storage. Its buffer and final store have no growing per-page directory. Searches still
publish only complete results: disk-full/write failures show an incomplete-search error, never a
silently shortened match count. The `ListView`/model ceiling remains **2,147,483,647 lines/results**;
spilling does not remove it or bound the source offset index.

Spilled result tabs cache at most **4 pages of 4,096 integers (64 KiB primitive payload)**.
`OccurrencePageService` reads pages off the FX thread with **2 workers and 16 queue slots**,
independent of source-text loading and scans. Admission includes completed pages awaiting delivery,
and one coalesced dispatcher bounds pending callbacks. Each tab requests at most **2 identifier pages**
at once. Rendering and selection inspect cached identifiers only; unloaded identifiers use a separate,
nonselectable loading state, followed by normal lazy text loading. Page errors remain explicit and
retryable without changing the result count. Original line numbers, copy/navigation and captured
snapshot boundaries are preserved. Identifier pages are not prefetched.

Refresh, cancellation, result/source-tab close and normal shutdown release owned storage, including
completed results cancelled before publication. Safe abandoned-file cleanup runs during spill setup;
active ownership locks protect other running instances, and foreign/unrecognized files are left alone.
Cleanup failures are reported/logged, not treated as successful deletion. Storage grows by approximately
**4 disk bytes per match**, subject to free space; it is not a disk quota. Cancellation remains
cooperative for blocked filesystem calls. Per-tab text caches, in-flight page/read buffers, source
indexes, snapshots, rendering and selections remain additional memory: these payload budgets are not
exact JVM heap measurements or a global application memory bound.

Constrained-heap evidence: Azul OpenJDK **27+35**, Linux **7.2.11-arch1-1 amd64**, `-Xmx32m`
(33,554,432-byte maximum heap), **10,000,000 generated ascending identifiers**. The B07 chunked
store exhausted heap during accumulation. B08 completed and verified every identifier, retained
**zero primitive payload bytes in the final disk-backed index**, wrote **40,000,000 filesystem bytes**,
and deleted its owned directory on close. The threshold buffer (up to 256 KiB), read buffers and
visible-page caches are additional; zero here does not mean zero heap usage. Temporary storage was
Linux `/tmp` on **tmpfs**: filesystem contents consume memory outside the Java heap, so this run is
not evidence of bounded total machine memory or physical-disk throughput. Use disk-backed temporary
storage when large spills must not occupy tmpfs RAM. Reproduce after `mvn test`:

```bash
javac -cp target/classes -d target/probes src/test/probes/SpillableOccurrenceIndexProbe.java
java -Xmx32m -cp target/classes:target/probes SpillableOccurrenceIndexProbe spill 10000000
# Expected baseline outcome: OutOfMemoryError in the original chunked builder.
java -Xmx32m -cp target/classes:target/probes SpillableOccurrenceIndexProbe chunked 10000000
```

Set `-Djava.io.tmpdir=/path/to/disk-backed-temp` on the application/probe JVM to choose a different
temporary filesystem. Safe cleanup only examines LumineLog-owned spill directories under that root.

#### Adaptive shared tail polling (B09)

File tabs share `TailScheduler`: **2 indexing workers, 8 queued jobs**, and one shared daemon
dispatcher, rather than one timer thread per file. Each file has at most one queued/running update;
callbacks count as in-flight work. Polling uses a monotonic clock and one next-due wakeup per scheduler,
not a permanent fixed-rate dispatcher tick. Due-order admission prevents an actively changing file
from repeatedly overtaking waiting files; saturated admission retries after **50 ms**, without
running file I/O on the dispatcher or FX thread. Indexing is independent of Follow Tail.

After a detected change, the next check is due in **50 ms**. Unchanged/error checks back off from
**100 ms**, doubling to **2 seconds** for inactive files or **250 ms** for the selected file.
Selection requests an immediate check, without overlapping existing work. These are scheduling
intervals, not hard end-to-end detection deadlines: queueing, indexing and filesystem latency are
additional. One blocked file leaves the other worker available; two blocked filesystem calls can
occupy both workers. Cancellation is cooperative, not a filesystem timeout. Consecutive errors are
logged once until a successful check resets error suppression. Closing a tab or shutting down removes
queued work, interrupts indexing and suppresses late publication; shutdown does not await blocked I/O.

Inactive tabs retain only the newest pending snapshot and defer list notifications, tail positioning,
footer updates and highlight recounts until activation. Activation publishes that snapshot before
updating the visible view, preserving the per-tab follow setting. Detected rewrites still invalidate
occurrence results promptly even while their source tab is inactive; stale completed searches cannot
attach replacement-file results. Appends leave captured occurrence results unchanged. Source offset
indexes and published/retained snapshots remain outside the cache budgets. No filesystem watcher is
included; view animation eligibility is handled separately below.

Reproducible **simulated operation-count evidence** from `TailSchedulerTest`: **50 idle readers** over
`[0, 60000)` milliseconds, a manual clock advanced in 1 ms ticks, zero-duration reads and a bounded
manual FIFO queue of 8 drained after each dispatch, with 50 ms saturation retries. The old 50 ms policy
performs **60,000 checks (1,000/second)**; adaptive polling performs **1,700** with all files inactive or
**1,905** with one selected file. This measures scheduler check counts, not real filesystem operations,
OS wakeups, CPU usage or wall-clock speedups; it needs no storage fixture or special heap setting.
Reproduce after compilation with:

```bash
mvn -Dtest=TailSchedulerTest test
```

#### Eligible-only view animation (B10)

`LogListView` starts its animation timer only for runnable tail positioning or edge-drag scrolling.
Pending tail positioning waits while a view is hidden (including hidden ancestors/inactive tabs),
detached, zero-height, in a hidden/minimized window, or pressed. Visibility, attachment, window,
layout and mouse-release changes resume eligible work without changing Follow Tail. Tail positioning
still uses two layout pulses. Interior dragging, reaching the first/last row, cancelling a gesture,
disabling follow without drag work, and disposing the view stop the timer immediately. Edge scrolling
retains its 16-pixel margin and 60 ms interval; selection is unchanged while animation is suspended.
Disposal removes ancestor/window observers and prevents later events from restarting animation.

Measured callback evidence from JavaFX interaction regressions: one 100-row, 500 × 300 view,
JavaFX **23.0.2**, Azul OpenJDK **27+35**, Linux x86-64, default Maven test JVM heap settings and
in-memory rows (no storage fixture). Over each **150 ms pulse window**, the pre-fix implementation
received **11 callbacks while hidden**, **2 with a hidden ancestor**, and **10 while pressed**.
The same tests after the fix receive **zero** in each suspended state, then verify that tail work
finishes after visibility/release. Counts vary with pulse timing; these are observed callback counts,
not measured CPU savings, OS wakeups or a guarantee that JavaFX itself stops pulsing. Additional
tests cover zero height, reparenting, inactive tabs, window hiding, drag edges and disposal. Reproduce:

```bash
mvn -Dtest=LogListViewTest test
```

#### Cached regex compilation failures (B11)

`HighlightRule` caches both successful and failed regex compilation across matching, occurrence
counting and span lookup. Gson-loaded rules initialize this transient cache on first use. Changing
the pattern or matching mode resets it; unchanged inputs and color-only edits do not. Invalid
expressions still produce no matches, and cache state is not saved in configuration. Concurrent
first use shares one compilation attempt. Count workers capture immutable matching inputs (B12);
Java regex execution time remains unbounded (B18).

Measured operation-count evidence from `HighlightRuleTest`: for invalid expression `[` and input
`error error`, 1,000 calls to each of the three matching operations previously caused **3,001**
compilation attempts for a constructed rule (including construction), or **3,000** for a Gson-loaded
rule. Both now make **one** attempt. The tests count calls at the actual regex compiler boundary;
these are deterministic compilation counts, not measured allocation totals or timing speedups.
Reproduce with:

```bash
mvn -Dtest=HighlightRuleTest test
```

#### Reusing highlight counts (B12)

Count identity is the ordered list of immutable `(pattern, matching mode)` signatures, independent
of rule-object identity, color, and compilation state. Reloading equivalent rules or changing only
colors reuses completed counts for the same snapshot without scanning; pending equivalent work
continues and publishes with the latest rules/colors. Worker inputs are captured before submission,
and results for edited matching inputs or invalidated generations cannot replace current counts.
Pattern/mode/order changes trigger a full recount. Appends reuse counts before the previous final
line and recount that line, including appends that complete a match without adding a newline.

Deterministic regression evidence uses a two-line file containing `ERROR\nERROR`. Before the fix,
the first equivalent-rule replacement launched another count job; afterwards that request launches
zero jobs, and 100 alternating red/blue equivalent replacements retain the same count array with no
pending scan. Tests reset the count throttle to avoid mistaking delayed work for reuse. These are
job-launch/cache-reuse observations, not measured heap reductions or timing speedups. Held-callback
and blocked-worker tests also cover edits before execution/delivery and latest-color publication.
Reproduce with:

```bash
mvn -Dtest=HighlightRuleTest,LogListViewTest test
```

### Building a package

```bash
# Clean:
mvn clean package
# Provision JDKS:
mvn -Pjdks
# Copy JARs:
mvn -Pjars
# Assemble Jlink distributions
mvn -Passemble
```

## Releasing 
```bash
mvn jreleaser:full-release -Djreleaser.dry.run=true
```

## License

This project is licensed under the **GNU General Public License v2.0**. See the `LICENSE` file for details.

## Author

Copyright (c) 2026 Robert von Burg <eitch@eitchnet.ch>
