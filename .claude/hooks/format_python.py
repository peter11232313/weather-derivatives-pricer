import json
import subprocess
import sys

path = json.load(sys.stdin).get("tool_input", {}).get("file_path", "")
if path.endswith(".py"):
    subprocess.run([sys.executable, "-m", "ruff", "format", path], check=False)
sys.exit(0)
