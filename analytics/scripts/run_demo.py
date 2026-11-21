"""One command for the demo: build the analytics outputs if needed, then run the backend with the model.

    python analytics/scripts/run_demo.py [--port 8080] [--model eval|final] [--rebuild] [--offline]

1. If an output is missing (or --rebuild): train, export, timelines and replays (python -m sbb_duration.*).
2. Build the backend (gradlew installDist) and start it on --port with
   DURATION_ARTIFACT_PATH (eval artifact by default: its models never saw the replayed incidents)
   and DISRUPTION_TIMELINES_DIR (replayed incidents, MOCK).
   TRANSPORT_PROVIDER is ojp when OJP_API_KEY is set in the environment or in .env, else mock;
   --offline forces mock and skips the service-points download.
Stop with Ctrl+C. See docs/DEMO.md.
"""
import argparse
import glob
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ANALYTICS = ROOT / "analytics"
BACKEND = ROOT / "backend"
OUTPUT = Path(os.environ.get("SBB_OUTPUT_DIR", ANALYTICS / "output"))
REQUIRED = ["duration_model.joblib", "duration_model.v1.json", "duration_model.v1.eval.json", "golden_vectors.json",
            "timelines/index.json"]


def run(cmd: list[str], cwd: Path, env: dict | None = None) -> None:
    print(f"$ {' '.join(cmd)}  (in {cwd.name}/)", flush=True)
    subprocess.run(cmd, cwd=cwd, env=env, check=True)


def read_dotenv() -> dict:
    env_file = ROOT / ".env"
    if not env_file.is_file():
        return {}
    pairs = (line.split("=", 1) for line in env_file.read_text(encoding="utf-8").splitlines()
             if "=" in line and not line.lstrip().startswith("#"))
    return {k.strip(): v.strip().strip('"') for k, v in pairs}


def java_home() -> str | None:
    if os.environ.get("JAVA_HOME"):
        return os.environ["JAVA_HOME"]
    candidates = sorted(glob.glob(os.path.expandvars(r"%LOCALAPPDATA%\Programs\Temurin\jdk-21*")))
    return candidates[-1] if candidates else None


def build_analytics(offline: bool) -> None:
    python = sys.executable
    run([python, "-m", "sbb_duration.train"], ANALYTICS)
    run([python, "-m", "sbb_duration.export"], ANALYTICS)
    run([python, "-m", "sbb_duration.sources"] + (["--no-fetch"] if offline else []), ANALYTICS)
    run([python, "-m", "sbb_duration.replay"], ANALYTICS)


def main(argv=None) -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--model", choices=["eval", "final"], default="eval",
                        help="eval: split models, honest for replays (default); final: trained on all data")
    parser.add_argument("--rebuild", action="store_true", help="retrain and regenerate every output")
    parser.add_argument("--offline", action="store_true", help="mock provider, no download")
    args = parser.parse_args(argv)

    missing = [f for f in REQUIRED if not (OUTPUT / f).exists()]
    if args.rebuild or missing:
        print(f"Building analytics outputs ({'rebuild' if args.rebuild else 'missing: ' + ', '.join(missing)})")
        build_analytics(args.offline)
    else:
        print(f"Analytics outputs present in {OUTPUT} (use --rebuild to regenerate)")

    env = {**os.environ}
    home = java_home()
    if home:
        env["JAVA_HOME"] = home
    secrets = read_dotenv()
    key = env.get("OJP_API_KEY") or secrets.get("OJP_API_KEY")
    provider = "ojp" if key and not args.offline else "mock"
    if provider == "ojp":
        env["OJP_API_KEY"] = key
    artifact = OUTPUT / ("duration_model.v1.eval.json" if args.model == "eval" else "duration_model.v1.json")
    env.update(PORT=str(args.port), TRANSPORT_PROVIDER=provider, DURATION_ARTIFACT_PATH=str(artifact),
               DISRUPTION_TIMELINES_DIR=str(OUTPUT / "timelines"))

    windows = os.name == "nt"
    gradlew = str(BACKEND / ("gradlew.bat" if windows else "gradlew"))
    run([gradlew, "--no-daemon", "-q", "installDist"], BACKEND, env)
    launcher = BACKEND / "build" / "install" / "cff-plan-b-backend" / "bin" / (
        "cff-plan-b-backend.bat" if windows else "cff-plan-b-backend")
    print(f"\nBackend on http://localhost:{args.port}  provider={provider}  model={artifact.name}  "
          f"timelines={OUTPUT / 'timelines'}  (Ctrl+C to stop)\n", flush=True)
    try:
        subprocess.run([str(launcher)], cwd=BACKEND, env=env, check=True)
    except KeyboardInterrupt:
        print("\nStopped.")


if __name__ == "__main__":
    main()
