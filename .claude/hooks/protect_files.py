import json
import sys

data = json.load(sys.stdin)
path = data.get("tool_input", {}).get("file_path", "")
norm = path.replace("\\", "/").lower()      # Windows paths use backslashes
name = norm.rsplit("/", 1)[-1]

if "/data/raw/" in norm or name in (".env", ".cdsapirc"):
    print(f"Blocked: {path} is protected", file=sys.stderr)
    sys.exit(2)
sys.exit(0)
