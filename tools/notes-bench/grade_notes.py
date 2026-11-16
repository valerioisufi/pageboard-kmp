"""Grades the note sets of NotesBench against the thresholds of docs/plans/local-notes.md.

Every note is judged once per pass and every set of notes scored once per pass: what was paid stays in
<bench>/grades/<lecture>.json. All the dollars are counted in <bench>/dollars.txt, with those of the
runs of NotesBench, and the requests stop at the cap. The lectures of test are refused without --test.

    python grade_notes.py --lectures <ids> --configs <names> [--reference <config>] [--passes 2]
        [--estimate] [--test] [--cap 6] [--bench ~/drawview-bench] [--workspace ~/lecture-eval-2]
"""
import argparse
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.request

GRADER = "gemini-3.8-flash"
INPUT_DOLLARS, OUTPUT_DOLLARS = 0.75, 3.75  # a million tokens, until 2026-12-31
CHARS_PER_TOKEN = 3.5
OUTPUT_PER_QUESTION, OUTPUT_PER_NOTE = 30, 60  # tokens, with the thinking at the low level

MAX_WRONG, MIN_ADDS, MAX_QUESTIONS_BEHIND, MAX_NOTES_RATIO, MAX_REQUEST_SECONDS = 0.05, 0.50, 0.15, 2.0, 100

SCORE_SYSTEM = """You grade how well a set of notes of a university lecture, read with its slides, answers questions on the lecture.

For every question, compare what the slides and the notes say with the expected answer:
- RIGHT: they give the expected answer, even in other words or abbreviated;
- PARTIAL: they give a part of it;
- WRONG: they do not give it, or say something else.
Judge only what the slides and the notes say: what you know yourself does not count.

Answer with JSON: items, one for every question, with its id and grade."""

JUDGE_SYSTEM = """You judge the notes that an assistant suggested to a student while a university lecture was going on. You receive the slides of the lecture, its transcript (it may contain recognition errors) and the numbered notes.

For every note give one verdict:
- ADDS: correct, and it says something the lecturer explained that the slides do not already say (an explanation, an example, a condition, an intuition, a proof step, a remark);
- SLIDE: correct, but it mostly repeats what the slides already say;
- TRIVIAL: correct but of little use to the student (generic, obvious, organizational, or a repetition of another note);
- WRONG: it says something the lecturer did not say, or something incorrect.
Judge from the slides and the transcript only.

Answer with JSON: items, one for every note, with its number, the verdict and a reason of at most 20 words."""

VERDICTS = ["ADDS", "SLIDE", "TRIVIAL", "WRONG"]


def schema(props, required):
    return {"type": "object", "properties": {"items": {"type": "array", "items": {
        "type": "object", "properties": props, "required": required}}}, "required": ["items"]}


SCORE_SCHEMA = schema({"id": {"type": "string"}, "grade": {"type": "string", "enum": ["RIGHT", "PARTIAL", "WRONG"]}}, ["id", "grade"])
JUDGE_SCHEMA = schema({"n": {"type": "integer"}, "verdict": {"type": "string", "enum": VERDICTS},
                       "reason": {"type": "string"}}, ["n", "verdict", "reason"])


class CapReached(Exception):
    pass


def read_json(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def write_json(path, value):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(value, f, ensure_ascii=False, indent=1)


def key_of(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:16]


def dollars_of(prompt_tokens, output_tokens):
    return (prompt_tokens * INPUT_DOLLARS + output_tokens * OUTPUT_DOLLARS) / 1e6


def estimated_dollars(text, output_tokens):
    return dollars_of(len(text) / CHARS_PER_TOKEN, output_tokens)


class Ledger:
    """The dollars spent on this bench, shared with NotesBench, and their cap."""

    def __init__(self, path, cap):
        self.path, self.cap = path, cap
        self.spent = 0.0
        if os.path.isfile(path):
            with open(path, encoding="utf-8") as f:
                self.spent = float(f.read().strip() or 0)

    def check(self, estimate):
        if self.spent + estimate > self.cap:
            raise CapReached(f"{self.spent:.2f} dollars spent, about {estimate:.2f} more would pass the cap of {self.cap:.2f}")

    def add(self, dollars):
        self.spent += dollars
        os.makedirs(os.path.dirname(self.path), exist_ok=True)
        with open(self.path, "w", encoding="utf-8") as f:
            f.write(str(self.spent))


class Estimate:
    """Stands for Gemini when only the cost is wanted: it counts the requests and answers nothing."""

    def __init__(self):
        self.requests, self.dollars = 0, 0.0

    def __call__(self, system, text, response_schema, output_tokens):
        self.requests += 1
        self.dollars += estimated_dollars(system + text, output_tokens)
        return []


class Gemini:
    """The grader, each request checked against the cap first and counted after."""

    def __init__(self, ledger, key):
        self.ledger, self.key = ledger, key

    def __call__(self, system, text, response_schema, output_tokens):
        self.ledger.check(estimated_dollars(system + text, output_tokens))
        body = json.dumps({
            "systemInstruction": {"parts": [{"text": system}]},
            "contents": [{"role": "user", "parts": [{"text": text}]}],
            "generationConfig": {"responseMimeType": "application/json", "responseJsonSchema": response_schema,
                                 "thinkingConfig": {"thinkingLevel": "low"}, "maxOutputTokens": 16384},
        }).encode("utf-8")
        url = f"https://generativelanguage.googleapis.com/v1beta/models/{GRADER}:generateContent"
        for attempt in range(4):
            request = urllib.request.Request(url, body, {"Content-Type": "application/json", "x-goog-api-key": self.key})
            try:
                with urllib.request.urlopen(request, timeout=300) as response:
                    answer = json.load(response)
                meta = answer.get("usageMetadata", {})
                self.ledger.add(dollars_of(meta.get("promptTokenCount", 0), meta.get("candidatesTokenCount", 0) + meta.get("thoughtsTokenCount", 0)))
                return json.loads("".join(p.get("text", "") for p in answer["candidates"][0]["content"]["parts"]))["items"]
            except urllib.error.HTTPError as e:
                print("HTTP", e.code, e.read()[:300], file=sys.stderr)
                if e.code not in (429, 500, 503) or attempt == 3:
                    raise
                time.sleep(30 * (attempt + 1))


class Lecture:
    """What the grader reads of a lecture: its questions, slides and transcript, its grades so far."""

    def __init__(self, entry, duration_ms, store_path):
        self.id = entry["lectureId"]
        self.hours = duration_ms / 3_600_000
        q = entry["questions"]
        self.questions = [x for x in q["questions"] if q["filters"][x["id"]]["bySentences"] and not q["filters"][x["id"]]["bySlides"] and not q["filters"][x["id"]]["duplicate"]]
        self.slides = "Lecture: " + entry["documentTitle"] + "\n\nSlides:\n" + "\n".join(
            f"[Page {s['number']}] {s['text'][:1500].replace(chr(10), ' / ')}" for s in entry["context"]["slides"]) + "\n\n"
        self.transcript = "\n".join(s["text"] for s in entry["context"]["sentences"])
        self.store_path = store_path
        self.store = read_json(store_path) if os.path.isfile(store_path) else {"verdicts": {}, "scores": {}}

    def save(self):
        write_json(self.store_path, self.store)

    def verdicts_of(self, note):
        return self.store["verdicts"].get(key_of(note), {}).get("passes", [])

    def judge(self, ask, notes, passes):
        """Every one of [notes] judged [passes] times, those judged already left out."""
        for done in range(passes):
            missing = list(dict.fromkeys(n for n in notes if len(self.verdicts_of(n)) <= done))
            if not missing:
                continue
            numbered = "\n".join(f"{i + 1}. {n.replace(chr(10), ' / ')}" for i, n in enumerate(missing))
            items = ask(JUDGE_SYSTEM, self.slides + "Transcript:\n" + self.transcript + "\n\nNotes:\n" + numbered, JUDGE_SCHEMA, OUTPUT_PER_NOTE * len(missing))
            for item in items:
                if 1 <= item["n"] <= len(missing):
                    note = missing[item["n"] - 1]
                    entry = self.store["verdicts"].setdefault(key_of(note), {"note": note, "passes": []})
                    if len(entry["passes"]) <= done:
                        entry["passes"].append({"verdict": item["verdict"], "reason": item.get("reason", "")})
            self.save()

    def score(self, ask, notes, passes):
        """The questions graded on [notes], [passes] times, whatever set of the same notes paid for it."""
        key = key_of("\n".join(sorted(notes)))
        kept = self.store["scores"].setdefault(key, [])
        if not notes or not self.questions:
            return
        numbered = "\n".join(f"{i + 1}. {n.replace(chr(10), ' / ')}" for i, n in enumerate(notes))
        questions = "\n".join(f"{x['id']}: {x['text']}\n  expected answer: {x['answer']}" for x in self.questions)
        for _ in range(len(kept), passes):
            items = ask(SCORE_SYSTEM, self.slides + "Notes:\n" + numbered + "\n\nQuestions:\n" + questions, SCORE_SCHEMA, OUTPUT_PER_QUESTION * len(self.questions))
            if not items:
                continue
            ids = {x["id"] for x in self.questions}
            kept.append({item["id"]: item["grade"] for item in items if item["id"] in ids})
            self.save()

    def measures(self, notes, passes):
        """The counts of a set of notes, the verdicts of all the passes pooled."""
        counts = dict.fromkeys(VERDICTS, 0)
        stable = both = 0
        for note in notes:
            verdicts = [p["verdict"] for p in self.verdicts_of(note)[:passes]]
            for verdict in verdicts:
                counts[verdict] += 1
            if len(verdicts) >= 2:
                both += 1
                stable += len(set(verdicts)) == 1
        grades = self.store["scores"].get(key_of("\n".join(sorted(notes))), [])[:passes]
        points = [sum({"RIGHT": 1, "PARTIAL": 0.5}.get(g.get(x["id"]), 0) for x in self.questions) for g in grades]
        return {"notes": len(notes), "hours": self.hours, "counts": counts, "stable": stable, "both": both,
                "questions": len(self.questions), "points": sum(points) / len(points) if points else 0.0}


def sum_measures(measures):
    counts = dict.fromkeys(VERDICTS, 0)
    for m in measures:
        for verdict in VERDICTS:
            counts[verdict] += m["counts"][verdict]
    judged = sum(counts.values())
    questions = sum(m["questions"] for m in measures)
    hours = sum(m["hours"] for m in measures)
    return {"notes": sum(m["notes"] for m in measures), "per_hour": sum(m["notes"] for m in measures) / hours if hours else 0.0,
            "adds": counts["ADDS"] / judged if judged else 0.0, "slide": counts["SLIDE"] / judged if judged else 0.0,
            "trivial": counts["TRIVIAL"] / judged if judged else 0.0, "wrong": counts["WRONG"] / judged if judged else 0.0,
            "questions": sum(m["points"] for m in measures) / questions if questions else 0.0,
            "stable": sum(m["stable"] for m in measures), "both": sum(m["both"] for m in measures)}


def unmet(total, reference, longest_seconds):
    """The thresholds a configuration misses, against [reference] when there is one."""
    missed = []
    if total["wrong"] > MAX_WRONG:
        missed.append("wrong notes")
    if total["adds"] < MIN_ADDS:
        missed.append("notes that add")
    if longest_seconds > MAX_REQUEST_SECONDS:
        missed.append("time")
    if reference is not None:
        if total["questions"] < reference["questions"] - MAX_QUESTIONS_BEHIND:
            missed.append("questions")
        if total["per_hour"] > reference["per_hour"] * MAX_NOTES_RATIO:
            missed.append("notes per hour")
    return missed


def report_of(lectures, runs, configs, reference, passes):
    """The sums of every configuration, by lecture and over all of them, in Markdown."""
    lines = ["| Configuration | Lecture | Notes | Per hour | Add | Slide | Trivial | Wrong | Questions | Same verdict twice | Longest request |",
             "|---|---|---|---|---|---|---|---|---|---|---|"]
    totals = {}
    longest = {}
    for config in configs:
        measures = []
        for lecture in lectures:
            run = runs[lecture.id].get(config)
            if run is None:
                continue
            notes = [note_text(n) for n in run["notes"]]
            measures.append(lecture.measures(notes, passes))
            longest[config] = max(longest.get(config, 0), run.get("longestMillis", 0) / 1000)
            lines.append(row(config, lecture.id[-6:], sum_measures(measures[-1:]), run.get("longestMillis", 0) / 1000))
        totals[config] = sum_measures(measures)
        lines.append(row(config, "**all**", totals[config], longest.get(config, 0)))
    lines.append("")
    reference_total = totals.get(reference)
    for config in configs:
        if config != reference:
            missed = unmet(totals[config], reference_total, longest.get(config, 0))
            lines.append(f"* `{config}`: " + ("passes the thresholds" if not missed else "misses " + ", ".join(missed)) + ("" if reference_total else " (no reference to compare with)"))
    return "\n".join(lines) + "\n"


def row(config, lecture, total, longest_seconds):
    stable = f"{total['stable']}/{total['both']}" if total["both"] else "-"
    return (f"| {config} | {lecture} | {total['notes']} | {total['per_hour']:.0f} | {total['adds']:.0%} | {total['slide']:.0%} | {total['trivial']:.0%} | "
            f"{total['wrong']:.0%} | {total['questions']:.0%} | {stable} | {longest_seconds:.0f} s |")


def note_text(note):
    return f"{note['title']}: {note['content']}"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--lectures", required=True)
    parser.add_argument("--configs", required=True)
    parser.add_argument("--reference")
    parser.add_argument("--passes", type=int, default=2)
    parser.add_argument("--estimate", action="store_true")
    parser.add_argument("--test", action="store_true")
    parser.add_argument("--cap", type=float, default=6.0)
    parser.add_argument("--bench", default=os.path.expanduser("~/drawview-bench"))
    parser.add_argument("--workspace", default=os.path.expanduser("~/lecture-eval-2"))
    parser.add_argument("--evalset", default="evalset-2026-10-18-3.json")
    args = parser.parse_args(argv)

    lecture_ids = args.lectures.split(",")
    configs = args.configs.split(",")
    split = read_json(os.path.join(args.workspace, "split.json"))["lectures"]
    on_test = [i for i in lecture_ids if split.get(i) == "TEST"]
    if on_test and not args.test:
        parser.error(f"lectures of test, to be read once a choice is made (--test): {', '.join(on_test)}")

    evalset = read_json(os.path.join(args.workspace, "exports", args.evalset))
    entries = {entry["lectureId"]: entry for entry in evalset["lectures"]}
    lectures, runs = [], {}
    for lecture_id in lecture_ids:
        prepared = read_json(os.path.join(args.workspace, "lectures", lecture_id, "prepared.json"))
        lectures.append(Lecture(entries[lecture_id], prepared["durationMs"], os.path.join(args.bench, "grades", lecture_id + ".json")))
        runs[lecture_id] = {}
        for config in configs:
            path = os.path.join(args.bench, "answers", "notes", lecture_id, config + ".json")
            if os.path.isfile(path):
                runs[lecture_id][config] = read_json(path)
            else:
                print(f"no run of {config} on {lecture_id}", file=sys.stderr)

    ledger = Ledger(os.path.join(args.bench, "dollars.txt"), args.cap)
    ask = Estimate() if args.estimate else Gemini(ledger, os.environ.get("GEMINI_API_KEY", ""))
    if not args.estimate and not ask.key:
        parser.error("no GEMINI_API_KEY")

    stopped = None
    try:
        for lecture in lectures:
            for config, run in runs[lecture.id].items():
                notes = [note_text(n) for n in run["notes"]]
                lecture.judge(ask, notes, args.passes)
                lecture.score(ask, notes, args.passes)
                print(f"{lecture.id} {config}: graded, {ledger.spent:.3f} dollars spent", flush=True)
    except CapReached as e:
        stopped = str(e)
        print("Stopped:", stopped, file=sys.stderr)

    if args.estimate:
        print(f"{ask.requests} requests still to pay, about {ask.dollars:.2f} dollars; spent so far {ledger.spent:.2f}, cap {args.cap:.2f}")
        return 0
    report = report_of(lectures, runs, configs, args.reference, args.passes)
    report_path = os.path.join(args.bench, "grades", "report.md")
    os.makedirs(os.path.dirname(report_path), exist_ok=True)
    with open(report_path, "w", encoding="utf-8") as f:
        f.write(report)
    print(report)
    print(f"{ledger.spent:.3f} dollars spent, cap {args.cap:.2f}")
    return 2 if stopped else 0


if __name__ == "__main__":
    sys.exit(main())
