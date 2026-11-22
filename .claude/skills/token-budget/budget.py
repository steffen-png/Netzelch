#!/usr/bin/env python3
"""Token-/Limit-Budget fuer Claude-Code-Sitzungen, auch fuer das Modell selbst lesbar.

Datenquellen:
  1. Statuszeile (stdin-JSON von Claude Code): context_window + rate_limits
     (5h/7d inkl. resets_at). Wird als Snapshot pro Sitzung abgelegt.
  2. Transkript (JSONL): usage der letzten Assistant-Antwort -> Kontextfuellung.
     Funktioniert auch ohne Statuszeile (headless, -p, Cloud).

Subcommands:
  status [--json]   Fuer Agenten/Skill: aktuelles Budget.
  resume-plan       Darf/wann nach Limit-Reset weitergearbeitet werden?
  revoke            Reset-Freigabe zuruecknehmen (erteilen kann nur der Nutzer).
  statusline        stdin = Statuszeilen-JSON. Speichert Snapshot, druckt Statuszeile.
  hook              stdin = Hook-JSON (SessionStart/UserPromptSubmit/PreToolUse/PostToolUse).
  install [--user | --project DIR] [--force-statusline]
                    Skills kopieren und settings.json ergaenzen (Backup *.bak).

Konfiguration per Umgebungsvariable:
  CLAUDE_BUDGET_DIR           Ablage der Zustandsdateien (Default ~/.claude/budget-state)
  CLAUDE_BUDGET_CONTEXT_SIZE  Kontextfenster, falls nicht aus Statuszeile bekannt (200000)
  CLAUDE_BUDGET_THRESHOLDS    Schwellen fuer PostToolUse-Hinweise (Default 50,75,90,95)
"""
import json
import os
import re
import shutil
import sys
import time
from datetime import datetime, timezone

STATE_DIR = os.path.expanduser(os.environ.get("CLAUDE_BUDGET_DIR", "~/.claude/budget-state"))
DEFAULT_CTX = int(os.environ.get("CLAUDE_BUDGET_CONTEXT_SIZE", "200000"))
THRESHOLDS = sorted(int(x) for x in os.environ.get("CLAUDE_BUDGET_THRESHOLDS", "50,75,90,95").split(",") if x.strip())
SKILL_DIR = os.path.dirname(os.path.abspath(__file__))


# ---------------------------------------------------------------- helpers
def _read_stdin_json():
    try:
        data = sys.stdin.read()
        return json.loads(data) if data.strip() else {}
    except Exception:
        return {}


def _path(session_id, suffix):
    safe = "".join(c for c in (session_id or "unknown") if c.isalnum() or c in "-_")
    return os.path.join(STATE_DIR, f"{safe}{suffix}")


def _load(path):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return None


def _save(path, obj):
    try:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(obj, f, indent=2, ensure_ascii=False)
        os.replace(tmp, path)
    except Exception:
        pass


def _fmt_reset(epoch):
    if not epoch:
        return None
    try:
        dt = datetime.fromtimestamp(float(epoch)).astimezone()
        mins = int((float(epoch) - time.time()) // 60)
        rel = f"in {mins // 60}h{mins % 60:02d}m" if mins >= 60 else f"in {max(mins, 0)}m"
        return f"{dt:%a %d.%m. %H:%M} ({rel})"
    except Exception:
        return None


def _iso(epoch):
    try:
        return datetime.fromtimestamp(float(epoch), tz=timezone.utc).isoformat()
    except Exception:
        return None


# ---------------------------------------------------------------- data
def _context_from_transcript(transcript_path, ctx_size):
    """Letzte Hauptketten-Assistant-Antwort -> belegte Input-Tokens."""
    if not transcript_path or not os.path.exists(transcript_path):
        return None
    try:
        with open(transcript_path, "rb") as f:
            f.seek(0, 2)
            size = f.tell()
            f.seek(max(0, size - 2_000_000))
            lines = f.read().decode("utf-8", "ignore").splitlines()
        for line in reversed(lines):
            if '"assistant"' not in line or '"usage"' not in line:
                continue
            try:
                ev = json.loads(line)
            except Exception:
                continue
            if ev.get("type") != "assistant" or ev.get("isSidechain"):
                continue
            u = (ev.get("message") or {}).get("usage") or {}
            used = (u.get("input_tokens", 0) + u.get("cache_read_input_tokens", 0)
                    + u.get("cache_creation_input_tokens", 0))
            if used <= 0:
                continue
            return {"used_tokens": used, "size": ctx_size,
                    "used_percentage": round(100.0 * used / ctx_size, 1), "source": "transcript"}
    except Exception:
        return None
    return None


def _latest_transcript(cwd, session_id):
    base = os.path.expanduser("~/.claude/projects")
    if session_id:
        for root, _, files in os.walk(base):
            if f"{session_id}.jsonl" in files:
                return os.path.join(root, f"{session_id}.jsonl")
    proj = os.path.join(base, re.sub(r"[^A-Za-z0-9]", "-", cwd or os.getcwd()))
    try:
        files = [os.path.join(proj, f) for f in os.listdir(proj) if f.endswith(".jsonl")]
        return max(files, key=os.path.getmtime) if files else None
    except Exception:
        return None


def collect(session_id=None, transcript_path=None, cwd=None):
    session_id = session_id or os.environ.get("CLAUDE_CODE_SESSION_ID")
    snap = _load(_path(session_id, ".json")) if session_id else None
    if snap is None and not session_id:  # Fallback: neuester Snapshot
        try:
            cands = [os.path.join(STATE_DIR, f) for f in os.listdir(STATE_DIR)
                     if re.fullmatch(r"[A-Za-z0-9_-]+\.json", f)]
            snap = _load(max(cands, key=os.path.getmtime)) if cands else None
        except Exception:
            snap = None
    snap = snap or {}

    now = time.time()
    limits = {}
    for key in ("five_hour", "seven_day"):
        w = (snap.get("rate_limits") or {}).get(key)
        if w and w.get("resets_at") and float(w["resets_at"]) > now:
            limits[key] = {"used_percentage": w.get("used_percentage"),
                           "resets_at": w.get("resets_at"),
                           "resets_at_iso": _iso(w.get("resets_at"))}

    cw = snap.get("context_window") or {}
    ctx_size = cw.get("context_window_size") or DEFAULT_CTX
    transcript_path = transcript_path or snap.get("transcript_path") or _latest_transcript(cwd, session_id)
    ctx = _context_from_transcript(transcript_path, ctx_size)
    if ctx is None and cw.get("used_percentage") is not None:
        ctx = {"used_percentage": cw.get("used_percentage"), "size": ctx_size, "source": "statusline"}

    return {
        "session_id": session_id or snap.get("session_id"),
        "context": ctx,
        "rate_limits": limits,
        "rate_limits_available": bool(snap.get("rate_limits")),
        "snapshot_age_s": round(now - snap["_saved_at"]) if snap.get("_saved_at") else None,
        "cost_usd": (snap.get("cost") or {}).get("total_cost_usd"),
        "advice": _advice(ctx, limits),
    }


def _advice(ctx, limits):
    lim = max([v.get("used_percentage") or 0 for v in limits.values()] or [0])
    c = (ctx or {}).get("used_percentage") or 0
    if lim >= 95:
        return "STOP: Limit fast erschoepft. Sofort sichern (commit/push), Notiz hinterlassen, Wiederaufnahme nach Reset planen."
    if lim >= 85:
        return "WRAP-UP: Keine neuen grossen Teilaufgaben/Subagenten. Laufendes abschliessen und sichern."
    if c >= 85:
        return "KONTEXT: Fenster fast voll. Zwischenstand notieren; Compaction steht bevor."
    if lim >= 70 or c >= 70:
        return "SPARSAM: Subagenten/Workflows sparsam, gezielt lesen statt breit suchen."
    return "OK"


# ---------------------------------------------------------------- render
def one_line(b):
    parts = []
    c = b.get("context")
    if c:
        parts.append(f"Kontext {c['used_percentage']:.0f}%")
    names = {"five_hour": "5h", "seven_day": "7d"}
    for k, v in b["rate_limits"].items():
        pct = v.get("used_percentage")
        r = _fmt_reset(v.get("resets_at"))
        parts.append(f"{names[k]}-Limit {pct:.0f}% (Reset {r})" if pct is not None else f"{names[k]}-Limit ?")
    if not b["rate_limits"]:
        parts.append("Rate-Limits unbekannt")
    return "[Budget] " + " | ".join(parts) + f" -> {b['advice']}"


# ---------------------------------------------------------------- statusline
def cmd_statusline():
    data = _read_stdin_json()
    sid = data.get("session_id")
    if sid:
        data["_saved_at"] = time.time()
        _save(_path(sid, ".json"), data)
    cw = data.get("context_window") or {}
    out = []
    model = (data.get("model") or {}).get("display_name")
    if model:
        out.append(model)
    if cw.get("used_percentage") is not None:
        out.append(f"ctx {cw['used_percentage']:.0f}%")
    for k, n in (("five_hour", "5h"), ("seven_day", "7d")):
        w = (data.get("rate_limits") or {}).get(k)
        if w and w.get("used_percentage") is not None:
            r = ""
            if w.get("resets_at"):
                r = " ↻" + datetime.fromtimestamp(float(w["resets_at"])).astimezone().strftime("%H:%M")
            out.append(f"{n} {w['used_percentage']:.0f}%{r}")
    sys.stdout.write(" · ".join(out) + "\n")


def _level(b):
    vals = [(b.get("context") or {}).get("used_percentage") or 0]
    vals += [v.get("used_percentage") or 0 for v in b["rate_limits"].values()]
    lvl = 0
    for t in THRESHOLDS:
        if max(vals) >= t:
            lvl = t
    return lvl


# ---------------------------------------------------------------- reset approval
# Die Freigabe entsteht NUR im UserPromptSubmit-Hook aus dem Text, den der Nutzer
# tippt. Es gibt bewusst keinen CLI-Befehl zum Erteilen. Ein PreToolUse-Hook
# blockiert Tool-Aufrufe, die die Freigabedatei anfassen oder eine Freigabe
# per geplanter/gesendeter Nachricht einschleusen wollen.
RESUME_MARKER = "[budget-resume]"
GRANT_RE = re.compile(r"^\s*/?reset-freigabe\b[:\s]*(.*)$", re.I | re.S)
GRANT_LINE_RE = re.compile(r"(^|\\n|\n)\s*/?reset-freigabe\b", re.I)
APPROVAL_SUFFIX = ".approval" + ".json"  # zusammengesetzt, damit diese Datei selbst nicht vom Guard erfasst wird
FALLBACK_DELAY_S = 5 * 3600 + 120  # ohne resets_at: laengstes 5h-Fenster + Puffer


def _approval_path(sid):
    return _path(sid or os.environ.get("CLAUDE_CODE_SESSION_ID"), APPROVAL_SUFFIX)


def approval_state(sid=None):
    a = _load(_approval_path(sid))
    if not a:
        return {"approved": False, "reason": "keine Freigabe erteilt"}
    if time.time() > a.get("expires_at", 0):
        return {"approved": False, "reason": "Freigabe abgelaufen", **a}
    if a.get("remaining", 0) <= 0:
        return {"approved": False, "reason": "Freigabe aufgebraucht", **a}
    return {"approved": True, **a, "expires_at_iso": _iso(a["expires_at"])}


def _parse_grant(args):
    """'' | '2' | '3 12h' | '1 2d' | 'aus' -> (count, seconds) oder None fuer Widerruf."""
    a = args.strip().lower()
    if re.match(r"^(aus|off|stop|widerruf\w*|nein|0)\b", a):
        return None
    count, secs = 1, 24 * 3600
    for tok in a.split()[:4]:
        m = re.fullmatch(r"(\d+)\s*([hd])", tok)
        if m:
            secs = int(m.group(1)) * (3600 if m.group(2) == "h" else 86400)
        elif tok.isdigit():
            count = int(tok)
    return max(1, min(count, 10)), max(3600, min(secs, 14 * 86400))


def _handle_grant(sid, prompt):
    m = GRANT_RE.match(prompt or "")
    if not m or (prompt or "").lstrip().startswith(RESUME_MARKER):
        return None
    parsed = _parse_grant(m.group(1).splitlines()[0] if m.group(1) else "")
    path = _approval_path(sid)
    if parsed is None:
        try:
            os.remove(path)
        except FileNotFoundError:
            pass
        return "[Budget] Reset-Freigabe vom Nutzer WIDERRUFEN. Keine Wiederaufnahmen nach Limit-Reset planen."
    count, secs = parsed
    now = time.time()
    _save(path, {"granted_at": now, "expires_at": now + secs, "remaining": count, "granted": count})
    return (f"[Budget] Reset-Freigabe vom Nutzer ERTEILT: bis zu {count} Wiederaufnahme(n) nach Limit-Reset, "
            f"gueltig bis {_fmt_reset(now + secs)}. Vorgehen: Skill token-budget, Abschnitt Wiederaufnahme.")


def resume_plan(sid=None):
    b = collect(sid)
    ap = approval_state(sid)
    windows = b["rate_limits"]
    if windows:  # Das Fenster, das bremst: hoechste Auslastung; bei Gleichstand das spaetere Reset.
        k, w = max(windows.items(), key=lambda kv: ((kv[1].get("used_percentage") or 0), kv[1]["resets_at"]))
        target = {"window": k, "at": float(w["resets_at"]) + 120}
    else:
        target = {"window": "unbekannt (Fallback 5h)", "at": time.time() + FALLBACK_DELAY_S}
    return {
        "approved": ap["approved"],
        "reason": ap.get("reason"),
        "remaining": ap.get("remaining", 0),
        "window": target["window"],
        "resume_at": target["at"],
        "resume_at_iso": _iso(target["at"]),
        "resume_at_local": _fmt_reset(target["at"]),
        "message_prefix": RESUME_MARKER,
        "next_step": ("send_later mit at=resume_at_iso und einer Nachricht, die mit "
                      f"'{RESUME_MARKER}' beginnt" if ap["approved"]
                      else "NICHT planen. Nutzer informieren: Freigabe mit /reset-freigabe moeglich."),
    }


SCHEDULING_TOOLS = ("send_later", "create_trigger", "update_trigger", "send_message",
                    "fire_trigger", "create_session", "SendMessage", "CronCreate", "ScheduleWakeup")


def _pretool_guard(data):
    """Gibt eine Deny-Begruendung zurueck oder None."""
    name = data.get("tool_name", "")
    blob = json.dumps(data.get("tool_input") or {}, ensure_ascii=False)
    low = blob.lower()
    scheduling = name.endswith(SCHEDULING_TOOLS)
    if scheduling and GRANT_LINE_RE.search(low):
        return "Geplante/gesendete Nachrichten duerfen keine Reset-Freigabe enthalten; die erteilt nur der Nutzer selbst."
    if APPROVAL_SUFFIX in low:
        return "Die Reset-Freigabedatei darf nur der Nutzer (per /reset-freigabe) setzen."
    if name.endswith(("send_later", "create_trigger", "CronCreate", "ScheduleWakeup")) and RESUME_MARKER in blob:
        sid = data.get("session_id")
        ap = approval_state(sid)
        if not ap["approved"]:
            return (f"Keine gueltige Reset-Freigabe ({ap.get('reason')}). "
                    "Nicht planen; Nutzer kann mit /reset-freigabe freigeben.")
        a = _load(_approval_path(sid))
        a["remaining"] = a.get("remaining", 1) - 1
        a.setdefault("used", []).append(time.time())
        _save(_approval_path(sid), a)
    return None


def cmd_hook():
    data = _read_stdin_json()
    event = data.get("hook_event_name", "")
    sid = data.get("session_id")

    if event == "PreToolUse":
        reason = _pretool_guard(data)
        if reason:
            print(json.dumps({"hookSpecificOutput": {
                "hookEventName": "PreToolUse", "permissionDecision": "deny",
                "permissionDecisionReason": reason}}))
        return

    grant_msg = _handle_grant(sid, data.get("prompt")) if event == "UserPromptSubmit" else None
    b = collect(sid, data.get("transcript_path"), data.get("cwd"))
    hook_state_path = _path(sid, ".hook.json")
    st = _load(hook_state_path) or {}
    lvl = _level(b)

    inject = False
    if event in ("UserPromptSubmit", "SessionStart"):
        inject = True
    elif event == "PostToolUse":
        inject = lvl > st.get("level", 0)
    if lvl != st.get("level"):
        st["level"] = lvl
        _save(hook_state_path, st)

    if inject or grant_msg:
        ctx = one_line(b)
        if grant_msg:
            ctx = grant_msg + "\n" + ctx
        elif b["advice"].startswith(("STOP", "WRAP-UP")):
            ap = approval_state(sid)
            ctx += (f" | Reset-Freigabe: {ap['remaining']} uebrig" if ap["approved"]
                    else " | Reset-Freigabe: keine")
        print(json.dumps({"hookSpecificOutput": {
            "hookEventName": event, "additionalContext": ctx}}))


def cmd_status(as_json):
    b = collect()
    b["reset_approval"] = approval_state()
    if as_json:
        print(json.dumps(b, indent=2, ensure_ascii=False))
    else:
        print(one_line(b))
        if not b["rate_limits_available"]:
            print("Hinweis: Keine Rate-Limit-Daten. Die kommen nur ueber die Statuszeile "
                  "(Pro/Max/Ultra, nach der ersten API-Antwort).")


# ---------------------------------------------------------------- install
def _launcher(py, script_expr, sub):
    """Shell-unabhaengiger Starter, der nichts tut, wenn das Skript fehlt
    (ein fehlendes Skript wuerde sonst mit Exit 2 alle Tools blockieren)."""
    code = (f"import os,sys,runpy;p={script_expr};sys.argv=[p,'{sub}'];"
            "os.path.isfile(p) and runpy.run_path(p,run_name='__main__')")
    return f'{py} -c "{code}"'


def cmd_install(argv):
    project = None
    if "--project" in argv:
        i = argv.index("--project")
        project = os.path.abspath(argv[i + 1] if i + 1 < len(argv) else os.getcwd())
    base = os.path.join(project, ".claude") if project else os.path.expanduser("~/.claude")
    dest_skill = os.path.join(base, "skills", "token-budget")
    src_root = os.path.dirname(SKILL_DIR)

    for name in ("token-budget", "reset-freigabe"):
        src, dst = os.path.join(src_root, name), os.path.join(base, "skills", name)
        if not os.path.isdir(src):
            print(f"FEHLT: {src}")
            continue
        if os.path.abspath(src) != os.path.abspath(dst):
            os.makedirs(dst, exist_ok=True)
            for f in os.listdir(src):
                if os.path.isfile(os.path.join(src, f)):
                    shutil.copy2(os.path.join(src, f), os.path.join(dst, f))
        print(f"Skill: {dst}")

    if project:
        py = "python3"
        expr = "os.path.join(os.environ.get('CLAUDE_PROJECT_DIR','.'),'.claude','skills','token-budget','budget.py')"
    else:
        py = '"' + sys.executable.replace("\\", "/") + '"' if sys.executable else "python3"
        expr = "r'" + os.path.join(dest_skill, "budget.py").replace("\\", "/") + "'"
    hook_cmd = _launcher(py, expr, "hook")
    status_cmd = _launcher(py, expr, "statusline")

    settings_path = os.path.join(base, "settings.json")
    settings = _load(settings_path) if os.path.exists(settings_path) else {}
    if settings is None:
        print(f"ABBRUCH: {settings_path} ist kein gueltiges JSON. Bitte zuerst reparieren.")
        sys.exit(1)
    if os.path.exists(settings_path):
        shutil.copy2(settings_path, settings_path + ".bak")

    def ours(h):
        return "token-budget" in h.get("command", "") or "budget.py" in h.get("command", "")

    hooks = settings.setdefault("hooks", {})
    wanted = {
        "SessionStart": None,
        "UserPromptSubmit": None,
        "PreToolUse": "Bash|Write|Edit|MultiEdit|NotebookEdit|SendMessage|CronCreate|ScheduleWakeup|mcp__claude-code-remote__.*",
        "PostToolUse": "*",
    }
    for event, matcher in wanted.items():
        groups = []
        for g in hooks.get(event, []):
            g = dict(g)
            g["hooks"] = [h for h in g.get("hooks", []) if not ours(h)]
            if g["hooks"]:
                groups.append(g)
        entry = {"hooks": [{"type": "command", "command": hook_cmd, "timeout": 10}]}
        if matcher:
            entry = {"matcher": matcher, **entry}
        groups.append(entry)
        hooks[event] = groups

    sl = settings.get("statusLine")
    if not sl or ours(sl) or "--force-statusline" in argv:
        settings["statusLine"] = {"type": "command", "command": status_cmd}
        print("Statuszeile: gesetzt")
    else:
        print("Statuszeile: vorhandene eigene Statuszeile behalten. Ohne sie gibt es keine "
              "Rate-Limit-Daten; mit --force-statusline ersetzen.")

    _save(settings_path, settings)
    print(f"Settings: {settings_path} (Backup: .bak)")
    print("Fertig. Neue Sitzung starten oder /hooks pruefen; danach erscheint eine [Budget]-Zeile.")


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "status"
    if cmd == "install":
        cmd_install(sys.argv[2:])
        return
    try:
        if cmd == "statusline":
            cmd_statusline()
        elif cmd == "hook":
            cmd_hook()
        elif cmd == "resume-plan":
            print(json.dumps(resume_plan(), indent=2, ensure_ascii=False))
        elif cmd == "revoke":  # Zuruecknehmen darf auch der Agent, erteilen nicht
            try:
                os.remove(_approval_path(None))
            except FileNotFoundError:
                pass
            print("Reset-Freigabe widerrufen.")
        else:
            cmd_status("--json" in sys.argv)
    except Exception as e:  # Hooks/Statuszeile duerfen nie die Sitzung stoeren
        if cmd in ("status", "resume-plan", "revoke"):
            print(f"budget: Fehler: {e}", file=sys.stderr)
            sys.exit(1)


if __name__ == "__main__":
    main()
