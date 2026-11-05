# The live notes of a local model: measured on more lectures, filtered before the student sees them

Phase 4 of [docs/local-models.md](./local-models.md) would put the assistant on the device, and the
only measure of its live notes says they are not good enough: on one lecture, Gemma 4 E4B wrote 53
notes, 6 that add to the slides, 35 that repeat them and 10 wrong. This plan finds out, before any of
that code is written, whether a local model with checks after it can write notes worth showing. It
touches the bench only: nothing in the application changes until its verdict.

It starts from `main` at d4b0c35e (2026-11-04) and continues what
[docs/lecture-cost.md](./lecture-cost.md) left for later ("A bench of more lectures"), with the
lectures of [docs/lecture-eval.md](./lecture-eval.md), whose plan on the PC is closed.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

1. **The bench.** `NotesBench` (jvmTest of `drawview-lecture`, run with `-Pdrawview.bench`) replays a
   whole lecture through `LecturePrompts.chunkPrompt`, a request every 5 minutes, on the notebook
   without the notes of the assistant (`BenchLecture.untouched`). `e4b-text` runs Gemma 4 E4B on
   LiteRT-LM, the pages near the visible one plus 3 found by searching the notebook; a Gemini
   configuration runs the same requests, `-cached` opening them with all the slides.
2. **One lecture.** `NotesBench` takes one `lectureId`, and `tools/notes-bench/grade_notes.py` has
   that lecture and its paths written in it. It reads its files in the encoding of the system, which
   on Windows is not UTF-8.
3. **The grader.** Gemini 3.8 Flash scores a set of notes on the questions of the eval set that the
   slides do not answer, and gives every note a verdict: `ADDS`, `SLIDE`, `TRIVIAL`, `WRONG`. It is
   noisy: the same set graded twice got 72% and 63%, and 4 verdicts of 16 changed.
4. **What it measured, on the lecture of 2026-10-15 (72 minutes):**

   | Configuration | Questions | Notes | Add | Repeat | Wrong |
   |---|---|---|---|---|---|
   | Gemini 3.8 Flash through the recorder, every 5 min, slides first | 72% | 17 | 9 | not recorded | 0 |
   | Gemma 4 E4B, every 5 min | 61% | 53 | 6 | 35 | 10 |

5. **Why E4B cannot take the slides first.** The bench gives it 16,384 tokens, and the slides of the
   course of distributed systems are 49,000 characters. So it sees only a few pages and cannot know
   what the others say. A check after it can read them all: whether that is enough is what this plan
   measures.
6. **Nothing checks a note today.** What the model answers is what the student is offered.
7. **The lectures.** The workspace `~/lecture-eval-2` on the PC keeps 16 lectures with their split:
   11 dev, 5 test. The questions are in `exports/evalset-2026-10-18-3.json`. The test lectures have
   never been run through `NotesBench`.
8. **This computer has none of it yet:** no `~/drawview-bench`, no model of LiteRT-LM, no
   `GEMINI_API_KEY`. The natives of LiteRT-LM were checked on Windows, but E4B has only been timed on
   the Mac.

## Requirements

1. The notes of a local model are measured on 4 dev lectures of 4 courses, against Gemini on the same
   lectures, the differences read beyond the noise of the grader.
2. Every check is tried and chosen on dev. The 5 test lectures are measured once, on the choice made.
3. A verdict comes out with the thresholds below: go on with phase 4 of
   [docs/local-models.md](./local-models.md), or keep the live notes off the local models.
4. **Spending:** at most $6 of Gemini in all, the grader and the runs stopping at that cap and saying
   what they spent. A phase says its estimate first. Only the text of the slides, the notes and the
   transcript go to Gemini, as the assistant sends them already: no audio.
5. **The data of the user:** the workspace and the answers of the bench stay out of the repository, in
   `~/lecture-eval-2` and `~/drawview-bench`. The bench reads the copy in the workspace, never a data
   directory of PencilNotes.
6. Nothing about the exam: no check, cue or measure about it.
7. The application, the stored formats, the protocol and the published APIs do not change: `checkKotlinAbi`
   stays as it is. Only the jvmTest of `drawview-lecture` and `tools/notes-bench` change.

### The lectures

| Course | Dev (phases 2–4) | Test (phase 5) |
|---|---|---|
| Real-time systems | `2026-10-15_09-48-14-2ce0b6` (23 questions) | `2026-10-15_09-32-08-fed1f6` (5) |
| Distributed systems | `2026-10-12_09-48-46-bdc931` (22) | `2026-10-16_09-51-00-d882f1` (17), `2026-10-16_10-39-59-32f14f` (14) |
| Security | `2026-10-12_14-02-53-a73332` (29) | `2026-10-13_14-52-54-d2c69a` (19) |
| Machine learning | `2026-10-13_11-36-52-928359` (26) | `2026-10-14_10-01-25-872626` (16) |

### The thresholds

Summed over the lectures, on the notes that reach the student:

| Measure | Passes at | Why |
|---|---|---|
| Wrong notes | 5% or less | Gemini writes none; E4B one out of five today. A wrong note in the notes of a student is the worst error |
| Notes that add | 50% or more | Gemini through the recorder: 9 of 17 |
| Questions | Within 15 points of Gemini | The noise of the grader alone is about 10 |
| Notes per hour | At most twice Gemini's | A student cannot read more during a lecture |
| Time | The answer and its checks within 100 s of a 5-minute interval, on this PC | The notes must not fall behind |

## Design

### Responsibilities

| Responsibility | Class (where) | GRASP |
|---|---|---|
| A run on several lectures, the answers kept by lecture and configuration | `NotesBench` (`lectureIds`, `<bench>/answers/notes/<lecture>/<config>.json`) | Controller |
| A lecture read from the copy of the workspace, without a `material` directory | `BenchLecture.read` (`<workspace>/snapshot`) | Information Expert |
| What a note must pass, in order, and why it stopped: kept or dropped with its reason | `NoteChecks` (jvmTest), a list of `NoteCheck` | Pure Fabrication, Polymorphism |
| A note that repeats a slide, or another note, by the words they share | `RepeatCheck` : `NoteCheck` | Information Expert |
| A note whose words the lecturer did not say, against the transcript of its interval | `SaidCheck` : `NoteCheck` | Information Expert |
| A note judged by the model, given the closest pages and the transcript | `ModelCheck` : `NoteCheck`, its prompt in the bench | Polymorphism, Protected Variations |
| A note with the words of the lecturer it comes from | A configuration of `NotesBench` (`-said`), adding `said` to the schema of the request | Protected Variations (the schema of the application is not touched) |
| The grades, by lecture and configuration, kept by the text of a note so that it is never paid twice; the estimate; the cap; test refused without `--test`; the sums against the thresholds | `tools/notes-bench/grade_notes.py` | Information Expert, High Cohesion |

If the verdict is to go on, phase 4 of [docs/local-models.md](./local-models.md) moves `NoteChecks`
into `drawview-lecture`, behind `DeviceBackend`, with its own design.

### The candidates

From the cheapest to the dearest; each is kept only if it moves the measures beyond the noise.

1. **Today** (`e4b-text`): the baseline.
2. **The rules** (`RepeatCheck`): the words of a note against the text of every slide, also those the
   request did not hold, and against the notes before it. The threshold comes from the verdicts of
   the grader on the notes of today. It costs nothing on a tablet.
3. **The words said** (`-said`, `SaidCheck`): the model gives the words of the lecturer each note comes
   from, and a note whose words are not in the transcript of the interval is dropped. This is aimed
   at the wrong notes, and asks no second request.
4. **The model as a judge** (`ModelCheck`): a short request per note that passed, with the closest
   pages found by the search and the transcript around it: adds, repeats or not said. It is the only
   candidate that costs time, so its time is measured with it.
5. **A cap:** at most so many notes per interval, the last resort if the others leave too many.

### The measures, and what they cost

The grader judges a note once per text, so a filter is measured on verdicts already paid: dropping
notes needs only the score on the questions again. The raw notes of a configuration are judged twice,
and a threshold is calibrated only on the verdicts that did not change.

Estimated from the sizes of the lectures (about 60,000 characters of transcript, up to 49,000 of
slides) and the prices of 3.8 Flash ($0.75 / $3.75 a million tokens until 2026-12-31): about $0.04 to
judge a set of notes, $0.02 to score it, $0.08 for a run of Gemini.

| Phase | What is paid | Estimate |
|---|---|---|
| 2 | Gemini twice on 4 lectures; Gemini and E4B graded twice | $1.6 |
| 3 | The score of 3 filters on 4 lectures | $0.3 |
| 4 | 3 candidates on 4 lectures, graded twice | $1.5 |
| 5 | Gemini on 5 shorter lectures, Gemini and the choice graded | $0.6 |
| **All** | | **$4, cap at $6** |

## Plan

Each phase: the estimate, the run, the sums against the thresholds written here, a commit. A phase
that fails its check stops the plan there, with what was learned.

| Phase | Content | Check | Status |
|---|---|---|---|
| 1 | The tools, no paid request: `NotesBench` on several lectures, `BenchLecture` from the workspace, `grade_notes.py` with its arguments, UTF-8, verdicts kept, estimate, cap and sums; tests on a small lecture; on this PC, E4B and EmbeddingGemma 2 downloaded (about 3.8 GB) into `~/drawview-bench`, and one lecture timed | A request within 100 s, or the plan moves the runs to the Mac | Tools done; the check failed (171 s on average, 214 s the longest, on the processor): the user chose to go on here, and the threshold, unchanged, is judged on the Mac at the end |
| 2 | The baseline on the 4 dev lectures: Gemini and `e4b-text`, each run and graded twice | The lecture of 2026-10-15 close to the figures of the Starting point, and the noise written down | Done; the check holds, except that E4B writes more notes (67 against 53) |
| 3 | The rules: `NoteChecks`, `RepeatCheck`, the threshold from the stable verdicts | Measured against the baseline | Not started |
| 4 | The words said, then the model as a judge, then the cap if needed; the choice | One candidate passes the thresholds on dev, or the verdict is written. If none does, one run of Gemma 4 12B on 2 lectures, to tell the limit of the model from the limit of the checks | Not started |
| 5 | The test, once: the choice and Gemini on the 5 test lectures; the verdict in [docs/local-models.md](./local-models.md) | The thresholds on test. They are not tuned on it: a miss is the verdict | Not started |

### Phase 1 results (2026-11-04)

* **The tools.** `NotesBench` takes `drawview.bench.noteLectures`, keeps a run by lecture and
  configuration, and reads it back instead of paying again; `#<n>` labels a repetition. The grader
  keeps every verdict and score, estimates (`--estimate`), stops at the cap (`--cap`, with
  `<bench>/dollars.txt` shared with the runs) and refuses test without `--test`. The set of the notes
  saved by the application is no longer graded: it comes from a prompt `main` has replaced.
* **The downloads.** E4B and EmbeddingGemma 2 in `~/drawview-bench/models`, each verified by its SHA-256.
* **The time, on the lecture of 2026-10-15** (`e4b-text`, 15 requests, 67 notes): 2,560 s in all, 171 s
  a request, 214 s the longest. It keeps up with an interval of 300 s, but not with the 100 s of the
  check. The bench of the models says why: on this JVM **the GPU of the PC cannot be used**, as
  [docs/local-models.md](./local-models.md) already found, so E4B runs on the 24 processors. The
  threshold of 100 s was set without looking at that, so this figure says more of the desktop JVM than
  of what a computer with its GPU can do.
* **2 answers of 15 could not be read** as the JSON of the notes (both broken at about 4,000 characters), so those 10 minutes wrote no note.
  They count as failed in the runs of phase 2 as well.
* **What it means for the runs.** The time does not change the notes, only how long they take: about
  43 minutes a lecture here, so about 6 hours for the 4 dev lectures twice.
* **The choice of the user:** the quality is measured here, on the processor, and the time, with its
  threshold unchanged, on the Mac at the end.

### Phase 2 results (2026-11-05)

Summed over the 4 dev lectures, the verdicts of the two passes pooled:

| Configuration | Notes | Per hour | Add | Slide | Trivial | Wrong | Questions | Longest request |
|---|---|---|---|---|---|---|---|---|
| `e4b-text` | 280 | 50 | 25% | 60% | 4% | 11% | 66% | 214 s |
| `gemini-3.8-flash-cached-text` | 60 | 11 | 88% | 9% | 2% | 0% | 71% | 9 s |
| `gemini-3.8-flash-cached-text#2` | 54 | 10 | 81% | 12% | 6% | 0% | 71% | 13 s |

* **Against the thresholds.** E4B misses the wrong notes (11% against 5%), the notes that add (25%
  against 50%), the notes per hour (50 against 22) and the time; its questions are within 15 points.
* **The lecture of 2026-10-15 against the Starting point.** Questions: E4B 57% (61% before), Gemini 68%
  and 66% (72%). Notes that add: E4B about 11 (6), Gemini 12 and 8 (9). Wrong notes: E4B about 11 (10),
  Gemini 0. All close, but for the notes of E4B: 67 against 53, not looked into.
* **The noise of the runs.** On this computer `e4b-text#2` gave the same notes as `e4b-text`, word for
  word, on all 4 lectures, so a second run of E4B measures nothing and the next phases need only one.
  Gemini changes: 15 and 8 notes on the same lecture, 60 and 54 in all.
* **The noise of the grader.** 353 verdicts of 393 notes are the same in both passes (90%): 164 `SLIDE`,
  154 `ADDS`, 24 `WRONG`, 11 `TRIVIAL`. The most frequent change is `ADDS` to `SLIDE` (18). The same set
  scored twice on the questions differs by up to 12 points on a lecture, and for E4B by less than 1
  point on the sum.
* **Failed requests.** E4B: 10 answers of 68 could not be read as the JSON of the notes, so those
  intervals wrote no note. Gemini: none.
* **What the checks must do.** With every note that adds kept, reaching 50% and 22 per hour means
  removing more than half of the 280 notes, and at most about 7 wrong ones of the 31 may stay.
* **The money.** $0.61 for the runs and $0.84 for the grades (the estimate said $1.13): $1.45 of $6.

## Decisions

Taken alone, revertible (R):

* **E4B first, E2B not measured here** (R): a tablet has no transcript yet (whisper does not run
  there, `consistent` makes it record only), so its notes wait for the speech anyway. If E4B does not
  pass on a computer, E2B will not.
* **LiteRT-LM, not Ollama** (R): it is the runtime phase 4 would ship, and the one tablets can have.
* **Gemini as the grader** (R): it is the instrument of the figures above, and the local matchers of
  [docs/lecture-eval.md](./lecture-eval.md) failed at a simpler judgement.
* **The checks after the model, not a new prompt first** (R): they can be read and measured one by
  one, and they carry over to any model.
* **The thresholds above** (R): proposed from the only figures there are, and fixed before the runs.

Left to the user, with the approval of this plan:

* **The spending** of requirement 4.
* **Where the runs go:** this PC with a `GEMINI_API_KEY` given for this plan, which replaces the rule
  of no paid API of [docs/lecture-eval-local.md](./lecture-eval-local.md) for this cap only; or the
  Mac, where the bench already stands.

## Left for later

* **Whisper on the tablets**, without which they write no notes whatever this plan says.
* **An entailment model** (the candidate of [docs/lecture-eval.md](./lecture-eval.md), on ONNX
  Runtime, a second runtime): if the rules and the judge leave repeats, and only then.
* **The extractive pipeline** of the same plan, the sentences of the lecturer shown instead of
  generated notes.
* **A lecture without slides** (`2026-10-16_11-34-00-22cfc7`), where "repeats the slides" has no
  meaning.
* **A local grader**, once the notes have one worth keeping: the local matchers say it is not simple.
