#!/usr/bin/env python3
"""Which OpenMetadata tool does a client pick for each question shape?

Runs every question through the Claude Code CLI headless against a live MCP server as one user
and records the OpenMetadata tools it called, in order. A case passes when the tools it expects
were called (and any it forbids were not). Same-chat follow-ups resume the first turn's session.

    python3 scripts/harness/tool_choice_eval.py --base http://localhost:8585 \
        --email ana@example.com --password '...' --trials 3 --label before

The user's token goes to a 0600 MCP config under .audit/ (git-ignored) and is deleted on exit; it
is never printed. The `remember` case saves a memory (the fact starts with EVAL:, which models
often drop); --cleanup deletes the memories this run wrote as the admin (OM_ADMIN_EMAIL / OM_ADMIN_PASSWORD, default admin@open-metadata.org
/ admin).
"""
import argparse
import base64
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

PREFIX = "mcp__openmetadata__"
EVAL_MARK = "EVAL:"


def login(base, email, password):
    body = json.dumps(
        {"email": email, "password": base64.b64encode(password.encode()).decode()}
    ).encode()
    request = urllib.request.Request(
        base + "/api/v1/users/login", body, {"Content-Type": "application/json"}
    )
    return json.load(urllib.request.urlopen(request))["accessToken"]


def api(base, token, method, path):
    request = urllib.request.Request(
        base + "/api" + path, headers={"Authorization": "Bearer " + token}, method=method
    )
    try:
        with urllib.request.urlopen(request) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        return error.code, None


class Call:
    def __init__(self, tool, args):
        self.tool = tool
        self.args = args

    def label(self):
        kind = self.args.get("entityType")
        return self.tool + (f"[{kind}]" if self.tool == "get_concept_context" and kind else "")


def called(calls, tool, **match):
    return any(
        call.tool == tool and all(call.args.get(key) == value for key, value in match.items())
        for call in calls
    )


def only_arr_daily_details(calls):
    others = [
        call
        for call in calls
        if call.tool == "get_entity_details"
        and "arr_daily" not in json.dumps(call.args)
    ]
    return not others


ARR = "What was last month's ARR?"
CASES = [
    {"id": "arr_last_month", "turns": [ARR],
     "check": lambda turns: called(turns[0], "finish_answer"), "want": "finish_answer"},
    {"id": "term_fresh", "turns": ["What does ARR mean here?"],
     "check": lambda turns: called(turns[0], "get_concept_context", entityType="glossaryTerm"),
     "want": "get_concept_context[glossaryTerm]"},
    {"id": "metric_fresh", "turns": ["How is the arr metric calculated?"],
     "check": lambda turns: called(turns[0], "get_concept_context", entityType="metric"),
     "want": "get_concept_context[metric]"},
    {"id": "term_followup", "turns": [ARR, "What does ARR mean here?"],
     "check": lambda turns: called(turns[1], "get_concept_context", entityType="glossaryTerm"),
     "want": "get_concept_context[glossaryTerm] on turn 2"},
    {"id": "metric_followup", "turns": [ARR, "How is the arr metric calculated?"],
     "check": lambda turns: called(turns[1], "get_concept_context", entityType="metric"),
     "want": "get_concept_context[metric] on turn 2"},
    {"id": "asset", "turns": ["Tell me about arr_daily"],
     "check": lambda turns: called(turns[0], "get_entity_details"), "want": "get_entity_details"},
    {"id": "lineage", "turns": ["What feeds arr_daily, and is anything upstream broken?"],
     "check": lambda turns: called(turns[0], "get_entity_lineage")
     and only_arr_daily_details(turns[0]),
     "want": "get_entity_lineage, no details per upstream table"},
    {"id": "relations", "turns": ["How does ARR connect to Bookings?"],
     "check": lambda turns: called(turns[0], "get_concept_relations"),
     "want": "get_concept_relations"},
    {"id": "remember",
     "turns": [f"Remember: {EVAL_MARK} monthly ARR is the arr_daily snapshot on the last day of the month"],
     "check": lambda turns: called(turns[0], "remember"), "want": "remember"},
    {"id": "correction", "turns": [ARR, "No - use arr_daily, never arr_v1, for ARR"],
     "check": lambda turns: called(turns[1], "remember"),
     "want": "remember on turn 2, without the word remember"},
]


def run_turn(config, workdir, prompt, session, model):
    command = [
        "claude", "-p", prompt, "--output-format", "stream-json", "--verbose",
        "--mcp-config", str(config), "--strict-mcp-config",
        "--allowedTools", PREFIX + "*", "--tools", "", "--setting-sources", "",
        "--disable-slash-commands", "--max-budget-usd", "2",
    ]
    if model:
        command += ["--model", model]
    if session:
        command += ["--resume", session]
    process = subprocess.run(
        command, cwd=workdir, capture_output=True, text=True, timeout=600, check=False
    )
    calls, new_session, error = [], session, None
    for line in process.stdout.splitlines():
        try:
            event = json.loads(line)
        except ValueError:
            continue
        new_session = event.get("session_id", new_session)
        if event.get("type") == "assistant":
            for block in event["message"].get("content", []):
                if block.get("type") == "tool_use" and block["name"].startswith(PREFIX):
                    calls.append(Call(block["name"][len(PREFIX):], block.get("input") or {}))
        if event.get("type") == "result" and event.get("is_error"):
            error = str(event.get("result"))[:200]
    if process.returncode != 0 and not calls and error is None:
        error = (process.stderr or "claude exited " + str(process.returncode))[:200]
    return calls, new_session, error


def run_trial(args, config, case):
    with tempfile.TemporaryDirectory(prefix="tool-eval-") as workdir:
        turns, session, error = [], None, None
        for prompt in case["turns"]:
            calls, session, error = run_turn(config, workdir, prompt, session, args.model)
            turns.append(calls)
            if error:
                break
    return turns, error


def cleanup(base, admin_token, since_ms):
    """Models paraphrase the fact and drop the EVAL: prefix, so memories are also matched by the
    CLI's client name and by when they were written."""
    removed = 0
    status, page = api(base, admin_token, "GET", "/v1/contextCenter/memories?limit=1000&fields=")
    for memory in (page or {}).get("data", []):
        ours = str(memory.get("title", "")).startswith(EVAL_MARK) or (
            "Claude CLI" in str(memory.get("description"))
            and (memory.get("updatedAt") or 0) >= since_ms
        )
        if ours:
            code, _ = api(
                base, admin_token, "DELETE",
                "/v1/contextCenter/memories/" + memory["id"] + "?hardDelete=true",
            )
            removed += code in (200, 204)
    return removed


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--base", default="http://localhost:8585")
    parser.add_argument("--email", default="ana@example.com")
    parser.add_argument("--password", default=os.environ.get("OM_EVAL_PASSWORD"))
    parser.add_argument("--trials", type=int, default=3)
    parser.add_argument("--model", default="sonnet")
    parser.add_argument("--only", help="comma-separated case ids")
    parser.add_argument("--label", default="run")
    parser.add_argument("--parallel", type=int, default=3)
    parser.add_argument("--json-out")
    parser.add_argument("--cleanup", action="store_true", help="delete EVAL: memories as admin")
    args = parser.parse_args()
    if not args.password:
        sys.exit("pass --password or set OM_EVAL_PASSWORD")

    audit = Path(__file__).resolve().parents[2] / ".audit"
    audit.mkdir(exist_ok=True)
    config = audit / f"tool-eval-mcp-{os.getpid()}.json"
    fd = os.open(config, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as handle:
        json.dump({"mcpServers": {"openmetadata": {
            "type": "http", "url": args.base + "/mcp",
            "headers": {"Authorization": "Bearer " + login(args.base, args.email, args.password)},
        }}}, handle)
    started = int(time.time() * 1000)
    wanted = set(args.only.split(",")) if args.only else None
    cases = [case for case in CASES if not wanted or case["id"] in wanted]
    jobs = [(case, trial) for case in cases for trial in range(args.trials)]
    try:
        with ThreadPoolExecutor(args.parallel) as pool:
            outcomes = list(pool.map(lambda job: run_trial(args, config, job[0]), jobs))
    finally:
        config.unlink(missing_ok=True)

    report, passes = [], 0
    print(f"\n== {args.label}: {args.base}, model {args.model}, {args.trials} trials ==")
    for case in cases:
        print(f"\n{case['id']}  (want {case['want']})")
        for (job_case, trial), (turns, error) in zip(jobs, outcomes):
            if job_case is not case:
                continue
            ok = not error and case["check"](turns)
            passes += ok
            shown = " | ".join(
                " > ".join(call.label() for call in turn) or "(no tools)" for turn in turns
            )
            print(f"  {'PASS' if ok else 'FAIL'}  {shown}" + (f"  ERROR {error}" if error else ""))
            report.append({"case": case["id"], "trial": trial, "pass": ok, "error": error,
                           "turns": [[call.label() for call in turn] for turn in turns]})
    print(f"\n{passes}/{len(jobs)} trials passed")
    if args.json_out:
        Path(args.json_out).write_text(json.dumps(report, indent=1))
    if args.cleanup:
        admin = login(args.base, os.environ.get("OM_ADMIN_EMAIL", "admin@open-metadata.org"),
                      os.environ.get("OM_ADMIN_PASSWORD", "admin"))
        print(f"deleted {cleanup(args.base, admin, started)} {EVAL_MARK} memories")


if __name__ == "__main__":
    main()
