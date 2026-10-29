"""Grades the note sets of NotesBench: the questions of the eval set and a verdict per note."""
import json
import os
import sys
import time
import urllib.error
import urllib.request

EVALSET = os.path.expanduser("~/lecture-eval-2/exports/evalset-2026-10-18-3.json")
LECTURE_JSON = os.path.expanduser(
    "~/lecture-eval-2/snapshot/lectures/doc-a41ffe77-c50c-4b5c-976a-a4b270dbb365/2026-10-15_09-48-14-2ce0b6/lecture.json")
NOTES_DIR = os.path.expanduser("~/drawview-bench/answers/notes")
OUT = sys.argv[1]
ONLY = set(sys.argv[2].split(",")) if len(sys.argv) > 2 else None
GRADER = "gemini-3.8-flash"
LECTURE_ID = "2026-10-15_09-48-14-2ce0b6"

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


def schema(props, required):
    return {"type": "object", "properties": {"items": {"type": "array", "items": {
        "type": "object", "properties": props, "required": required}}}, "required": ["items"]}


SCORE_SCHEMA = schema({"id": {"type": "string"}, "grade": {"type": "string", "enum": ["RIGHT", "PARTIAL", "WRONG"]}}, ["id", "grade"])
JUDGE_SCHEMA = schema({"n": {"type": "integer"}, "verdict": {"type": "string", "enum": ["ADDS", "SLIDE", "TRIVIAL", "WRONG"]},
                       "reason": {"type": "string"}}, ["n", "verdict", "reason"])

usage = {"prompt": 0, "output": 0}


def ask(system, text, response_schema):
    body = json.dumps({
        "systemInstruction": {"parts": [{"text": system}]},
        "contents": [{"role": "user", "parts": [{"text": text}]}],
        "generationConfig": {"responseMimeType": "application/json", "responseJsonSchema": response_schema,
                             "thinkingConfig": {"thinkingLevel": "low"}, "maxOutputTokens": 16384},
    }).encode()
    url = f"https://generativelanguage.googleapis.com/v1beta/models/{GRADER}:generateContent"
    for attempt in range(4):
        request = urllib.request.Request(url, body, {"Content-Type": "application/json", "x-goog-api-key": os.environ["GEMINI_API_KEY"]})
        try:
            with urllib.request.urlopen(request, timeout=300) as response:
                answer = json.load(response)
            meta = answer.get("usageMetadata", {})
            usage["prompt"] += meta.get("promptTokenCount", 0)
            usage["output"] += meta.get("candidatesTokenCount", 0) + meta.get("thoughtsTokenCount", 0)
            return json.loads("".join(p.get("text", "") for p in answer["candidates"][0]["content"]["parts"]))["items"]
        except urllib.error.HTTPError as e:
            print("HTTP", e.code, e.read()[:300], file=sys.stderr)
            if e.code not in (429, 500, 503) or attempt == 3:
                raise
            time.sleep(30 * (attempt + 1))


def main():
    evalset = json.load(open(EVALSET))
    lecture = next(l for l in evalset["lectures"] if l["lectureId"] == LECTURE_ID)
    q = lecture["questions"]
    kept = [x for x in q["questions"] if q["filters"][x["id"]]["bySentences"] and not q["filters"][x["id"]]["bySlides"] and not q["filters"][x["id"]]["duplicate"]]
    slides = "Lecture: " + lecture["documentTitle"] + "\n\nSlides:\n" + "\n".join(
        f"[Page {s['number']}] {s['text'][:1500].replace(chr(10), ' / ')}" for s in lecture["context"]["slides"]) + "\n\n"
    transcript = "\n".join(s["text"] for s in lecture["context"]["sentences"])

    sets = {}
    recorded = json.load(open(LECTURE_JSON))
    sets["app-today (3.8 Flash, 90 s, medium thinking)"] = [f"{s['title']}: {s['content']}" for s in recorded["suggestions"] if not s.get("fromFinalReview")]
    runs = {}
    for name in sorted(os.listdir(NOTES_DIR)):
        if name.endswith(".json"):
            run = json.load(open(os.path.join(NOTES_DIR, name)))
            runs[run["config"]] = run
            sets[run["config"]] = [f"{n['title']}: {n['content']}" for n in run["notes"]]

    if ONLY is not None:
        sets = {k: v for k, v in sets.items() if k in ONLY}
    results = {"questions": len(kept), "sets": {}}
    for name, notes in sets.items():
        numbered = "\n".join(f"{i + 1}. {n.replace(chr(10), ' / ')}" for i, n in enumerate(notes))
        questions = "\n".join(f"{x['id']}: {x['text']}\n  expected answer: {x['answer']}" for x in kept)
        grades = {x["id"]: "WRONG" for x in kept}
        if notes:
            for item in ask(SCORE_SYSTEM, slides + "Notes:\n" + numbered + "\n\nQuestions:\n" + questions, SCORE_SCHEMA):
                if item["id"] in grades:
                    grades[item["id"]] = item["grade"]
        verdicts = ask(JUDGE_SYSTEM, slides + "Transcript:\n" + transcript + "\n\nNotes:\n" + numbered, JUDGE_SCHEMA) if notes else []
        score = sum({"RIGHT": 1, "PARTIAL": 0.5, "WRONG": 0}[g] for g in grades.values()) / len(kept)
        counts = {v: sum(1 for x in verdicts if x["verdict"] == v) for v in ["ADDS", "SLIDE", "TRIVIAL", "WRONG"]}
        words = sum(len(n.split()) for n in notes)
        results["sets"][name] = {"notes": len(notes), "words": words, "score": score, "grades": grades, "verdicts": verdicts, "counts": counts,
                                 "run": {k: v for k, v in runs.get(name, {}).items() if k not in ("notes", "outline")}}
        print(f"{name}: {len(notes)} notes, {words} words, questions {score:.0%}, {counts}", flush=True)
    results["graderUsage"] = usage
    json.dump(results, open(OUT, "w"), ensure_ascii=False, indent=1)
    print("grader tokens", usage, "dollars", round((usage["prompt"] * 0.75 + usage["output"] * 3.75) / 1e6, 3))


main()
