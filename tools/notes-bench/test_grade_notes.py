"""Tests of grade_notes.py, with no request: python tools/notes-bench/test_grade_notes.py."""
import json
import os
import tempfile
import unittest

import grade_notes as g


def entry():
    question = {"bySentences": True, "bySlides": False, "duplicate": False}
    return {
        "lectureId": "one",
        "documentTitle": "t",
        "context": {"slides": [{"number": 1, "text": "s"}], "sentences": [{"text": "è una frase"}]},
        "questions": {
            "questions": [{"id": "q1", "text": "?", "answer": "a"}, {"id": "q2", "text": "?", "answer": "b"}, {"id": "q3", "text": "?", "answer": "c"}],
            "filters": {"q1": question, "q2": question, "q3": dict(question, bySlides=True)},
        },
    }


class FakeGrader:
    """Answers every note with [verdict], every question RIGHT; counts the requests."""

    def __init__(self, verdict="ADDS", grade="RIGHT"):
        self.verdict, self.grade, self.judged, self.scored = verdict, grade, [], 0

    def __call__(self, system, text, response_schema, output_tokens):
        if system == g.JUDGE_SYSTEM:
            notes = [line for line in text.split("Notes:\n")[1].split("\n")]
            self.judged.append(len(notes))
            return [{"n": i + 1, "verdict": self.verdict, "reason": "r"} for i in range(len(notes))]
        self.scored += 1
        return [{"id": "q1", "grade": self.grade}, {"id": "q2", "grade": "PARTIAL"}]


class GradeNotesTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.store = os.path.join(self.directory.name, "grades", "one.json")
        self.lecture = g.Lecture(entry(), 3_600_000, self.store)

    def test_a_note_is_not_paid_twice(self):
        grader = FakeGrader()
        self.lecture.judge(grader, ["a", "b"], 2)
        self.assertEqual([2, 2], grader.judged)
        self.lecture.judge(grader, ["a", "b", "c"], 2)
        self.assertEqual([2, 2, 1, 1], grader.judged)

    def test_what_was_paid_survives_a_new_run(self):
        grader = FakeGrader()
        self.lecture.judge(grader, ["è a"], 1)
        self.lecture.score(grader, ["è a"], 1)
        again = g.Lecture(entry(), 3_600_000, self.store)
        other = FakeGrader()
        again.judge(other, ["è a"], 1)
        again.score(other, ["è a"], 1)
        self.assertEqual(([], 0), (other.judged, other.scored))
        self.assertEqual("ADDS", again.verdicts_of("è a")[0]["verdict"])

    def test_the_same_notes_in_another_order_are_the_same_set(self):
        grader = FakeGrader()
        self.lecture.score(grader, ["a", "b"], 1)
        self.lecture.score(grader, ["b", "a"], 1)
        self.assertEqual(1, grader.scored)

    def test_the_questions_the_slides_answer_do_not_count(self):
        grader = FakeGrader()
        self.lecture.judge(grader, ["a"], 1)
        self.lecture.score(grader, ["a"], 1)
        measures = self.lecture.measures(["a"], 1)
        self.assertEqual(2, measures["questions"])
        self.assertEqual(1.5, measures["points"])

    def test_an_estimate_pays_nothing_and_counts_every_pass(self):
        estimate = g.Estimate()
        self.lecture.judge(estimate, ["a", "b"], 2)
        self.lecture.score(estimate, ["a", "b"], 1)
        self.assertEqual(3, estimate.requests)
        self.assertGreater(estimate.dollars, 0)
        self.assertEqual({}, g.Lecture(entry(), 3_600_000, self.store).store["verdicts"])

    def test_an_estimate_counts_every_pass_of_the_score(self):
        estimate = g.Estimate()
        self.lecture.score(estimate, ["a", "b"], 2)
        self.assertEqual(2, estimate.requests)

    def test_the_cap_counts_what_the_runs_spent(self):
        path = os.path.join(self.directory.name, "dollars.txt")
        with open(path, "w", encoding="utf-8") as f:
            f.write("5.99")
        ledger = g.Ledger(path, 6.0)
        ledger.check(0.01)
        with self.assertRaises(g.CapReached):
            ledger.check(0.02)
        ledger.add(0.5)
        self.assertAlmostEqual(6.49, g.Ledger(path, 6.0).spent)

    def test_the_thresholds(self):
        good = {"wrong": 0.05, "adds": 0.5, "questions": 0.6, "per_hour": 20}
        reference = {"questions": 0.74, "per_hour": 10}
        self.assertEqual([], g.unmet(good, reference, 100))
        self.assertEqual(["wrong notes", "notes that add", "time", "questions", "notes per hour"],
                         g.unmet({"wrong": 0.06, "adds": 0.49, "questions": 0.58, "per_hour": 21}, reference, 101))

    def test_test_lectures_are_refused(self):
        workspace = os.path.join(self.directory.name, "w")
        os.makedirs(workspace)
        with open(os.path.join(workspace, "split.json"), "w", encoding="utf-8") as f:
            json.dump({"lectures": {"one": "TEST"}}, f)
        with self.assertRaises(SystemExit):
            g.main(["--lectures", "one", "--configs", "c", "--estimate", "--workspace", workspace, "--bench", self.directory.name])


if __name__ == "__main__":
    unittest.main()
