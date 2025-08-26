from flask import Flask, request, jsonify
import os, subprocess, shlex, time, uuid, logging
from colorama import init, Fore, Style

init(autoreset=True)
app = Flask(__name__)

# --- Paths ---
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
KOTLINC  = os.environ.get("KOTLINC_PATH", "kotlinc")
JAVA     = os.environ.get("JAVA_PATH",    "java")
IS_WINDOWS = os.name == "nt"

# --- Logging (silence werkzeug request lines) ---
logging.basicConfig(level=logging.INFO, format="%(message)s")
logger = logging.getLogger("CompilerServer")
logging.getLogger("werkzeug").setLevel(logging.WARNING)

def pretty_log(req_id, stage, message, color=Fore.WHITE):
    prefix = f"{Fore.CYAN}[{req_id}]{Style.RESET_ALL}"
    stage  = f"{color}{stage:<8}{Style.RESET_ALL}"
    logger.info(f"{prefix} {stage} {message}")

# --- Helpers to keep console short ---
def _shorten_path(p: str) -> str:
    """For console only: make paths relative to BASE_DIR and use .\\ on Windows."""
    try:
        if os.path.isabs(p) and os.path.commonpath([BASE_DIR, p]) == BASE_DIR:
            rel = os.path.relpath(p, BASE_DIR)
            return (".\\" if IS_WINDOWS else "./") + rel.replace("/", "\\" if IS_WINDOWS else "/")
    except Exception:
        pass
    return p

def _render_cmd_for_console(cmd_list):
    """Return a short, pretty command string for logs."""
    shown = []
    for a in cmd_list:
        # shorten file-like args and keep flags as-is
        if "\\" in a or "/" in a or os.path.isabs(a):
            shown.append(_shorten_path(a))
        else:
            shown.append(a)
    # quote only if contains spaces (for readability)
    def q(s): return f'"{s}"' if (" " in s or "\t" in s) else s
    return " ".join(q(x) for x in shown)

def run_cmd(cmd, input_text=None, cwd=None, req_id=""):
    """Executes with full paths; logs with shortened, pretty output."""
    # Build actual command
    if IS_WINDOWS:
        if isinstance(cmd, list):
            def q(a: str) -> str:
                a = a.replace('"', r'\"')
                return f'"{a}"' if (" " in a or "\t" in a) else a
            cmdline = " ".join(q(x) for x in cmd)
        else:
            cmdline = cmd
        # Pretty print a short version
        if isinstance(cmd, list):
            pretty = _render_cmd_for_console(cmd)
        else:
            pretty = cmd  # best effort
        pretty_log(req_id, "CMD", pretty, Fore.YELLOW)

        proc = subprocess.run(
            cmdline, shell=True, input=input_text,
            capture_output=True, text=True, cwd=cwd
        )
        return proc.returncode, proc.stdout, proc.stderr
    else:
        full_cmd = cmd if isinstance(cmd, list) else shlex.split(cmd)
        pretty_log(req_id, "CMD", _render_cmd_for_console(full_cmd), Fore.YELLOW)
        proc = subprocess.run(
            full_cmd, input=input_text,
            capture_output=True, text=True, cwd=cwd
        )
        return proc.returncode, proc.stdout, proc.stderr

def parse_kotlinc_errors(stderr: str):
    errors = []
    for line in stderr.splitlines():
        parts = line.split(":", 3)
        if len(parts) >= 4 and parts[1].strip().isdigit():
            try:
                errors.append({"line": int(parts[1].strip()), "message": parts[3].strip()})
            except: pass
    if not errors and stderr.strip():
        errors = [{"line": 0, "message": stderr.strip()}]
    return errors

@app.route("/compile", methods=["POST"])
def compile_code():
    req_id = str(uuid.uuid4())[:8]
    src_path = jar_path = None
    try:
        data = request.get_json(force=True)
        file_name = data.get("fileName", "Main.kt")
        code      = data.get("code", "")

        pretty_log(req_id, "START", f"Received request for {file_name}", Fore.GREEN)

        if not file_name.lower().endswith(".kt"):
            return jsonify(ok=False, output="", errors=[{"line":0,"message":"Only .kt files are supported"}])

        stamp = f"{int(time.time())}_{req_id}"
        src_path = os.path.join(BASE_DIR, f"compile_{stamp}.kt")
        jar_path = os.path.join(BASE_DIR, f"program_{stamp}.jar")

        with open(src_path, "w", encoding="utf-8") as f:
            f.write(code)
            f.flush()
            os.fsync(f.fileno())

        # Compile
        compile_cmd = [KOTLINC, src_path, "-include-runtime", "-d", jar_path]
        rc, out, err = run_cmd(compile_cmd, cwd=BASE_DIR, req_id=req_id)
        if rc != 0:
            pretty_log(req_id, "ERROR", "Compilation failed", Fore.RED)
            return jsonify(ok=False, output="", errors=parse_kotlinc_errors(err))
        pretty_log(req_id, "OK", "Compilation succeeded", Fore.GREEN)

        # Run
        run_cmdline = [JAVA, "-jar", jar_path]
        rc2, out2, err2 = run_cmd(run_cmdline, input_text="", cwd=BASE_DIR, req_id=req_id)
        output = out2 if out2 else (err2 or "")
        pretty_log(req_id, "DONE", "Execution finished", Fore.CYAN)

        return jsonify(ok=True, output=output, errors=[])

    except Exception as e:
        pretty_log(req_id, "FATAL", str(e), Fore.RED)
        return jsonify(ok=False, output="", errors=[{"line":0,"message":str(e)}])
    finally:
        for p in (src_path, jar_path):
            try:
                if p and os.path.exists(p):
                    os.remove(p)
            except: pass
        pretty_log(req_id, "END", "Request cleaned up\n", Fore.MAGENTA)

if __name__ == "__main__":
    port = int(os.environ.get("PORT", "8081"))
    logger.info(f"\n Compiler server running at http://0.0.0.0:{port}")
    logger.info(f" Base dir: {_shorten_path(BASE_DIR)}")  # one-time info line
    app.run(host="0.0.0.0", port=port)
