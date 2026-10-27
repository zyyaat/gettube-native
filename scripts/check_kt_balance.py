#!/usr/bin/env python3
"""Rough brace/paren/bracket balance checker for Kotlin sources.

Usage:
  python3 scripts/check_kt_balance.py <file.kt> [baseline.kt]

Counts (), {}, [] outside line comments, block comments and strings.
If a baseline is given, prints the DELTA instead (a zero delta means the edit
is balance-neutral even when the absolute count is off due to raw strings
containing braces).
"""
import sys


def strip(code: str) -> str:
    out = []
    i, n = 0, len(code)
    state = "code"  # code | line | block | str | rawstr | char
    while i < n:
        c = code[i]
        nxt = code[i + 1] if i + 1 < n else ""
        if state == "code":
            if c == "/" and nxt == "/":
                state = "line"
                i += 2
                continue
            if c == "/" and nxt == "*":
                state = "block"
                i += 2
                continue
            if c == '"':
                if code[i : i + 3] == '"""':
                    state = "rawstr"
                    i += 3
                    continue
                state = "str"
                i += 1
                continue
            if c == "'":
                state = "char"
                i += 1
                continue
            out.append(c)
            i += 1
        elif state == "line":
            if c == "\n":
                state = "code"
                out.append(c)
            i += 1
        elif state == "block":
            if c == "*" and nxt == "/":
                state = "code"
                i += 2
                continue
            i += 1
        elif state == "str":
            if c == "\\":
                i += 2
                continue
            if c == '"':
                state = "code"
            i += 1
        elif state == "rawstr":
            if code[i : i + 3] == '"""':
                state = "code"
                i += 3
                continue
            if c == "$" and nxt == "{":
                out.append(c)
                out.append(nxt)
                i += 2
                continue
            i += 1
        elif state == "char":
            if c == "\\":
                i += 2
                continue
            if c == "'":
                state = "code"
            i += 1
    return "".join(out)


def counts(code: str):
    s = strip(code)
    return {
        "paren": s.count("(") - s.count(")"),
        "brace": s.count("{") - s.count("}"),
        "bracket": s.count("[") - s.count("]"),
    }


if __name__ == "__main__":
    target = sys.argv[1]
    baseline = sys.argv[2] if len(sys.argv) > 2 else None
    code = open(target, encoding="utf-8").read()
    c = counts(code)
    print(f"{target}: {c}")
    if baseline:
        b = counts(open(baseline, encoding="utf-8").read())
        delta = {k: c[k] - b[k] for k in c}
        print(f"baseline {baseline}: {b}")
        print(f"delta: {delta}")
        if all(v == 0 for v in delta.values()):
            print("BALANCE-NEUTRAL OK")
        else:
            print("BALANCE CHANGED - inspect")
            sys.exit(1)
