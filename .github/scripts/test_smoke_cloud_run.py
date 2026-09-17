import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("smoke-cloud-run.sh").resolve()


class SmokeCloudRunTest(unittest.TestCase):
    def run_smoke(self, status="200", body='{"status":"ok"}', url="https://candidate.example"):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            curl = root / "curl"
            curl.write_text(
                "#!/usr/bin/env python3\n"
                "import os, sys\n"
                "from pathlib import Path\n"
                "args = sys.argv[1:]\n"
                "assert args[-1] == 'https://candidate.example/health'\n"
                "Path(args[args.index('--output') + 1]).write_text(os.environ['TEST_BODY'])\n"
                "sys.stdout.write(os.environ['TEST_STATUS'])\n",
                encoding="utf-8",
            )
            curl.chmod(0o755)
            env = dict(os.environ, SERVICE_URL=url, TEST_STATUS=status, TEST_BODY=body,
                       SMOKE_MAX_ATTEMPTS="1", SMOKE_INTERVAL_SECONDS="0")
            env["PATH"] = str(root) + os.pathsep + env["PATH"]
            return subprocess.run(["bash", str(SCRIPT)], env=env,
                                  capture_output=True, text=True, timeout=10)

    def test_public_health_with_exact_body_passes(self):
        self.assertEqual(0, self.run_smoke().returncode)

    def test_trailing_origin_slash_passes(self):
        self.assertEqual(0, self.run_smoke(url="https://candidate.example/").returncode)

    def test_reserved_path_style_404_fails(self):
        self.assertNotEqual(0, self.run_smoke("404", "<html>Not Found</html>").returncode)

    def test_wrong_body_fails_even_with_200(self):
        self.assertNotEqual(0, self.run_smoke(body='{"status":"ready"}').returncode)

    def test_bad_origin_rejected(self):
        self.assertEqual(2, self.run_smoke(url="http://candidate.example").returncode)


if __name__ == "__main__":
    unittest.main()
