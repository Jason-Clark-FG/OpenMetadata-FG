#!/usr/bin/env python3
"""Calls one tool on a running OpenMetadata MCP endpoint and prints what comes back.

Used to check the answer harness by hand, as a given user, without an agent in the loop:

    OM_TOKEN=<personal access token> scripts/harness/answer_smoke.py \
        --tool finish_answer \
        --args '{"question": "What was last month'"'"'s ARR?", "answer": "ARR was 12.4M.",
                 "concepts": ["finance.ARR"], "timeWindow": "last month",
                 "tables": ["svc.db.schema.arr_daily"]}'

Prints the text content (for finish_answer, the Markdown block) and then the structured content.
Standard library only.
"""

import argparse
import json
import os
import sys
import urllib.error
import urllib.request

DEFAULT_URL = "http://localhost:8585/mcp"


def parse_response(body: str) -> dict:
    """The server answers with JSON or, for streamable HTTP, an event stream of one message."""
    data_lines = [line[5:].strip() for line in body.splitlines() if line.startswith("data:")]
    return json.loads("\n".join(data_lines) if data_lines else body)


def call_tool(url: str, token: str, tool: str, arguments: dict) -> dict:
    payload = {
        "jsonrpc": "2.0",
        "id": 1,
        "method": "tools/call",
        "params": {"name": tool, "arguments": arguments},
    }
    request = urllib.request.Request(
        url,
        data=json.dumps(payload).encode(),
        headers={
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
            "Authorization": f"Bearer {token}",
        },
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        return parse_response(response.read().decode())


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--url", default=os.environ.get("OM_MCP_URL", DEFAULT_URL))
    parser.add_argument("--tool", default="finish_answer")
    parser.add_argument("--args", default="{}", help="the tool arguments, as JSON")
    options = parser.parse_args()
    token = os.environ.get("OM_TOKEN")
    if not token:
        print("Set OM_TOKEN to a personal access token of the user to call as.", file=sys.stderr)
        return 2
    try:
        reply = call_tool(options.url, token, options.tool, json.loads(options.args))
    except urllib.error.HTTPError as error:
        print(f"HTTP {error.code}: {error.read().decode()}", file=sys.stderr)
        return 1
    if "error" in reply:
        print(json.dumps(reply["error"], indent=2), file=sys.stderr)
        return 1
    result = reply["result"]
    for item in result.get("content", []):
        print(item.get("text", ""))
    if "structuredContent" in result:
        print("\n--- structuredContent ---")
        print(json.dumps(result["structuredContent"], indent=2))
    return 1 if result.get("isError") else 0


if __name__ == "__main__":
    sys.exit(main())
