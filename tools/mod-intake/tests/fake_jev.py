"""An in-memory stand-in for TypeSafe's POST /v1/systemone (https://docs.typesafe.ai/api): checks requests the way
the API does (auth, shape, option limits) and answers from per-thread scripts. No network, no real key."""
import json

KEY = "ts-test-key-not-real"


class FakeJev:
    """script: {title fragment: {question id: option or noul value, question id + '_p': top probability}}.
    Replies are classed by their text (fix / crash) unless a script names them. queue: HTTP statuses to answer
    first, one per request (429, 529, 500, 401, 422)."""

    def __init__(self, script=None, queue=None):
        self.script = script or {}
        self.queue = list(queue or [])
        self.requests = []          # parsed bodies of the requests answered 200
        self.calls = 0

    def post(self, url, headers, body):
        self.calls += 1
        if headers.get("Authorization") != f"Bearer {KEY}":
            return 401, {}, b'{"detail": "invalid api key"}'
        if self.queue:
            status = self.queue.pop(0)
            return status, ({"retry-after": "0"} if status == 429 else {}), json.dumps({"detail": "queued"}).encode()
        data = json.loads(body.decode("utf-8"))
        problem = self.invalid(data)
        if problem:
            return 422, {}, json.dumps({"detail": problem}).encode()
        self.requests.append(data)
        plan = self.plan(data["state"])
        answers = {qid: self.answer(qid, q, plan, data["state"]) for qid, q in data["questions"].items()}
        usage = {"input_tokens": len(body) // 4, "output_tokens": 10 * len(answers)}
        return 200, {}, json.dumps({"model": "jev-1.13.0", "answers": answers, "usage": usage}).encode()

    @staticmethod
    def invalid(data):
        if not isinstance(data.get("model"), str) or "state" not in data or not isinstance(data.get("questions"), dict):
            return "model, state and questions are required"
        if not data["questions"]:
            return "questions must not be empty"
        for qid, q in data["questions"].items():
            if q.get("type") not in ("noul", "choice", "score") or not q.get("instructions"):
                return f"{qid}: type and instructions are required"
            if q["type"] == "choice":
                criteria = q.get("criteria")
                if not isinstance(criteria, dict) or not 1 <= len(criteria) <= 255:
                    return f"{qid}: a choice needs 1 to 255 options"
        return None

    def plan(self, state):
        title = (state.get("release") or {}).get("title", "")
        for fragment, plan in self.script.items():
            if fragment in title:
                return plan
        return {}

    def answer(self, qid, q, plan, state):
        if q["type"] == "noul":
            return {"type": "noul", "noul": float(plan.get(qid, 0.05))}
        options = list(q["criteria"])
        choice = plan.get(qid)
        if choice is None and qid.startswith("reply_"):
            text = state["replies"][int(qid.split("_")[1])]["text"].lower()
            choice = "fix" if "fix" in text else "bug_report" if "crash" in text else "neither"
        if choice not in options:
            choice = "none" if "none" in options else options[0]
        p = float(plan.get(qid + "_p", 0.9))
        rest = (1.0 - p) / max(1, len(options) - 1)
        probs = {o: (p if o == choice else rest) for o in options}
        return {"type": "choice", "choice": choice, "probabilities": probs, "confidence": p}
